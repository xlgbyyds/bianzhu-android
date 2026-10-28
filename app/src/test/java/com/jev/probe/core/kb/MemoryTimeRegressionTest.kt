package com.jev.probe.core.kb

import android.content.Context
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Msg
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MemoryTimeRegressionTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var ledger: ConversationLedger
    private val app = "com.tencent.mm"
    private fun at(day: Int) = LocalDateTime.of(2026, 10, day, 18, 0)
        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private fun page(time: String?) = ChatSnapshot("同一人", listOf(
        Msg("me", "我家那边倒是没这么多故事", visibleTime = time),
        Msg("other", "奶奶讲这些老事还是挺有意思的", visibleTime = time),
        Msg("me", "下次你给我也讲讲小时候的事情", visibleTime = time)))
    @Before fun setup() { context.deleteDatabase("conversation-memory.db"); ledger = ConversationLedger(context) }
    @After fun cleanup() { ledger.close(); context.deleteDatabase("conversation-memory.db") }

    @Test fun expandedDateEnrichesClockWithoutCopyingMessages() {
        ledger.recordDetailed("a", app, page("下午4:45"), false, at(11))
        val result = ledger.recordDetailed("a", app, page("10月11日 周日 下午4:45"), true, at(12))
        assertEquals(0, result.added)
        assertEquals(3, result.timeUpdated)
        assertEquals(3, ledger.lineCount("a"))
        assertTrue(ledger.items("a", false, 20, 0).all { it.visibleTime == "10月11日 周日 下午4:45" })
        val replay = ledger.recordDetailed("a", app, page("下午4:45"), true, at(12))
        assertEquals(0, replay.added)
        assertEquals(0, replay.timeUpdated)
    }

    @Test fun sameWordsAndClockOnDifferentKnownDaysAreSeparate() {
        ledger.recordDetailed("a", app, page("10月11日 下午4:45"), true, at(12))
        assertEquals(3, ledger.recordDetailed("a", app, page("10月12日 下午4:45"), true, at(12)).added)
        assertEquals(6, ledger.lineCount("a"))
    }

    @Test fun relativeTimeIsFrozenAtObservationInsteadOfChangingAfterReopen() {
        ledger.recordDetailed("a", app, page("昨天 下午4:45"), true, at(11))
        ledger.close(); ledger = ConversationLedger(context)
        assertEquals(0, ledger.recordDetailed("a", app, page("10月10日 下午4:45"), true, at(12)).added)
        assertEquals(3, ledger.recordDetailed("a", app, page("昨天 下午4:45"), true, at(12)).added)
    }

    @Test fun repeatedShortUntimedPageRequestsReviewInsteadOfClaimingSaved() {
        val short = ChatSnapshot("同一人", listOf(Msg("me", "哈哈哈"), Msg("other", "嗯嗯")))
        ledger.recordDetailed("a", app, short, false, at(11))
        val result = ledger.recordDetailed("a", app, short, false, at(12))
        assertFalse(result.saved)
        assertTrue(result.needsReview)
        assertEquals(2, ledger.lineCount("a"))
        val dated = short.copy(messages = short.messages.map { it.copy(visibleTime = "10月12日 上午9:22") })
        assertTrue(ledger.recordDetailed("a", app, dated, true, at(12)).saved)
        assertEquals(4, ledger.lineCount("a"))
    }

    @Test fun laterBackfillDoesNotReplaceRecentDatedExpressionSamples() {
        ledger.recordDetailed("a", app, ChatSnapshot("同一人", listOf(
            Msg("me", "那你以后少考我 我怕翻车", visibleTime = "10月12日 上午1:13"))), true, at(12))
        ledger.recordDetailed("a", app, ChatSnapshot("同一人", listOf(
            Msg("me", "昨天那件事情我想了想", visibleTime = "10月10日 下午4:00"))), true, at(12))
        assertEquals("那你以后少考我 我怕翻车", ledger.ownExpressions("a", app).first())
        assertTrue(ledger.ownExpressions("b", app).isEmpty())
    }

    @Test fun legacyEnrichedRelativeLabelWithoutProvenanceStaysDateUnknownInModelContext() {
        ledger.recordDetailed("a", app, page(null), true, at(10))
        ledger.writableDatabase.execSQL("UPDATE line SET visible_time='昨天 下午4:45',time_confidence='partial'")
        val source = ledger.relationshipSource("a", app)
        assertTrue(source.lines.all { it.visibleTime == "日期未知 下午4:45" })
        val result = ledger.recordDetailed("a", app, page("10月11日 下午4:45"), true, at(12))
        assertEquals(0, result.added)
        assertEquals(3, result.timeUpdated)
        assertEquals(3, ledger.lineCount("a"))
    }

    @Test fun shiftedDatedShortMessagesReuseTheirOccurrencesAcrossTwoStoredPages() {
        val early = Msg("me", "早啊", visibleTime = "10月12日 上午8:56")
        val replies = listOf(Msg("other", "早上好呀", visibleTime = "10月12日 上午9:10"),
            Msg("other", "?", visibleTime = "10月12日 上午9:14"),
            Msg("other", "啥意思", visibleTime = "10月12日 上午9:14"))
        ledger.recordDetailed("a", app, ChatSnapshot("同一人", listOf(
            Msg("me", "那我去睡了 你也早点休息 别熬太晚", visibleTime = "10月12日 凌晨1:33"), early)), true, at(12))
        ledger.recordDetailed("a", app, ChatSnapshot("同一人", replies), true, at(12))
        val count = ledger.lineCount("a")
        assertEquals(0, ledger.recordDetailed("a", app, ChatSnapshot("同一人", listOf(early) + replies), true, at(12)).added)
        assertEquals(count, ledger.lineCount("a"))
        val tomorrow = (listOf(early) + replies).map { it.copy(visibleTime = it.visibleTime!!.replace("12日", "13日")) }
        assertEquals(4, ledger.recordDetailed("a", app, ChatSnapshot("同一人", tomorrow), true, at(13)).added)
    }

    @Test fun repeatedDatedShortWordsWithinTheSamePageRemainTwoActualMessages() {
        val messages = listOf(Msg("me", "哈哈哈", visibleTime = "10月12日 上午9:10"),
            Msg("me", "哈哈哈", visibleTime = "10月12日 上午9:10"),
            Msg("other", "嘿嘿", visibleTime = "10月12日 上午9:14"))
        ledger.recordDetailed("a", app, ChatSnapshot("同一人", messages), true, at(12))
        assertEquals(3, ledger.lineCount("a"))
        assertEquals(2, ledger.items("a", false, 10, 0).count { it.body == "哈哈哈" })
        assertEquals(0, ledger.recordDetailed("a", app, ChatSnapshot("同一人", messages.drop(1)), true, at(12)).added)
        assertEquals(3, ledger.lineCount("a"))
    }
}
