package com.jev.probe.capture

import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.content.res.Resources
import android.widget.LinearLayout
import android.view.View
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Msg
import com.jev.probe.core.Prefs
import com.jev.probe.core.ReplySuggestion
import com.jev.probe.overlay.OverlayController
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class CaptureEventTest {
    class Service : ChatCaptureService() {
        var reads = 0
        var page: AccessibilityNodeInfo? = null
        override fun getRootInActiveWindow(): AccessibilityNodeInfo? {
            reads++
            return page
        }
    }

    private lateinit var service: Service
    private lateinit var overlay: OverlayController

    @Before fun setUp() {
        service = Robolectric.buildService(Service::class.java).create().get()
        overlay = OverlayController(service)
        overlay.showIdle(null)
        setField("prefs", Prefs(service).apply { enabled = true; overlayHidden = false })
        setField("overlay", overlay)
    }

    @After fun tearDown() { service.onDestroy() }

    @Test fun transientSystemWindowCannotEndBackfillFromAnyScheduledHandler() {
        val target = ConversationSession.Target("com.tencent.mm", 7, "Alice")
        val run = getField<BackfillSession>("backfill").start(target, "contact-a")!!
        setField("autoBackfillRunning", true)
        service.page = AccessibilityNodeInfo.obtain().apply { packageName = "com.android.systemui" }
        for (handler in listOf("scanWechatBackfill", "performAutoBackfillStep", "onAutoBackfillTimeout")) {
            invoke(handler)
            assertSame(run, getField<BackfillSession>("backfill").active)
            assertTrue(getField<Boolean>("autoBackfillRunning"))
        }
    }

    @Test fun reviewWindowsAndScheduledRefreshCannotEraseTheOriginalInvitationTarget() {
        val target = ConversationSession.Target("com.tencent.mm", 7, "Alice")
        getField<ConversationSession>("session").observe(target)
        setField("reviewActivityActive", true)
        setField("reviewTarget", target)
        setField("reviewRequestId", "review-invitation")
        setField("reviewPurpose", com.jev.probe.jev.ConversationPurpose.INVITATION)
        service.page = AccessibilityNodeInfo.obtain().apply { packageName = service.packageName }
        invoke("maybeCapture")
        event("android", AccessibilityEvent.TYPE_WINDOWS_CHANGED)
        assertEquals(target, getField<ConversationSession>("session").target)
        assertEquals("review-invitation", getField<String>("reviewRequestId"))
        assertEquals(com.jev.probe.jev.ConversationPurpose.INVITATION,
            getField<com.jev.probe.jev.ConversationPurpose>("reviewPurpose"))
        service.page = null
        invoke("maybeCapture")
        assertEquals(target, getField<ConversationSession>("session").target)
    }

    @Test fun automaticStepActuallyStopsWhenAnotherAppBecomesForeground() {
        val target = ConversationSession.Target("com.tencent.mm", 7, "Alice")
        getField<BackfillSession>("backfill").start(target, "contact-a")!!
        setField("autoBackfillRunning", true)
        service.page = AccessibilityNodeInfo.obtain().apply { packageName = SoulAdapter.PACKAGE }
        invoke("performAutoBackfillStep")
        assertEquals(null, getField<BackfillSession>("backfill").active)
        assertFalse(getField<Boolean>("autoBackfillRunning"))
    }

    @Test fun delayedScrollEventCannotBeMistakenForManualPause() {
        val target = ConversationSession.Target("com.tencent.mm", 7, "Alice")
        val run = getField<BackfillSession>("backfill").start(target, "contact-a")!!
        setField("autoBackfillRunning", true)
        setField("ignoreTouchPauseUntil", 0L)
        event(target.pkg, AccessibilityEvent.TYPE_VIEW_SCROLLED)
        assertSame(run, getField<BackfillSession>("backfill").active)
        assertTrue(getField<Boolean>("autoBackfillRunning"))
    }

    @Test fun hiddenAssistantIgnoresChatEventsAndResumesOnlyAfterExplicitRestore() {
        val prefs = getField<Prefs>("prefs")
        val adapter = object : ChatAppAdapter {
            override val pkg = SoulAdapter.PACKAGE
            override fun extract(root: AccessibilityNodeInfo, res: Resources) =
                ChatSnapshot("Alice", listOf(Msg("other", "现在的消息")))
        }
        setField("adapters", mapOf(adapter.pkg to adapter))
        service.page = AccessibilityNodeInfo.obtain().apply { packageName = adapter.pkg }
        prefs.overlayHidden = true
        repeat(10) { event(adapter.pkg, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) }
        invoke("maybeCapture")
        assertEquals(0, service.reads)
        assertFalse(overlay.isShowing())
        prefs.overlayHidden = false
        invoke("maybeCapture")
        assertTrue(service.reads > 0)
        assertTrue(overlay.isShowing())
    }

    @Test fun overlayContentAndScrollDoNotReadChatOrErasePanel() {
        event(service.packageName, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        event(service.packageName, AccessibilityEvent.TYPE_VIEW_SCROLLED)
        assertEquals(0, service.reads)
        assertTrue(overlay.isShowing())
    }

    @Test fun openingActualAssistantSettingsStillHidesOverlay() {
        service.page = AccessibilityNodeInfo.obtain().apply { packageName = service.packageName }
        event(service.packageName, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
        assertTrue(service.reads > 0)
        assertFalse(overlay.isShowing())
    }

    @Test fun externalChatEventStillChecksLiveWindowAndRejectsMissingPage() {
        event("com.tencent.mm", AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        ShadowLooper.idleMainLooper(180L)
        assertTrue(service.reads > 0)
        assertFalse(overlay.isShowing())
    }

    @Test fun statusBarContentRefreshDoesNotReadChatOrClearAnOpener() {
        overlay.showOpeners(listOf(ReplySuggestion("测试开场", "natural", ""))) { }
        repeat(30) { event("com.android.systemui", AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) }
        ShadowLooper.idleMainLooper(200L)
        assertEquals(0, service.reads)
        assertTrue(overlay.isShowing())
    }

    @Test fun soulContentBurstIsReadOnceAfterSettling() {
        repeat(30) { event(SoulAdapter.PACKAGE, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) }
        assertEquals(0, service.reads)
        ShadowLooper.idleMainLooper(180L)
        assertEquals(1, service.reads)
        assertFalse(overlay.isShowing())
    }

    @Test fun soulScrollWaitsForTheLastFrameBeforeReading() {
        repeat(6) {
            event(SoulAdapter.PACKAGE, AccessibilityEvent.TYPE_VIEW_SCROLLED)
            event(SoulAdapter.PACKAGE, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
            ShadowLooper.idleMainLooper(100L)
        }
        assertEquals(0, service.reads)
        ShadowLooper.idleMainLooper(360L)
        assertEquals(1, service.reads)
    }

    @Test fun leavingSoulDuringScrollDoesNotWaitToHideItsOverlay() {
        event(SoulAdapter.PACKAGE, AccessibilityEvent.TYPE_VIEW_SCROLLED)
        service.page = AccessibilityNodeInfo.obtain().apply { packageName = service.packageName }
        event(service.packageName, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
        assertFalse(overlay.isShowing())
    }

    @Test fun screenshotTitleSurvivesOwnOverlayEventsButExpiresOnWechatContentChange() {
        val guard = ChatCaptureService::class.java.getDeclaredField("wechatTitleGuard")
            .apply { isAccessible = true }.get(service) as WechatTitleGuard
        guard.remember(guard.begin("page"), "page", "测试联系人")
        event(service.packageName, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        assertEquals("测试联系人", guard.title("page"))
        event("com.tencent.mm", AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        assertEquals(null, guard.title("page"))
    }

    @Test fun lateTitleCallbackDoesNotReopenPanelOnHomeScreen() {
        service.page = AccessibilityNodeInfo.obtain().apply { packageName = "com.miui.home" }
        ChatCaptureService::class.java.getDeclaredMethod("titlePageChanged")
            .apply { isAccessible = true }.invoke(service)
        assertFalse(overlay.isShowing())
    }

    @Test fun endingBackfillDoesNotRequireALivePageOrCachedSnapshot() {
        val state = ChatCaptureService::class.java.getDeclaredField("backfill")
            .apply { isAccessible = true }.get(service) as BackfillSession
        state.start(ConversationSession.Target("com.tencent.mm", 1, "测试联系人"), "test-contact")
        service.page = null
        ChatCaptureService::class.java.getDeclaredMethod("toggleHistoryCapture")
            .apply { isAccessible = true }.invoke(service)
        assertEquals(null, state.active)
    }

    @Test fun backfillSurvivesTransientMissingTreeWithoutWalkingItOnEveryScrollEvent() {
        val state = ChatCaptureService::class.java.getDeclaredField("backfill")
            .apply { isAccessible = true }.get(service) as BackfillSession
        val run = state.start(ConversationSession.Target("com.tencent.mm", 1, "测试联系人"), "test-contact")
        repeat(30) { event("com.tencent.mm", AccessibilityEvent.TYPE_VIEW_SCROLLED) }
        assertEquals(0, service.reads)
        ChatCaptureService::class.java.getDeclaredMethod("scanWechatBackfill")
            .apply { isAccessible = true }.invoke(service)
        assertEquals(run, state.active)
        assertTrue(overlay.isShowing())
    }

    @Test fun backfillStopsWhenScannerProvesUserLeftWechat() {
        val state = ChatCaptureService::class.java.getDeclaredField("backfill")
            .apply { isAccessible = true }.get(service) as BackfillSession
        state.start(ConversationSession.Target("com.tencent.mm", 1, "测试联系人"), "test-contact")
        service.page = AccessibilityNodeInfo.obtain().apply { packageName = "com.miui.home" }
        ChatCaptureService::class.java.getDeclaredMethod("scanWechatBackfill")
            .apply { isAccessible = true }.invoke(service)
        assertEquals(null, state.active)
        assertFalse(overlay.isShowing())
    }

    @Test fun xiaomiTransientSystemWindowPausesInsteadOfEndingBackfill() {
        val state = ChatCaptureService::class.java.getDeclaredField("backfill")
            .apply { isAccessible = true }.get(service) as BackfillSession
        val run = state.start(ConversationSession.Target("com.tencent.mm", 1, "测试联系人"), "test-contact")
        service.page = AccessibilityNodeInfo.obtain().apply { packageName = "com.android.systemui" }
        ChatCaptureService::class.java.getDeclaredMethod("scanWechatBackfill")
            .apply { isAccessible = true }.invoke(service)
        assertEquals(run, state.active)
        assertTrue(overlay.isShowing())
    }

    @Test fun incomingMessagesPreserveBothPendingAnalysisAndCompletedSuggestions() {
        val original = ChatSnapshot("Alice", listOf(Msg("other", "第一条消息")))
        var visible = original
        val adapter = object : ChatAppAdapter {
            override val pkg = SoulAdapter.PACKAGE
            override fun extract(root: AccessibilityNodeInfo, res: Resources) = visible
        }
        setField("adapters", mapOf(adapter.pkg to adapter))
        service.page = AccessibilityNodeInfo.obtain().apply { packageName = adapter.pkg }
        invoke("maybeCapture")
        val session = getField<ConversationSession>("session")
        val token = session.begin()!!
        setField("pendingSnapshot", original)
        setField("analyzing", true)
        invoke("pinAnalysis", token.target)
        overlay.showOpeners(listOf(ReplySuggestion("原先的建议 别消失", "natural", ""))) { }
        val content = OverlayController::class.java.getDeclaredField("contentBox")
            .apply { isAccessible = true }.get(overlay) as LinearLayout
        val originalCard = content.getChildAt(content.childCount - 2)

        visible = original.copy(messages = original.messages + Msg("other", "新消息一"))
        event(adapter.pkg, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        ShadowLooper.idleMainLooper(180L)
        assertTrue(session.accepts(token))
        assertSame(originalCard, content.getChildAt(content.childCount - 2))
        assertSame(original, getField<ChatSnapshot>("pendingSnapshot"))

        // Exercise the actual completion transition used by both model paths.
        invoke("completeAnalysis", token.target)
        assertFalse(getField<Boolean>("analyzing"))
        visible = visible.copy(messages = visible.messages + Msg("other", "新消息二"))
        repeat(10) { event(adapter.pkg, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) }
        ShadowLooper.idleMainLooper(180L)
        assertTrue(session.accepts(token))
        assertSame(originalCard, content.getChildAt(content.childCount - 2))
        assertSame(original, getField<ChatSnapshot>("pendingSnapshot"))
        val banner = OverlayController::class.java.getDeclaredField("snapshotUpdateBanner")
            .apply { isAccessible = true }.get(overlay) as View
        assertEquals(View.VISIBLE, banner.visibility)

        // A different named chat must still discard all of Alice's content.
        visible = ChatSnapshot("Bob", listOf(Msg("other", "另一位联系人")))
        event(adapter.pkg, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        ShadowLooper.idleMainLooper(180L)
        assertFalse(session.accepts(token))
        assertEquals("Bob", session.target?.title)
        assertEquals(null, getField<ChatSnapshot?>("pendingSnapshot"))
        assertEquals(1, content.childCount)
        assertEquals(View.GONE, banner.visibility)
    }

    @Test fun contactAndVoiceEditorDoNotInvalidateTheOriginalChatWhileOwnActivityIsOpen() {
        val session = getField<ConversationSession>("session")
        val original = ConversationSession.Target("com.tencent.mm", 0, "Alice")
        session.observe(original)
        setField("toolsActivityActive", true)
        setField("toolsTarget", original)
        service.page = AccessibilityNodeInfo.obtain().apply { packageName = service.packageName }
        event(service.packageName, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
        event(service.packageName, AccessibilityEvent.TYPE_WINDOWS_CHANGED)
        invoke("maybeCapture")
        assertEquals(original, session.target)
        assertTrue(overlay.isShowing())
    }

    @Test fun temporarySelectionBlocksBackfillBeforeAnyRecordingSessionCanStart() {
        val original = ConversationSession.Target("com.tencent.mm", 0, "Alice")
        getField<ConversationSession>("session").observe(original)
        getField<ConversationContactBinding>("contactBinding").select(original, "chosen")
        invoke("toggleHistoryCapture")
        assertEquals(null, getField<BackfillSession>("backfill").active)
        assertEquals("chosen", getField<ConversationContactBinding>("contactBinding").contactId(original))
    }

    @Test fun returningFromPickerKeepsTheSessionUntilTheLiveTitleIsVerified() {
        val original = ConversationSession.Target(SoulAdapter.PACKAGE, 0, "Alice")
        getField<ConversationSession>("session").observe(original)
        setField("toolsActivityActive", true)
        setField("toolsTarget", original)
        val adapter = object : ChatAppAdapter {
            override val pkg = SoulAdapter.PACKAGE
            override fun extract(root: AccessibilityNodeInfo, res: Resources) =
                ChatSnapshot("Bob", listOf(Msg("other", "返回期间的消息")))
        }
        setField("adapters", mapOf(adapter.pkg to adapter))
        service.page = AccessibilityNodeInfo.obtain().apply { packageName = adapter.pkg }
        event(adapter.pkg, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
        invoke("maybeCapture")
        assertEquals(original, getField<ConversationSession>("session").target)
        // A readable different title is rejected by the result handler, not bound.
        finishTools(original)
        assertFalse(getField<Boolean>("toolsActivityActive"))
        assertNullBinding(original)
    }

    @Test fun pickerReturnAcceptsTheSameChatWithNewMessagesAndRestoresAutoMatching() {
        service.page = AccessibilityNodeInfo.obtain().apply { packageName = SoulAdapter.PACKAGE }
        val original = ConversationSession.Target(SoulAdapter.PACKAGE, service.page!!.windowId, "Alice")
        getField<ConversationSession>("session").observe(original)
        getField<ConversationContactBinding>("contactBinding").select(original, "previous-choice")
        setField("toolsActivityActive", true)
        setField("toolsTarget", original)
        val adapter = object : ChatAppAdapter {
            override val pkg = SoulAdapter.PACKAGE
            override fun extract(root: AccessibilityNodeInfo, res: Resources) =
                ChatSnapshot("Alice", listOf(Msg("other", "进入选择页后刚收到的新消息")))
        }
        setField("adapters", mapOf(adapter.pkg to adapter))
        service.page = AccessibilityNodeInfo.obtain().apply { packageName = adapter.pkg }
        finishTools(original)
        assertFalse(getField<Boolean>("toolsActivityActive"))
        assertEquals(original, getField<ConversationSession>("session").target)
        assertNullBinding(original)
    }

    @Test fun delayedPickerReturnCannotApplyAfterAnotherToolRequestStarts() {
        val original = ConversationSession.Target(SoulAdapter.PACKAGE, 0, "Alice")
        getField<ConversationSession>("session").observe(original)
        setField("toolsActivityActive", true)
        setField("toolsTarget", original)
        setField("toolsRequestId", "new-request")
        finishTools(original)
        assertTrue(getField<Boolean>("toolsActivityActive"))
        assertEquals("new-request", getField<String>("toolsRequestId"))
    }

    @Test fun pickerReturnWaitsForAnInitiallyEmptyWechatTreeButTimesOutWithoutBinding() {
        service.page = AccessibilityNodeInfo.obtain().apply { packageName = "com.tencent.mm" }
        // Framework-provided accessibility roots are sealed; view-ID searches
        // reject an unsealed synthetic node before exercising the return path.
        AccessibilityNodeInfo::class.java.getDeclaredMethod("setSealed", Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(service.page, true)
        val original = ConversationSession.Target("com.tencent.mm", service.page!!.windowId, "Alice")
        getField<ConversationSession>("session").observe(original)
        setField("toolsActivityActive", true)
        setField("toolsTarget", original)
        finishTools(original)
        assertTrue(getField<Boolean>("toolsActivityActive"))
        assertEquals(original, getField<ConversationSession>("session").target)
        ShadowLooper.idleMainLooper(3500L)
        assertFalse(getField<Boolean>("toolsActivityActive"))
        assertNullBinding(original)
    }

    private fun assertNullBinding(target: ConversationSession.Target) {
        assertEquals(null, getField<ConversationContactBinding>("contactBinding").contactId(target))
    }

    private fun finishTools(target: ConversationSession.Target) {
        ChatCaptureService::class.java.getDeclaredMethod("finishConversationTools",
            ConversationSession.Target::class.java, String::class.java,
            Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(service, target, null, false, false, 0)
    }

    private fun invoke(name: String, target: ConversationSession.Target? = null) {
        if (target == null) ChatCaptureService::class.java.getDeclaredMethod(name)
            .apply { isAccessible = true }.invoke(service)
        else ChatCaptureService::class.java.getDeclaredMethod(name, ConversationSession.Target::class.java)
            .apply { isAccessible = true }.invoke(service, target)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> getField(name: String): T = ChatCaptureService::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(service) as T

    private fun event(pkg: String, type: Int) {
        val event = AccessibilityEvent.obtain(type).apply { packageName = pkg }
        try { service.onAccessibilityEvent(event) } finally { event.recycle() }
    }

    private fun setField(name: String, value: Any) {
        ChatCaptureService::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.set(service, value)
    }
}
