package com.llmcouncil.mobile.data

import com.llmcouncil.mobile.model.GitHubRepo
import com.llmcouncil.mobile.model.RepoFile
import com.llmcouncil.mobile.model.RepoSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

class GitHubClient(private val settings: SecureSettings) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private data class TreeMeta(val path: String, val sha: String, val size: Long, val category: String)
    private data class PendingTree(val prefix: String, val sha: String)

    private fun auth(request: Request.Builder): Request.Builder {
        val token = settings.getGitHubToken()
        if (token.isNotBlank()) request.header("Authorization", "Bearer $token")
        return request
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "OmniCouncil-Android")
    }

    suspend fun listRepos(): List<GitHubRepo> = withContext(Dispatchers.IO) {
        require(settings.getGitHubToken().isNotBlank()) { "GitHub token is not configured" }
        val out = mutableListOf<GitHubRepo>()
        for (page in 1..20) {
            val request = auth(Request.Builder().url(
                "https://api.github.com/user/repos?per_page=100&page=$page&sort=updated&affiliation=owner,collaborator,organization_member"
            )).build()
            val data = JSONArray(executeText(request))
            for (i in 0 until data.length()) {
                val o = data.getJSONObject(i)
                out += GitHubRepo(
                    fullName = o.getString("full_name"),
                    defaultBranch = o.optString("default_branch", "main"),
                    privateRepo = o.optBoolean("private", false),
                    updatedAt = o.optString("updated_at", ""),
                    description = o.optString("description", "")
                )
            }
            if (data.length() < 100) break
        }
        out.distinctBy { it.fullName }.sortedBy { it.fullName.lowercase() }
    }

    suspend fun snapshot(
        repo: GitHubRepo,
        ref: String = repo.defaultBranch,
        onProgress: (Int, Int, String) -> Unit = { _, _, _ -> }
    ): RepoSnapshot = withContext(Dispatchers.IO) {
        require(settings.getGitHubToken().isNotBlank()) { "GitHub token is not configured" }
        val safeRepo = repo.fullName.split('/').joinToString("/") { encode(it) }

        val commitRequest = auth(Request.Builder().url(
            "https://api.github.com/repos/$safeRepo/commits/${encode(ref)}"
        )).build()
        val commitRoot = JSONObject(executeText(commitRequest))
        val commitSha = commitRoot.getString("sha")
        val rootTreeSha = commitRoot.optJSONObject("commit")?.optJSONObject("tree")?.optString("sha").orEmpty()
        if (rootTreeSha.isBlank()) throw IllegalStateException("GitHub commit response did not include the root tree SHA")

        val manifest = loadCompleteTreeManifest(safeRepo, rootTreeSha)
        val required = linkedMapOf<String, TreeMeta>()
        val excluded = mutableListOf<RepoFile>()
        for (item in manifest) {
            val reason = exclusionReason(item.path, item.size)
            if (reason != null) {
                excluded += RepoFile(item.path, item.sha, item.size, "", item.category, true, reason)
            } else {
                required[item.path] = item
            }
        }

        val found = linkedMapOf<String, RepoFile>()
        val archiveRequest = auth(Request.Builder().url(
            "https://api.github.com/repos/$safeRepo/zipball/$commitSha"
        )).build()
        http.newCall(archiveRequest).execute().use { response ->
            if (!response.isSuccessful) throw githubError(response.code, response.body?.string().orEmpty())
            val body = response.body ?: throw IllegalStateException("GitHub returned an empty repository archive")
            ZipInputStream(body.byteStream().buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val path = entry.name.substringAfter('/', entry.name)
                        val meta = required[path]
                        if (meta != null) {
                            onProgress(found.size + 1, required.size, path)
                            val bytes = zip.readBytes()
                            when {
                                bytes.size > 1_000_000 -> {
                                    excluded += RepoFile(path, meta.sha, meta.size, "", meta.category, true, "archive entry exceeds 1 MB audit ingestion limit")
                                    required.remove(path)
                                }
                                bytes.any { it.toInt() == 0 } -> {
                                    excluded += RepoFile(path, meta.sha, meta.size, "", meta.category, true, "binary content")
                                    required.remove(path)
                                }
                                else -> found[path] = RepoFile(path, meta.sha, meta.size, String(bytes, StandardCharsets.UTF_8), meta.category)
                            }
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }

        val missing = required.keys - found.keys
        if (missing.isNotEmpty()) {
            throw IllegalStateException(
                "Pinned repository archive did not contain ${missing.size} required manifest file(s), including ${missing.take(8).joinToString()}. Exhaustive audit aborted rather than silently skipping them."
            )
        }

        RepoSnapshot(
            repo = repo,
            ref = ref,
            commitSha = commitSha,
            files = required.keys.map { found.getValue(it) },
            excluded = excluded.distinctBy { it.path }.sortedBy { it.path }
        )
    }

    private fun loadCompleteTreeManifest(safeRepo: String, rootTreeSha: String): List<TreeMeta> {
        val recursiveRequest = auth(Request.Builder().url(
            "https://api.github.com/repos/$safeRepo/git/trees/$rootTreeSha?recursive=1"
        )).build()
        val recursiveRoot = JSONObject(executeText(recursiveRequest))
        if (!recursiveRoot.optBoolean("truncated", false)) {
            return blobsFromTree(recursiveRoot.optJSONArray("tree") ?: JSONArray(), prefix = "")
        }

        val out = linkedMapOf<String, TreeMeta>()
        val queue = ArrayDeque<PendingTree>()
        queue.add(PendingTree("", rootTreeSha))
        val visitedTrees = mutableSetOf<String>()
        var visitedCount = 0

        while (queue.isNotEmpty()) {
            val pending = queue.removeFirst()
            if (!visitedTrees.add(pending.sha)) continue
            visitedCount++
            if (visitedCount > 100_000) {
                throw IllegalStateException("GitHub tree traversal exceeded 100,000 tree objects; exhaustive manifest aborted rather than risking incomplete coverage")
            }
            val request = auth(Request.Builder().url(
                "https://api.github.com/repos/$safeRepo/git/trees/${pending.sha}"
            )).build()
            val root = JSONObject(executeText(request))
            if (root.optBoolean("truncated", false)) {
                throw IllegalStateException("GitHub unexpectedly truncated a non-recursive tree object; exhaustive manifest cannot be proven")
            }
            val tree = root.optJSONArray("tree") ?: JSONArray()
            for (i in 0 until tree.length()) {
                val item = tree.optJSONObject(i) ?: continue
                val name = item.optString("path")
                if (name.isBlank()) continue
                val path = if (pending.prefix.isBlank()) name else "${pending.prefix}/$name"
                when (item.optString("type")) {
                    "blob" -> out[path] = TreeMeta(path, item.optString("sha"), item.optLong("size", 0L), classify(path))
                    "tree" -> {
                        val sha = item.optString("sha")
                        if (sha.isBlank()) throw IllegalStateException("GitHub tree entry $path did not include a SHA")
                        queue.add(PendingTree(path, sha))
                    }
                }
            }
        }
        return out.values.sortedBy { it.path }
    }

    private fun blobsFromTree(tree: JSONArray, prefix: String): List<TreeMeta> = buildList {
        for (i in 0 until tree.length()) {
            val item = tree.optJSONObject(i) ?: continue
            if (item.optString("type") != "blob") continue
            val rawPath = item.optString("path")
            val path = if (prefix.isBlank()) rawPath else "$prefix/$rawPath"
            add(TreeMeta(path, item.optString("sha"), item.optLong("size", 0L), classify(path)))
        }
    }

    private fun exclusionReason(path: String, size: Long): String? {
        val lower = path.lowercase().replace('\\', '/')
        if (size > 1_000_000) return "file exceeds 1 MB audit ingestion limit"

        val segments = lower.split('/')
        val generatedDirs = setOf(
            ".git", "node_modules", "vendor", "dist", "build", ".gradle", ".idea", "coverage", "target", "pods",
            ".venv", "venv", "env", "__pycache__", ".pytest_cache", ".mypy_cache", ".ruff_cache", ".tox",
            ".next", ".nuxt", ".svelte-kit", ".parcel-cache", ".turbo", ".cache", "site-packages",
            "bin", "obj", "generated", "generated-src"
        )
        if (segments.any { it in generatedDirs }) return "generated/vendor/cache directory"

        if (lower.startsWith(".dev/evidence/") ||
            lower.contains("/generated-evidence/") ||
            lower.contains("/audit-evidence/") ||
            lower.contains("/test-results/") ||
            lower.contains("/test-results-/") ||
            lower.contains("/playwright-report/") ||
            lower.contains("/allure-results/")) {
            return "generated/historical evidence artifact"
        }

        val generatedNames = setOf(".ds_store", "thumbs.db", "desktop.ini")
        if (segments.lastOrNull() in generatedNames) return "generated operating-system metadata"

        val blocked = setOf(
            ".png", ".jpg", ".jpeg", ".gif", ".webp", ".ico", ".pdf", ".zip", ".gz", ".7z", ".jar", ".aar", ".apk",
            ".so", ".dll", ".exe", ".bin", ".mp3", ".mp4", ".mov", ".woff", ".woff2", ".ttf", ".otf",
            ".class", ".pyc", ".pyo", ".o", ".a", ".dylib", ".map"
        )
        if (blocked.any(lower::endsWith)) return "binary/generated/non-source file type"
        if (lower.endsWith(".min.js") || lower.endsWith(".min.css")) return "generated/minified asset"
        return null
    }

    private fun classify(path: String): String {
        val p = path.lowercase()
        return when {
            p.endsWith(".md") || p.endsWith(".rst") || p.endsWith(".txt") || p.contains("docs/") -> "documentation"
            p.contains("test") || p.contains("spec") -> "tests"
            p.endsWith(".yml") || p.endsWith(".yaml") || p.endsWith(".json") || p.endsWith(".toml") || p.endsWith(".ini") || p.endsWith(".xml") || p.endsWith(".properties") || p.endsWith(".lock") -> "configuration-dependencies"
            p.contains("migration") || p.endsWith(".sql") -> "database"
            p.contains(".github/") || p.contains("docker") || p.contains("terraform") || p.endsWith(".tf") -> "ci-infrastructure"
            else -> "source"
        }
    }

    private fun executeText(request: Request): String {
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw githubError(response.code, body)
            return body
        }
    }

    private fun githubError(code: Int, body: String): IllegalStateException {
        val message = runCatching { JSONObject(body).optString("message") }.getOrNull().orEmpty().ifBlank { body.take(500) }
        return IllegalStateException("GitHub HTTP $code: $message")
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
