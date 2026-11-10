package com.jev.probe.capture

import com.jev.probe.core.MessageKind
import com.jev.probe.core.Msg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class WechatPageFingerprintTest {
    private val region = "[352,138][867,284]"

    @Test fun redrawNoiseKeepsTheSamePageIdentity() {
        val before = listOf(
            Msg("other", "嗯呐", visibleTime = "昨天"),
            Msg("me", "嗯 早点睡", visibleTime = "昨天")
        )
        // Same words, different visible-time label and kind: WeChat redrew the list.
        val after = listOf(
            Msg("other", "嗯呐", visibleTime = "1月1日", timeConfidence = "partial"),
            Msg("me", "嗯 早点睡", kind = MessageKind.VOICE_UNKNOWN)
        )
        assertEquals(
            wechatPageFingerprint(12, region, before),
            wechatPageFingerprint(12, region, after)
        )
    }

    @Test fun differentWordsOrWindowMeanAnotherPage() {
        val page = listOf(Msg("other", "在吗"), Msg("me", "在"))
        assertNotEquals(
            wechatPageFingerprint(12, region, page),
            wechatPageFingerprint(12, region, listOf(Msg("other", "在吗"), Msg("me", "刚忙完")))
        )
        assertNotEquals(
            wechatPageFingerprint(12, region, page),
            wechatPageFingerprint(13, region, page)
        )
    }
}
