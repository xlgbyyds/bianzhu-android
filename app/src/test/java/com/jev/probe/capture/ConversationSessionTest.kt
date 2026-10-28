package com.jev.probe.capture

import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Msg
import org.junit.Assert.*
import org.junit.Test

class ConversationSessionTest {
    private val chatA = ConversationSession.Target("com.tencent.mobileqq", 7, "Alice")
    private val chatB = chatA.copy(title = "Bob")

    @Test fun pinnedAnalysisSurvivesNewMessagesInTheSameNamedChatOnly() {
        val pin = PinnedAnalysisTarget()
        pin.pin(chatA)
        assertTrue(pin.protects(chatA.copy(messagesSignature = "new incoming message")))
        assertFalse(pin.protects(chatB))
        assertFalse(pin.protects(chatA.copy(pkg = "com.tencent.mm")))
        assertFalse(pin.protects(chatA.copy(windowId = chatA.windowId + 1)))
        pin.clear()
        assertFalse(pin.protects(chatA))
    }

    @Test fun pinnedAnalysisToleratesAnUnreadableFrameInTheSameWindowOnly() {
        val pin = PinnedAnalysisTarget()
        pin.pin(chatA)

        assertTrue(pin.isBoundTo(chatA))
        assertTrue(pin.isSameWindow(chatA.pkg, chatA.windowId))
        assertTrue(pin.isTransientRead(chatA.copy(title = null)))
        assertFalse(pin.isTransientRead(chatB))
        assertFalse(pin.isSameWindow(chatA.pkg, chatA.windowId + 1))
        assertFalse(pin.isSameWindow("com.tencent.mm", chatA.windowId))
    }

    @Test fun titlelessWechatPinSurvivesNewVisibleRowsWithoutBindingANewContact() {
        val pin = PinnedAnalysisTarget()
        val titleless = ConversationSession.Target("com.tencent.mm", 11, null, "visible rows A")
        pin.pin(titleless)

        assertTrue(pin.isBoundTo(titleless))
        assertTrue(pin.isTransientRead(titleless.copy(messagesSignature = "visible rows B")))
        assertFalse(pin.protects(titleless.copy(messagesSignature = "visible rows B")))
        assertFalse(pin.isSameWindow("com.tencent.mm", 12))
    }

    @Test fun scopeReadRetriesAreBoundedAndResetForAnotherWindow() {
        val retry = ScopeReadRetry(maxAttempts = 2)
        assertEquals(1, retry.next("open", "com.tencent.mm", 7))
        assertEquals(2, retry.next("open", "com.tencent.mm", 7))
        assertNull(retry.next("open", "com.tencent.mm", 7))
        assertEquals(1, retry.next("open", "com.tencent.mm", 8))
        retry.reset("open")
        assertEquals(1, retry.next("open", "com.tencent.mm", 8))
    }

    @Test fun unchangedConversationAcceptsBothCallbacks() {
        val session = ConversationSession()
        session.observe(chatA)
        val request = session.begin()!!
        assertFalse(session.observe(chatA))
        assertTrue(session.accepts(request)) // judgment
        assertTrue(session.accepts(request)) // replies
    }

    @Test fun switchingChatRejectsOldResultsAndFill() {
        val session = ConversationSession()
        session.observe(chatA)
        val request = session.begin()!!
        session.observe(chatB)
        assertFalse(session.accepts(request))
        assertTrue(session.accepts(session.begin()!!))
    }

    @Test fun returningToOriginalChatDoesNotReviveOldRequest() {
        val session = ConversationSession()
        session.observe(chatA)
        val old = session.begin()!!
        session.observe(chatB)
        session.observe(chatA)
        assertFalse(session.accepts(old))
    }

    @Test fun sameTitleInDifferentAppIsDifferentTarget() {
        val session = ConversationSession()
        session.observe(chatA)
        val request = session.begin()!!
        session.observe(chatA.copy(pkg = "com.twitter.android"))
        assertFalse(session.accepts(request))
    }

    @Test fun replacementWindowInvalidatesTarget() {
        val session = ConversationSession()
        session.observe(chatA)
        val request = session.begin()!!
        session.observe(chatA.copy(windowId = 8))
        assertFalse(session.accepts(request))
    }

    @Test fun differentMessagesInvalidateEvenWhenChatTitlesMatch() {
        val session = ConversationSession()
        session.observe(chatA.copy(messagesSignature = "other:hello"))
        val request = session.begin()!!
        session.observe(chatA.copy(messagesSignature = "other:goodbye"))
        assertFalse(session.accepts(request))
    }

    @Test fun unnamedWechatFallbackMatchesOnlyTheCapturedVisibleMessages() {
        val snapshot = ChatSnapshot(null, listOf(Msg("other", "hello")))
        val target = ConversationSession.Target("com.tencent.mm", 2, null, snapshot.signature())
        assertTrue(target.matchesSnapshot(snapshot))
        assertFalse(target.matchesSnapshot(snapshot.copy(messages = listOf(Msg("other", "changed")))))
        assertFalse(target.copy(pkg = "com.tantan.x").matchesSnapshot(snapshot))
    }

    @Test fun unnamedWechatFallbackCannotMatchAnEmptyPageOrAContactTitle() {
        val snapshot = ChatSnapshot(null, listOf(Msg("other", "hello")))
        val target = ConversationSession.Target("com.tencent.mm", 2, null, snapshot.signature())
        assertFalse(target.matchesSnapshot(ChatSnapshot(null, emptyList())))
        assertFalse(target.matchesSnapshot(snapshot.copy(title = "猜出来的名字")))
    }

    @Test fun leavingChatInvalidatesResultsAndDelayedWrites() {
        val session = ConversationSession()
        session.observe(chatA)
        val request = session.begin()!!
        session.observe(null)
        assertFalse(session.accepts(request))
        assertNull(session.begin())
    }

    @Test fun newRequestRejectsPreviousRequestEvenInSameChat() {
        val session = ConversationSession()
        session.observe(chatA)
        val old = session.begin()!!
        val new = session.begin()!!
        assertFalse(session.accepts(old))
        assertTrue(session.accepts(new))
    }

    @Test fun explicitInvalidationRejectsScreenshotAndNetworkCallbacks() {
        val session = ConversationSession()
        session.observe(chatA)
        val screenshot = session.token()!!
        val request = session.begin()!!
        session.invalidate()
        assertFalse(session.accepts(screenshot))
        assertFalse(session.accepts(request))
    }
}
