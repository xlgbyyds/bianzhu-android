package com.jev.probe.jev

import com.jev.probe.core.*
import com.jev.probe.core.kb.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** Opt-in local request export. No network, credentials, phone changes or fixture copies in the repo. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReplyReplayExportTest {
    @Test fun exportAuthorizedReplayRequests() {
        val path = System.getenv("JEV_REPLAY_INPUT").orEmpty()
        assumeTrue(path.isNotBlank())
        val input = JSONObject(File(path).readText())
        val context = RuntimeEnvironment.getApplication()
        val contacts = File(context.filesDir, "kb/contacts.json")
        contacts.parentFile!!.mkdirs()
        contacts.writeText(JSONArray().put(input.getJSONObject("contact")).toString())
        KbStore::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        val contact = KbStore.get(context).contact(input.getJSONObject("contact").getString("id"))!!
        val prefs = Prefs(context, "local-replay-export")
        prefs.replyBaseUrl = input.getString("base")
        prefs.replyModel = input.getString("model")
        val result = JSONArray()
        val cases = input.getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val rows = case.getJSONArray("messages")
            val messages = (0 until rows.length()).map { index -> rows.getJSONObject(index).let {
                Msg(it.getString("side"), it.getString("text"))
            } }
            val history = messages.dropLast(10).map { LogEntry(it.side, it.text, 0, "com.tencent.mm") }
            val ctx = ChatContext(contact, history, emptyList(), contact.relationshipProfiles["com.tencent.mm"],
                ReplyVoicePolicy.guidance(emptyList(), emptyList(), true))
            val client = ReplyClient(prefs) { _, _, body ->
                result.put(JSONObject().put("name", case.getString("name")).put("body", body))
                JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content",
                    """{"replies":[{"style":"natural","text":"我在听"},{"style":"follow_detail","text":"你接着说"},{"style":"playful","text":"慢慢讲"}]}"""))))
            }
            val snapshot = ChatSnapshot(contact.name, messages.takeLast(10))
            if (case.optBoolean("opener")) client.openers(snapshot, ctx.effectiveRelationship(""), ctx)
            else client.draft(snapshot, ctx.effectiveRelationship(""), ctx)
        }
        File(input.getString("output")).writeText(result.toString())
    }
}
