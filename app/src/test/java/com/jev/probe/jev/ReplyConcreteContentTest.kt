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
