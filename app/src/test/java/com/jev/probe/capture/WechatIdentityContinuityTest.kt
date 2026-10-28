package com.jev.probe.capture

import com.jev.probe.core.Msg
import org.junit.Assert.*
import org.junit.Test

class WechatIdentityContinuityTest {
    @Test fun retainsResolvedIdentityAcrossOverlappingScrollPages() {
        val state = WechatIdentityContinuity()
        val first = listOf(Msg("other", "??????"), Msg("me", "??????"), Msg("other", "????"))
        val next = listOf(Msg("me", "??????"), Msg("other", "????"), Msg("me", "??"))
        assertEquals("??", state.observe(5, first, "??"))
        assertEquals("??", state.observe(5, next, null))
    }

    @Test fun keepsIdentityForAnUnchangedVisiblePage() {
        val state = WechatIdentityContinuity()
        val page = listOf(Msg("other", "?????????"), Msg("me", "?????"))
        state.observe(5, page, "??")
        assertEquals("??", state.observe(5, page, null))
    }

    @Test fun dropsIdentityWhenMessagesDoNotProveContinuity() {
        val state = WechatIdentityContinuity()
        state.observe(5, listOf(Msg("other", "?????"), Msg("me", "?????")), "??")
        assertNull(state.observe(5, listOf(Msg("other", "??"), Msg("me", "??")), null))
        assertNull(state.observe(5, listOf(Msg("other", "?????"), Msg("me", "?????")), null))
    }

    @Test fun neverCarriesIdentityAcrossWindowsOrOneMessageCoincidence() {
        val state = WechatIdentityContinuity()
        state.observe(5, listOf(Msg("other", "????????"), Msg("me", "??????????")), "??")
        assertNull(state.observe(6, listOf(Msg("other", "????????"), Msg("me", "??????????")), null))
        assertNull(state.observe(6, listOf(Msg("other", "????????")), null))
    }
}
