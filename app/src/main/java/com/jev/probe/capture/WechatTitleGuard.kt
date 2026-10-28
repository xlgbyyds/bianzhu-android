package com.jev.probe.capture

/** A screenshot title belongs only to the unchanged visible page that requested it. */
internal class WechatTitleGuard {
    data class Ticket(val generation: Long, val page: String)
    private var generation = 0L
    private var resolved: Pair<Ticket, String>? = null

    fun begin(page: String) = Ticket(generation, page)
    fun accepts(ticket: Ticket, page: String?) =
        ticket.generation == generation && ticket.page == page

    fun remember(ticket: Ticket, page: String?, title: String): Boolean {
        if (!accepts(ticket, page) || title.isBlank()) return false
        resolved = ticket to title
        return true
    }

    fun title(page: String?): String? = resolved?.takeIf { accepts(it.first, page) }?.second

    fun invalidate() { generation++; resolved = null }
}

/** The OCR crop contains only the chat toolbar title. A short remark may be
 * split into a few adjacent OCR lines, so combine a small bounded number of
 * fragments instead of rejecting every result that is not exactly one line. */
internal fun wechatTitleCandidate(
    lines: List<String>,
    nonChatTitles: Set<String>,
    isTransient: (String) -> Boolean
): String? {
    if (lines.isEmpty() || lines.size > 3) return null
    val title = lines.asSequence().map(String::trim).filter(String::isNotEmpty).joinToString("")
    if (title.isBlank() || title.length > 80 || title in nonChatTitles || isTransient(title) ||
        title.endsWith("…") || title.endsWith("...")) return null
    return title
}
