package com.jev.probe

import com.jev.probe.core.kb.Contact

/** A presentation-only filter: keep identities, ordering and stored records untouched. */
internal object ContactDirectory {
    fun visible(contacts: List<Contact>, query: String, app: String, appLabel: (String) -> String): List<Contact> {
        val needle = query.trim()
        return contacts.filter { contact ->
            (app.isBlank() || app in contact.apps) && (needle.isBlank() ||
                (listOf(contact.name, contact.relationship, contact.notes) + contact.aliases +
                    contact.appAliases.values.flatten() + contact.apps.flatMap { listOf(it, appLabel(it)) })
                    .any { it.contains(needle, ignoreCase = true) })
        }
    }
}
