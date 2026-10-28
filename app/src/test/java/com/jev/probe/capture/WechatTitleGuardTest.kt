package com.jev.probe.capture

import org.junit.Assert.*
import org.junit.Test

class WechatTitleGuardTest {
    @Test fun shortRenamedNicknameIsAcceptedAfterLocalOcr() {
        assertEquals("K", wechatTitleCandidate(listOf("K"), setOf("微信", "搜索")) { false })
    }

    @Test fun adjacentOcrFragmentsMayFormOneToolbarTitle() {
        assertEquals("测试联系人", wechatTitleCandidate(
            listOf("测试", "联系", "人"), setOf("微信", "搜索")) { false })
    }

    @Test fun titleRecognitionStillRejectsTooManyFragmentsAndTransientPages() {
        assertNull(wechatTitleCandidate(listOf("A", "B", "C", "D"), emptySet()) { false })
        assertNull(wechatTitleCandidate(listOf("连接中"), emptySet()) { it.contains("连接中") })
        assertNull(wechatTitleCandidate(listOf("搜索"), setOf("微信", "搜索")) { false })
    }

    @Test fun unchangedPageCanUseItsRecognizedTitle() {
        val guard = WechatTitleGuard()
        val request = guard.begin("window:chat-a")
        assertTrue(guard.remember(request, "window:chat-a", "测试联系人"))
        assertEquals("测试联系人", guard.title("window:chat-a"))
        assertNull(guard.title("window:chat-b"))
    }

    @Test fun navigatingAwayAndBackDoesNotReviveAnOldScreenshot() {
        val guard = WechatTitleGuard()
        val request = guard.begin("same visible messages")
        guard.invalidate()
        assertFalse(guard.remember(request, "same visible messages", "旧联系人"))
        assertNull(guard.title("same visible messages"))
    }

    @Test fun changedMessagesWithoutAnEventStillRejectOldIdentity() {
        val guard = WechatTitleGuard()
        val request = guard.begin("before")
        assertFalse(guard.remember(request, "after", "旧联系人"))
        assertTrue(guard.remember(request, "before", "当前联系人"))
        assertNull(guard.title("after"))
        guard.invalidate()
        assertNull(guard.title("before"))
    }
}
