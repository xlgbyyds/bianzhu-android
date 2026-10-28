package com.jev.probe.jev

import android.util.Log
import com.jev.probe.core.Analysis
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Choice
import com.jev.probe.core.Prefs
import com.jev.probe.core.RankedReply
import com.jev.probe.core.Score
import com.jev.probe.core.kb.ChatContext
import org.json.JSONObject

/**
 * The Jev judgment route only: the 7 judgment questions in one call, and the
 * ranking question over already-drafted candidates. Reads judgeProvider /
 * judgeBaseUrl / judgeKey / judgeModel from [Prefs]; nothing generative here.
 */
class JudgeClient(private val prefs: Prefs,
    private val post: (String, String, JSONObject) -> JSONObject = { url, key, body ->
        HttpJson.post(url, key, body, Route.JUDGE, HttpJson.headersFor(url))
    }
) {

    /**
     * The 7 judgment questions (fast, ~1s). Errors are returned, not thrown.
     *
     * @param ctx D-stage knowledge context; null or empty means the request body
     *        is byte-for-byte what v1.2 sent.
     */
    fun judge(snapshot: ChatSnapshot, relationship: String, ctx: ChatContext? = null): Analysis {
        val start = System.currentTimeMillis()
        return try {
            val answers = postDecisions(
                snapshot, relationship, ctx,
                JevQuestions.judge()
            )
            Analysis(
                trueIntent = parseChoice(answers.optJSONObject("true_intent")),
                dangerLevel = parseScore(answers.optJSONObject("danger_level")),
                sheNeeds = parseChoice(answers.optJSONObject("she_needs")),
                shouldReplyNow = answers.optJSONObject("should_reply_now")?.optDouble("noul"),
                bestAction = parseChoice(answers.optJSONObject("best_action")),
                tensionResolved = answers.optJSONObject("tension_resolved")?.optDouble("noul"),
                literalQuestion = answers.optJSONObject("literal_question")?.optDouble("noul"),
                rankedReplies = emptyList(),
                latencyMs = System.currentTimeMillis() - start,
                invitationOpportunity = parseChoice(answers.optJSONObject("invitation_opportunity"))
            )
        } catch (e: Exception) {
            Log.w(TAG, "judge failed: ${e.message}")
            Analysis(null, null, null, null, null, null, null, emptyList(),
                System.currentTimeMillis() - start, error = e.message ?: "判断接口请求失败")
        }
    }

    /** One decisions round trip; a failure never silently changes to unguided invitations. */
    fun invitation(snapshot: ChatSnapshot, relationship: String, ctx: ChatContext?): InvitationPlan {
        val evidence = InvitationPolicy.evidence(snapshot, ctx)
        val state = JevQuestions.buildState(snapshot, relationship,
            ctx?.background(relationship, includeRelationship = false).orEmpty())
            .put("invitation_evidence", org.json.JSONArray(evidence.map { it.display() }))
            .put("timing", com.jev.probe.core.ConversationTiming.from(snapshot).prompt())
        return InvitationPolicy.parse(send(state, InvitationPolicy.questions(evidence)), evidence)
    }

    /** Ask Jev which of the candidate replies is best; throws on failure. */
    fun rank(
        snapshot: ChatSnapshot,
        relationship: String,
        candidates: List<String>,
        ctx: ChatContext? = null
    ): List<RankedReply> {
        val questions = JSONObject().put("best_reply",
            JevQuestions.rankQuestion(candidates).getJSONObject("best_reply"))
        val answers = postDecisions(snapshot, relationship, ctx, questions, ctx?.replyVoiceGuidance.orEmpty())
        return parseRanked(answers.optJSONObject("best_reply"), candidates)
    }

    /**
     * POST one decisions request, with the knowledge fields when there are any.
     *
     * Retry without optional background/history only for payload rejection
     * (400/413/422). Authentication, region and rate errors must not cause a
     * second request that needlessly drops the contact context.
     */
    private fun postDecisions(
        snapshot: ChatSnapshot,
        relationship: String,
        ctx: ChatContext?,
        questions: JSONObject,
        styleGuidance: String = ""
    ): JSONObject {
        val background = listOf(ctx?.background(relationship, includeRelationship = false).orEmpty(), styleGuidance)
            .filter(String::isNotBlank).joinToString("\n")
        val history = ctx?.history ?: emptyList()
        val enriched = background.isNotBlank() || history.isNotEmpty()
        return try {
            send(JevQuestions.buildState(snapshot, relationship, background, history), questions)
        } catch (e: ApiException) {
            if (enriched && e.status in setOf(400, 413, 422)) {
                Log.w(TAG, "judge HTTP ${e.status} with background/history; retrying plain")
                send(JevQuestions.buildState(snapshot, relationship), questions)
            } else throw e
        }
    }

    private fun send(state: JSONObject, questions: JSONObject): JSONObject {
        val url = prefs.judgeEndpoint()
        val body = JSONObject()
            .put("model", prefs.judgeModel)
            .put("state", state)
            .put("questions", questions)
        val resp = post(url, prefs.judgeKey, body)
        val answers = resp.optJSONObject("answers")
            ?: throw IllegalStateException("判断接口未返回有效 answers，不能视为判断成功")
        questions.keys().forEach { name ->
            val answer = answers.optJSONObject(name)
                ?: throw IllegalStateException("判断接口缺少 $name 结果，请重试")
            val question = questions.getJSONObject(name)
            val valid = when (question.optString("type")) {
                "choice" -> answer.optString("choice").let { it.isNotBlank() && question.getJSONObject("criteria").has(it) }
                "noul" -> answer.optDouble("noul", Double.NaN).let { it.isFinite() && it in 0.0..1.0 }
                "score" -> answer.optDouble("score", Double.NaN).isFinite()
                else -> false
            }
            if (!valid) throw IllegalStateException("判断接口的 $name 结果格式无效，请重试")
        }
        return answers
    }

    private fun parseChoice(o: JSONObject?): Choice? {
        o ?: return null
        val probs = HashMap<String, Double>()
        o.optJSONObject("probabilities")?.let { p ->
            p.keys().forEach { k -> probs[k] = p.optDouble(k) }
        }
        return Choice(o.optString("choice"), o.optDouble("confidence", 0.0), probs)
    }

    private fun parseScore(o: JSONObject?): Score? {
        o ?: return null
        val legend = o.optJSONObject("legend")
        val maxLevel = legend?.keys()?.asSequence()?.mapNotNull { it.toIntOrNull() }?.maxOrNull() ?: 9
        return Score(o.optDouble("score", 0.0), o.optDouble("confidence", 0.0), maxLevel)
    }

    private fun parseRanked(o: JSONObject?, candidates: List<String>): List<RankedReply> {
        val keys = listOf("reply_a", "reply_b", "reply_c")
        val probs = o?.optJSONObject("probabilities")
            ?: throw IllegalStateException("判断接口未返回有效排序分值，保留未排序初稿")
        val scores = keys.map { probs.optDouble(it, Double.NaN) }
        if (scores.any { !it.isFinite() || it !in 0.0..1.0 } || scores.sum() <= 0.0)
            throw IllegalStateException("判断接口排序分值无效，保留未排序初稿")
        val list = candidates.mapIndexed { i, text ->
            RankedReply(text, scores[i])
        }
        return list.sortedByDescending { it.prob }
    }

    companion object { private const val TAG = "JEVASSIST" }
}
