package com.llmcouncil.mobile.data

import android.util.Base64
import com.llmcouncil.mobile.model.AuditScopePreview
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

    private data class TreeMeta(val path: String, val sha: String, val size: Long)
    private data class PendingTree(val prefix: String, val sha: String)
    private data class ScopeDecision(val required: Boolean, val category: String, val reason: String? = null)
    private data class ScopedManifest(
        val required: LinkedHashMap<String, Pair<TreeMeta, ScopeDecision>>,
        val excluded: List<RepoFile>,
        val totalTracked: Int,
        val requiredByCategory: Map<String, Int>,
        val excludedByReason: Map<String, Int>
    )

    private fun auth(request: Request.Builder): Request.Builder {
        val token = settings.getGitHubToken()
        if (token.isNotBlank()) request.header("Authorization", "Bearer $token")
        return request.header("Accept", "application/vnd.github+json").header("X-GitHub-Api-Version", "2022-11-28").header("User-Agent", "OmniCouncil-Android")
    }

    suspend fun listRepos(): List<GitHubRepo> = withContext(Dispatchers.IO) {
        require(settings.getGitHubToken().isNotBlank()) { "GitHub token is not configured" }
        val out = mutableListOf<GitHubRepo>()
        for (page in 1..20) {
            val request = auth(Request.Builder().url("https://api.github.com/user/repos?per_page=100&page=$page&sort=updated&affiliation=owner,collaborator,organization_member")).build()
            val data = JSONArray(executeText(request))
            for (i in 0 until data.length()) {
                val o = data.getJSONObject(i)
                out += GitHubRepo(o.getString("full_name"), o.optString("default_branch", "main"), o.optBoolean("private", false), o.optString("updated_at", ""), o.optString("description", ""))
            }
            if (data.length() < 100) break
        }
        out.distinctBy { it.fullName }.sortedBy { it.fullName.lowercase() }
    }

    suspend fun previewScope(repo: GitHubRepo, ref: String = repo.defaultBranch): AuditScopePreview = withContext(Dispatchers.IO) {
        require(settings.getGitHubToken().isNotBlank()) { "GitHub token is not configured" }
        val safeRepo = repo.fullName.split('/').joinToString("/") { encode(it) }
        val resolved = resolveCommitAndTree(safeRepo, ref)
        val manifest = loadCompleteTreeManifest(safeRepo, resolved.second)
        val scoped = classifyManifest(safeRepo, manifest)
        AuditScopePreview(repo.fullName, ref, resolved.first, scoped.totalTracked, scoped.required.size, scoped.excluded.size, scoped.requiredByCategory, scoped.excludedByReason)
    }

    suspend fun snapshot(repo: GitHubRepo, ref: String = repo.defaultBranch, onProgress: (Int, Int, String) -> Unit = { _, _, _ -> }): RepoSnapshot = withContext(Dispatchers.IO) {
        require(settings.getGitHubToken().isNotBlank()) { "GitHub token is not configured" }
        val safeRepo = repo.fullName.split('/').joinToString("/") { encode(it) }
        val resolved = resolveCommitAndTree(safeRepo, ref)
        val commitSha = resolved.first
        val manifest = loadCompleteTreeManifest(safeRepo, resolved.second)
        val scoped = classifyManifest(safeRepo, manifest)
        val required = LinkedHashMap(scoped.required)
        val excluded = scoped.excluded.toMutableList()
        val found = linkedMapOf<String, RepoFile>()
        val archiveRequest = auth(Request.Builder().url("https://api.github.com/repos/$safeRepo/zipball/$commitSha")).build()
        http.newCall(archiveRequest).execute().use { response ->
            if (!response.isSuccessful) throw githubError(response.code, response.body?.string().orEmpty())
            val body = response.body ?: throw IllegalStateException("GitHub returned an empty repository archive")
            ZipInputStream(body.byteStream().buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val path = entry.name.substringAfter('/', entry.name)
                        val scopedMeta = required[path]
                        if (scopedMeta != null) {
                            val (meta, decision) = scopedMeta
                            onProgress(found.size + 1, required.size, path)
                            val bytes = zip.readBytes()
                            when {
                                bytes.size > 1_000_000 -> { excluded += RepoFile(path, meta.sha, meta.size, "", decision.category, true, "archive entry exceeds 1 MB audit ingestion limit"); required.remove(path) }
                                bytes.any { it.toInt() == 0 } -> { excluded += RepoFile(path, meta.sha, meta.size, "", decision.category, true, "binary content detected in archive"); required.remove(path) }
                                else -> found[path] = RepoFile(path, meta.sha, meta.size, String(bytes, StandardCharsets.UTF_8), decision.category)
                            }
                        }
                    }
                    zip.closeEntry(); entry = zip.nextEntry
                }
            }
        }
        val missing = required.keys - found.keys
        if (missing.isNotEmpty()) throw IllegalStateException("Pinned repository archive did not contain ${missing.size} required manifest file(s), including ${missing.take(8).joinToString()}. Exhaustive audit aborted rather than silently skipping them.")
        RepoSnapshot(repo, ref, commitSha, required.keys.map { found.getValue(it) }, excluded.distinctBy { it.path }.sortedBy { it.path })
    }

    private fun resolveCommitAndTree(safeRepo: String, ref: String): Pair<String, String> {
        val root = JSONObject(executeText(auth(Request.Builder().url("https://api.github.com/repos/$safeRepo/commits/${encode(ref)}")).build()))
        val commitSha = root.getString("sha")
        val treeSha = root.optJSONObject("commit")?.optJSONObject("tree")?.optString("sha").orEmpty()
        if (treeSha.isBlank()) throw IllegalStateException("GitHub commit response did not include the root tree SHA")
        return commitSha to treeSha
    }

    private fun classifyManifest(safeRepo: String, manifest: List<TreeMeta>): ScopedManifest {
        val gitIgnore = manifest.firstOrNull { it.path == ".gitignore" }?.let { loadBlobText(safeRepo, it.sha) }.orEmpty()
        val matcher = GitIgnoreMatcher(gitIgnore)
        val required = linkedMapOf<String, Pair<TreeMeta, ScopeDecision>>()
        val excluded = mutableListOf<RepoFile>()
        val req = linkedMapOf<String, Int>(); val exc = linkedMapOf<String, Int>()
        for (item in manifest) {
            val d = classifyScope(item.path, item.size, matcher)
            if (d.required) { required[item.path] = item to d; req[d.category] = (req[d.category] ?: 0) + 1 }
            else { val reason = d.reason ?: "excluded by canonical audit scope"; excluded += RepoFile(item.path,item.sha,item.size,"",d.category,true,reason); exc[reason]=(exc[reason]?:0)+1 }
        }
        return ScopedManifest(required, excluded, manifest.size, req, exc)
    }

    private fun classifyScope(path: String, size: Long, ignoreMatcher: GitIgnoreMatcher): ScopeDecision {
        val lower = path.lowercase().replace('\\','/'); val segments=lower.split('/'); val name=segments.lastOrNull().orEmpty(); val tail=name.substringAfterLast('.',""); val ext=if(tail==name) "" else ".$tail"
        if(size>1_000_000)return ScopeDecision(false,"excluded-large","file exceeds 1 MB audit ingestion limit")
        val binary=setOf(".png",".jpg",".jpeg",".gif",".webp",".ico",".pdf",".zip",".gz",".7z",".jar",".aar",".apk",".so",".dll",".exe",".bin",".mp3",".wav",".flac",".mp4",".mov",".avi",".woff",".woff2",".ttf",".otf",".class",".pyc",".pyo",".o",".a",".dylib",".map",".sqlite",".sqlite3",".db",".db-shm",".db-wal")
        if(ext in binary)return ScopeDecision(false,"excluded-binary","binary/generated/non-source file type")
        if(lower.endsWith(".min.js")||lower.endsWith(".min.css"))return ScopeDecision(false,"excluded-generated","generated/minified asset")
        val dirs=setOf(".git","node_modules","vendor","dist","dist-ssr","build",".gradle",".idea","coverage","target","pods",".venv","venv","env","__pycache__",".pytest_cache",".mypy_cache",".ruff_cache",".tox",".next",".nuxt",".svelte-kit",".parcel-cache",".turbo",".cache",".uv-cache","site-packages","bin","obj","generated","generated-src","playwright-report","allure-results","test-results")
        if(segments.any{it in dirs})return ScopeDecision(false,"excluded-generated","generated/vendor/cache directory")
        if(lower.startsWith(".dev/")||lower.startsWith("investigation/")||lower.contains("/generated-evidence/")||lower.contains("/audit-evidence/")||lower.contains("/historical-evidence/")||lower.contains("/forensics/")||lower.contains("/forensic-output/"))return ScopeDecision(false,"excluded-historical-evidence","historical/diagnostic evidence workspace")
        if(ignoreMatcher.isIgnored(lower))return ScopeDecision(false,"excluded-repository-ignored","matches repository .gitignore")
        if(name in setOf(".ds_store","thumbs.db","desktop.ini"))return ScopeDecision(false,"excluded-generated","generated operating-system metadata")
        val source=setOf(".kt",".kts",".java",".py",".pyi",".js",".jsx",".mjs",".cjs",".ts",".tsx",".go",".rs",".c",".h",".hpp",".cpp",".cc",".cs",".rb",".php",".swift",".dart",".scala",".sh",".bash",".zsh",".fish",".ps1",".sql",".proto",".graphql",".gql",".vue",".svelte",".html",".htm",".css",".scss",".sass",".less")
        val testPath=segments.any{it in setOf("test","tests","spec","specs","e2e","integration")}; if(ext in source)return ScopeDecision(true,if(testPath)"tests" else "source")
        val docs=setOf(".md",".mdx",".rst",".adoc",".asciidoc",".mdc"); if(ext in docs)return ScopeDecision(true,if(ext==".mdc")"configuration" else "documentation")
        val configs=setOf(".yml",".yaml",".toml",".ini",".cfg",".conf",".properties",".xml",".json",".jsonc",".lock")
        if(ext in configs){val dataLike=segments.any{it in setOf("fixtures","fixture","samples","sample-data","datasets","dataset","snapshots")};return if(dataLike)ScopeDecision(false,"excluded-test-data","fixture/sample/dataset artifact")else ScopeDecision(true,"configuration")}
        val canonical=setOf("dockerfile","makefile","procfile","gemfile","rakefile","justfile","cmakelists.txt","requirements.txt","constraints.txt","pipfile","poetry.lock","package.json","package-lock.json","pnpm-lock.yaml","yarn.lock","gradlew","gradlew.bat",".gitignore",".gitattributes",".editorconfig",".dockerignore",".npmrc")
        if(name in canonical||name.startsWith("readme")||name.startsWith("license")||name.startsWith("changelog"))return ScopeDecision(true,if(name.startsWith("readme")||name.startsWith("license")||name.startsWith("changelog"))"documentation" else "configuration")
        if(name.endsWith(".env.example")||name.endsWith(".env.sample")||name.endsWith(".example"))return ScopeDecision(true,"configuration")
        if(ext in setOf(".csv",".tsv",".jsonl",".ndjson",".parquet",".feather",".arrow",".pkl",".pickle",".npy",".npz"))return ScopeDecision(false,"excluded-data","runtime/fixture/dataset file type")
        if(ext==".txt"){val canonicalDoc=segments.firstOrNull() in setOf("docs","doc")||name.startsWith("readme")||name.contains("requirements");return if(canonicalDoc)ScopeDecision(true,"documentation")else ScopeDecision(false,"excluded-text-artifact","unclassified text/report artifact")}
        return ScopeDecision(false,"excluded-unclassified","not recognised as canonical source/test/config/documentation")
    }

    private fun loadBlobText(safeRepo:String,sha:String):String=runCatching{val root=JSONObject(executeText(auth(Request.Builder().url("https://api.github.com/repos/$safeRepo/git/blobs/$sha")).build()));if(root.optString("encoding")!="base64")"" else String(Base64.decode(root.optString("content"),Base64.DEFAULT),StandardCharsets.UTF_8)}.getOrDefault("")

    private fun loadCompleteTreeManifest(safeRepo:String,rootTreeSha:String):List<TreeMeta>{val root=JSONObject(executeText(auth(Request.Builder().url("https://api.github.com/repos/$safeRepo/git/trees/$rootTreeSha?recursive=1")).build()));if(!root.optBoolean("truncated",false))return blobsFromTree(root.optJSONArray("tree")?:JSONArray(),"");val out=linkedMapOf<String,TreeMeta>();val q=ArrayDeque<PendingTree>();q.add(PendingTree("",rootTreeSha));val visited=mutableSetOf<String>();var count=0;while(q.isNotEmpty()){val p=q.removeFirst();if(!visited.add(p.sha))continue;if(++count>100_000)throw IllegalStateException("GitHub tree traversal exceeded 100,000 tree objects; exhaustive manifest aborted rather than risking incomplete coverage");val tr=JSONObject(executeText(auth(Request.Builder().url("https://api.github.com/repos/$safeRepo/git/trees/${p.sha}")).build()));if(tr.optBoolean("truncated",false))throw IllegalStateException("GitHub unexpectedly truncated a non-recursive tree object; exhaustive manifest cannot be proven");val a=tr.optJSONArray("tree")?:JSONArray();for(i in 0 until a.length()){val x=a.optJSONObject(i)?:continue;val n=x.optString("path");if(n.isBlank())continue;val path=if(p.prefix.isBlank())n else "${p.prefix}/$n";when(x.optString("type")){"blob"->out[path]=TreeMeta(path,x.optString("sha"),x.optLong("size",0));"tree"->{val s=x.optString("sha");if(s.isBlank())throw IllegalStateException("GitHub tree entry $path did not include a SHA");q.add(PendingTree(path,s))}}}};return out.values.sortedBy{it.path}}
    private fun blobsFromTree(tree:JSONArray,prefix:String):List<TreeMeta>=buildList{for(i in 0 until tree.length()){val x=tree.optJSONObject(i)?:continue;if(x.optString("type")!="blob")continue;val raw=x.optString("path");val path=if(prefix.isBlank())raw else "$prefix/$raw";add(TreeMeta(path,x.optString("sha"),x.optLong("size",0)))}}

    private class GitIgnoreMatcher(text:String){private data class Rule(val regex:Regex,val negated:Boolean);private val rules=text.lineSequence().mapNotNull(::compile).toList();fun isIgnored(path:String):Boolean{var ignored=false;for(r in rules)if(r.regex.matches(path))ignored=!r.negated;return ignored};private fun compile(raw:String):Rule?{var p=raw.trim();if(p.isBlank()||p.startsWith("#"))return null;val n=p.startsWith("!");if(n)p=p.drop(1);if(p.isBlank())return null;p=p.replace('\\','/').removePrefix("/").removeSuffix("/");val body=globToRegex(p);return Rule(Regex((if(p.contains('/'))"^" else "(^|.*/)")+body+"(/.*)?$"),n)};private fun globToRegex(g:String):String{val o=StringBuilder();var i=0;while(i<g.length){when(val c=g[i]){'*'->{if(i+1<g.length&&g[i+1]=='*'){o.append(".*");i++}else o.append("[^/]*")};'?'->o.append("[^/]");'.','(',')','+','|','^','$','@','%'->o.append('\\').append(c);else->o.append(c)};i++};return o.toString()}}
    private fun executeText(request:Request):String{http.newCall(request).execute().use{r->val b=r.body?.string().orEmpty();if(!r.isSuccessful)throw githubError(r.code,b);return b}}
    private fun githubError(code:Int,body:String):IllegalStateException{val m=runCatching{JSONObject(body).optString("message")}.getOrNull().orEmpty().ifBlank{body.take(500)};return IllegalStateException("GitHub HTTP $code: $m")}
    private fun encode(value:String):String=URLEncoder.encode(value,"UTF-8").replace("+","%20")
}
