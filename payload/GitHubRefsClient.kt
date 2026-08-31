package com.llmcouncil.mobile.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class GitHubRefsClient(private val settings: SecureSettings) {
    private val http=OkHttpClient.Builder().connectTimeout(20,TimeUnit.SECONDS).readTimeout(45,TimeUnit.SECONDS).build()
    suspend fun branches(repoFullName:String):List<String> = withContext(Dispatchers.IO){
        require(settings.getGitHubToken().isNotBlank()){ "GitHub token is not configured" }
        val safe=repoFullName.split('/').joinToString("/"){URLEncoder.encode(it,"UTF-8")}
        val out=mutableListOf<String>()
        for(page in 1..10){
            val req=Request.Builder().url("https://api.github.com/repos/$safe/branches?per_page=100&page=$page")
                .header("Authorization","Bearer ${settings.getGitHubToken()}").header("Accept","application/vnd.github+json").header("X-GitHub-Api-Version","2022-11-28").header("User-Agent","OmniCouncil-Android").build()
            http.newCall(req).execute().use{r->val body=r.body?.string().orEmpty();if(!r.isSuccessful){val msg=runCatching{JSONObject(body).optString("message")}.getOrNull().orEmpty().ifBlank{body.take(300)};throw IllegalStateException("GitHub HTTP ${r.code}: $msg")};val a=JSONArray(body);for(i in 0 until a.length())a.optJSONObject(i)?.optString("name")?.takeIf{it.isNotBlank()}?.let(out::add);if(a.length()<100)return@withContext out.distinct()}
        }
        out.distinct()
    }
}
