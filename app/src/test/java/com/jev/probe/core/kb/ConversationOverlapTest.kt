package com.jev.probe.core.kb

import com.jev.probe.core.MessageKind
import com.jev.probe.core.Msg
import org.junit.Assert.*
import org.junit.Test

class ConversationOverlapTest {
    private fun msg(side: String, text: String, time: String? = null) =
        Msg(side, text, MessageKind.TEXT, time, if (time == null) "unknown" else "partial")
    private fun stored(page: List<Msg>, start: Long = 1) = page.mapIndexed { i, m ->
        ConversationOverlap.Line(start + i, m.side, m.kind.name, m.text, m.visibleTime, m.timeConfidence)
    }
    private val page = listOf(msg("me", "你在长沙不"), msg("other", "在的"),
        msg("me", "这是一条跨过了屏幕中线的较长消息", "14:33"))

    @Test fun threeOrderedMessagesMatchWhenTimeLabelLeavesScreen() {
        val withoutTime = page.map { it.copy(visibleTime = null, timeConfidence = "unknown") }
        assertEquals(setOf(0, 1, 2), ConversationOverlap.match(withoutTime, listOf(stored(page))).keys)
    }

    @Test fun knownConflictingTimeRejectsTheEntireEqualTextRun() {
        val repeated = page.map { it.copy(visibleTime = "9月16日 14:33") }
        val earlier = page.map { it.copy(visibleTime = "9月15日 14:33") }
        assertTrue(ConversationOverlap.match(repeated, listOf(stored(earlier))).isEmpty())
        val twoLong = listOf(msg("me", "这是一条足够长的消息用来比较上下文"),
            msg("other", "这是另一条较长的回复不能单独忽略日期"), msg("me", "明天见", "9月15日"))
        assertTrue(ConversationOverlap.match(twoLong.dropLast(1) + twoLong.last().copy(visibleTime = "9月16日"),
            listOf(stored(twoLong))).isEmpty())
    }

    @Test fun severalCommonFillersDoNotProveTheSameConversationSegment() {
        val filler = listOf(msg("me", "哈哈"), msg("other", "好的"), msg("me", "嗯"))
        assertTrue(ConversationOverlap.match(filler, listOf(stored(filler))).isEmpty())
    }

    @Test fun twoDistinctiveMessagesCanMatchButOneCannot() {
        val long = listOf(msg("me", "今天路上看到一家很有意思的小店"), msg("other", "是哪一家呀"))
        assertEquals(setOf(0, 1), ConversationOverlap.match(long, listOf(stored(long))).keys)
        assertTrue(ConversationOverlap.match(long.take(1), listOf(stored(long))).isEmpty())
    }

    @Test fun speakerOrderAndMessageTypeMustMatch() {
        assertTrue(ConversationOverlap.match(page.map { it.copy(side = if (it.side == "me") "other" else "me") },
            listOf(stored(page))).isEmpty())
        assertTrue(ConversationOverlap.match(page.reversed(), listOf(stored(page))).isEmpty())
        assertTrue(ConversationOverlap.match(page.map { it.copy(kind = MessageKind.VOICE_TRANSCRIPT) },
            listOf(stored(page))).isEmpty())
    }

    @Test fun uncertainSpeakerRowsCanDeduplicateOnlyInsideAConfirmedSequence() {
        val mixed = listOf(msg("unknown", "uncertain sender phrase"),
            msg("other", "a sufficiently long distinctive message for context"),
            msg("me", "another long distinctive reply with enough context"))
        assertEquals(setOf(0, 1, 2), ConversationOverlap.match(mixed, listOf(stored(mixed))).keys)
    }

    @Test fun multipleOldOccurrencesAreAmbiguousButReferencesToSameIdsAreNot() {
        assertTrue(ConversationOverlap.match(page, listOf(stored(page), stored(page, 20))).isEmpty())
        assertEquals(3, ConversationOverlap.match(page, listOf(stored(page), stored(page))).size)
    }

    @Test fun oneOldOccurrenceCannotConsumeTwoNewOccurrences() {
        assertTrue(ConversationOverlap.match(page + page, listOf(stored(page))).isEmpty())
    }

    @Test fun aLongerUniqueContextIsNotVetoedByAShorterRepeatedFragment() {
        val full = listOf(msg("me", "第一条上下文需要足够独特"), msg("other", "第二条上下文也是完整的话"),
            msg("me", "第三条很明确的回复用于定位"), msg("other", "第四条继续构成唯一的聊天片段"))
        assertEquals(setOf(0, 1, 2, 3), ConversationOverlap.match(full,
            listOf(stored(full), stored(full.take(2), 20))).keys)
    }

    @Test fun equallyStrongRepeatedContextsStillNeedDisambiguation() {
        val full = page + msg("other", "第四条独特消息用于比较上下文")
        assertTrue(ConversationOverlap.match(full, listOf(stored(full), stored(full, 20))).isEmpty())
        assertTrue(ConversationOverlap.coveredBySinglePage(full, listOf(stored(full), stored(full, 20))))
        val yesterday = stored(full.map { it.copy(visibleTime = "10月2日") })
        assertFalse(ConversationOverlap.coveredBySinglePage(full.map { it.copy(visibleTime = "10月3日") }, listOf(yesterday)))
    }

    @Test fun unreadableMediaAndMissingPositionsCannotAnchorOrBridgeAnOverlap() {
        val media = List(3) { Msg("other", "", MessageKind.MEDIA_UNKNOWN) }
        assertTrue(ConversationOverlap.match(media, listOf(stored(media))).isEmpty())
        assertTrue(ConversationOverlap.match(page, listOf(listOf(stored(page)[0], null, stored(page)[2]))).isEmpty())
    }
}
