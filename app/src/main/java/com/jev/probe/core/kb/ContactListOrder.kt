package com.jev.probe.core.kb

import android.content.Context
import org.json.JSONArray

/** Presentation preferences only. Contact identities, profiles and memory are never rewritten. */
class ContactListOrder(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("jev_contact_list_order", Context.MODE_PRIVATE)
    val manual: Boolean get() = prefs.getString("mode", "memory") == "manual"

    private fun savedIds(): List<String> = runCatching {
        val values = JSONArray(prefs.getString("ids", "[]"))
        (0 until values.length()).map { values.optString(it) }.filter { it.isNotBlank() }.distinct()
    }.getOrDefault(emptyList())

    fun arrange(contacts: List<Contact>, counts: Map<String, Int>): List<Contact> {
        val positions = savedIds().withIndex().associate { it.value to it.index }
        val original = contacts.withIndex().associate { it.value.id to it.index }
        val memory = compareByDescending<Contact> { counts[it.id] ?: 0 }
            .thenBy { positions[it.id] ?: Int.MAX_VALUE }
            .thenBy { original[it.id] ?: Int.MAX_VALUE }
        return if (manual) contacts.sortedWith(compareBy<Contact> { positions[it.id] ?: Int.MAX_VALUE }
            .then(memory)) else contacts.sortedWith(memory)
    }

    fun useMemoryOrder(): Boolean = prefs.edit().putString("mode", "memory").commit()

    fun saveManual(ids: List<String>): Boolean = prefs.edit()
        .putString("ids", JSONArray(ids.filter { it.isNotBlank() }.distinct()).toString())
        .putString("mode", "manual").commit()
}
