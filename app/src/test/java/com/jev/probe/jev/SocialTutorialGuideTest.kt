package com.jev.probe.jev

import com.jev.probe.core.*
import com.jev.probe.core.kb.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SocialTutorialGuideTest {
    private val soul = "cn.soulapp.android"
    private val pingzi = "com.pingzishuo.www"
    private fun shot(text: String) = ChatSnapshot("测试对话", listOf(Msg("other", text)))
    private fun context(s: ChatSnapshot, app: String) = ChatContext(null, emptyList(), emptyList(),
        sceneGuidance = if (app == soul) SoulChatGuide.guidance(s) else
            RoleChatGuide.guidance(s, RoleChatPreference(), pingzi))
    private fun response(text: String) = JSONObject().put("choices", JSONArray().put(
        JSONObject().put("message", JSONObject().put("content", text))))
    private val fake = """{"replies":[{"text":"可以 你发我"},{"text":"先在这里聊"},{"text":"行呀"}]}"""
    private fun client(capture: (JSONObject) -> Unit) = ReplyClient(Prefs(RuntimeEnvironment.getApplication(), "tutorial-test")) { _, _, body ->
        capture(body); response(fake)
    }
    private fun prompt(body: JSONObject) = body.getJSONArray("messages").let {
        (0 until it.length()).joinToString("\n") { i -> it.getJSONObject(i).getString("content") }
    }

    @Test fun workComplaintDoesNotBecomeAContactRefusal() {
        val s = shot("不想上班 不喜欢加班")
        assertFalse(SocialTutorialGuide.hasConcern(s))
        assertFalse(SocialTutorialGuide.hasConcern(shot("不想加班")))
        assertEquals("daily_detail", SocialTutorialGuide.select(s, true)?.id)
        assertTrue(SocialTutorialGuide.hasConcern(shot("先不加微信 就在这聊")))
    }

    @Test fun currentExplicitContactOfferCanFollowAnEarlierDecline() {
        val s = shot("还是加q吧").copy(messages = listOf(Msg("other", "先不加微信"),
            Msg("me", "行 就在这聊"), Msg("other", "还是加q吧")), roleChat = RoleChatMetadata(null, 24))
        assertFalse(SocialTutorialGuide.hasConcern(s))
        val text = RoleChatGuide.replyInstruction(s, context(s, pingzi).sceneGuidance, false)
        assertTrue(text.contains("主动提出换联系方式"))
        assertFalse(text.contains("本轮有顾虑"))
    }

    @Test fun communicationModeIsNotRejectionOrAnExcuseToSwitchApps() {
        val s = shot("我在宿舍 不方便语音 只能打字")
        assertEquals("text_only", SocialTutorialGuide.select(s, true)?.id)
        assertTrue(SocialTutorialGuide.select(s, true)!!.writing.contains("打字就行"))
    }

    @Test fun explainTermsOnlyWithActualRoleContextAndNoAssignedIdentity() {
        val s = shot("sub是什么意思")
        assertEquals("role_explain", SocialTutorialGuide.select(s, true)?.id)
        assertNull(SocialTutorialGuide.select(s, false))
        assertNull(SocialTutorialGuide.select(shot("什么意思"), true))
        assertEquals("role_mismatch", SocialTutorialGuide.select(shot("我不是m 我不玩圈"), true)?.id)
    }

    @Test fun oneWayQuestionsNeedBothSpeakerSequenceAndTwoBriefAnswers() {
        val s = shot("嗯").copy(messages = listOf(Msg("me", "平时看电影吗"), Msg("other", "偶尔"),
            Msg("me", "最近看了什么"), Msg("other", "没看")))
        assertEquals("reciprocity", SocialTutorialGuide.select(s, false)?.id)
        assertNotEquals("reciprocity", SocialTutorialGuide.select(s.copy(messages=s.messages.drop(1)), false)?.id)
        assertNotEquals("reciprocity", SocialTutorialGuide.select(s.copy(messages=s.messages.dropLast(1)+Msg("other", "你呢？")), false)?.id)
    }

    @Test fun photoRefusalAndUnknownMediaNeverTriggerExchangeSamples() {
        assertNull(SocialTutorialGuide.select(shot("不想发照片"), true))
        val s = shot("照片").copy(messages = listOf(Msg("other", "照片", MessageKind.MEDIA_UNKNOWN)))
        assertNull(SocialTutorialGuide.select(s, true))
        assertEquals("payment_boundary", SocialTutorialGuide.select(shot("先转200定金"), false)?.id)
    }

    @Test fun inviteHintsRespectDecisionAndOnlyUsePositiveCurrentInterest() {
        val s = shot("我最近挺想去看展")
        assertTrue(SocialTutorialGuide.invitationDetail(s, InvitationAction.INVITE).contains("一起去看个展"))
        for (a in listOf(InvitationAction.WAIT, InvitationAction.STOP, InvitationAction.CLARIFY, InvitationAction.CONFIRM))
            assertEquals("", SocialTutorialGuide.invitationDetail(s, a))
        assertEquals("", SocialTutorialGuide.invitationDetail(shot("我不喜欢看展"), InvitationAction.INVITE))
        assertFalse(SocialTutorialGuide.invitationDetail(shot("太快了 有点害怕"), InvitationAction.REFRAME).contains("一起去看"))
    }

    @Test fun soulContextKeepsContactAndManualRelationshipAndDoesNotInfectOtherApps() {
        val app = RuntimeEnvironment.getApplication()
        val store = KbStore.get(app)
        val c = Contact("soul-tutorial", "测试对话", apps=listOf(soul), relationship="普通朋友", notes="爱看展")
        store.saveContact(c)
        val before = store.contact(c.id)
        val ctx = ContextBuilder.build(app, shot("我喜欢看展"), soul, Prefs(app, "soul-context"))
        assertEquals(c.id, ctx.contact?.id)
        assertEquals("普通朋友", ctx.effectiveRelationship("全局关系"))
        assertTrue(ctx.background("").contains("爱看展"))
        assertTrue(ctx.sceneGuidance.startsWith(SoulChatGuide.PREFIX))
        assertFalse(ctx.background("").contains("匿名聊天里"))
        for (pkg in listOf("com.tencent.mm", "com.tantan.x", "com.yixiangweilai.yuebaobao")) {
            val other = ContextBuilder.build(app, shot("我喜欢看展"), pkg, Prefs(app, "other-context"))
            assertFalse(other.sceneGuidance.contains(SoulChatGuide.PREFIX))
        }
        assertEquals(before, store.contact(c.id))
    }

    @Test fun soulOpenerAnswersFeedbackInsteadOfForcingFreshTopicAndKeepsCallCount() {
        val s = shot("感觉都是你问我答 话术很像")
        var calls=0
        var text=""
        // Feedback without punctuation still takes precedence over an opener request.
        val c=client { calls++; text=prompt(it) }
        c.openers(s, "刚认识", context(s, soul))
        assertTrue(text.contains("style_reset"))
        assertFalse(text.contains("fresh_opening 必须换到轻松的新话题"))
        assertFalse(text.contains("本轮按瓶子说"))
        assertEquals(1,calls)
    }

    @Test fun perTurnLibraryBudgetIsSmallAndContainsOnlyOneSelectedLesson() {
        for (s in listOf(shot("想去看展"),shot("先转200定金"),shot("sub是什么意思"),shot("不方便语音"))) {
            assertTrue(SoulChatGuide.guidance(s).length <= 500)
            assertTrue(SoulChatGuide.replyInstruction(s,SoulChatGuide.guidance(s),false).length <= 300)
        }
    }

    @Test fun soulPendingQuestionIsAnsweredEvenWithoutAMatchingTutorialLesson() {
        val s=shot("你多大")
        assertNull(SocialTutorialGuide.select(s,false))
        var text=""
        client { text=prompt(it) }.openers(s,"刚认识",context(s,soul))
        assertTrue(text.contains("本轮按Soul本轮接话处理"))
        assertFalse(text.contains("至少一条脱离当前屏幕上已经收尾的旧话题"))
    }

    @Test fun exportSyntheticProductionRequestsAndReplaySavedModelResponses() {
        val dir=System.getenv("SOCIAL_REPLAY_DIR")?.let(::File)
        val saved=dir?.resolve("live-replay-results.json")?.takeIf { it.exists() }?.let { JSONArray(it.readText()) }
        val cases=listOf(
            Triple(pingzi,"reply",shot("sub是什么意思")),
            Triple(pingzi,"reply",shot("我在宿舍 只能打字")),
            Triple(pingzi,"reply",shot("先转200定金")),
            Triple(pingzi,"reply",shot("不想上班 不喜欢加班")),
            Triple(soul,"opener",shot("你说话像机器人")),
            Triple(soul,"reply",shot("你发的咖啡照片挺好看")),
            Triple(soul,"reply",shot("可以加微信吗")),
            Triple(soul,"invitation",shot("我最近挺想去看展")))
        val requests=JSONArray()
        val displayed=JSONArray()
        for((i,case) in cases.withIndex()) {
            val (app,route,initial)=case
            val s=if(app==pingzi) initial.copy(roleChat=RoleChatMetadata(null,24)) else initial
            val ctx=context(s,app)
            var calls=0
            val c=ReplyClient(Prefs(RuntimeEnvironment.getApplication(),"tutorial-replay")) { _,_,body ->
                calls++
                requests.put(JSONObject().put("case", "$i ${SocialTutorialGuide.latest(s)}").put("app",app).put("route",route).put("body",body))
                val content=(0 until (saved?.length() ?: 0)).map { saved!!.getJSONObject(it) }
                    .firstOrNull { it.optString("case").startsWith("$i ") }?.optString("content").orEmpty().ifBlank { fake }
                response(content)
            }
            val result=when(route) {
                "opener" -> c.openers(s,"刚认识",ctx)
                "invitation" -> { val e=InvitationPolicy.evidence(s,ctx); c.invitations(s,"刚认识",ctx,InvitationPlan(InvitationAction.INVITE,e.last(),"确认时间",e)) }
                else -> c.draft(s,"刚认识",ctx)
            }
            assertEquals(1,calls)
            assertTrue(result.isNotEmpty())
            displayed.put(JSONObject().put("case","$i ${SocialTutorialGuide.latest(s)}").put("replies",JSONArray(result.map { it.text })))
        }
        dir?.let { it.mkdirs(); it.resolve("production-requests.json").writeText(requests.toString(2)); it.resolve("displayed-replies.json").writeText(displayed.toString(2)) }
    }
}
