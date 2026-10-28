package com.jev.probe.core

import com.jev.probe.core.kb.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReplyVoiceFeedbackTest {
    // Robolectric resets SQLite connection handles between tests, whereas these
    // process-wide stores outlive the previous test's Application.
    @Before @After fun releaseStores() {
        val ledger = ConversationLedger::class.java.getDeclaredField("instance").apply { isAccessible = true }
        runCatching { (ledger.get(null) as? ConversationLedger)?.close() }
        ledger.set(null, null)
        KbStore::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
    }
    @Test fun directCriticismIsDifferentFromDiscussingModelsOrPositiveFeedback() {
        listOf("你讲话真像豆包", "你说话好像deep seek", "你回复太像AI了", "你这语气好像客服").forEach {
            assertTrue(it, ReplyVoicePolicy.isStyleComplaint(it))
        }
        listOf("我用豆包查了路线", "你说话不像AI", "你讲话不太像机器人", "他讲话像豆包", "豆包回复很快").forEach {
            assertFalse(it, ReplyVoicePolicy.isStyleComplaint(it))
        }
    }

    @Test fun complaintSuppressesImplicitImitationButKeepsExplicitApprovalAndBudget() {
        val prompt = ReplyVoicePolicy.guidance(listOf("我确实想多了解你一点"), listOf("说到底还是不够成熟"), true)
        assertTrue(prompt.contains("明确认可：我确实想多了解你一点"))
        assertFalse(prompt.contains("说到底还是不够成熟"))
        assertTrue(prompt.contains("不是当前要回答的新消息"))
        assertTrue(prompt.length <= ReplyVoicePolicy.MAX_CHARS)
    }

    @Test fun savedFeedbackIsScopedAndLeavesHistoryAvailableWithoutWritingIt() {
        val context = RuntimeEnvironment.getApplication()
        val ledger = ConversationLedger.get(context)
        val kb = KbStore.get(context)
        val app = "test.feedback.app"
        val id = "feedback-scope"
        kb.saveContact(Contact(id, "反馈测试", apps = listOf(app), memoryEnabled = true))
        ledger.record(id, app, ChatSnapshot("反馈测试", listOf(
            Msg("me", "说到底还是不够成熟"), Msg("other", "你讲话真像豆包"))), true)
        assertTrue(ledger.hasRecentStyleComplaint(id, app))
        assertFalse(ledger.hasRecentStyleComplaint("someone-else", app))
        assertFalse(ledger.hasRecentStyleComplaint(id, "another.app"))
        val count = ledger.totalLineCount()
        val ctx = ContextBuilder.build(context, ChatSnapshot("反馈测试", listOf(Msg("other", "后来呢"))), app, Prefs(context))
        assertTrue(ctx.replyVoiceGuidance.contains("暂停自动模仿"))
        assertFalse(ctx.replyVoiceGuidance.contains("历史我的发言："))
        assertEquals(count, ledger.totalLineCount())
        assertTrue(ledger.ownExpressions(id, app).contains("说到底还是不够成熟"))
    }
}
