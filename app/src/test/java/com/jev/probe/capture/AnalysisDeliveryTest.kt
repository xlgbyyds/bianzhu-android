package com.jev.probe.capture

import com.jev.probe.core.Analysis
import com.jev.probe.core.ReplySuggestion
import org.junit.Assert.*
import org.junit.Test

class AnalysisDeliveryTest {
    private val replies = listOf("自然接话", "轻松调侃", "有分寸地亲近").map { ReplySuggestion(it, "natural", "") }
    private fun judgment(error: String? = null) = Analysis(null, null, null, null, null, null, null, emptyList(), 1, error)

    @Test fun failedJudgePreservesDraftsInEitherArrivalOrderAndNeverRanks() {
        for (judgeFirst in listOf(true, false)) {
            var visible = emptyList<ReplySuggestion>()
            var issue: String? = null
            var ranked = 0
            var finished = 0
            val delivery = AnalysisDelivery({}, { list, reason -> visible = list; issue = reason }, {}, {},
                { fail("A valid draft must not be replaced by an error") }, { ranked++ }, { finished++ })
            if (judgeFirst) delivery.judgmentReceived(judgment("HTTP 451 地区受限"))
            delivery.draftReceived(replies, null)
            if (!judgeFirst) delivery.judgmentReceived(judgment("HTTP 451 地区受限"))
            assertEquals(replies, visible)
            assertTrue(issue!!.contains("未经 Jev 判断和排序"))
            assertEquals(0, ranked)
            assertEquals(1, finished)
        }
    }

    @Test fun rankWaitsForBothSuccessesAndFailureKeepsTheDraft() {
        var ranks = 0
        var finishes = 0
        var visible = emptyList<ReplySuggestion>()
        val delivery = AnalysisDelivery({}, { list, _ -> visible = list }, {}, {}, {}, { ranks++ }, { finishes++ })
        delivery.draftReceived(replies, null)
        assertEquals(0, ranks)
        delivery.judgmentReceived(judgment())
        assertEquals(1, ranks)
        delivery.rankingReceived(emptyList(), "排序超时")
        assertEquals(replies, visible)
        assertEquals(1, finishes)
    }

    @Test fun twoDraftsFinishWithoutLeavingSortingPending() {
        var issue: String? = null
        var ranks = 0
        var finishes = 0
        val delivery = AnalysisDelivery({}, { _, reason -> issue = reason }, {}, {}, {},
            { ranks++ }, { finishes++ })
        delivery.judgmentReceived(judgment())
        delivery.draftReceived(replies.take(2), null)
        assertEquals(0, ranks)
        assertEquals(1, finishes)
        assertTrue(issue.orEmpty().contains("本轮已完成"))
        delivery.timeout()
        assertEquals(1, finishes)
    }

    @Test fun timeoutPreservesDraftAndRejectsEveryLateCallback() {
        var renders = 0
        var finishes = 0
        val delivery = AnalysisDelivery({ renders++ }, { _, _ -> renders++ }, { renders++ },
            { renders++ }, { renders++ }, { fail("Must not rank after timeout") }, { finishes++ })
        delivery.draftReceived(replies, null)
        delivery.timeout()
        val before = renders
        delivery.judgmentReceived(judgment())
        delivery.draftReceived(replies, null)
        delivery.rankingReceived(emptyList(), null)
        delivery.timeout()
        assertEquals(before, renders)
        assertEquals(1, finishes)
    }
}
