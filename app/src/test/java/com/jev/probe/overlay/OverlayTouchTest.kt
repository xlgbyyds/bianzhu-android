package com.jev.probe.overlay

import android.app.Activity
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.jev.probe.core.Analysis
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Choice
import com.jev.probe.core.Msg
import com.jev.probe.core.Prefs
import com.jev.probe.core.RankedReply
import com.jev.probe.core.ReplySuggestion
import com.jev.probe.core.Score
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper

/** No WeChat, network, or phone: a passive refresh must not swallow a user's tap. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class OverlayTouchTest {
    private lateinit var overlay: OverlayController
    private lateinit var root: FrameLayout
    private lateinit var content: LinearLayout
    private var clicks = 0

    @Before fun setUp() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        overlay = OverlayController(activity)
        Prefs(activity).overlayHidden = false
        overlay.onManualAnalyze = { clicks++ }
        overlay.showIdle(null)
        root = field("root")
        content = field("contentBox")
        field<LinearLayout>("panel").visibility = View.VISIBLE
        layout()
    }

    @After fun tearDown() { overlay.hide() }

    @Test fun explicitHideSurvivesRefreshScreenshotRestoreAndControllerRecreation() {
        val prefs = field<Prefs>("prefs")
        var hidden = 0
        overlay.onHideAssistant = { hidden++ }
        overlay.hideForUser()
        assertEquals(1, hidden)
        org.junit.Assert.assertTrue(prefs.overlayHidden)
        repeat(10) {
            overlay.showIdle("原来的会话")
            overlay.showOpeners(listOf(ReplySuggestion("晚到的建议", "natural", ""))) { }
            overlay.setHiddenForShot(false)
        }
        org.junit.Assert.assertFalse(overlay.isShowing())
        val next = OverlayController(Robolectric.buildActivity(Activity::class.java).setup().get())
        next.showIdle(null)
        org.junit.Assert.assertFalse(next.isShowing())
        prefs.overlayHidden = false
        next.showIdle(null)
        org.junit.Assert.assertTrue(next.isShowing())
        next.hide()
    }

    @Test fun tapWithoutRefreshInvokesAnalysisExactlyOnce() {
        tapWithRefresh { }
        assertEquals(1, clicks)
    }

    @Test fun idleRefreshBetweenDownAndUpDoesNotSwallowTap() {
        tapWithRefresh { repeat(3) { overlay.showIdle(null) } }
        assertEquals(1, clicks)
    }

    @Test fun repeatedUnidentifiedPageBetweenDownAndUpDoesNotSwallowTap() {
        tapWithRefresh {
            repeat(3) {
                overlay.resetForNewConversation()
                overlay.showIdle(null)
            }
        }
        assertEquals(1, clicks)
    }

    @Test fun passiveIdleDoesNotEraseLoadingOrErrorFeedback() {
        overlay.showLoading("正在识别")
        val progress = content.getChildAt(0)
        overlay.showIdle(null)
        assertSame(progress, content.getChildAt(0))
        overlay.showError("无法确认会话")
        val error = content.getChildAt(0)
        overlay.showIdle(null)
        assertSame(error, content.getChildAt(0))
    }

    @Test fun genuineConversationResetRemovesOldFeedback() {
        overlay.showNotice("上一位联系人的提示")
        overlay.resetForNewConversation()
        assertEquals(0, content.childCount)
        overlay.showIdle(null)
        assertEquals(1, content.childCount)
    }

    @Test fun failedOpenerRetriesOpenersInsteadOfAnalyzingTheLastMessage() {
        var openerClicks = 0
        overlay.showError("开场请求失败", "重试开场建议") { openerClicks++ }
        layout()
        tapViewWithRefresh(content.getChildAt(content.childCount - 1)) { overlay.showIdle(null) }
        assertEquals(1, openerClicks)
        assertEquals(0, clicks)
    }

    @Test @Config(qualifiers = "w411dp-h914dp-420dpi")
    fun speakerConfirmationShowsTheMessageAndDoesNotAnalyzeBeforeChoice() {
        var chosen: String? = null
        overlay.showSpeakerChoice(Msg("unknown", "待核对的消息", visibleTime = "9月15日 16:00")) { chosen = it }
        val preview = (content.getChildAt(1) as TextView).text.toString()
        org.junit.Assert.assertTrue(preview.contains("待核对的消息"))
        org.junit.Assert.assertTrue(preview.contains("9月15日 16:00"))
        assertEquals(null, chosen)
        layout()
        tapViewWithRefresh(content.getChildAt(content.childCount - 1)) { overlay.showIdle(null) }
        assertEquals("me", chosen)
        assertEquals(0, clicks)
    }

    @Test @Config(qualifiers = "w411dp-h914dp-420dpi")
    fun backfillStatusUpdatesDoNotSwallowStopTap() {
        overlay.onToggleHistoryCapture = { clicks++ }
        overlay.showBackfillProgress(1, 7, true)
        layout()
        val stop = content.getChildAt(content.childCount - 1)
        tapViewWithRefresh(stop) {
            overlay.showBackfillWaiting("正在核对联系人")
            overlay.showBackfillProgress(2, 12, true)
        }
        assertSame(stop, content.getChildAt(content.childCount - 1))
        assertEquals(1, clicks)
    }

    @Test @Config(qualifiers = "w411dp-h914dp-420dpi")
    fun incomingUpdatePreservesTheCardAndCopyTapInProgress() {
        overlay.showOpeners(listOf(ReplySuggestion("原来的建议", "natural", ""))) { }
        layout()
        val card = content.getChildAt(content.childCount - 2) as LinearLayout
        val copy = descendants(card).filterIsInstance<TextView>().first { it.text == "复制" }
        tapViewWithRefresh(copy) { repeat(5) { overlay.showSnapshotUpdate() } }
        assertSame(card, content.getChildAt(content.childCount - 2))
        assertEquals("已复制",
            org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
        val banner = field<LinearLayout>("snapshotUpdateBanner")
        assertEquals(View.VISIBLE, banner.visibility)
        var refreshed = 0
        overlay.onRefreshAnalysis = { refreshed++ }
        banner.getChildAt(1).performClick()
        assertEquals(1, refreshed)
        overlay.resetForNewConversation()
        assertEquals(View.GONE, banner.visibility)
    }

    @Test fun explicitNewAnalysisDoesNotReuseAnOldDraftOrSkipProgress() {
        overlay.showDraft(listOf(ReplySuggestion("旧建议", "natural", "旧依据"))) { }
        overlay.showLoading("重新生成中")
        overlay.showProgress("正在判断新的画面")
        assertEquals("正在判断新的画面", (content.getChildAt(0) as TextView).text.toString())
        assertEquals(null, field<Any?>("lastJudgment"))
        assertEquals(emptyList<ReplySuggestion>(), field<List<ReplySuggestion>>("draftReplies"))
    }

    @Test fun closeButtonNotifiesTheServiceWithoutWaitingForAnotherChatEvent() {
        overlay.showOpeners(listOf(ReplySuggestion("可关闭的建议", "natural", ""))) { }
        var dismissed = 0
        overlay.onDismissAnalysis = { dismissed++ }
        val header = field<LinearLayout>("panel").getChildAt(0) as LinearLayout
        header.getChildAt(header.childCount - 1).performClick()
        assertEquals(1, dismissed)
        assertEquals(false, field<Boolean>("expanded"))
    }

    @Test fun openerFeedbackIsRememberedWithoutAnalyzingOrReplacingTheCards() {
        val prefs = field<Prefs>("prefs")
        prefs.clearReplyFeedback()
        var openerClicks = 0
        var filled: String? = null
        var approved: String? = null
        overlay.onApproveReplyVoice = { approved = it }
        overlay.onOpenConversation = { openerClicks++ }
        overlay.showOpeners(listOf(ReplySuggestion("今天想多黏你一会", "playful", ""))) { filled = it }
        val card = content.getChildAt(content.childCount - 2)
        val buttons = descendants(card).filterIsInstance<TextView>().associateBy { it.text.toString() }
        listOf("像我", "少点AI", "少主动", "少问句").forEach { buttons.getValue(it).performClick() }
        val guidance = prefs.replyFeedbackGuidance()
        assertEquals("今天想多黏你一会", approved)
        org.junit.Assert.assertFalse(guidance.contains("轻松"))
        org.junit.Assert.assertTrue(guidance.contains("避免工整套路句"))
        org.junit.Assert.assertTrue(guidance.contains("降低主动推进感"))
        org.junit.Assert.assertTrue(guidance.contains("少用问句"))
        assertSame(card, content.getChildAt(content.childCount - 2))
        assertEquals(0, clicks)
        assertEquals(0, openerClicks)
        assertEquals(null, filled)
        content.getChildAt(content.childCount - 1).performClick()
        assertEquals(1, openerClicks)
        assertEquals(0, clicks)
        prefs.clearReplyFeedback()
    }

    @Test fun approvalAndRewriteReceiveActualTextEvenWithoutStyleAndNeverFillInput() {
        var approved: String? = null
        var rewritten: String? = null
        var filled = false
        overlay.onApproveReplyVoice = { approved = it }
        overlay.onEditReplyVoice = { rewritten = it }
        overlay.showOpeners(listOf(ReplySuggestion("好家伙 你比猫还能折腾", "", ""))) { filled = true }
        descendants(content).filterIsInstance<TextView>().first { it.text == "像我" }.performClick()
        assertEquals("好家伙 你比猫还能折腾", approved)
        descendants(content).filterIsInstance<TextView>().first { it.text == "改写学习" }.performClick()
        assertEquals(approved, rewritten)
        org.junit.Assert.assertFalse(filled)
    }

    @Test fun contactSelectorStaysAvailableAndClearlyMarksTemporaryMemoryPause() {
        var choices = 0
        overlay.onChooseContact = { choices++ }
        overlay.setContactInfo("测试联系人", true)
        val label = descendants(root).filterIsInstance<TextView>().first { it.text.toString().contains("仅本次：") }
        org.junit.Assert.assertTrue(label.text.toString().contains("记忆采集暂停"))
        label.performClick()
        assertEquals(1, choices)
    }

    @Test fun touchingBackfillBubbleRequestsPauseWithoutAccessibilityTouchEvents() {
        var pauses = 0
        overlay.onHistoryInteraction = { pauses++ }
        overlay.showBackfillProgress(3, 20, false)
        layout()
        val bubble = field<TextView>("bubble").parent as View
        tapViewWithRefresh(bubble) { overlay.showBackfillProgress(3, 20, false) }
        assertEquals(1, pauses)
        assertEquals(0, clicks)
        overlay.collapseForAutomaticBackfill()
        assertEquals(1, pauses)
    }

    @Test @Config(qualifiers = "w411dp-h914dp-420dpi")
    fun openersShowTwoFullReplyTextsBeforeScrollingEvenWithLongRecognitionNotes() {
        prepareLongDetails()
        val replies = sampleReplies()
        overlay.showOpeners(replies) { }
        assertSecondReplyVisible(replies[1].text)
        assertDetailsAndAdjustmentsExpandWithoutRequestingOrReplacingCards(replies[0].text)
    }

    @Test @Config(qualifiers = "w411dp-h914dp-420dpi")
    fun draftsAndRankedRepliesShowSecondTextWithoutScrollingAndKeepElevatedRiskVisible() {
        prepareLongDetails()
        val replies = sampleReplies()
        val judgment = Analysis(Choice("casual_chat", .7, emptyMap()), Score(6.0, .8, 9),
            Choice("care", .7, emptyMap()), .4, Choice("acknowledge", .8, emptyMap()),
            .8, null, emptyList(), 300)
        overlay.showJudgment(judgment)
        overlay.showDraft(replies) { }
        assertSecondReplyVisible(replies[1].text)
        val danger = descendants(content).filterIsInstance<TextView>().first { it.text == "危险 6/9" }
        org.junit.Assert.assertTrue(danger.isShown)
        overlay.showReplies(replies.map { RankedReply(it.text, .8, it.style, it.reason) }) { }
        assertSecondReplyVisible(replies[1].text)
        assertDetailsAndAdjustmentsExpandWithoutRequestingOrReplacingCards(replies[0].text)
    }

    private fun prepareLongDetails() {
        overlay.setSnapshotAudit(ChatSnapshot("测试联系人", listOf(
            Msg("other", "当前聊天", visibleTime = "9月15日 16:00"))))
        overlay.setNote("识别注意事项与页面时间可信度说明".repeat(20))
        overlay.setContextInfo(3, 30, true, true)
    }

    @Test @Config(qualifiers = "w411dp-h914dp-420dpi")
    fun invitationShowsSecondReplyAndPreservesRetryPurposeAndCopyOnIncomingMessage() {
        prepareLongDetails()
        var invitationClicks = 0
        overlay.onInviteConversation = { invitationClicks++ }
        val evidence = com.jev.probe.jev.InvitationEvidence("e0", "other", "周末有空", "可见消息")
        val plan = com.jev.probe.jev.InvitationPlan(com.jev.probe.jev.InvitationAction.INVITE,
            evidence, "时间安排", listOf(evidence))
        val replies = sampleReplies()
        overlay.showInvitations(plan, replies) { }
        assertSecondReplyVisible(replies[1].text)
        val card = descendants(content).filterIsInstance<TextView>().first { it.text == replies[0].text }
        overlay.showSnapshotUpdate()
        assertSame(card, descendants(content).filterIsInstance<TextView>().first { it.text == replies[0].text })
        descendants(content).filterIsInstance<TextView>().first { it.text == "换一组邀约建议" }.performClick()
        assertEquals(1, invitationClicks)
        assertEquals(0, clicks)
    }

    @Test fun opportunityHintOnlyRunsWhenUserTapsIt() {
        var invitations = 0
        overlay.onInviteConversation = { invitations++ }
        overlay.showJudgment(Analysis(null, null, null, null, null, null, null, emptyList(), 1,
            invitationOpportunity = Choice("offer", .8, emptyMap())))
        assertEquals(0, invitations)
        descendants(content).filterIsInstance<TextView>().first { it.text == "可以顺势邀约" }.performClick()
        assertEquals(1, invitations)
        assertEquals(0, clicks)
    }

    private fun sampleReplies() = listOf(
        ReplySuggestion("好家伙 你这一天过得比连续剧还热闹 我都想接着听了", "playful", "仅供参考的详细依据".repeat(20)),
        ReplySuggestion("这段我还真没想到 你后来怎么接的 我先搬个小板凳", "natural", "仅供参考的详细依据".repeat(20)),
        ReplySuggestion("行 今天这个故事值得我多听一会", "natural", ""))

    private fun assertSecondReplyVisible(text: String) {
        layout()
        val reply = descendants(content).filterIsInstance<TextView>().first { it.text == text }
        val scroll = content.parent as ScrollView
        val rect = Rect(0, 0, reply.width, reply.height)
        scroll.offsetDescendantRectToMyCoords(reply, rect)
        assertEquals(0, scroll.scrollY)
        org.junit.Assert.assertTrue("Second reply must fit completely: $rect / ${scroll.height}",
            rect.top >= 0 && rect.bottom <= scroll.height)
    }

    private fun assertDetailsAndAdjustmentsExpandWithoutRequestingOrReplacingCards(reply: String) {
        val text = descendants(content).filterIsInstance<TextView>().first { it.text == reply }
        val review = descendants(content).filterIsInstance<TextView>().first { it.text == "核对本次识别内容" }
        org.junit.Assert.assertFalse(review.isShown)
        var reviews = 0
        overlay.onReviewSnapshot = { reviews++ }
        descendants(content).filterIsInstance<TextView>().first {
            it.text.toString().startsWith("识别") && it.text.toString().endsWith("▾")
        }.performClick()
        org.junit.Assert.assertTrue(review.isShown)
        review.performClick()
        assertEquals(1, reviews)
        val like = descendants(content).filterIsInstance<TextView>().first { it.text == "像我" }
        org.junit.Assert.assertFalse(like.isShown)
        val adjust = descendants(content).filterIsInstance<TextView>().first { it.text == "调整 ▾" }
        adjust.performClick()
        org.junit.Assert.assertTrue(like.isShown)
        adjust.performClick()
        org.junit.Assert.assertFalse(like.isShown)
        assertSame(text, descendants(content).filterIsInstance<TextView>().first { it.text == reply })
        assertEquals(0, clicks)
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }
        else emptyList()

    private fun tapWithRefresh(refresh: () -> Unit) {
        tapViewWithRefresh(content.getChildAt(0), refresh)
    }

    private fun tapViewWithRefresh(button: View, refresh: () -> Unit) {
        val rect = Rect(0, 0, button.width, button.height)
        root.offsetDescendantRectToMyCoords(button, rect)
        val downTime = SystemClock.uptimeMillis()
        dispatch(downTime, downTime, MotionEvent.ACTION_DOWN, rect)
        refresh()
        layout()
        dispatch(downTime, downTime + 80, MotionEvent.ACTION_UP, rect)
        ShadowLooper.idleMainLooper()
    }

    private fun dispatch(down: Long, time: Long, action: Int, rect: Rect) {
        val event = MotionEvent.obtain(down, time, action,
            rect.exactCenterX(), rect.exactCenterY(), 0)
        try { root.dispatchTouchEvent(event) } finally { event.recycle() }
    }

    private fun layout() {
        root.measure(View.MeasureSpec.makeMeasureSpec(1220, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(2712, View.MeasureSpec.AT_MOST))
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(name: String): T = OverlayController::class.java
        .getDeclaredField(name).apply { isAccessible = true }.get(overlay) as T
}
