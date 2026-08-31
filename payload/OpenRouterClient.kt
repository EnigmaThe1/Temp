package com.llmcouncil.mobile.data

import com.llmcouncil.mobile.model.ModelSource
import com.llmcouncil.mobile.model.OpenRouterModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

sealed class ApiFailure(message: String) : Exception(message) {
    class Authentication(message: String) : ApiFailure(message)
    class Credits(message: String) : ApiFailure(message)
    class RateLimit(message: String) : ApiFailure(message)
    class Unavailable(message: String) : ApiFailure(message)
    class Network(message: String) : ApiFailure(message)
    class Other(message: String) : ApiFailure(message)
}

class OpenRouterClient(private val settings: SecureSettings) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(150, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    suspend fun models(): List<OpenRouterModel> = withContext(Dispatchers.IO) {
        val all = mutableListOf<OpenRouterModel>()
        val failures = mutableListOf<String>()
        fun collect(block: () -> List<OpenRouterModel>) {
            try { all += block() } catch (e: Exception) { failures += (e.message ?: e.toString()) }
        }
        if (settings.getOpenRouterKey().isNotBlank()) collect { openRouterModels() }
        if (settings.getOpenAiKey().isNotBlank()) collect { openAiModels() }
        if (settings.getAnthropicKey().isNotBlank()) collect { anthropicModels() }
        if (settings.getGeminiKey().isNotBlank()) collect { geminiModels() }
        if (all.isEmpty() && failures.isNotEmpty()) throw ApiFailure.Other(failures.joinToString(" · ").take(800))
        all.distinctBy { it.id }.sortedWith(compareBy<OpenRouterModel> { it.source.ordinal }.thenBy { it.name.lowercase() })
    }

    private fun openRouterModels(): List<OpenRouterModel> {
        val request = Request.Builder().url("https://openrouter.ai/api/v1/models")
            .header("Authorization", "Bearer ${settings.getOpenRouterKey()}")
            .header("Accept", "application/json").build()
        return execute(request) { body ->
            val data = JSONObject(body).optJSONArray("data") ?: JSONArray()
            buildList {
                for (i in 0 until data.length()) {
                    val o = data.optJSONObject(i) ?: continue
                    val id = o.optString("id").trim()
                    if (id.isEmpty()) continue
                    val pricing = o.optJSONObject("pricing")
                    val architecture = o.optJSONObject("architecture")
                    add(OpenRouterModel(
                        id, o.optString("name", id).ifBlank { id }, ModelSource.OPENROUTER, id,
                        o.optInt("context_length", 0),
                        pricing?.optString("prompt")?.takeIf { it.isNotBlank() }?.toDoubleOrNull(),
                        pricing?.optString("completion")?.takeIf { it.isNotBlank() }?.toDoubleOrNull(),
                        architecture?.optJSONArray("input_modalities").toStringSet(),
                        architecture?.optJSONArray("output_modalities").toStringSet(),
                        description = o.optString("description", "")
                    ))
                }
            }
        }
    }

    private fun openAiModels(): List<OpenRouterModel> {
        val request = Request.Builder().url("https://api.openai.com/v1/models")
            .header("Authorization", "Bearer ${settings.getOpenAiKey()}").build()
        return execute(request) { body ->
            val data = JSONObject(body).optJSONArray("data") ?: JSONArray()
            buildList {
                for (i in 0 until data.length()) {
                    val id = data.optJSONObject(i)?.optString("id")?.trim().orEmpty()
                    if (!looksLikeOpenAiTextModel(id)) continue
                    add(OpenRouterModel(ModelSource.key(ModelSource.OPENAI, id), id, ModelSource.OPENAI, id,
                        inputModalities = setOf("text"), outputModalities = setOf("text"), description = "Direct OpenAI API model"))
                }
            }
        }
    }

    private fun anthropicModels(): List<OpenRouterModel> {
        val request = Request.Builder().url("https://api.anthropic.com/v1/models?limit=100")
            .header("x-api-key", settings.getAnthropicKey()).header("anthropic-version", "2023-06-01").build()
        return execute(request) { body ->
            val data = JSONObject(body).optJSONArray("data") ?: JSONArray()
            buildList {
                for (i in 0 until data.length()) {
                    val o = data.optJSONObject(i) ?: continue
                    val id = o.optString("id").trim()
                    if (id.isEmpty()) continue
                    add(OpenRouterModel(ModelSource.key(ModelSource.ANTHROPIC, id), o.optString("display_name", id).ifBlank { id }, ModelSource.ANTHROPIC, id,
                        inputModalities = setOf("text"), outputModalities = setOf("text"), description = "Direct Anthropic API model"))
                }
            }
        }
    }

    private fun geminiModels(): List<OpenRouterModel> {
        val request = Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/models")
            .header("x-goog-api-key", settings.getGeminiKey()).build()
        return execute(request) { body ->
            val data = JSONObject(body).optJSONArray("models") ?: JSONArray()
            buildList {
                for (i in 0 until data.length()) {
                    val o = data.optJSONObject(i) ?: continue
                    val methods = o.optJSONArray("supportedGenerationMethods").toStringSet()
                    if (methods.isNotEmpty() && "generatecontent" !in methods) continue
                    val id = o.optString("name").removePrefix("models/").trim()
                    if (id.isEmpty() || !id.contains("gemini", true)) continue
                    add(OpenRouterModel(ModelSource.key(ModelSource.GEMINI, id), o.optString("displayName", id).ifBlank { id }, ModelSource.GEMINI, id,
                        o.optInt("inputTokenLimit", 0), inputModalities = setOf("text"), outputModalities = setOf("text"),
                        description = o.optString("description", "Direct Gemini API model")))
                }
            }
        }
    }

    suspend fun chat(model: String, prompt: String, maxTokens: Int = 2048): String = withContext(Dispatchers.IO) {
        val source = ModelSource.fromKey(model)
        val id = ModelSource.apiIdFromKey(model)
        val auditCall = prompt.contains("EXHAUSTIVE engineering audit", true) ||
            prompt.contains("repository-audit evidence", true) ||
            prompt.contains("repository audit", true)
        val attempts = if (auditCall) 3 else 1
        var last: Exception? = null
        repeat(attempts) { attempt ->
            try {
                val text = when (source) {
                    ModelSource.OPENROUTER -> openRouterChat(id, prompt, maxTokens)
                    ModelSource.OPENAI -> openAiChat(id, prompt, maxTokens)
                    ModelSource.ANTHROPIC -> anthropicChat(id, prompt, maxTokens)
                    ModelSource.GEMINI -> geminiChat(id, prompt, maxTokens)
                }.trim()
                if (!auditCall || isSubstantiveAuditText(text)) return@withContext text
                last = ApiFailure.Unavailable("$model returned an empty or non-substantive repository-audit response")
            } catch (e: Exception) {
                last = e
            }
            if (attempt + 1 < attempts) delay(750L * (attempt + 1))
        }
        throw last ?: ApiFailure.Unavailable("$model did not return a usable response")
    }

    private fun isSubstantiveAuditText(text: String): Boolean {
        if (text.length < 80) return false
        val lower = text.lowercase()
        if (lower in setOf("null", "nil", "none", "n/a", "ok")) return false
        return text.count { it.isLetter() } >= 40
    }

    private fun openRouterChat(id: String, prompt: String, max: Int): String {
        if (settings.getOpenRouterKey().isBlank()) throw ApiFailure.Authentication("OpenRouter API key is not configured")
        val payload = JSONObject().put("model", id).put("max_tokens", max.coerceIn(8, 8192))
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
        val request = Request.Builder().url("https://openrouter.ai/api/v1/chat/completions")
            .header("Authorization", "Bearer ${settings.getOpenRouterKey()}")
            .header("Content-Type", "application/json")
            .header("HTTP-Referer", "https://github.com/EnigmaThe1/OmniCouncil")
            .header("X-Title", "OmniCouncil")
            .post(payload.toString().toRequestBody(jsonType)).build()
        return execute(request) { body -> parseOpenRouterText(id, body) }
    }

    private fun parseOpenRouterText(modelId: String, body: String): String {
        val root = runCatching { JSONObject(body) }.getOrElse {
            throw ApiFailure.Other("OpenRouter returned malformed JSON for $modelId: ${body.take(300)}")
        }
        root.optJSONObject("error")?.let { error ->
            val message = error.optString("message").ifBlank { error.toString() }
            throw ApiFailure.Unavailable("OpenRouter provider error for $modelId: ${message.take(600)}")
        }
        val choices = root.optJSONArray("choices")
            ?: throw ApiFailure.Unavailable("OpenRouter returned no choices for $modelId. Response: ${body.take(350)}")
        if (choices.length() == 0) throw ApiFailure.Unavailable("OpenRouter returned an empty choices array for $modelId")
        val choice = choices.optJSONObject(0)
            ?: throw ApiFailure.Unavailable("OpenRouter returned a malformed first choice for $modelId")
        choice.optJSONObject("error")?.let { error ->
            throw ApiFailure.Unavailable("OpenRouter choice error for $modelId: ${error.optString("message", error.toString()).take(600)}")
        }
        val message = choice.optJSONObject("message")
        val content = message?.opt("content")
        val text = when (content) {
            is String -> content
            is JSONArray -> buildString {
                for (i in 0 until content.length()) {
                    val part = content.optJSONObject(i) ?: continue
                    val value = part.optString("text").ifBlank { part.optString("content") }
                    if (value.isNotBlank()) { if (isNotEmpty()) append('\n'); append(value) }
                }
            }
            else -> choice.optString("text")
        }.trim()
        if (text.isBlank()) {
            val finish = choice.optString("finish_reason", "unknown")
            throw ApiFailure.Unavailable("OpenRouter returned no text for $modelId (finish_reason=$finish)")
        }
        return text
    }

    private fun openAiChat(id: String, prompt: String, max: Int): String {
        if (settings.getOpenAiKey().isBlank()) throw ApiFailure.Authentication("OpenAI API key is not configured")
        val payload = JSONObject().put("model", id).put("input", prompt).put("max_output_tokens", max.coerceIn(16, 8192))
        val request = Request.Builder().url("https://api.openai.com/v1/responses")
            .header("Authorization", "Bearer ${settings.getOpenAiKey()}").header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(jsonType)).build()
        return execute(request) { body ->
            val output = JSONObject(body).optJSONArray("output") ?: JSONArray()
            buildString {
                for (i in 0 until output.length()) {
                    val content = output.optJSONObject(i)?.optJSONArray("content") ?: continue
                    for (j in 0 until content.length()) {
                        val text = content.optJSONObject(j)?.optString("text").orEmpty()
                        if (text.isNotBlank()) { if (isNotEmpty()) append('\n'); append(text) }
                    }
                }
            }
        }
    }

    private fun anthropicChat(id: String, prompt: String, max: Int): String {
        if (settings.getAnthropicKey().isBlank()) throw ApiFailure.Authentication("Anthropic API key is not configured")
        val payload = JSONObject().put("model", id).put("max_tokens", max.coerceIn(16, 8192))
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
        val request = Request.Builder().url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", settings.getAnthropicKey()).header("anthropic-version", "2023-06-01")
            .header("Content-Type", "application/json").post(payload.toString().toRequestBody(jsonType)).build()
        return execute(request) { body ->
            val content = JSONObject(body).optJSONArray("content") ?: JSONArray()
            buildString {
                for (i in 0 until content.length()) {
                    val text = content.optJSONObject(i)?.takeIf { it.optString("type") == "text" }?.optString("text").orEmpty()
                    if (text.isNotBlank()) { if (isNotEmpty()) append('\n'); append(text) }
                }
            }
        }
    }

    private fun geminiChat(id: String, prompt: String, max: Int): String {
        if (settings.getGeminiKey().isBlank()) throw ApiFailure.Authentication("Gemini API key is not configured")
        val encoded = URLEncoder.encode(id, "UTF-8").replace("%2F", "/")
        val payload = JSONObject().put("contents", JSONArray().put(JSONObject().put("role", "user")
            .put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            .put("generationConfig", JSONObject().put("maxOutputTokens", max.coerceIn(16, 8192)))
        val request = Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/models/$encoded:generateContent")
            .header("x-goog-api-key", settings.getGeminiKey()).header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(jsonType)).build()
        return execute(request) { body ->
            val candidates = JSONObject(body).optJSONArray("candidates") ?: JSONArray()
            val parts = candidates.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
            buildString {
                for (i in 0 until parts.length()) {
                    val text = parts.optJSONObject(i)?.optString("text").orEmpty()
                    if (text.isNotBlank()) { if (isNotEmpty()) append('\n'); append(text) }
                }
            }
        }
    }

    private fun looksLikeOpenAiTextModel(id: String): Boolean {
        val s = id.lowercase()
        if (listOf("embedding", "whisper", "tts", "image", "dall-e", "realtime", "audio").any { s.contains(it) }) return false
        return s.startsWith("gpt-") || s.startsWith("o1") || s.startsWith("o3") || s.startsWith("o4") || s.startsWith("chatgpt-") || s.startsWith("codex-")
    }

    private fun <T> execute(request: Request, parser: (String) -> T): T {
        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw failure(response.code, body)
                return parser(body)
            }
        } catch (e: ApiFailure) {
            throw e
        } catch (e: IOException) {
            throw ApiFailure.Network(e.message ?: e.toString())
        } catch (e: Exception) {
            throw ApiFailure.Other(e.message ?: e.toString())
        }
    }

    private fun JSONArray?.toStringSet(): Set<String> = if (this == null) emptySet() else buildSet {
        for (i in 0 until length()) optString(i).trim().lowercase().takeIf { it.isNotEmpty() }?.let(::add)
    }

    private fun failure(code: Int, body: String): ApiFailure {
        val message = try {
            val root = JSONObject(body)
            root.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
                ?: root.optString("message").takeIf { it.isNotBlank() } ?: body
        } catch (_: Exception) { body }
        val clean = message.ifBlank { "HTTP $code" }.take(700)
        return when (code) {
            401, 403 -> ApiFailure.Authentication(clean)
            402 -> ApiFailure.Credits(clean)
            429 -> ApiFailure.RateLimit(clean)
            404, 408, 502, 503, 504 -> ApiFailure.Unavailable(clean)
            else -> ApiFailure.Other("HTTP $code — $clean")
        }
    }
}
