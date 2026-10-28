package com.jev.probe.core

import android.content.Context

/** Process-local diagnostics; never stores chat text or API credentials. */
object RuntimeState {
    @Volatile var serviceConnected = false
    @Volatile var lastEventPackage: String? = null
    @Volatile var overlayIssue: String? = null

    fun recordAction(context: Context, action: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(LAST_ACTION, action).apply()
    }

    fun lastAction(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(LAST_ACTION, null) ?: "尚未触发分析"

    private const val PREFS = "jev_runtime_status"
    private const val LAST_ACTION = "last_action"
}
