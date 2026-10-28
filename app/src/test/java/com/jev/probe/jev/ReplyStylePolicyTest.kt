package com.jev.probe.jev

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyStylePolicyTest {
    @Test fun olderMessagesUseDelayedReplyStylesAndUnknownDatesStayNeutral() {
        assertFalse(ReplyStylePolicy.isDelayed(null))
        assertFalse(ReplyStylePolicy.isDelayed(2))
        assertTrue(ReplyStylePolicy.isDelayed(3))
        assertEquals(listOf("restart", "resume_topic", "share_now"), ReplyStylePolicy.stylesFor(12))
        assertEquals(listOf("natural", "follow_detail", "playful"), ReplyStylePolicy.stylesFor(null))
    }
}
