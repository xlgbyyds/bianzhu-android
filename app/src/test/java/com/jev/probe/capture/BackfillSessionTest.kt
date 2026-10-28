package com.jev.probe.capture

import org.junit.Assert.*
import org.junit.Test

class BackfillSessionTest {
    private val target = ConversationSession.Target("com.tencent.mm", 7, "测试联系人")

    @Test fun changingVisibleMessagesDoesNotEndConsentForTheSameContact() {
        val state = BackfillSession()
        val run = state.start(target, "contact-a")!!
        repeat(3) { assertTrue(state.accepts(run, target.copy())) }
    }

    @Test fun otherContactOrWindowCannotWriteToTheOriginalRun() {
        val state = BackfillSession()
        val run = state.start(target, "contact-a")!!
        assertFalse(state.accepts(run, target.copy(title = "另一个联系人")))
        assertFalse(state.accepts(run, target.copy(windowId = 8)))
        assertFalse(state.accepts(run, target.copy(pkg = "com.tantan.x")))
        assertFalse(state.accepts(run, null))
    }

    @Test fun stoppingThenRestartingSameContactRejectsAnOldOcrCallback() {
        val state = BackfillSession()
        val first = state.start(target, "contact-a")!!
        assertEquals(first, state.stop())
        val second = state.start(target, "contact-a")!!
        assertFalse(state.accepts(first, target))
        assertTrue(state.accepts(second, target))
    }

    @Test fun transientSystemOrMissingWindowWaitsWithoutLosingConsent() {
        val state = BackfillSession(); val run = state.start(target, "contact-a")!!
        assertEquals(BackfillSession.WindowState.WAIT, state.windowState(run, "com.android.systemui", 99))
        assertEquals(BackfillSession.WindowState.WAIT, state.windowState(run, null, null))
        assertEquals(BackfillSession.WindowState.TARGET, state.windowState(run, target.pkg, target.windowId))
        assertTrue(state.isCurrent(run))
    }

    @Test fun actualNavigationAndStoppedRunNeverResumeFromTransientWindows() {
        val state = BackfillSession(); val run = state.start(target, "contact-a")!!
        assertEquals(BackfillSession.WindowState.LEFT, state.windowState(run, target.pkg, 8))
        assertEquals(BackfillSession.WindowState.LEFT, state.windowState(run, "cn.soulapp.android", 7))
        assertEquals(BackfillSession.WindowState.LEFT, state.windowState(run, "com.jev.personal.chatassistant", 7))
        state.stop()
        assertEquals(BackfillSession.WindowState.LEFT, state.windowState(run, "com.android.systemui", 99))
    }

    @Test fun titlelessConversationCannotStartContactBackfill() {
        val state = BackfillSession()
        assertNull(state.start(target.copy(title = null), "contact-a"))
        assertNull(state.start(target, ""))
        assertNull(state.active)
    }
}
