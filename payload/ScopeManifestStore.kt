package com.llmcouncil.mobile.data

import android.content.Context
import com.llmcouncil.mobile.model.AuditScopeEntry
import com.llmcouncil.mobile.model.AuditScopePreview
import com.llmcouncil.mobile.model.AuditScopeStatus
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class ScopeManifestStore(private val context: Context) {
    private fun fileFor(commitSha: String): File = File(context.filesDir, "audit-scope-${commitSha.take(24)}.json")

    fun save(preview: AuditScopePreview) {
        val root = JSONObject()
            .put("repo", preview.repoFullName)
            .put("ref", preview.ref)
            .put("commit", preview.commitSha)
            .put("totalTracked", preview.totalTrackedFiles)
            .put("validationSummary", preview.validationSummary)
            .put("rulesText", preview.rulesText)
            .put("manifestHash", preview.manifestHash)
        val entries = JSONArray()
        preview.manifest.forEach { e ->
            entries.put(JSONObject()
                .put("path", e.path)
                .put("sha", e.sha)
                .put("size", e.size)
                .put("category", e.category)
                .put("status", e.status.name)
                .put("reason", e.reason)
                .put("confidence", e.confidence)
                .put("decisionSource", e.decisionSource))
        }
        root.put("manifest", entries)
        val file = fileFor(preview.commitSha)
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(root.toString())
        if (!tmp.renameTo(file)) {
            file.writeText(root.toString())
            tmp.delete()
        }
    }

    fun load(commitSha: String): AuditScopePreview? = runCatching {
        val file = fileFor(commitSha)
        if (!file.exists()) return@runCatching null
        val root = JSONObject(file.readText())
        val entriesJson = root.optJSONArray("manifest") ?: JSONArray()
        val entries = buildList {
            for (i in 0 until entriesJson.length()) {
                val o = entriesJson.optJSONObject(i) ?: continue
                add(AuditScopeEntry(
                    path = o.optString("path"),
                    sha = o.optString("sha"),
                    size = o.optLong("size"),
                    category = o.optString("category", "unclassified"),
                    status = runCatching { AuditScopeStatus.valueOf(o.optString("status")) }.getOrDefault(AuditScopeStatus.NEEDS_REVIEW),
                    reason = o.optString("reason"),
                    confidence = o.optInt("confidence", 0),
                    decisionSource = o.optString("decisionSource", "stored")
                ))
            }
        }
        val preview = AuditScopePreview(
            repoFullName = root.optString("repo"),
            ref = root.optString("ref"),
            commitSha = root.optString("commit"),
            totalTrackedFiles = root.optInt("totalTracked", entries.size),
            manifest = entries,
            validationSummary = root.optString("validationSummary"),
            rulesText = root.optString("rulesText")
        )
        val storedHash = root.optString("manifestHash")
        if (storedHash.isNotBlank() && storedHash != preview.manifestHash) return@runCatching null
        preview
    }.getOrNull()

    fun delete(commitSha: String) { fileFor(commitSha).delete() }
}
