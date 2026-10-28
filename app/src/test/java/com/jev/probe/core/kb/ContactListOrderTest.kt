package com.jev.probe.core.kb

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ContactListOrderTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val contacts = listOf(Contact("a", "同名", apps = listOf("com.tencent.mm")),
        Contact("b", "同名", apps = listOf("cn.soulapp.android")), Contact("c", "第三位"))
    private lateinit var order: ContactListOrder
    @Before fun start() {
        context.getSharedPreferences("jev_contact_list_order", 0).edit().clear().commit()
        order = ContactListOrder(context)
    }
    @Test fun defaultsToDescendingSavedMemoryAndDoesNotMutateContacts() {
        val copy = contacts.toList()
        assertEquals(listOf("b", "a", "c"), order.arrange(contacts, mapOf("a" to 2, "b" to 4000)).map { it.id })
        assertFalse(order.manual)
        assertEquals(copy, contacts)
    }
    @Test fun manualOrderSurvivesRecreationRenameAndChangingCountsWithoutMixingSameNames() {
        assertTrue(order.saveManual(listOf("c", "b", "a", "a", "")))
        val renamed = contacts.map { if (it.id == "b") it.copy(name = "新备注") else it }
        val reopened = ContactListOrder(context)
        assertTrue(reopened.manual)
        assertEquals(listOf("c", "b", "a"), reopened.arrange(renamed, mapOf("a" to 9000)).map { it.id })
    }
    @Test fun deletedContactsStayDeletedAndNewContactsAppendToManualList() {
        order.saveManual(listOf("b", "c", "a"))
        val now = contacts.filter { it.id != "b" } + Contact("d", "新增") + Contact("e", "另一个新增")
        assertEquals(listOf("c", "a", "e", "d"),
            order.arrange(now, mapOf("d" to 20, "e" to 40)).map { it.id })
        assertEquals(now.map { it.id }.toSet(), order.arrange(now, emptyMap()).map { it.id }.toSet())
    }
    @Test fun memoryModeRecomputesCountsAndKeepsTiesStableAfterEditingContact() {
        order.saveManual(listOf("b", "a", "c"))
        assertTrue(order.useMemoryOrder())
        val changed = contacts.map { it.copy(updatedAt = 5000L) }
        assertEquals(listOf("c", "b", "a"), order.arrange(changed, mapOf("c" to 99, "a" to 2, "b" to 2)).map { it.id })
        assertFalse(ContactListOrder(context).manual)
    }
}
