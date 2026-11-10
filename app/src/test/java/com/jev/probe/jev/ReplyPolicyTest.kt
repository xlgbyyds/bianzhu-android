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
    @Test fun unreadableConversationCannotSupportInventedSharedChatReferences() {
        val texts = candidates("上次聊得挺开心 想接着聊", "你之前说过喜欢这个", "来找你聊两句", "你上次去哪玩了")
        assertEquals(listOf("来找你聊两句", "你上次去哪玩了"),
            ReplyPolicy.withGroundedConversationReferences(texts, false).map { it.text })
        assertEquals(texts, ReplyPolicy.withGroundedConversationReferences(texts, true))
    }
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

    @Test fun independentQuestionAlternativesAreNotMistakenForRepeatedQuestioning() {
        val result = ReplyPolicy.safe(listOf(
            "你喜欢这个吗？", "你后来怎么想的？", "这事听着挺有趣。",
            "我懂你说的那个感觉。", "你还想聊什么？"
        ), "挺有趣的")
        assertEquals(listOf("你喜欢这个吗", "你后来怎么想的", "这事听着挺有趣"), result)
    }

    @Test fun canAnswerAnInvitationThatTheOtherPersonActuallyMade() {
        val result = ReplyPolicy.safe(listOf("好呀，我们找时间一起吃饭。"), "周末要不要一起吃饭？")
        assertTrue(result.single().contains("一起吃饭"))
    }

    @Test fun casualOfferToShareSomethingIsNotFilteredAsADateInvitation() {
        assertEquals(listOf("有空我把那份游戏攻略发你"),
            ReplyPolicy.safe(listOf("有空我把那份游戏攻略发你"), "哈哈"))
        assertTrue(ReplyPolicy.safe(listOf("周末有空一起吃饭吗"), "哈哈").isEmpty())
        assertTrue(ReplyPolicy.safe(listOf("那周末见一面吗"), "哈哈").isEmpty())
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
        assertEquals(listOf("今天开心", "后来呢", "这句 有点意思"), result)
        assertTrue(result.none { Regex("\\p{P}").containsMatchIn(it) })
    }

    @Test fun knownInformationEchoIsRemovedBeforeFormatting() {
        val other = listOf("我感冒了")
        assertEquals("多喝热水 吃药了没",
            ReplyPolicy.stripKnownEcho("感冒了啊 多喝热水 吃药了没", other))
        assertEquals("多喝热水",
            ReplyPolicy.stripKnownEcho("你感冒了啊 多喝热水", other))
        assertEquals("多喝热水 吃药了没",
            ReplyPolicy.stripKnownEcho("我感冒了啊 多喝热水 吃药了没", other))
        assertEquals("吃药了没？",
            ReplyPolicy.stripKnownEcho("感冒了啊，吃药了没？", other))
        assertEquals("吃药了没",
            ReplyPolicy.stripKnownEcho("感冒了啊 吃药了没", listOf("刚才在开会", "我感冒了")))
    }

    @Test fun naturalContinuationAfterARepeatedDetailIsNotStripped() {
        val other = listOf("我感冒了", "社长选的")
        listOf("感冒还上班啊", "感冒了就别熬夜", "社长选的哪一首",
            "社长选的这首我也喜欢", "社长选的", "感冒了啊", "嗯")
            .forEach { assertEquals(it, ReplyPolicy.stripKnownEcho(it, other)) }
    }

    @Test fun noEchoOrEmptyContextLeavesTheReplyUntouched() {
        val text = "我晚点给你带点药"
        assertEquals(text, ReplyPolicy.stripKnownEcho(text, emptyList()))
        assertEquals(text, ReplyPolicy.stripKnownEcho(text, listOf("今天好热")))
        assertEquals("", ReplyPolicy.stripKnownEcho("", listOf("我感冒了")))
    }

    @Test fun echoStrippingKeepsStyleAndReasonAndSurvivesSafeSuggestions() {
        val original = ReplySuggestion("感冒了啊 多喝热水", "natural", "依据一")
        val stripped = ReplyPolicy.stripKnownEcho(listOf(original), listOf("我感冒了"))
        assertEquals("多喝热水", stripped.single().text)
        assertEquals("natural", stripped.single().style)
        assertEquals("依据一", stripped.single().reason)
        val result = ReplyPolicy.safeSuggestions(stripped, "我感冒了")
        assertEquals("多喝热水", result.single().text)
        assertEquals("natural", result.single().style)
    }

    // The chat log carries a WeChat emote in about one of every ten lines I actually sent,
    // while Jev never emitted one because the punctuation strip ate the brackets.
    @Test fun knownWechatEmoteSurvivesPunctuationStripping() {
        assertEquals("[旺柴] 行", ReplyPolicy.format("[旺柴] 行"))
        assertEquals("行吧[捂脸][捂脸]", ReplyPolicy.format("行吧[捂脸][捂脸]"))
        assertEquals("今天好累[皱眉] 先睡了", ReplyPolicy.format("今天好累[皱眉] 先睡了"))
        assertEquals("[呲牙]", ReplyPolicy.format("[呲牙]"))
    }

    @Test fun inventedBracketGroupIsDroppedInsteadOfLeakingItsText() {
        assertEquals("看见 了", ReplyPolicy.format("看见[注意]了"))
        assertEquals("行", ReplyPolicy.format("[胡编的表情]行"))
        assertEquals("", ReplyPolicy.format("[不存在的]"))
    }

    @Test fun emoteSurvivesTheFullSafePipeline() {
        val result = ReplyPolicy.safe(listOf("[捂脸] 又来", "哈哈[呲牙]"), "今天又加班")
        assertEquals(listOf("[捂脸] 又来", "哈哈[呲牙]"), result)
    }

    @Test fun questionAndPunctuationRulesStillHoldAroundAnEmote() {
        assertEquals("后来呢[吃瓜]", ReplyPolicy.format("后来呢？[吃瓜]"))
        assertEquals("[破涕为笑] 没事", ReplyPolicy.format("[破涕为笑]，没事。"))
    }

    @Test fun girlfriendStatusOnTheUserIsRewrittenToHer() {
        // Both phrasings were produced live on 2026-11-10 while the model was
        // answering "那我有幸成为你的女朋友吗".
        assertEquals("那你这女朋友身份算转正了",
            ReplyPolicy.fixSpeakerPerspective("那我这女朋友身份算转正了"))
        assertEquals("那你这女朋友身份算是有着落了",
            ReplyPolicy.fixSpeakerPerspective("那我这女朋友身份算是有着落了"))
        // "我女朋友" without 这 keeps meaning "my girlfriend".
        assertEquals("我女朋友今天加班", ReplyPolicy.fixSpeakerPerspective("我女朋友今天加班"))
        assertEquals("我这份工作干腻了", ReplyPolicy.fixSpeakerPerspective("我这份工作干腻了"))
        assertEquals("", ReplyPolicy.fixSpeakerPerspective(""))
    }

    @Test fun perspectiveFixSurvivesTheSafePipeline() {
        val fixed = ReplyPolicy.fixSpeakerPerspective(
            listOf(ReplySuggestion("那我这女朋友身份算是有着落了", "natural", "依据")))
        assertEquals("那你这女朋友身份算是有着落了", fixed.single().text)
        assertEquals("natural", fixed.single().style)
        assertEquals(listOf("那你这女朋友身份算是有着落了"),
            ReplyPolicy.safeSuggestions(fixed, "").map { it.text })
    }
}
