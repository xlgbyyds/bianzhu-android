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
class PingziStyleGuideTest {
    private val pkg = "com.pingzishuo.www"
    private fun shot(text: String = "你怎么这么直接", age: Int? = 24) =
        ChatSnapshot("匿名对话", listOf(Msg("other", text)), roleChat = RoleChatMetadata(null, age))
    private fun scene(s: ChatSnapshot, app: String = pkg, enabled: Boolean = true) =
        RoleChatGuide.guidance(s, RoleChatPreference(enabled = enabled), app)
    private fun ctx(s: ChatSnapshot, app: String = pkg) = ChatContext(null, emptyList(), emptyList(),
        sceneGuidance = scene(s, app), sceneDefaultRelationship = RoleChatGuide.defaultRelationship(app))

    private fun client(capture: (JSONObject) -> Unit): ReplyClient =
        ReplyClient(Prefs(RuntimeEnvironment.getApplication(), "pingzi-writing-test")) { _, _, body ->
            capture(body)
            val rows = JSONArray()
            listOf("想认识你 就直说了", "可以 你发我", "行呀").forEach {
                rows.put(JSONObject().put("text", it))
            }
            JSONObject().put("choices", JSONArray().put(JSONObject().put("message",
                JSONObject().put("content", JSONObject().put("replies", rows).toString()))))
        }
    private fun messages(body: JSONObject) = body.getJSONArray("messages").let {
        it.getJSONObject(0).getString("content") + "\n" + it.getJSONObject(1).getString("content")
    }

    @Test fun onlyPingziEnabledSceneGetsNewWritingAndPlainShortSlot() {
        val s = shot()
        var prompt = ""
        var calls = 0
        val client = client { calls++; prompt = messages(it) }
        client.draft(s, "陌生人", ctx(s))
        assertTrue(prompt.contains(PingziStyleGuide.MARKER))
        assertTrue(prompt.contains("本轮三个 style：natural, follow_detail, simple"))
        assertEquals(1, calls)
        for (app in listOf("com.yixiangweilai.yuebaobao", "com.tencent.mm", "cn.soulapp.android", "com.tantan.x")) {
            val c = if (app == "com.yixiangweilai.yuebaobao") ctx(s, app) else ChatContext(null, emptyList(), emptyList())
            client.draft(s, "陌生人", c)
            assertFalse(app, prompt.contains(PingziStyleGuide.MARKER))
        }
        assertFalse(PingziStyleGuide.active(s, scene(s, enabled = false)))
        assertFalse(PingziStyleGuide.active(s.copy(roleChat = null), scene(s)))
    }

    @Test fun openerAnswersCurrentQuestionWithoutForcingUnrelatedFreshTopic() {
        val s = shot("你来这想找什么")
        var prompt = ""
        var calls = 0
        client { calls++; prompt = messages(it) }.openers(s, "陌生人", ctx(s))
        assertEquals(1, calls)
        assertTrue(prompt.contains("有尚未回应的问题先回答"))
        assertFalse(prompt.contains("fresh_opening 必须换到轻松的新话题"))
        assertFalse(prompt.contains("至少一条脱离当前屏幕上已经收尾的旧话题"))
        assertTrue(prompt.contains("用户意图未提供就不替他选"))
    }

    @Test fun hesitationWinsOverContactOfferAndUnknownMediaDoesNotInjectExamples() {
        val s = shot("加微信我有点怕 先在这聊")
        val text = RoleChatGuide.replyInstruction(s, scene(s), true)
        assertTrue(text.contains("本轮有顾虑"))
        assertFalse(text.contains("可以 你发我"))
        for (kind in listOf(MessageKind.VOICE_UNKNOWN, MessageKind.MEDIA_UNKNOWN)) {
            val unknown = s.copy(messages = listOf(Msg("other", "", kind)))
            assertEquals("", RoleChatGuide.replyInstruction(unknown, scene(unknown), true))
        }
    }

