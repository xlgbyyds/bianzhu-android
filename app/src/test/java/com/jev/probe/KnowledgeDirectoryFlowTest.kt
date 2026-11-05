package com.jev.probe

import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Msg
import com.jev.probe.core.kb.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KnowledgeDirectoryFlowTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var kb: KbStore
    @Before fun setup() {
        resetStore(); kb = KbStore.get(app); kb.clearAll()
        val profile = RelationshipProfile(ProfileClaim("用户确认的关系", userEdited = true), ProfileClaim("待观察"),
            emptyList(), emptyList(), pacingAdvice = "自然交流", unknowns = emptyList(), sourceApp = "com.tencent.mm",
            sourceLineCount = 2, sourcePageCount = 1, sourceMeCount = 1, sourceOtherCount = 1,
            sourceUnknownSideCount = 0, sourceVisibleTimeCount = 0, sourceHistoricalCount = 0,
            sourceFirstObservedAt = 1, sourceLatestObservedAt = 2)
        kb.saveContact(Contact("keep", "测试联系人", apps = listOf("com.tencent.mm"), memoryEnabled = true,
            relationship = "保留的关系", relationshipProfiles = mapOf("com.tencent.mm" to profile)))
        kb.saveContact(Contact("other", "另一个人", apps = listOf("cn.soulapp.android")))
        kb.saveNote(Note("note", "约定", "下周一起喝咖啡"))
        ConversationLedger.get(app).record("keep", "com.tencent.mm",
            ChatSnapshot("测试联系人", listOf(Msg("me", "周末见"), Msg("other", "好呀"))), historical = false)
    }
    @After fun cleanup() { kb.clearAll(); resetStore() }
    private fun resetStore() {
        val ledger = ConversationLedger::class.java.getDeclaredField("instance").apply { isAccessible = true }
        (ledger.get(null) as? ConversationLedger)?.close(); ledger.set(null, null)
        KbStore::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
    }
    private fun descendants(v: View): List<View> = listOf(v) +
        if (v is ViewGroup) (0 until v.childCount).flatMap { descendants(v.getChildAt(it)) } else emptyList()
    private fun views(a: KnowledgeActivity) = descendants(a.window.decorView)
    private fun tap(a: KnowledgeActivity, label: String) {
        views(a).filterIsInstance<TextView>().first { it.text.toString() == label }.performClick()
        ShadowLooper.idleMainLooper()
    }
    private fun awaitList(a: KnowledgeActivity) {
        val executor = KnowledgeActivity::class.java.getDeclaredField("contactListExecutor").apply { isAccessible = true }
            .get(a) as ExecutorService
        executor.submit {}.get(5, TimeUnit.SECONDS)
        ShadowLooper.idleMainLooper()
    }
    @Test fun browseSearchExpandAndCancelEditPreserveContactsProfilesNotesAndMessages() {
        val contacts = kb.contacts()
        val notes = kb.notes()
        val history = kb.historyItems("keep", false, 100, 0)
        val raw = File(app.filesDir, "kb/contacts.json").readBytes()
        val controller = Robolectric.buildActivity(KnowledgeActivity::class.java).setup()
        val a = controller.get(); awaitList(a)
        assertTrue(views(a).filterIsInstance<TextView>().any { it.text.toString() == "测试联系人" })
        assertFalse(views(a).filterIsInstance<TextView>().any { it.text.toString() == "清空此人全部记忆" })
        val search = views(a).filterIsInstance<EditText>().single()
        search.setText("测试联系人")
        assertFalse(views(a).filterIsInstance<TextView>().any { it.text.toString() == "另一个人" })
        tap(a, "关系档案与管理  ▾")
        tap(a, "编辑联系人")
        (ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog)
            .getButton(android.content.DialogInterface.BUTTON_NEGATIVE).performClick()
        tap(a, "知识笔记"); tap(a, "联系人"); awaitList(a)
        assertEquals(contacts, kb.contacts()); assertEquals(notes, kb.notes())
        assertEquals(history, kb.historyItems("keep", false, 100, 0))
        assertArrayEquals(raw, File(app.filesDir, "kb/contacts.json").readBytes())
        controller.pause().stop().destroy()
    }
}
