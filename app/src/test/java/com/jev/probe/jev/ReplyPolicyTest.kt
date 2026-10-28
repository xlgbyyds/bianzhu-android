package com.jev.probe.jev

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.jev.probe.core.ReplySuggestion
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Msg
import java.time.LocalDate

class ReplyPolicyTest {
    private val currentDay = LocalDate.of(2026, 10, 10)
    private fun candidates(vararg text: String) = text.map { ReplySuggestion(it, "fresh_opening", "") }

    @Test fun oldWeatherJokeCannotBecomeTodaysWeatherOrAClaimThatIHaveFinishedWork() {
        val old = ChatSnapshot("Alice", listOf(
            Msg("me", "不上班喝西北风", visibleTime = "10月3日 16:55"),
            Msg("me", "等会回你", visibleTime = "10月3日 16:55")))
        val result = ReplyPolicy.withGroundedCurrentFacts(candidates(
            "今天风大 上班路上多穿点", "忙完了 现在能聊了", "刚忙完 我来冒个泡", "来找你聊两句"), old, currentDay)
        assertEquals(listOf("来找你聊两句"), result.map { it.text })
    }

    @Test fun UnknownDateAndOtherPersonsStatusCannotSupportMyCurrentStatus() {
        val page = ChatSnapshot("Alice", listOf(Msg("me", "忙完了", visibleTime = "下午2:32"),
            Msg("other", "忙完了", visibleTime = "10月10日 14:32")))
        assertTrue(ReplyPolicy.withGroundedCurrentFacts(candidates("忙完了 来聊两句"), page, currentDay).isEmpty())
    }

    @Test fun confirmedSameDayVisibleFactsCanBeUsedWithoutAnotherModelCall() {
        val page = ChatSnapshot("Alice", listOf(Msg("me", "我忙完了", visibleTime = "10月10日 14:32"),
            Msg("other", "风大", visibleTime = "10月10日 14:32")))
        assertEquals(2, ReplyPolicy.withGroundedCurrentFacts(candidates(
            "忙完了 来找你聊两句", "今天风大 多穿点"), page, currentDay).size)
    }

    @Test fun callbackJokesAndConditionalPlansAreNotCurrentWeatherAssertions() {
        val page = ChatSnapshot("Alice", emptyList())
        val texts = listOf("还记得你那句喝西北风", "等你忙完了再聊", "今天风大吗")
        assertEquals(texts, ReplyPolicy.withGroundedCurrentFacts(candidates(*texts.toTypedArray()), page, currentDay).map { it.text })
    }
    @Test fun ordinaryChatFiltersEarlyInvitesAndPrivateStateQuestions() {
        val result = ReplyPolicy.safe(listOf(
            "你现在是在床上躺着刷剧吗？",
            "你是躺着还是坐着刷剧？",
            "你现在在哪？",
            "周末有空吗，一起出来坐坐？",
            "哈哈，这个画面感一下就有了。",
            "你说的这个点我还挺有共鸣的。",
            "这句把我逗笑了，我也想起一件小事。"
        ), "哈哈哈哈")
        assertEquals(3, result.size)
        assertFalse(result.any { it.contains("床上") || it.contains("躺着") ||
            it.contains("现在在哪") || it.contains("有空") })
    }

    @Test fun atMostOneQuestionAmongThreeSuggestions() {
        val result = ReplyPolicy.safe(listOf(
            "你喜欢这个吗？", "你后来怎么想的？", "这事听着挺有趣。",
            "我懂你说的那个感觉。", "你还想聊什么？"
        ), "挺有趣的")
        assertEquals(listOf("你喜欢这个吗", "这事听着挺有趣", "我懂你说的那个感觉"), result)
    }

    @Test fun canAnswerAnInvitationThatTheOtherPersonActuallyMade() {
        val result = ReplyPolicy.safe(listOf("好呀，我们找时间一起吃饭。"), "周末要不要一起吃饭？")
        assertTrue(result.single().contains("一起吃饭"))
    }

    @Test fun suggestionsUseSpacesWithoutPunctuationAndKeepStyleMetadata() {
        val result = ReplyPolicy.safeSuggestions(listOf(
            ReplySuggestion("哈哈，  被你发现了！\n下次还敢。", "playful", "原来的依据"),
            ReplySuggestion("哈哈 被你发现了 下次还敢", "natural", "重复候选")
        ), "哈哈")
        assertEquals(1, result.size)
        assertEquals("哈哈 被你发现了 下次还敢", result.single().text)
        assertEquals("playful", result.single().style)
        assertEquals("原来的依据", result.single().reason)
    }

    @Test fun politeTemplatesAreFilteredButNaturalComplimentsRemain() {
        val result = ReplyPolicy.safe(listOf("谢谢你的分享。", "请问你喜欢这个吗？",
            "您好！", "辛苦了。", "你好可爱啊！", "这句让我笑了。"), "哈哈")
        assertEquals(listOf("你好可爱啊", "这句让我笑了"), result)
    }

    @Test fun punctuationIsRemovedOnlyAfterQuestionAndPrivacyChecks() {
        val result = ReplyPolicy.safe(listOf("今天开心？", "后来呢？", "你现在在哪？",
            "这句——有点意思…", "我记下了（笑）"), "哈哈")
        assertEquals(listOf("今天开心", "这句 有点意思", "我记下了 笑"), result)
        assertTrue(result.none { Regex("\\p{P}").containsMatchIn(it) })
    }
}