    @Test fun minorHurtAndStyleFeedbackDoNotReceiveFlirtSamples() {
        for (s in listOf(shot(age = 17), shot("好难过 想抱抱"), shot("别再问了"), shot("你说话像机器人"))) {
            assertFalse(PingziStyleGuide.active(s, scene(s)))
            assertFalse(RoleChatGuide.replyInstruction(s, scene(s), true).contains(PingziStyleGuide.MARKER))
        }
    }

    @Test fun contactRequestIsAnsweredButPersonalFactsAreNeverTakenFromSamples() {
        val offer = shot("加q吧")
        val text = RoleChatGuide.replyInstruction(offer, scene(offer), false)
        assertTrue(text.contains("本轮对方主动提出换联系方式"))
        assertTrue(text.contains("不能凭空给账号"))
        val fact = shot("你多大了")
        val answer = RoleChatGuide.replyInstruction(fact, scene(fact), false)
        assertTrue(answer.contains("本轮在问用户的具体事实"))
        assertFalse(answer.contains("可以 你发我"))
        assertTrue(scene(fact).contains("不继承其中的年龄"))
    }

    @Test fun invitationDecisionsRemainAuthoritativeAndOnlyOneRequestIsMade() {
        val s = shot("周末可以见一面")
        for (action in InvitationAction.values()) {
            var prompt = ""
            var calls = 0
            client { calls++; prompt = messages(it) }.invitations(s, "陌生人", ctx(s),
                InvitationPlan(action, null, "时间", emptyList()))
            assertEquals(1, calls)
            assertTrue(action.name, prompt.contains(PingziStyleGuide.MARKER))
            if (action in setOf(InvitationAction.WAIT, InvitationAction.STOP, InvitationAction.CLARIFY)) {
                assertFalse(prompt.contains("想和你见一面 找个咖啡店坐坐"))
            }
        }
    }

    @Test fun anonymousContextIgnoresEvenAnExplicitStaleContactWithoutChangingIt() {
        val app = RuntimeEnvironment.getApplication()
        val store = KbStore.get(app)
        val saved = Contact(id = "pingzi-stale-contact", name = "匿名对话", apps = listOf(pkg),
            relationship = "私人旧关系", notes = "不得串入匿名会话", memoryEnabled = true)
        store.saveContact(saved)
        val persistedBeforeAnalysis = store.contact(saved.id)
        for (id in listOf(null, saved.id)) {
            val c = ContextBuilder.build(app, shot(), pkg, Prefs(app, "pingzi-context"), id)
            assertNull(c.contact)
            assertNull(c.relationshipProfile)
            assertTrue(c.history.isEmpty())
            assertFalse(c.background("陌生人").contains(saved.notes))
        }
        assertEquals(persistedBeforeAnalysis, store.contact(saved.id))
    }

    /** Optional export of synthetic production requests; never includes private chats or keys. */
    @Test fun boundedScenarioReplayUsesTheProductionPromptPaths() {
        val requests = JSONArray()
        val cases = listOf("你好", "你怎么这么直接", "加q吧", "不想加 就在这聊", "你多大了", "我今天加班好累")
        for ((i, text) in cases.withIndex()) {
            val s = shot(text)
            val c = client { requests.put(JSONObject().put("case", text).put("route", if (i == 0) "opener" else "reply").put("body", it)) }
            val result = if (i == 0) c.openers(s, "匿名陌生人", ctx(s)) else c.draft(s, "匿名陌生人", ctx(s))
            assertTrue(result.isNotEmpty())
        }
        assertEquals(6, requests.length())
        for (i in 0 until requests.length()) {
            assertTrue(messages(requests.getJSONObject(i).getJSONObject("body")).contains(PingziStyleGuide.MARKER))
        }
        System.getenv("PINGZI_REPLAY_OUTPUT")?.takeIf { it.isNotBlank() }?.let { path ->
            File(path).writeText(requests.toString(2), Charsets.UTF_8)
        }
    }
}
