package com.jev.probe.core

import android.graphics.Rect
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** A visible message. Unknown content is never sent to the model as speech. */
enum class MessageKind { TEXT, VOICE_TRANSCRIPT, VOICE_UNKNOWN, MEDIA_UNKNOWN }

/** side is "me", "other", or "unknown" when the UI gives no reliable clue. */
data class Msg(
    val side: String,
    val text: String,
    val kind: MessageKind = MessageKind.TEXT,
    /** Literal label shown by the chat app, never synthesized from capture time. */
    val visibleTime: String? = null,
    /** "partial" means e.g. 16:00 with no trustworthy calendar date. */
    val timeConfidence: String = "unknown"
) {
    fun modelText(): String {
        val pageTime = visibleTime?.let { "[页面时间：$it；非逐条精确发送时间] " }.orEmpty()
        val body = when (kind) {
            MessageKind.TEXT -> text
            MessageKind.VOICE_TRANSCRIPT -> "[语音转写] $text"
            MessageKind.VOICE_UNKNOWN -> "[语音消息，内容未知]"
            MessageKind.MEDIA_UNKNOWN -> "[非文字消息，内容未知]"
        }
        return pageTime + body
    }
}

/** Rough age of a page date label. A missing year uses its most recent past occurrence. */
fun pageDateAgeDays(label: String?, today: LocalDate = LocalDate.now()): Long? {
    val value = label?.trim().orEmpty()
    if (value.isEmpty()) return null
    if (value.contains("今天")) return 0
    if (value.contains("昨天")) return 1
    if (value.contains("前天")) return 2
    val match = Regex("""(?:(\d{4})年)?(\d{1,2})月(\d{1,2})日""").find(value) ?: return null
    val year = match.groupValues[1].takeIf { it.isNotEmpty() }?.toIntOrNull()
    val month = match.groupValues[2].toIntOrNull() ?: return null
    val day = match.groupValues[3].toIntOrNull() ?: return null
    val pageDate = if (year != null) {
        runCatching { LocalDate.of(year, month, day) }.getOrNull()
    } else {
        (0..8).firstNotNullOfOrNull { yearsBack ->
            runCatching { LocalDate.of(today.year - yearsBack, month, day) }.getOrNull()
                ?.takeUnless { it.isAfter(today) }
        }
    } ?: return null
    if (pageDate.isAfter(today)) return null
    return ChronoUnit.DAYS.between(pageDate, today)
}

data class PageTimeEvidence(val label: String?, val ageDays: Long?)

/** Use the latest visible calendar label even when the last bubble only shows HH:mm. */
fun pageTimeEvidence(messages: List<Msg>, today: LocalDate = LocalDate.now()): PageTimeEvidence {
    val labels = messages.asReversed().mapNotNull { it.visibleTime?.trim()?.takeIf(String::isNotEmpty) }
    val dated = labels.firstNotNullOfOrNull { label ->
        pageDateAgeDays(label, today)?.let { age -> label to age }
    }
    return PageTimeEvidence(dated?.first ?: labels.firstOrNull(), dated?.second)
}

/**
 * A bubble the node tree can locate but not read (Feishu draws its message text
 * itself). [rect] is in screen coordinates; [side] is what the tree could infer
 * around the bubble. The service OCRs each rect to get the words.
 */
data class BubbleRect(val rect: Rect, val side: String)

/** A custom-drawn transcription belonging to an already identified voice row. */
data class VoiceTranscriptRect(val messageIndex: Int, val rect: Rect)

/**
 * A snapshot of the currently-open conversation in whichever chat app is
 * foreground (see ChatAppAdapter).
 *
 * Adapter contract: `extract` returning null means "not in a chat window".
 * Returning a snapshot whose [messages] is empty means "in a chat window, but
 * the tree holds no text" — that is the OCR fallback's cue, and the one case
 * where [bubbleRects] may be populated.
 *
 * [note] is a caveat about how this snapshot was produced, shown verbatim in
 * the analysis panel (OCR captures cannot tell who said what).
 */
data class ChatSnapshot(
    val title: String?,
    val messages: List<Msg>,
    val bubbleRects: List<BubbleRect> = emptyList(),
    val note: String? = null,
    val voiceTranscriptRects: List<VoiceTranscriptRect> = emptyList()
) {
    val latestFrom: String? get() = messages.lastOrNull()?.side

    /** A stable signature of the last few messages, to detect real changes. */
    fun signature(): String =
        messages.takeLast(6).joinToString("|") {
            "${it.side}:${it.kind}:${it.text}:${it.visibleTime.orEmpty()}"
        }
}

/** Jev's judgment result for one snapshot, plus the ranked candidate replies. */
data class Analysis(
    val trueIntent: Choice?,
    val dangerLevel: Score?,
    val sheNeeds: Choice?,
    val shouldReplyNow: Double?,
    val bestAction: Choice?,
    val tensionResolved: Double?,
    val literalQuestion: Double?,
    val rankedReplies: List<RankedReply>,
    val latencyMs: Long,
    val error: String? = null,
    /** Optional opportunity hint, not confirmation of the other person's intentions. */
    val invitationOpportunity: Choice? = null
)

data class Choice(val choice: String, val confidence: Double, val probabilities: Map<String, Double>)
data class Score(val score: Double, val confidence: Double, val maxLevel: Int)
data class ReplySuggestion(val text: String, val style: String, val reason: String)
data class RankedReply(
    val text: String,
    val prob: Double,
    val style: String = "",
    val reason: String = ""
)
