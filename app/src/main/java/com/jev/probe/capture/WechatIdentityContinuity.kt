package com.jev.probe.capture

import com.jev.probe.core.Msg

/**
 * Keeps a locally resolved WeChat title across adjacent views of the same chat.
 * The rendered message window changes while scrolling, so it is deliberately
 * not part of the contact identity. Carry is allowed only with a strong,
 * ordered message overlap; a new/disconnected page drops the cached identity.
 */
internal class WechatIdentityContinuity {
    private data class MessageKey(val side: String, val text: String)
    private var windowId: Int? = null
    private var title: String? = null
    private var previous: List<MessageKey> = emptyList()

    fun observe(window: Int, messages: List<Msg>, visibleTitle: String?): String? {
        if (windowId != window) reset(window)
        val keys = messages.mapNotNull { msg ->
            val text = msg.text.trim().replace(Regex("\\s+"), " ")
            if (text.isBlank()) null else MessageKey(msg.side, text)
        }
        val candidate = visibleTitle?.trim()?.takeIf { it.isNotEmpty() }
        if (candidate != null) {
            title = candidate
            previous = keys
            return candidate
        }
        val carried = title?.takeIf { stronglyOverlaps(previous, keys) }
        title = carried
        previous = keys
        return carried
    }

    fun clear() {
        windowId = null
        title = null
        previous = emptyList()
    }

    private fun reset(window: Int) {
        windowId = window
        title = null
        previous = emptyList()
    }

    private fun stronglyOverlaps(a: List<MessageKey>, b: List<MessageKey>): Boolean {
        if (a.size < 2 || b.size < 2) return false
        for (i in a.indices) for (j in b.indices) {
            var k = 0
            var meaningfulChars = 0
            while (i + k < a.size && j + k < b.size && a[i + k] == b[j + k]) {
                meaningfulChars += a[i + k].text.count { !it.isWhitespace() }
                k++
            }
            if (k >= 2 && meaningfulChars >= 8) return true
        }
        return false
    }
}
