package com.jev.probe.jev

import com.jev.probe.core.*
import com.jev.probe.core.kb.ChatContext
import com.jev.probe.core.kb.LogEntry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class InvitationFlowTest {
    private val snapshot = ChatSnapshot("模拟联系人", listOf(Msg("me", "之前说想去散步"),
        Msg("other", "这周末不用加班了", visibleTime = "10月27日 10:00")))
    private fun answers(action: String, basis: String = "e1") = JSONObject().put("answers", JSONObject()
        .put("invitation_action", JSONObject().put("choice", action))
        .put("invitation_basis", JSONObject().put("choice", basis))
        .put("invitation_obstacle", JSONObject().put("choice", "time")))
    private fun completion() = JSONObject().put("choices", org.json.JSONArray().put(JSONObject()
        .put("message", JSONObject().put("content", """{"replies":[
            {"style":"invite_direct","text":"周末要不要出来走走"},
            {"style":"invite_context","text":"找个下午一起喝杯咖啡吧"},
            {"style":"invite_playful","text":"那周末见一面吗"}]}"""))))

    @Test fun decisionCompletesBeforeDraftAndItsExactEvidenceReachesReplyModel() {
        val calls = arrayListOf<String>()
        val prefs = Prefs(RuntimeEnvironment.getApplication(), "invitation-flow")
        val judge = JudgeClient(prefs) { _, _, body ->
            calls.add("judge")
            assertEquals(3, body.getJSONObject("questions").length())
            assertTrue(body.getJSONObject("state").getJSONArray("invitation_evidence").toString().contains("from=other"))
            answers("invite")
        }
        val reply = ReplyClient(prefs) { _, _, body ->
            assertEquals(listOf("judge", "plan"), calls)
            calls.add("draft")
            val user = body.getJSONArray("messages").getJSONObject(1).getString("content")
            assertTrue(user.contains("Jev 本轮先行决策：INVITE"))
            assertTrue(user.contains("[e1]"))
            assertTrue(user.contains("from=other"))
            assertTrue(user.contains("这周末不用加班了"))
            assertTrue(user.contains("虚构场景范文"))
            completion()
        }
        val result = JevClient(prefs, judge, reply).invitations(snapshot, "已认识", null) { calls.add("plan") }
        assertEquals(listOf("judge", "plan", "draft"), calls)
        assertEquals(3, result.replies.size)
    }

    @Test fun failedJudgeDoesNotMakeAnUnguidedReplyRequestOrRetryPlain() {
        var judgeCalls = 0
        var replyCalls = 0
        val prefs = Prefs(RuntimeEnvironment.getApplication(), "invitation-failure")
        val client = JevClient(prefs, JudgeClient(prefs) { _, _, _ ->
            judgeCalls++; throw ApiException(Route.JUDGE, 451, "simulated")
        }, ReplyClient(prefs) { _, _, _ -> replyCalls++; completion() })
        assertTrue(runCatching { client.invitations(snapshot, "", null) }.isFailure)
        assertEquals(1, judgeCalls)
        assertEquals(0, replyCalls)
    }

    @Test fun cancellationAfterDecisionDoesNotStartReply() {
        var replyCalls = 0
        val prefs = Prefs(RuntimeEnvironment.getApplication(), "invitation-cancel")
        val client = JevClient(prefs, JudgeClient(prefs) { _, _, _ -> answers("invite") },
            ReplyClient(prefs) { _, _, _ -> replyCalls++; completion() })
        try {
            assertTrue(runCatching { client.invitations(snapshot, "", null) { Thread.currentThread().interrupt() } }.isFailure)
            assertEquals(0, replyCalls)
        } finally { Thread.interrupted() }
    }

    @Test fun ownWordsCannotBeSelectedAsProofOfAgreementAndUnknownAnswerFailsClosed() {
        val evidence = InvitationPolicy.evidence(snapshot, null)
        val result = InvitationPolicy.parse(answers("confirm", "e0").getJSONObject("answers"), evidence)
        assertEquals(InvitationAction.CLARIFY, result.action)
        assertNull(result.basis)
        val options = InvitationPolicy.questions(evidence).getJSONObject("invitation_basis").getJSONObject("criteria")
        assertFalse(options.has("e0"))
        assertTrue(options.has("e1"))
        assertTrue(runCatching { InvitationPolicy.parse(JSONObject(), evidence) }.isFailure)
    }

    @Test fun evidenceKeepsUnknownDatesAndVoiceAttributionWithoutAdoptingUnknownSpeakers() {
        val history = listOf(LogEntry("other", "下周看看", 1, "app", historical = true))
        val page = ChatSnapshot("模拟", listOf(Msg("unknown", "想见面"),
            Msg("me", "语音转写", kind = MessageKind.VOICE_TRANSCRIPT),
            Msg("other", "内容未知", kind = MessageKind.VOICE_UNKNOWN)))
        val evidence = InvitationPolicy.evidence(page, ChatContext(null, history, emptyList()))
        assertEquals(listOf("other", "me"), evidence.map { it.side })
        assertTrue(evidence.first().text.contains("发送时间未知"))
        assertTrue(evidence.first().text.contains("补录旧消息"))
        assertTrue(evidence.last().text.contains("语音转写"))
    }

    @Test fun normalModeStillFiltersInvitesWhileExplicitInvitationKeepsThreeAlternatives() {
        val texts = listOf("周末一起喝杯咖啡吗", "要不要出来走走", "约个时间见面吧")
        assertTrue(ReplyPolicy.safe(texts, "哈哈").isEmpty())
        assertEquals(3, ReplyPolicy.safe(texts, "哈哈", allowInvitation = true).size)
        assertTrue(ReplyPolicy.safe(listOf("你住哪"), "", allowInvitation = true).isEmpty())
    }

    @Test fun oldDeferralCannotSupportConfirmationWhenTheVisiblePageHasActualAcceptance() {
        val history = listOf(LogEntry("other", "再说吧", 1, "app", historical = true))
        val page = ChatSnapshot("模拟", listOf(Msg("me", "周末一起吃饭吗"), Msg("other", "可以呀🤭")))
        val evidence = InvitationPolicy.evidence(page, ChatContext(null, history, emptyList()))
        val result = InvitationPolicy.parse(answers("confirm", "e0").getJSONObject("answers"), evidence)
        assertEquals(InvitationAction.CONFIRM, result.action)
        assertTrue(result.basis!!.source.startsWith("点击时可见"))
        assertTrue(result.basis!!.text.contains("可以呀"))
        val noAcceptance = InvitationPolicy.parse(answers("confirm", "e0").getJSONObject("answers"), evidence.take(1))
        assertEquals(InvitationAction.CLARIFY, noAcceptance.action)
    }

    @Test fun negationAndThirdPartySpeechAreNotAgreement() {
        for (text in listOf("不可以", "可以再说吧", "好啊是她说的", "如果可以", "他说可以", "明天不方便"))
            assertFalse(text, InvitationPolicy.isAcceptance(text))
        assertTrue(InvitationPolicy.isAcceptance("[页面时间：10月27日 16:00] 周日晚上有空"))
    }

    @Test fun laterDirectBoundaryOverridesAnOldAcceptanceEvenIfModelChoosesInvite() {
        val page = ChatSnapshot("模拟", listOf(Msg("other", "可以呀"), Msg("other", "不要再约我了")))
        val evidence = InvitationPolicy.evidence(page, null)
        val plan = InvitationPolicy.parse(answers("invite", "e0").getJSONObject("answers"), evidence)
        assertEquals(InvitationAction.STOP, plan.action)
        assertEquals("e1", plan.basis!!.id)
    }

    @Test fun stopAndWaitDecisionCannotBeChangedIntoInvitationByReplyOutput() {
        val prefs = Prefs(RuntimeEnvironment.getApplication(), "invitation-stop")
        val evidence = InvitationPolicy.evidence(snapshot, null)
        for (action in listOf("stop", "wait")) {
            val plan = InvitationPolicy.parse(answers(action).getJSONObject("answers"), evidence)
            val client = ReplyClient(prefs) { _, _, body ->
                val user = body.getJSONArray("messages").getJSONObject(1).getString("content")
                assertTrue(user.contains("Jev 本轮先行决策：${plan.action.name}"))
                completion()
            }
            assertTrue(runCatching { client.invitations(snapshot, "", null, plan) }.isFailure)
        }
    }
}
