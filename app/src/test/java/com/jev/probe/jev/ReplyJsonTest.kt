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
class ReplyJsonTest {
    @Test fun duplicateTextCannotReplaceSendableReplyWithStrategyNotes() {
        var calls = 0
        val raw = """{"replies":[{"style":"natural","text":"想跟你多聊聊","text":"顺势表达兴趣"}]}"""
        val client = ReplyClient(Prefs(RuntimeEnvironment.getApplication(), "duplicate-json")) { _, _, _ ->
            calls++
            JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", raw))))
        }
        val outcome = runCatching { client.draft(ChatSnapshot("测试", listOf(Msg("other", "哈哈"))), "朋友") }
        assertTrue(outcome.isFailure)
        assertTrue(outcome.exceptionOrNull()!!.message!!.contains("重复JSON字段"))
        assertEquals(1, calls)
    }

    @Test fun sameKeysInDifferentRowsAndEscapedTextAreValid() {
        ReplyJson.requireUnambiguousKeys("""{"replies":[{"text":"他说 \"text\": 别误判 { }"},{"text":"可以"}]}""")
        ReplyJson.requireUnambiguousKeys("""["第一条","第二条"]""")
        assertTrue(runCatching {
            ReplyJson.requireUnambiguousKeys("""{"text":"第一条","\u0074ext":"第二条"}""")
        }.isFailure)
    }

    @Test fun identicalDuplicateTextIsAcceptedButBrokenStructuredOutputIsNeverSendable() {
        val prefs = Prefs(RuntimeEnvironment.getApplication(), "json-recovery")
        fun draft(raw: String) = ReplyClient(prefs) { _, _, _ ->
            JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", raw))))
        }.draft(ChatSnapshot("测试", listOf(Msg("other", "行呀"))), "朋友")
        val same = """{"replies":[{"style":"natural","text":"接着聊","text":"接着聊"}]}"""
        assertEquals("接着聊", draft(same).single().text)
        val broken = """{"replies":[{"style":"natural","text":"接着聊","}]}"""
        assertTrue(runCatching { draft(broken) }.isFailure)
    }
}
