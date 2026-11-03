package com.jev.probe.core

import android.content.Context
import org.json.JSONObject

data class RoleChatPreference(
    val enabled: Boolean = true,
    val myRole: String = "未确定",
    val otherRole: String = "未确认",
    val tone: String = "自然认识",
    val boundaries: String = ""
)

/** Manual preferences only. Names, model guesses and profile tags never create overrides. */
class RoleChatStore(context: Context) {
    private val prefs = context.getSharedPreferences("role_chat_preferences", Context.MODE_PRIVATE)
    fun load(contactId: String? = null): RoleChatPreference {
        val own = prefs.getString(key(contactId), null)
        val raw = own ?: if (contactId != null) prefs.getString(key(null), null) else null
        val obj = runCatching { JSONObject(raw.orEmpty()) }.getOrDefault(JSONObject())
        return RoleChatPreference(obj.optBoolean("enabled", true),
            obj.optString("myRole", "未确定").takeIf { it in MY_ROLES } ?: "未确定",
            // An app-wide preference must not assign the same role to every stranger.
            if (contactId == null || own == null) "未确认"
            else obj.optString("otherRole", "未确认").take(80),
            obj.optString("tone", "自然认识").takeIf { it in TONES } ?: "自然认识",
            obj.optString("boundaries").take(600))
    }
    fun save(value: RoleChatPreference, contactId: String? = null): Boolean {
        if (value.myRole !in MY_ROLES || value.tone !in TONES) return false
        return prefs.edit().putString(key(contactId), JSONObject()
            .put("enabled", value.enabled).put("myRole", value.myRole)
            .put("otherRole", if (contactId == null) "未确认" else value.otherRole.trim().take(80))
            .put("tone", value.tone).put("boundaries", value.boundaries.trim().take(600)).toString()).commit()
    }
    fun reset(contactId: String): Boolean = prefs.edit().remove(key(contactId)).commit()
    fun clear(): Boolean = prefs.edit().clear().commit()
    private fun key(contactId: String?) = if (contactId == null) "pupcall.defaults" else "pupcall.contact.$contactId"
    companion object {
        val MY_ROLES = listOf("未确定", "偏照顾与引导", "偏接受引导", "可以切换")
        val TONES = listOf("自然认识", "轻松调侃", "温柔亲近")
    }
}
