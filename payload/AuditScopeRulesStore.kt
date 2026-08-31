package com.llmcouncil.mobile.data

import android.content.Context

class AuditScopeRulesStore(context: Context) {
    private val prefs = context.getSharedPreferences("omnicouncil_audit_scope_rules_v1", Context.MODE_PRIVATE)

    fun get(repoFullName: String): String = prefs.getString(key(repoFullName), "").orEmpty()

    fun set(repoFullName: String, rules: String) {
        prefs.edit().putString(key(repoFullName), rules.trim()).apply()
    }

    private fun key(repoFullName: String): String = repoFullName.trim().lowercase()
}
