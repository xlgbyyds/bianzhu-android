package com.jev.probe.core

import org.junit.Assert.*
import org.junit.Test

class ReplyExpressionQualityTest {
    @Test fun unsolicitedCharacterVerdictsAndOrnamentalAffectionLosePriority() {
        listOf(
            "难怪你这么自律", "看来你品味挺好", "你眼光真好",
            "说明大家都信任你", "这份陪伴比糖甜", "灵魂终于有了归宿"
        ).forEach { assertTrue(it, ReplyExpressionQuality.penalty(it) > 0) }
    }

    @Test fun ordinaryWordsAffectionHumorAndConcreteAssessmentsRemainAvailable() {
        listOf(
            "那明天换一家", "这面条确实咸", "真是没想到车晚点了",
            "看起来这个盒子放不下", "你笑起来好看", "想你了", "我也想跟你多待会",
            "行呀", "今天你负责点菜 我来洗碗", "我把周五的车次记下了",
            "之前那家买一送一 这家太贵了", "听起来是电池没电了", "一口下去给我辣清醒了"
        ).forEach { assertEquals(it, 0, ReplyExpressionQuality.penalty(it)) }
    }

    @Test fun echoNeedsAGenericVerdictNotJustASharedTopicOrConfirmation() {
        val other = listOf("社长选的")
        assertTrue(ReplyExpressionQuality.penalty("社长选的啊 那你还挺有面子哈哈", other) > 0)
        assertEquals(0, ReplyExpressionQuality.penalty("社长选的", other))
        assertEquals(0, ReplyExpressionQuality.penalty("社长选的哪一首", other))
        assertEquals(0, ReplyExpressionQuality.penalty("社长选的这首我也喜欢", other))
        assertEquals(0, ReplyExpressionQuality.penalty("嗯", listOf("嗯")))
        assertEquals(0, ReplyExpressionQuality.penalty("好想抱抱你", listOf("好想抱抱你")))
        assertEquals(0, ReplyExpressionQuality.penalty("周六下午两点", listOf("周六下午两点")))
    }

    @Test fun requestedOpinionIsNotAnUnsolicitedPersonalityReview() {
        assertEquals(0, ReplyExpressionQuality.penalty("看来你品味挺好", listOf("你觉得我品味怎么样")))
        assertTrue(ReplyExpressionQuality.penalty("看来你品味挺好", listOf("你觉得选哪家店")) > 0)
    }

    @Test fun stableOrderPreservesTextMetadataAndEvenAllFlaggedFallback() {
        val polished = ReplySuggestion("看来你品味挺好", "natural", "依据一")
        val concrete = ReplySuggestion("我选靠窗那个位置", "follow_detail", "依据二")
        val plain = ReplySuggestion("行呀", "playful", "依据三")
        assertEquals(listOf(concrete, plain, polished),
            ReplyExpressionQuality.preferConcrete(listOf(polished, concrete, plain), emptyList()))
        val flagged = listOf(polished, ReplySuggestion("你眼光真好", "simple", "依据四"))
        assertEquals(flagged, ReplyExpressionQuality.preferConcrete(flagged, emptyList()))
    }

    @Test fun automaticExamplesSkipStockVerdictsButExplicitApprovalsRemainUsersChoice() {
        val auto = ReplyVoicePolicy.guidance(emptyList(), listOf("看来你品味挺好", "这份陪伴比糖甜", "这面条确实咸"))
        assertFalse(auto.contains("看来你品味挺好"))
        assertFalse(auto.contains("这份陪伴比糖甜"))
        assertTrue(auto.contains("历史我的发言：这面条确实咸"))
        val explicit = ReplyVoicePolicy.guidance(listOf("看来你品味挺好"), emptyList())
        assertTrue(explicit.contains("明确认可：看来你品味挺好"))
        assertTrue(explicit.length <= ReplyVoicePolicy.MAX_CHARS)
    }
}
