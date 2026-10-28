package com.jev.probe.capture

import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.MessageKind

/** Bounded, in-memory OCR reuse. Never crosses an observed conversation change. */
internal class WechatTranscriptCache(private val now: () -> Long = { android.os.SystemClock.elapsedRealtime() }) {
    private data class Entry(val snapshot: ChatSnapshot, val at: Long, val complete: Boolean)
    private val entries = LinkedHashMap<String, Entry>()

    fun key(windowId: Int, snapshot: ChatSnapshot): String =
        "$windowId|${snapshot.title}|${snapshot.messages}|" + snapshot.voiceTranscriptRects.joinToString(";") {
            "${it.messageIndex}:${it.rect.toShortString()}"
        }

    fun get(key: String): ChatSnapshot? {
        val entry = entries[key] ?: return null
        // A failed crop may be retried on the next explicit tap; do not loop
        // immediately when the successful screenshot contained no legible text.
        if (!entry.complete && now() - entry.at > 2000L) { entries.remove(key); return null }
        return entry.snapshot
    }

    fun remember(key: String, snapshot: ChatSnapshot, texts: Map<Int, String>): ChatSnapshot {
        val eligible = snapshot.voiceTranscriptRects.map { it.messageIndex }.toSet()
        val messages = snapshot.messages.mapIndexed { index, message ->
            val body = texts[index]?.trim().orEmpty()
            if (index in eligible && message.kind == MessageKind.VOICE_UNKNOWN &&
                body.isNotBlank() && !isWechatTimeLabel(body) && body !in setOf("转文字", "语音转文字", "播放"))
                message.copy(text = body, kind = MessageKind.VOICE_TRANSCRIPT)
            else message
        }
        val resolved = snapshot.copy(messages = messages, voiceTranscriptRects = emptyList(),
            note = listOfNotNull(snapshot.note, "展开的语音转写按原消息区域识别；发言者沿用该消息头像，未展开的语音内容仍未知").joinToString("；"))
        entries[key] = Entry(resolved, now(), eligible.all { messages.getOrNull(it)?.kind == MessageKind.VOICE_TRANSCRIPT })
        while (entries.size > 12) entries.remove(entries.keys.first())
        return resolved
    }

    fun clear() = entries.clear()
}
