package com.llmcouncil.mobile.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureSettings(context: Context, private val auditMode: Boolean = false) {
    private val prefs = context.getSharedPreferences("llm_council_v4", Context.MODE_PRIVATE)
    private val alias = "llm_council_openrouter_key"
    private val separator = ":"

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }

    private fun setSecret(key: String, value: String) {
        if (value.isBlank()) { prefs.edit().remove(key).apply(); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        prefs.edit().putString(key, Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + separator + Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
    }

    private fun getSecret(key: String): String {
        val packed = prefs.getString(key, null) ?: return ""
        return try {
            val parts = packed.split(separator, limit = 2)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8)
        } catch (_: Exception) { "" }
    }

    fun setApiKey(value: String) = setOpenRouterKey(value)
    fun getApiKey() = getOpenRouterKey()
    fun setOpenRouterKey(value: String) = setSecret("api_key_enc", value)
    fun getOpenRouterKey() = getSecret("api_key_enc")
    fun setOpenAiKey(value: String) = setSecret("openai_key_enc", value)
    fun getOpenAiKey() = getSecret("openai_key_enc")
    fun setAnthropicKey(value: String) = setSecret("anthropic_key_enc", value)
    fun getAnthropicKey() = getSecret("anthropic_key_enc")
    fun setGeminiKey(value: String) = setSecret("gemini_key_enc", value)
    fun getGeminiKey() = getSecret("gemini_key_enc")
    fun setGitHubToken(value: String) = setSecret("github_token_enc", value)
    fun getGitHubToken() = getSecret("github_token_enc")
    fun exportTreeUri(): String? = prefs.getString("export_tree_uri", null)
    fun setExportTreeUri(value: String?) { prefs.edit().apply { if (value == null) remove("export_tree_uri") else putString("export_tree_uri", value) }.apply() }

    private fun standardCouncilModels(): List<String> = prefs.getStringSet("council_models", null)?.toList()?.sorted().orEmpty()
    private fun standardChairman(): String = prefs.getString("chairman_model", "").orEmpty()

    fun councilModels(): List<String> = if (auditMode) {
        (auditReviewerModels() + auditChairman().takeIf { it.isNotBlank() }).filterNotNull().distinct()
    } else standardCouncilModels()
    fun setCouncilModels(ids: Set<String>) { prefs.edit().putStringSet("council_models", ids).apply() }
    fun chairman(): String = if (auditMode) auditChairman() else standardChairman()
    fun setChairman(id: String) { prefs.edit().putString("chairman_model", id).apply() }

    fun auditReviewerModels(): List<String> {
        val packed = prefs.getString("repo_audit_reviewer_models_v1", null)
        if (packed != null) return packed.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
        return standardCouncilModels()
    }
    fun setAuditReviewerModels(ids: List<String>) { prefs.edit().putString("repo_audit_reviewer_models_v1", ids.map { it.trim() }.filter { it.isNotBlank() }.joinToString("\n")).apply() }
    fun auditChairman(): String = prefs.getString("repo_audit_chairman_model_v1", null) ?: standardChairman()
    fun setAuditChairman(id: String) { prefs.edit().putString("repo_audit_chairman_model_v1", id.trim()).apply() }

    fun maxConcurrency() = prefs.getInt("max_concurrency", 6)
    fun setMaxConcurrency(value: Int) { prefs.edit().putInt("max_concurrency", value.coerceIn(1, 12)).apply() }
    fun activePreset(): String? = prefs.getString("active_preset", null)
    fun setActivePreset(value: String?) { prefs.edit().apply { if (value == null) remove("active_preset") else putString("active_preset", value) }.apply() }
}
