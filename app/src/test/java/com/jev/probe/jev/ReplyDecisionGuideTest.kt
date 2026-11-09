package com.jev.probe.jev

import com.jev.probe.core.Analysis
import com.jev.probe.core.Choice
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Msg
import com.jev.probe.core.Prefs
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReplyDecisionGuideTest {
    @Test fun completedJevDecisionGuidesTheReplyDraftWithoutBecomingChatText() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = Prefs(context, "decision-guide-test")
        var system = ""
        val client = ReplyClient(prefs) { _, _, body ->
            system = body.getJSONArray("messages").getJSONObject(0).getString("content")
            JSONObject().put("choices", org.json.JSONArray().put(JSONObject().put("message", JSONObject()
                .put("content", """{"replies":[
                    {"style":"natural","text":"这个确实挺有意思"},
                    {"style":"follow_detail","text":"我也想试试这种玩法"},
                    {"style":"simple","text":"这点我还真没想到"}]}"""))))
        }
        val analysis = Analysis(
            trueIntent = Choice("casual_chat", .91, emptyMap()),
            dangerLevel = null,
            sheNeeds = Choice("nothing", .76, emptyMap()),
            shouldReplyNow = null,
            bestAction = Choice("answer_detail", .83, emptyMap()),
            tensionResolved = null,
            literalQuestion = null,
            rankedReplies = emptyList(),
            latencyMs = 1
        )
        val suggestions = client.draft(ChatSnapshot("联系人", listOf(Msg("other", "最近开始学摄影了"))),
            "关系未知", analysis = analysis)
        assertEquals(3, suggestions.size)
        assertTrue(system.contains("Jev本轮推测"))
        assertTrue(system.contains("直接回答问题或回应一个具体细节"))
        assertTrue(system.contains("普通分享、玩笑或轻松交流"))
        assertTrue(system.contains("不要把这些判断写成对她的评价"))
    }

    @Test fun failedOrMissingJudgmentDoesNotInventDirectionAndTaxonomyHasEveryNewAction() {
        assertEquals("", ReplyDecisionGuide.prompt(null))
        assertEquals("", ReplyDecisionGuide.prompt(Analysis(
            null, null, null, null, null, null, null, emptyList(), 1, error = "HTTP 451")))
        val criteria = JevQuestions.judge().getJSONObject("best_action").getJSONObject("criteria")
        listOf("answer_detail", "share_related", "light_banter", "express_interest", "ask_one", "restart_topic")
            .forEach { assertTrue("missing action $it", criteria.has(it)) }
    }
}
