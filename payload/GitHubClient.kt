package com.llmcouncil.mobile.data

import android.util.Base64
import com.llmcouncil.mobile.domain.RepositoryContentSanitizer
import com.llmcouncil.mobile.model.*
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

    private data class TreeMeta(val path:String,val sha:String,val size:Long)
    private data class PendingTree(val prefix:String,val sha:String)
    private data class ScopeDecision(val status:AuditScopeStatus,val category:String,val reason:String,val confidence:Int,val source:String="deterministic")
    private data class CustomRule(val include:Boolean,val pattern:String,val regex:Regex,val sourceLine:String)

    private fun auth(builder: Request.Builder): Request.Builder {
        val token = settings.getGitHubToken()
        if (token.isNotBlank()) builder.header("Authorization", "Bearer $token")
        return builder.header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "OmniCouncil-Android")
    }

    suspend fun listRepos(): List<GitHubRepo> = withContext(Dispatchers.IO) {
        require(settings.getGitHubToken().isNotBlank()) { "GitHub token is not configured" }
        val out = mutableListOf<GitHubRepo>()
        for (page in 1..20) {
            val req = auth(Request.Builder().url("https://api.github.com/user/repos?per_page=100&page=$page&sort=updated&direction=desc&affiliation=owner,collaborator,organization_member")).build()
            val data = JSONArray(executeText(req))
            for (i in 0 until data.length()) {
                val o = data.getJSONObject(i)
                out += GitHubRepo(o.getString("full_name"), o.optString("default_branch","main"), o.optBoolean("private",false), o.optString("updated_at",""), o.optString("description",""))
            }
            if (data.length() < 100) break
        }
        out.distinctBy { it.fullName }.sortedByDescending { it.updatedAt }
    }

    suspend fun previewScope(repo: GitHubRepo, ref: String = repo.defaultBranch, customRulesText: String = ""): AuditScopePreview = withContext(Dispatchers.IO) {
        require(settings.getGitHubToken().isNotBlank()) { "GitHub token is not configured" }
        val safeRepo = repo.fullName.split('/').joinToString("/") { encode(it) }
        val (commitSha, treeSha) = resolveCommitAndTree(safeRepo, ref)
        val manifest = loadCompleteTreeManifest(safeRepo, treeSha)
        val gitIgnore = manifest.firstOrNull { it.path == ".gitignore" }?.let { loadBlobText(safeRepo, it.sha) }.orEmpty()
        val matcher = GitIgnoreMatcher(gitIgnore)
        val customRules = parseCustomRules(customRulesText)
        val entries = manifest.map { item ->
            val d = classifyScope(item.path, item.size, matcher, customRules)
            AuditScopeEntry(item.path,item.sha,item.size,d.category,d.status,d.reason,d.confidence,d.source)
        }
        AuditScopePreview(repo.fullName, ref, commitSha, manifest.size, entries, rulesText=customRulesText.trim())
    }

    suspend fun loadScopeSamples(
        repoFullName:String,
        commitSha:String,
        paths:Set<String>,
        maxCharsPerFile:Int=1800
    ):Map<String,String> = withContext(Dispatchers.IO) {
        if(paths.isEmpty()) return@withContext emptyMap()
        require(settings.getGitHubToken().isNotBlank()) { "GitHub token is not configured" }
        val safeRepo = repoFullName.split('/').joinToString("/") { encode(it) }
        val (_,treeSha)=resolveCommitAndTree(safeRepo,commitSha)
        val meta=loadCompleteTreeManifest(safeRepo,treeSha).associateBy{it.path}
        val found=linkedMapOf<String,String>()
        for(path in paths.sorted()) {
            val item=meta[path] ?: continue
            if(item.size>1_000_000) continue
            val raw=loadBlobText(safeRepo,item.sha)
            if(raw.isBlank()) continue
            val sanitized=RepositoryContentSanitizer.sanitize(raw,maxCharsPerFile)
            found[path]=buildString {
                if(sanitized.redactionCount>0) append("[${sanitized.redactionCount} sensitive value(s) redacted]\n")
                append(sanitized.text)
            }
        }
        found
    }

    suspend fun snapshot(
        repo: GitHubRepo,
        ref: String = repo.defaultBranch,
        approvedManifest: AuditScopePreview,
        onProgress: (Int,Int,String)->Unit = { _,_,_-> }
    ): RepoSnapshot = withContext(Dispatchers.IO) {
        require(settings.getGitHubToken().isNotBlank()) { "GitHub token is not configured" }
        require(approvedManifest.unresolvedFiles == 0) { "Audit scope still has ${approvedManifest.unresolvedFiles} unresolved files" }
        val safeRepo = repo.fullName.split('/').joinToString("/") { encode(it) }
        val (commitSha, treeSha) = resolveCommitAndTree(safeRepo, ref)
        require(commitSha == approvedManifest.commitSha) { "Repository moved since scope validation. Re-analyse scope before auditing." }
        require(repo.fullName == approvedManifest.repoFullName) { "Validated scope belongs to a different repository" }

        val current = loadCompleteTreeManifest(safeRepo, treeSha).associateBy { it.path }
        val approvedByPath = approvedManifest.manifest.associateBy { it.path }
        if (current.keys != approvedByPath.keys) throw IllegalStateException("Tracked-file manifest changed after validation. Re-analyse scope.")
        for ((path, meta) in current) {
            val approved = approvedByPath.getValue(path)
            if (approved.sha != meta.sha) throw IllegalStateException("File $path changed after scope validation. Re-analyse scope.")
        }

        val required = approvedManifest.manifest.filter { it.status == AuditScopeStatus.REQUIRED }.associateBy { it.path }.toMutableMap()
        val excluded = approvedManifest.manifest.filter { it.status == AuditScopeStatus.EXCLUDED }.map {
            RepoFile(it.path,it.sha,it.size,"",it.category,true,it.reason)
        }.toMutableList()
        val found = linkedMapOf<String,RepoFile>()

        val archive = auth(Request.Builder().url("https://api.github.com/repos/$safeRepo/zipball/$commitSha")).build()
        http.newCall(archive).execute().use { response ->
            if (!response.isSuccessful) throw githubError(response.code,response.body?.string().orEmpty())
            val body = response.body ?: throw IllegalStateException("GitHub returned an empty repository archive")
            ZipInputStream(body.byteStream().buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val path = entry.name.substringAfter('/',entry.name)
                        val scope = required[path]
                        if (scope != null) {
                            onProgress(found.size + 1, required.size, path)
                            val bytes = zip.readBytes()
                            when {
                                bytes.size > 1_000_000 -> throw IllegalStateException("Validated required file $path exceeds ingestion limit at snapshot time")
                                bytes.any { it.toInt() == 0 } -> throw IllegalStateException("Validated required file $path is binary at snapshot time")
                                else -> found[path] = RepoFile(path,scope.sha,scope.size,String(bytes,StandardCharsets.UTF_8),scope.category)
                            }
                        }
                    }
                    zip.closeEntry(); entry = zip.nextEntry
                }
            }
        }
        val missing = required.keys - found.keys
        if (missing.isNotEmpty()) throw IllegalStateException("Pinned archive omitted ${missing.size} validated required files, including ${missing.take(8).joinToString()}")
        RepoSnapshot(repo,ref,commitSha,required.keys.map { found.getValue(it) },excluded.sortedBy { it.path })
    }

    private fun classifyScope(path:String,size:Long,ignore:GitIgnoreMatcher,customRules:List<CustomRule>):ScopeDecision {
        val lower = path.lowercase().replace('\\','/')
        val segments = lower.split('/')
        val name = segments.lastOrNull().orEmpty()
        val tail = name.substringAfterLast('.',"")
        val ext = if (tail == name) "" else ".$tail"
        fun excluded(category:String,reason:String,confidence:Int=99,source:String="deterministic")=ScopeDecision(AuditScopeStatus.EXCLUDED,category,reason,confidence,source)
        fun required(category:String,reason:String,confidence:Int=98,source:String="deterministic")=ScopeDecision(AuditScopeStatus.REQUIRED,category,reason,confidence,source)
        fun review(category:String,reason:String,confidence:Int=50)=ScopeDecision(AuditScopeStatus.NEEDS_REVIEW,category,reason,confidence,"deterministic")

        if (size > 1_000_000) return excluded("excluded-large","hard exclusion: file exceeds 1 MB audit ingestion limit",100,"hard-rule")
        val binary = setOf(".png",".jpg",".jpeg",".gif",".webp",".ico",".pdf",".zip",".gz",".7z",".jar",".aar",".apk",".so",".dll",".exe",".bin",".mp3",".wav",".flac",".mp4",".mov",".avi",".woff",".woff2",".ttf",".otf",".class",".pyc",".pyo",".o",".a",".dylib",".map",".sqlite",".sqlite3",".db",".db-shm",".db-wal")
        if (ext in binary) return excluded("excluded-binary","hard exclusion: binary/generated/non-source file type",100,"hard-rule")
        if (lower.startsWith(".dev/") || lower == ".dev") return excluded("excluded-dev","hard exclusion: .dev workspace is outside audit scope",100,"hard-rule")
        if (lower.endsWith(".min.js") || lower.endsWith(".min.css")) return excluded("excluded-generated","hard exclusion: generated/minified asset",100,"hard-rule")

        customRules.lastOrNull { it.regex.matches(lower) }?.let { rule ->
            return if (rule.include) required("custom-required","included by repository-specific rule: ${rule.sourceLine}",100,"user-rule")
            else excluded("custom-excluded","excluded by repository-specific rule: ${rule.sourceLine}",100,"user-rule")
        }

        val generatedDirs = setOf(".git","node_modules","vendor","dist","dist-ssr","build",".gradle",".idea","coverage","target","pods",".venv","venv","env","__pycache__",".pytest_cache",".mypy_cache",".ruff_cache",".tox",".next",".nuxt",".svelte-kit",".parcel-cache",".turbo",".cache",".uv-cache","site-packages","bin","obj","generated","generated-src","playwright-report","allure-results","test-results")
        if (segments.any { it in generatedDirs }) return excluded("excluded-generated","generated/vendor/cache directory")
        if (lower.startsWith("investigation/") || lower.contains("/generated-evidence/") || lower.contains("/audit-evidence/") || lower.contains("/historical-evidence/") || lower.contains("/forensics/") || lower.contains("/forensic-output/")) return excluded("excluded-historical-evidence","historical/diagnostic evidence workspace")
        if (ignore.isIgnored(lower)) return review("repository-ignored","tracked file matches .gitignore; treated as a signal, not automatic exclusion",65)

        val sourceExt = setOf(".kt",".kts",".java",".py",".pyi",".js",".jsx",".mjs",".cjs",".ts",".tsx",".go",".rs",".c",".h",".hpp",".cpp",".cc",".cs",".rb",".php",".swift",".dart",".scala",".sh",".bash",".zsh",".fish",".ps1",".sql",".proto",".graphql",".gql",".vue",".svelte",".html",".htm",".css",".scss",".sass",".less")
        val testPath = segments.any { it in setOf("test","tests","spec","specs","e2e","integration") }
        val suspiciousSourceFamily = segments.any { it in setOf("archive","archives","legacy","examples","example","samples","sample","fixtures","fixture","benchmarks","benchmark") }
        if (ext in sourceExt) {
            val category = if (testPath) "tests" else "source"
            return if (suspiciousSourceFamily) review(category,"source-like file is under an archive/example/fixture/legacy family")
            else required(category,"recognised ${if(testPath) "test" else "source"} file")
        }

        val canonicalNames = setOf("dockerfile","makefile","procfile","gemfile","rakefile","justfile","cmakelists.txt","requirements.txt","constraints.txt","pipfile","poetry.lock","package.json","package-lock.json","pnpm-lock.yaml","yarn.lock","gradlew","gradlew.bat",".gitignore",".gitattributes",".editorconfig",".dockerignore",".npmrc")
        if (name in canonicalNames || name.endsWith(".env.example") || name.endsWith(".env.sample")) return required("configuration","canonical build/dependency/repository configuration")
        if (lower.startsWith(".github/") && ext in setOf(".yml",".yaml",".md")) return required("configuration","GitHub CI/workflow/governance configuration")

        val configExt = setOf(".yml",".yaml",".toml",".ini",".cfg",".conf",".properties",".xml",".json",".jsonc",".lock")
        if (ext in configExt) {
            if (segments.any { it in setOf("fixtures","fixture","samples","sample-data","datasets","dataset","snapshots") }) return excluded("excluded-test-data","fixture/sample/dataset artifact")
            if (segments.size == 1) return required("configuration","root project configuration",92)
            return review("configuration","structured text may be configuration, generated metadata, report data or runtime state")
        }

        val docExt = setOf(".md",".mdx",".rst",".adoc",".asciidoc")
        if (ext in docExt) {
            if (segments.size == 1 && (name.startsWith("readme") || name.startsWith("license") || name.startsWith("changelog") || name.startsWith("contributing") || name.startsWith("security"))) return required("documentation","canonical root project documentation")
            val canonicalDocFamily = segments.any { it in setOf("architecture","architectures","adr","adrs","requirements","spec","specs","api","security","deployment","deploy","operations","runbooks","runbook") }
            return if (canonicalDocFamily) required("documentation","canonical architecture/requirements/API/security/operations documentation",90)
            else review("documentation","documentation-like file requires current-vs-historical scope validation")
        }

        val dataExt = setOf(".csv",".tsv",".jsonl",".ndjson",".parquet",".feather",".arrow",".pkl",".pickle",".npy",".npz")
        if (ext in dataExt) return excluded("excluded-data","runtime/fixture/dataset file type")
        if (ext == ".txt") return review("documentation","plain text may be canonical documentation or generated/report output")
        return review("unclassified","file is not safely classifiable from deterministic repository metadata")
    }

    private fun parseCustomRules(text:String):List<CustomRule> = text.lineSequence().mapIndexedNotNull { index, raw ->
        val line = raw.trim()
        if (line.isBlank() || line.startsWith("#")) return@mapIndexedNotNull null
        val lower = line.lowercase()
        val include = when {
            lower.startsWith("include:") -> true
            lower.startsWith("exclude:") -> false
            line.startsWith("+") -> true
            line.startsWith("-") -> false
            else -> throw IllegalArgumentException("Invalid custom scope rule on line ${index + 1}. Use include: <glob> or exclude: <glob>.")
        }
        var pattern = when {
            lower.startsWith("include:") || lower.startsWith("exclude:") -> line.substringAfter(':').trim()
            else -> line.drop(1).trim()
        }.replace('\\','/').removePrefix("/")
        if (pattern.isBlank()) throw IllegalArgumentException("Empty custom scope pattern on line ${index + 1}")
        if (!pattern.contains('*') && !pattern.contains('?') && !pattern.substringAfterLast('/').contains('.')) pattern = pattern.trimEnd('/') + "/**"
        CustomRule(include,pattern,Regex("^${globToRegex(pattern.lowercase())}$"),line)
    }.toList()

    private fun globToRegex(glob:String):String {
        val out=StringBuilder(); var i=0
        while(i<glob.length){ when(val c=glob[i]){
            '*'->{ if(i+1<glob.length&&glob[i+1]=='*'){out.append(".*");i++}else out.append("[^/]*") }
            '?'->out.append("[^/]")
            '.', '(', ')', '+', '|', '^', '$', '@', '%', '[', ']', '{', '}' -> out.append('\\').append(c)
            else->out.append(c)
        }; i++ }
        return out.toString()
    }

    private fun resolveCommitAndTree(safeRepo:String,ref:String):Pair<String,String> {
        val root = JSONObject(executeText(auth(Request.Builder().url("https://api.github.com/repos/$safeRepo/commits/${encode(ref)}")).build()))
        val commit = root.getString("sha")
        val tree = root.optJSONObject("commit")?.optJSONObject("tree")?.optString("sha").orEmpty()
        if (tree.isBlank()) throw IllegalStateException("GitHub commit response did not include root tree SHA")
        return commit to tree
    }

    private fun loadBlobText(safeRepo:String,sha:String):String = runCatching {
        val root = JSONObject(executeText(auth(Request.Builder().url("https://api.github.com/repos/$safeRepo/git/blobs/$sha")).build()))
        if (root.optString("encoding") != "base64") "" else String(Base64.decode(root.optString("content"),Base64.DEFAULT),StandardCharsets.UTF_8)
    }.getOrDefault("")

    private fun loadCompleteTreeManifest(safeRepo:String,rootTreeSha:String):List<TreeMeta> {
        val recursive = JSONObject(executeText(auth(Request.Builder().url("https://api.github.com/repos/$safeRepo/git/trees/$rootTreeSha?recursive=1")).build()))
        if (!recursive.optBoolean("truncated",false)) return blobsFromTree(recursive.optJSONArray("tree") ?: JSONArray(),"")
        val out = linkedMapOf<String,TreeMeta>()
        val queue = ArrayDeque<PendingTree>(); queue.add(PendingTree("",rootTreeSha))
        val visited = mutableSetOf<String>(); var count = 0
        while (queue.isNotEmpty()) {
            val pending = queue.removeFirst(); if (!visited.add(pending.sha)) continue
            if (++count > 100_000) throw IllegalStateException("GitHub tree traversal exceeded 100,000 tree objects")
            val root = JSONObject(executeText(auth(Request.Builder().url("https://api.github.com/repos/$safeRepo/git/trees/${pending.sha}")).build()))
            if (root.optBoolean("truncated",false)) throw IllegalStateException("GitHub truncated a non-recursive tree object")
            val tree = root.optJSONArray("tree") ?: JSONArray()
            for (i in 0 until tree.length()) {
                val item = tree.optJSONObject(i) ?: continue
                val name = item.optString("path"); if (name.isBlank()) continue
                val path = if (pending.prefix.isBlank()) name else "${pending.prefix}/$name"
                when (item.optString("type")) {
                    "blob" -> out[path] = TreeMeta(path,item.optString("sha"),item.optLong("size",0))
                    "tree" -> queue.add(PendingTree(path,item.optString("sha")))
                }
            }
        }
        return out.values.sortedBy { it.path }
    }

    private fun blobsFromTree(tree:JSONArray,prefix:String):List<TreeMeta> = buildList {
        for (i in 0 until tree.length()) {
            val item = tree.optJSONObject(i) ?: continue
            if (item.optString("type") != "blob") continue
            val raw = item.optString("path")
            val path = if (prefix.isBlank()) raw else "$prefix/$raw"
            add(TreeMeta(path,item.optString("sha"),item.optLong("size",0)))
        }
    }

    private class GitIgnoreMatcher(text:String) {
        private data class Rule(val regex:Regex,val negated:Boolean)
        private val rules = text.lineSequence().mapNotNull(::compile).toList()
        fun isIgnored(path:String):Boolean { var ignored=false; for(rule in rules) if(rule.regex.matches(path)) ignored=!rule.negated; return ignored }
        private fun compile(raw:String):Rule? {
            var p=raw.trim(); if(p.isBlank()||p.startsWith("#"))return null
            val negated=p.startsWith("!"); if(negated)p=p.drop(1); if(p.isBlank())return null
            p=p.replace('\\','/').removePrefix("/").removeSuffix("/")
            val body=globToRegex(p); val prefix=if(p.contains('/'))"^" else "(^|.*/)"
            return Rule(Regex(prefix+body+"(/.*)?$"),negated)
        }
        private fun globToRegex(glob:String):String {
            val out=StringBuilder(); var i=0
            while(i<glob.length){ when(val c=glob[i]){
                '*'->{ if(i+1<glob.length&&glob[i+1]=='*'){out.append(".*");i++}else out.append("[^/]*") }
                '?'->out.append("[^/]")
                '.', '(', ')', '+', '|', '^', '$', '@', '%' -> out.append('\\').append(c)
                else->out.append(c)
            }; i++ }
            return out.toString()
        }
    }

    private fun executeText(request:Request):String { http.newCall(request).execute().use { r -> val body=r.body?.string().orEmpty(); if(!r.isSuccessful)throw githubError(r.code,body); return body } }
    private fun githubError(code:Int,body:String):IllegalStateException { val msg=runCatching{JSONObject(body).optString("message")}.getOrNull().orEmpty().ifBlank{body.take(500)}; return IllegalStateException("GitHub HTTP $code: $msg") }
    private fun encode(value:String)=URLEncoder.encode(value,"UTF-8").replace("+","%20")
}
