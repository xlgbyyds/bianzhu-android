package com.jev.probe.capture

import com.jev.probe.core.Analysis
import com.jev.probe.core.RankedReply
import com.jev.probe.core.ReplySuggestion

/** Main-thread coordinator: drafts survive judge/ranking failures in either arrival order. */
internal class AnalysisDelivery(
    private val showJudgment: (Analysis) -> Unit,
    private val showDraft: (List<ReplySuggestion>, String?) -> Unit,
    private val showReplies: (List<RankedReply>) -> Unit,
    private val showProgress: (String) -> Unit,
    private val showError: (String) -> Unit,
    private val rank: (List<ReplySuggestion>) -> Unit,
    private val finish: () -> Unit
) {
    private var judgment: Analysis? = null
    private var drafts: List<ReplySuggestion>? = null
    private var draftError: String? = null
    private var rankStarted = false
    private var finished = false

    fun judgmentReceived(value: Analysis) {
        if (finished) return
        judgment = value
        if (value.error == null) showJudgment(value)
        advance()
    }

    fun draftReceived(value: List<ReplySuggestion>, error: String?) {
        if (finished) return
        drafts = value
        draftError = error
        advance()
    }

    private fun advance() {
        val replies = drafts
        val judgeError = judgment?.error
        when {
            !replies.isNullOrEmpty() -> showDraft(replies, judgeError?.let {
                "Jev 判断暂不可用：$it。以下仅为回复模型初稿，未经 Jev 判断和排序。"
            } ?: draftError)
            judgeError != null && replies == null -> showProgress("Jev 判断暂不可用：$judgeError；仍在等待回复模型初稿…")
            replies != null && judgment != null -> showError(listOfNotNull(judgeError, draftError)
                .joinToString("\n").ifBlank { "回复接口未返回建议，请重试" })
        }
        if (judgment == null || replies == null) return
        if (judgeError != null || draftError != null) complete()
        else if (replies.size != 3) {
            if (replies.isNotEmpty()) showDraft(replies,
                "本轮生成 ${replies.size} 条建议；Jev 排序需要 3 条，本轮已完成。可直接参考或重新分析。")
            complete()
        } else if (!rankStarted) {
            rankStarted = true
            rank(replies)
        }
    }

    fun rankingReceived(replies: List<RankedReply>, error: String?) {
        if (finished) return
        if (replies.isNotEmpty()) showReplies(replies)
        else drafts?.takeIf { it.isNotEmpty() }?.let {
            showDraft(it, error ?: "Jev 排序未返回结果，保留回复初稿")
        }
        complete()
    }

    fun timeout() {
        if (finished) return
        drafts?.takeIf { it.isNotEmpty() }?.let {
            showDraft(it, "本轮已等待 60 秒，已停止等待，保留初稿；Jev 判断或排序尚未完成。")
        } ?: showError("本轮已等待 60 秒，已停止等待；请检查网络后重试。")
        complete()
    }

    private fun complete() {
        if (finished) return
        finished = true
        finish()
    }
}
