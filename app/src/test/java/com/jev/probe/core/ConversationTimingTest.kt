package com.jev.probe.core

import java.time.LocalDateTime
import org.junit.Assert.*
import org.junit.Test

class ConversationTimingTest {
    private val evening = LocalDateTime.of(2026, 10, 10, 20, 10)
    private fun timing(label: String?, body: String = "猫又把我的杯子推倒了") =
        ConversationTiming.from(ChatSnapshot("测试", listOf(Msg("other", body, visibleTime = label))), evening)
    @Test fun morningToEveningRestartsWithoutInventingPresentActivity() {
        val t = timing("今天 上午09:10")
        assertEquals(11L, t.approximateHours); assertEquals("晚上", t.daypart); assertTrue(t.restart)
        assertTrue(t.prompt().contains("2026-10-10 20:10"))
        assertTrue(t.prompt().contains("不能直接延续到现在"))
        assertTrue(t.prompt().contains("不据此编造"))
    }
    @Test fun timeOnlyDoesNotInventCalendarDate() {
        val t = timing("09:10")
        assertNull(t.ageDays); assertNull(t.approximateHours); assertFalse(t.restart)
        assertTrue(t.prompt().contains("不能把仅有的钟点认定为今天"))
    }
    @Test fun afternoonClockIsParsedAsPm() {
        assertEquals(5L, timing("今天 下午03:10").approximateHours)
        assertEquals(0L, timing("今天 晚上08:00").approximateHours)
    }
    @Test fun futureClockCannotBecomeNegativeElapsedOrInventYesterday() {
        assertNull(timing("今天 23:30").approximateHours)
        assertNull(timing("2027年10月10日 12:00").ageDays)
    }
    @Test fun oldDatedMessagesAndMidnightRetainTheirDate() {
        assertEquals(25L, timing("9月15日 16:00").ageDays)
        assertTrue(timing("9月15日 16:00").restart)
        val t = ConversationTiming.from(ChatSnapshot("测试", listOf(Msg("other", "等会聊",
            visibleTime = "昨天 晚上11:30"))), LocalDateTime.of(2026, 10, 11, 0, 40))
        assertEquals(1L, t.approximateHours); assertEquals("凌晨", t.daypart)
    }
    @Test fun closedThreadCanRestartEvenWithoutAnExactGap() {
        assertTrue(timing(null, "先忙吧").closed)
        assertTrue(timing(null, "晚安").restart)
        assertFalse(timing(null, "你每天都说晚安吗").closed)
    }
    @Test fun newSubstantiveMessageAfterGoodnightIsNotAClosedThread() {
        val t = ConversationTiming.from(ChatSnapshot("测试", listOf(Msg("me", "晚安"),
            Msg("other", "明天能帮我看一下这个吗"))), evening)
        assertFalse(t.closed)
    }
    @Test fun laterClockDoesNotBorrowEarlierCalendarLabel() {
        val t = ConversationTiming.from(ChatSnapshot("测试", listOf(
            Msg("other", "昨天的消息", visibleTime = "昨天 09:00"),
            Msg("me", "后来的一条", visibleTime = "20:00"))), evening)
        assertNull(t.ageDays); assertNull(t.approximateHours)
    }
}
