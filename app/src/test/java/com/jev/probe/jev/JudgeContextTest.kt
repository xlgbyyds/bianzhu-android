package com.jev.probe.jev

import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Msg
import com.jev.probe.core.Prefs
import com.jev.probe.core.kb.ChatContext
import com.jev.probe.core.kb.Contact
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class JudgeContextTest {
    private val snapshot = ChatSnapshot("测试联系人", listOf(Msg("other", "分享一件开心的小事")))
    private val relation = "亲密相处 保持自然的双向交流".repeat(30)
    private val ctx = ChatContext(Contact("test", "测试联系人", relationship = relation, notes = "共同话题是猫"),
        emptyList(), emptyList())

    @Test fun regionAuthenticationAndRateErrorsDoNotTriggerContextStrippingRetry() {
        for (status in listOf(401, 403, 429, 451)) {
            var attempts = 0
            val client = JudgeClient(Prefs(RuntimeEnvironment.getApplication(), "judge-test")) { _, _, body ->
                attempts++
                val state = body.getJSONObject("state")
                assertEquals(relation, state.getJSONObject("chat").getString("relationship"))
                assertFalse(state.getString("background").contains(relation))
                assertTrue(state.getString("background").contains("猫"))
                throw ApiException(Route.JUDGE, status, "test error")
            }
            val result = client.judge(snapshot, relation, ctx)
            assertTrue(result.error!!.contains(status.toString()))
            assertEquals(1, attempts)
        }
    }

    @Test fun incompatibleContextGetsOnePlainRetryWhileKeepingTheFullRelationship() {
        var attempts = 0
        val client = JudgeClient(Prefs(RuntimeEnvironment.getApplication(), "judge-test")) { _, _, body ->
            attempts++
            val state = body.getJSONObject("state")
            assertEquals(relation, state.getJSONObject("chat").getString("relationship"))
            if (attempts == 1) {
                assertTrue(state.has("background"))
                throw ApiException(Route.JUDGE, 422, "unsupported background")
            }
            assertFalse(state.has("background"))
            validAnswers(body.getJSONObject("questions"))
        }
        assertNull(client.judge(snapshot, relation, ctx).error)
        assertEquals(2, attempts)
    }

    private fun validAnswers(questions: JSONObject) = JSONObject().put("answers", JSONObject().apply {
        questions.keys().forEach { name ->
            val q = questions.getJSONObject(name)
            put(name, when (q.getString("type")) {
                "choice" -> JSONObject().put("choice", q.getJSONObject("criteria").keys().next()).put("confidence", .7)
                "noul" -> JSONObject().put("noul", .6)
                else -> JSONObject().put("score", 1.0)
            })
        }
    })

    @Test fun emptyOrPartialAnswersNeverBecomeSuccessfulJudgments() {
        val broken = listOf(JSONObject(), JSONObject().put("answers", JSONObject()),
            JSONObject().put("answers", JSONObject().put("true_intent", JSONObject().put("choice", "casual_chat"))))
        broken.forEach { response ->
            val client = JudgeClient(Prefs(RuntimeEnvironment.getApplication(), "invalid-judge")) { _, _, _ -> response }
            assertNotNull(client.judge(snapshot, relation).error)
        }
        val client = JudgeClient(Prefs(RuntimeEnvironment.getApplication(), "invalid-number")) { _, _, body ->
            validAnswers(body.getJSONObject("questions")).apply {
                getJSONObject("answers").put("should_reply_now", JSONObject().put("noul", 2))
            }
        }
        assertNotNull(client.judge(snapshot, relation).error)
    }

    @Test fun rankingRequiresRealProbabilitiesInsteadOfMakingUpThreeZeros() {
        val candidates = listOf("第一条", "第二条", "第三条")
        val prefs = Prefs(RuntimeEnvironment.getApplication(), "rank-result")
        fun client(probabilities: JSONObject?) = JudgeClient(prefs) { _, _, _ ->
            JSONObject().put("answers", JSONObject().put("best_reply",
                JSONObject().put("choice", "reply_b").apply { probabilities?.let { put("probabilities", it) } }))
        }
        assertTrue(runCatching { client(null).rank(snapshot, relation, candidates) }.isFailure)
        assertTrue(runCatching { client(JSONObject().put("reply_a", 0).put("reply_b", 0).put("reply_c", 0))
            .rank(snapshot, relation, candidates) }.isFailure)
        assertTrue(runCatching { client(JSONObject().put("reply_b", .8)).rank(snapshot, relation, candidates) }.isFailure)
        val ranked = client(JSONObject().put("reply_a", .1).put("reply_b", .8).put("reply_c", .1))
            .rank(snapshot, relation, candidates)
        assertEquals("第二条", ranked.first().text)
        assertEquals(.8, ranked.first().prob, .001)
    }
}
