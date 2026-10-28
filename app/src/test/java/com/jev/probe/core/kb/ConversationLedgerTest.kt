package com.jev.probe.core.kb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationLedgerTest {
    @Test fun chineseTopicPairsSurvivePunctuationButFillersDoNot() {
        val grams = ConversationLedger.grams("工作，周末？哈哈")
        assertTrue(grams.contains("工作"))
        assertTrue(grams.contains("周末"))
        assertFalse(grams.contains("哈哈"))
    }

    @Test fun unknownMediaKeepsUnknownTimeAndContentInModelText() {
        val line = LogEntry("unknown", "", 1L, "chat", "VOICE_UNKNOWN", historical = true)
        assertTrue(line.modelText().contains("补录旧消息"))
        assertTrue(line.modelText().contains("发送时间未知"))
        assertTrue(line.modelText().contains("语音消息，内容未知"))
    }

    @Test fun manualTimeIsLabeledAsUserSuppliedNotAsCaptureTime() {
        val line = LogEntry("other", "你好", 123L, "chat", visibleTime = "9月15日 16:00",
            timeConfidence = "manual", historical = true)
        assertTrue(line.modelText().contains("用户人工填写"))
        assertFalse(line.modelText().contains("123"))
    }
    private fun line(side: String, text: String) = LogEntry(side, text, 1L, "chat")

    @Test fun overlappingScreensAppendOnlyNewTail() {
        val old = listOf(line("other", "你好"), line("me", "你也好"), line("other", "吃饭了吗"))
        val page = listOf(line("me", "你也好"), line("other", "吃饭了吗"), line("other", "刚吃"))
        assertEquals(2, ConversationLedger.longestOverlap(old, page))
    }

    @Test fun repeatedShortMessagesKeepTheirPositions() {
        val old = listOf(line("other", "嗯"), line("other", "嗯"))
        val page = listOf(line("other", "嗯"), line("other", "嗯"), line("me", "好"))
        assertEquals(2, ConversationLedger.longestOverlap(old, page))
    }

    private fun key(length: Int, id: String) = length.toString(16).padStart(4, '0') + id

    @Test fun overlappingPagesStoreOnlyNewPositions() {
        val a = key(12, "a")
        val b = key(2, "b")
        val c = key(10, "c")
        val d = key(8, "d")
        val old = listOf(a, b, c)
        assertEquals(setOf(0, 1), ConversationLedger.coveredByEarlierPages(
            listOf(b, c, d), listOf(old)))
        assertEquals(setOf(1, 2), ConversationLedger.coveredByEarlierPages(
            listOf(d, a, b), listOf(old)))
    }

    @Test fun ambiguousRepeatedShortMessagesAreKept() {
        val hello = key(2, "hello")
        val okay = key(1, "okay")
        assertTrue(ConversationLedger.coveredByEarlierPages(
            listOf(hello, hello, okay), listOf(listOf(hello, hello))).isEmpty())
        assertTrue(ConversationLedger.coveredByEarlierPages(
            listOf(hello, okay), listOf(listOf(hello, key(8, "distinct")))).isEmpty())
    }

    @Test fun aPageCanOverlapMultiplePreviousScreens() {
        val a = key(9, "a")
        val b = key(8, "b")
        val c = key(7, "c")
        val d = key(6, "d")
        assertEquals(setOf(0, 1, 2, 3), ConversationLedger.coveredByEarlierPages(
            listOf(a, b, c, d), listOf(listOf(a, b), listOf(c, d))))
    }
}
