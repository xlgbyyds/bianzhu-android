package com.jev.probe.jev

import com.jev.probe.core.Analysis
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Prefs
import com.jev.probe.core.ReplySuggestion
import com.jev.probe.core.RankedReply
import com.jev.probe.core.kb.ChatContext

/**
 * Thin facade over the three split clients so callers keep one entry point.
 * Construct with [Prefs] — every route reads its own address / key / model from
 * there, so switching providers in settings takes effect on the next call.
 */
class JevClient(prefs: Prefs,
    private val judgeClient: JudgeClient = JudgeClient(prefs),
    private val replyClient: ReplyClient = ReplyClient(prefs)
) {

    /** The 7 judgment questions. Errors come back inside [Analysis.error]. */
    fun judge(snapshot: ChatSnapshot, relationship: String, ctx: ChatContext? = null): Analysis =
        judgeClient.judge(snapshot, relationship, ctx)

    fun draft(snapshot: ChatSnapshot, relationship: String, ctx: ChatContext? = null): List<ReplySuggestion> =
        replyClient.draft(snapshot, relationship, ctx)

    fun rank(snapshot: ChatSnapshot, relationship: String, candidates: List<String>,
             ctx: ChatContext? = null): List<RankedReply> =
        judgeClient.rank(snapshot, relationship, candidates, ctx)

    fun openers(snapshot: ChatSnapshot, relationship: String, ctx: ChatContext? = null,
                profileFacts: String? = null): List<ReplySuggestion> =
        replyClient.openers(snapshot, relationship, ctx, profileFacts)

    fun invitations(snapshot: ChatSnapshot, relationship: String, ctx: ChatContext?,
                    onPlan: (InvitationPlan) -> Unit = {}): InvitationResult {
        val plan = judgeClient.invitation(snapshot, relationship, ctx)
        if (Thread.currentThread().isInterrupted) throw InterruptedException("邀约已取消")
        onPlan(plan)
        if (Thread.currentThread().isInterrupted) throw InterruptedException("邀约已取消")
        return InvitationResult(plan, replyClient.invitations(snapshot, relationship, ctx, plan))
    }

    /** Draft 3 candidates on the reply route, then rank them on the judge route. */
    fun draftAndRank(
        snapshot: ChatSnapshot,
        relationship: String,
        ctx: ChatContext? = null
    ): List<RankedReply> {
        val candidates = draft(snapshot, relationship, ctx)
        val metadata = candidates.associateBy { it.text }
        return rank(snapshot, relationship, candidates.map { it.text }, ctx).map { ranked ->
            val suggestion = metadata[ranked.text]
            ranked.copy(style = suggestion?.style.orEmpty(), reason = suggestion?.reason.orEmpty())
        }
    }

    /** Judge + replies, sequential. Used by the settings connectivity test. */
    fun analyze(snapshot: ChatSnapshot, relationship: String, ctx: ChatContext? = null): Analysis {
        val a = judge(snapshot, relationship, ctx)
        if (a.error != null) return a
        val ranked = try { draftAndRank(snapshot, relationship, ctx) } catch (e: Exception) { emptyList() }
        return a.copy(rankedReplies = ranked)
    }
}
