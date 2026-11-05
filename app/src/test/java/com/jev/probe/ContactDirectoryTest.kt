package com.jev.probe

import com.jev.probe.core.kb.Contact
import org.junit.Assert.*
import org.junit.Test

class ContactDirectoryTest {
    private val contacts = listOf(
        Contact("wx", "同名", apps = listOf("com.tencent.mm"), appAliases = mapOf("com.tencent.mm" to listOf("旧备注"))),
        Contact("soul", "同名", apps = listOf("cn.soulapp.android"), notes = "喜欢徒步"),
        Contact("new", "新朋友", aliases = listOf("昵称"), apps = listOf("unknown.app")))
    private fun label(app: String) = if (app == "com.tencent.mm") "微信" else app

    @Test fun sameNameContactsRemainSeparateAndFiltersKeepTheExistingSortOrder() {
        assertEquals(listOf("wx", "soul"), ContactDirectory.visible(contacts, "同名", "", ::label).map { it.id })
        assertEquals(listOf("wx"), ContactDirectory.visible(contacts, "同名", "com.tencent.mm", ::label).map { it.id })
        assertEquals(contacts, ContactDirectory.visible(contacts, "  ", "", ::label))
    }

    @Test fun namesConfirmedRemarksNotesAndAppsAreSearchableWithoutModifyingContacts() {
        val before = contacts.toList()
        for ((query, id) in listOf("旧备注" to "wx", "微信" to "wx", "SOULAPP" to "soul", "徒步" to "soul", "昵称" to "new")) {
            assertEquals(listOf(id), ContactDirectory.visible(contacts, query, "", ::label).map { it.id })
        }
        assertTrue(ContactDirectory.visible(contacts, "不存在", "", ::label).isEmpty())
        assertEquals(before, contacts)
    }
}
