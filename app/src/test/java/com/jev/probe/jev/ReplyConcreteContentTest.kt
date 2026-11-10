package com.jev.probe.jev

import com.jev.probe.core.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReplyConcreteContentTest {
    @Test fun explicitPlayfulPreferenceStillWorksWithoutChangingStoredSettings() {
        val prefs = Prefs(RuntimeEnvironment.getApplication(), "explicit-playful")
        assertFalse(prefs.prefersPlayfulReplies())
        prefs.rememberReplyFeedback("style:playful")
        assertTrue(prefs.prefersPlayfulReplies())
        assertEquals("playful", ReplyStylePolicy.stylesFor(null, prefs.prefersPlayfulReplies()).last())
        prefs.rememberReplyFeedback("style:natural")
        assertFalse(prefs.prefersPlayfulReplies())
    }

    @Test fun draftRemovesTheKnownInformationEchoFromRealModelOutput() {
        val prefs = Prefs(RuntimeEnvironment.getApplication(), "known-echo-draft")
        val client = ReplyClient(prefs) { _, _, _ ->
            val rows = JSONArray()
            val styles = listOf("natural", "follow_detail", "simple")
            listOf("感冒了啊 多喝热水 吃药了没", "感冒了啊 要不要我给你带点药",
                "感冒了就别熬夜了").forEachIndexed { i, text ->
                rows.put(JSONObject().put("style", styles[i]).put("text", text))
            }
            JSONObject().put("choices", JSONArray().put(JSONObject().put("message",
                JSONObject().put("content", JSONObject().put("replies", rows).toString()))))
        }
        val snapshot = ChatSnapshot("测试", listOf(Msg("other", "我感冒了")))
        val result = client.draft(snapshot, "朋友")
        assertTrue(result.isNotEmpty())
        assertTrue(result.toString(), result.none { it.text.contains("感冒了啊") })
        assertEquals("多喝热水 吃药了没", result.first().text)
        // A genuine continuation that happens to start with the same words stays intact.
        assertTrue(result.any { it.text == "感冒了就别熬夜了" })
    }

    @Test fun ordinaryDraftAndRankerCarryTheRealDevicePlainReactionRules() {
        val prefs = Prefs(RuntimeEnvironment.getApplication(), "plain-reaction-device-audit")
        var system = ""
        val client = ReplyClient(prefs) { _, _, body ->
            system = body.getJSONArray("messages").getJSONObject(0).getString("content")
            val rows = JSONArray()
            listOf("natural", "follow_detail", "simple").forEachIndexed { i, style ->
                rows.put(JSONObject().put("style", style).put("text", listOf(
                    "行 早点休息", "那先睡 明天再说", "嗯")[i]))
            }
            JSONObject().put("choices", JSONArray().put(JSONObject().put("message",
                JSONObject().put("content", JSONObject().put("replies", rows).toString()))))
        }
        client.draft(ChatSnapshot("测试", listOf(Msg("other", "我现在这状态不能熬夜"))), "朋友")
        assertTrue(system.contains("这点我记住了"))
        assertTrue(system.contains("真够反差的"))
        assertTrue(system.contains("只给一个反应也是完整回复"))

        val instructions = JevQuestions.rankQuestion(listOf("行 早点休息",
            "那今晚就早点睡 别硬撑", "不熬夜这点我记住了"))
            .getJSONObject("best_reply").getString("instructions")
        assertTrue(instructions.contains("two to eight characters"))
        assertTrue(instructions.contains("我认了"))
        assertTrue(instructions.contains("别硬撑"))
    }

    @Test fun replyAndOpenerUseOneRequestAndKeepConcreteChoiceFirst() {
        val prefs = Prefs(RuntimeEnvironment.getApplication(), "concrete-content")
        for (opener in listOf(false, true)) {
            var calls = 0
            val client = ReplyClient(prefs) { _, _, body ->
                calls++
                assertTrue(body.getJSONArray("messages").getJSONObject(0).getString("content").contains("不是单字"))
                val rows = JSONArray()
                val styles = if (opener) listOf("fresh_opening", "light_callback", "simple")
                    else listOf("natural", "follow_detail", "simple")
                listOf("看来你品味挺好", "上次那张唱片还在循环吗", "我也喜欢鼓点轻一点的").forEachIndexed { i, text ->
                    rows.put(JSONObject().put("style", styles[i]).put("text", text))
                }
                JSONObject().put("choices", JSONArray().put(JSONObject().put("message",
                    JSONObject().put("content", JSONObject().put("replies", rows).toString()))))
            }
            val snapshot = ChatSnapshot("测试", listOf(Msg("other", "我选了那张轻爵士")))
            val result = if (opener) client.openers(snapshot, "朋友") else client.draft(snapshot, "朋友")
            assertEquals(1, calls)
            assertEquals(3, result.size)
            assertEquals("上次那张唱片还在循环吗", result.first().text)
            assertEquals(if (opener) "light_callback" else "follow_detail", result.first().style)
            assertEquals("看来你品味挺好", result.last().text)
        }
    }
}
