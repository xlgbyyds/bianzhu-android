package com.jev.probe.core.kb

import android.os.SystemClock
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Msg
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WechatMemoryCaptureTest {
    private val store get() = KbStore.get(RuntimeEnvironment.getApplication())
    private val contact = Contact("wechat-test", "测试联系人", apps = listOf("com.tencent.mm"), memoryEnabled = true)
    private val snapshot = ChatSnapshot(contact.name, listOf(
        Msg("me", "测试我方消息", visibleTime = "9月15日 16:00", timeConfidence = "partial"),
        Msg("other", "测试对方回复", visibleTime = "9月15日 16:00", timeConfidence = "partial")))

    @Before fun prepare() {
        resetSingletons()
        assertTrue(store.saveContact(contact))
    }
    @After fun close() {
        ConversationLedger.get(RuntimeEnvironment.getApplication()).close()
        resetSingletons()
    }

    private fun resetSingletons() {
        listOf(KbStore::class.java, ConversationLedger::class.java).forEach {
            it.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        }
    }

    @Test fun visibleMessagesRequireTheContactsMemorySwitch() {
        assertTrue(store.saveContact(contact.copy(memoryEnabled = false)))
        assertFalse(capture(snapshot))
        assertEquals(0, store.logSize(contact.id))
    }

    @Test fun aNullOrBlankTitleNeverMatchesAnyContact() {
        assertNull(store.findContact(null, "com.tencent.mm"))
        assertNull(store.findContact("   ", "com.tencent.mm"))
    }

    @Test fun lockedBackfillContactIdRejectsANameThatResolvesElsewhere() {
        val other = Contact("same-title-soul", contact.name,
            apps = listOf("cn.soulapp.android"), memoryEnabled = true)
        assertTrue(store.saveContact(other))
        assertFalse(store.captureVisible(other.id, snapshot, "com.tencent.mm", true,
            System.currentTimeMillis(), SystemClock.elapsedRealtime() + 1))
        assertEquals(0, store.logSize(other.id))
    }

    @Test fun anUncertainSpeakerPreventsTheWholePageFromEnteringMemory() {
        assertFalse(capture(snapshot.copy(messages = snapshot.messages + Msg("unknown", "不能当成对方的话"))))
        assertEquals(0, store.logSize(contact.id))
    }

    @Test fun repeatedPageDoesNotDuplicateStorageAndKeepsLiteralDate() {
        assertTrue(capture(snapshot))
        assertFalse(capture(snapshot))
        assertEquals(2, store.logSize(contact.id))
        val records = store.historyItems(contact.id, false, 50, 0)
        assertTrue(records.all { it.visibleTime == "9月15日 16:00" && it.timeConfidence == "partial" })
    }

    @Test fun sameNameInAnotherAppDoesNotWriteToWechatContact() {
        assertFalse(capture(snapshot, "cn.soulapp.android"))
        assertEquals(0, store.logSize(contact.id))
    }

    @Test fun returningToSameNameInSameAppReusesTheContact() {
        assertTrue(capture(snapshot))
        store.saveOrMergeContact(contact.name, "com.tencent.mm")
        assertEquals(contact.id, store.findContact(contact.name, "com.tencent.mm")?.id)
        assertEquals(1, store.contacts().size)
        assertFalse(capture(snapshot))
        assertEquals(2, store.logSize(contact.id))
    }

    @Test fun explicitlySavingSupportedChatEnablesMemoryAndCapture() {
        val message = store.saveOrMergeContact("新微信联系人", "com.tencent.mm")
        val saved = store.findContact("新微信联系人", "com.tencent.mm")!!
        assertTrue(message.contains("聊天记忆已开启"))
        assertTrue(saved.memoryEnabled)
        assertTrue(store.captureVisible(snapshot.copy(title = saved.name), "com.tencent.mm", false,
            System.currentTimeMillis(), SystemClock.elapsedRealtime() + 1))
        assertEquals(2, store.logSize(saved.id))
    }

    @Test fun explicitlySavingExistingDisabledContactEnablesItWithoutReplacingProfile() {
        val disabled = contact.copy(memoryEnabled = false, relationship = "同学", notes = "喜欢猫")
        assertTrue(store.saveContact(disabled))
        val message = store.saveOrMergeContact(disabled.name, "com.tencent.mm")
        val saved = store.findContact(disabled.name, "com.tencent.mm")!!
        assertTrue(message.contains("聊天记忆已开启"))
        assertTrue(saved.memoryEnabled)
        assertEquals("同学", saved.relationship)
        assertEquals("喜欢猫", saved.notes)
    }

    @Test fun savingAnUnsupportedAppDoesNotEnableAnUnavailableMemoryFeature() {
        val message = store.saveOrMergeContact("Uki 联系人", "cn.neoclub.uki")
        val saved = store.findContact("Uki 联系人", "cn.neoclub.uki")!!
        assertFalse(saved.memoryEnabled)
        assertTrue(message.contains("暂不支持可见聊天记忆"))
    }

    @Test fun sameNameSavedFromAnotherAppCreatesASeparateMemoryProfile() {
        assertTrue(store.saveContact(Contact("same-name-soul", "跨应用同名",
            apps = listOf("cn.soulapp.android"), memoryEnabled = false)))
        store.saveOrMergeContact("跨应用同名", "com.tencent.mm")
        val wechat = store.findContact("跨应用同名", "com.tencent.mm")!!
        val soul = store.contact("same-name-soul")!!
        assertNotEquals(wechat.id, soul.id)
        assertTrue(wechat.memoryEnabled)
        assertFalse(soul.memoryEnabled)
        assertEquals(3, store.contacts().size)
    }

    @Test fun missingTimeIsNotReplacedWithCaptureTime() {
        assertTrue(capture(snapshot.copy(messages = listOf(Msg("other", "没有时间的文字")))))
        val record = store.historyItems(contact.id, false, 50, 0).single()
        assertNull(record.visibleTime)
        assertEquals("unknown", record.timeConfidence)
    }

    private fun capture(value: ChatSnapshot, app: String = "com.tencent.mm") =
        store.captureVisible(value, app, true, System.currentTimeMillis(), SystemClock.elapsedRealtime() + 1)
}
