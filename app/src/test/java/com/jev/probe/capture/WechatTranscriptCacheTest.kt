package com.jev.probe.capture

import android.graphics.Rect
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.MessageKind
import com.jev.probe.core.Msg
import com.jev.probe.core.VoiceTranscriptRect
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WechatTranscriptCacheTest {
    private fun snapshot(title: String = "联系人") = ChatSnapshot(title, listOf(
        Msg("other", "", MessageKind.VOICE_UNKNOWN, "周四 晚上9:08", "partial"),
        Msg("me", "", MessageKind.VOICE_UNKNOWN), Msg("me", "原回复")),
        voiceTranscriptRects = listOf(VoiceTranscriptRect(0, Rect(100, 300, 700, 400)),
            VoiceTranscriptRect(1, Rect(400, 600, 900, 700))))

    @Test fun recognizedTextKeepsOriginalSpeakerAndTimeAndCannotOverwriteOtherRows() {
        val cache = WechatTranscriptCache()
        val raw = snapshot()
        val result = cache.remember(cache.key(1, raw), raw,
            mapOf(0 to "我刚到家", 1 to "早点休息", 2 to "不能覆盖", 20 to "不存在"))
        assertEquals(listOf("other", "me", "me"), result.messages.map { it.side })
        assertEquals(listOf("我刚到家", "早点休息", "原回复"), result.messages.map { it.text })
        assertEquals("周四 晚上9:08", result.messages.first().visibleTime)
        assertEquals("partial", result.messages.first().timeConfidence)
        assertEquals(MessageKind.VOICE_TRANSCRIPT, result.messages[1].kind)
        assertTrue(result.voiceTranscriptRects.isEmpty())
    }

    @Test fun cacheCannotCrossNamesWindowsOrChangedCropGeometry() {
        val cache = WechatTranscriptCache()
        val raw = snapshot()
        cache.remember(cache.key(1, raw), raw, mapOf(0 to "转写", 1 to "另一条"))
        assertNotNull(cache.get(cache.key(1, raw)))
        assertNull(cache.get(cache.key(2, raw)))
        assertNull(cache.get(cache.key(1, snapshot("另一个人"))))
        assertNull(cache.get(cache.key(1, raw.copy(voiceTranscriptRects =
            listOf(VoiceTranscriptRect(0, Rect(100, 500, 700, 600)))))))
        cache.clear()
        assertNull(cache.get(cache.key(1, raw)))
    }

    @Test fun failedOrControlOnlyCropsRemainUnknownAndMayBeRetried() {
        var elapsed = 0L
        val cache = WechatTranscriptCache { elapsed }
        val raw = snapshot()
        val key = cache.key(1, raw)
        val result = cache.remember(key, raw, mapOf(0 to "转文字", 1 to "周四 晚上9:08"))
        assertTrue(result.messages.take(2).all { it.kind == MessageKind.VOICE_UNKNOWN && it.text.isEmpty() })
        assertNotNull(cache.get(key))
        elapsed = 2100
        assertNull(cache.get(key))
    }
}
