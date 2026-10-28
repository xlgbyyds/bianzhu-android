package com.jev.probe.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class ChatModelsTimeTest {
    @Test fun pageTimeIsMetadataNotClaimedAsExactSendTime() {
        assertEquals(
            "[页面时间：9月15日 23:52；非逐条精确发送时间] 哈哈哈",
            Msg("other", "哈哈哈", visibleTime = "9月15日 23:52", timeConfidence = "partial")
                .modelText()
        )
        assertEquals("[语音转写] 在路上", Msg("other", "在路上", MessageKind.VOICE_TRANSCRIPT)
            .modelText())
    }

    @Test fun pageDateAgeUsesLabelWithoutInventingAnExactSendTime() {
        assertEquals(14L, pageDateAgeDays("9月15日 23:52", LocalDate.of(2026, 9, 29)))
        assertEquals(2L, pageDateAgeDays("12月31日 20:00", LocalDate.of(2027, 1, 2)))
        assertEquals(379L, pageDateAgeDays("2025年9月15日", LocalDate.of(2026, 9, 29)))
        assertNull(pageDateAgeDays("23:52", LocalDate.of(2026, 9, 29)))
    }

    @Test fun pageDateIsFoundEvenWhenLatestBubbleOnlyShowsClockTime() {
        val evidence = pageTimeEvidence(listOf(
            Msg("other", "旧消息", visibleTime = "9月15日"),
            Msg("me", "后来回复", visibleTime = "16:00")
        ), LocalDate.of(2026, 9, 29))
        assertEquals("9月15日", evidence.label)
        assertEquals(14L, evidence.ageDays)
    }
}
