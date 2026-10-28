package com.jev.probe.capture

import com.jev.probe.core.ChatSnapshot

/** Main-thread session state. A return to the same chat never revives an old request. */
internal class ConversationSession {
    data class Target(
        val pkg: String,
        val windowId: Int,
        val title: String?,
        val messagesSignature: String? = null
    )
    data class Token(val target: Target, val revision: Long)

    var target: Target? = null
        private set
    private var revision = 0L

    fun observe(next: Target?): Boolean {
        if (target == next) return false
        target = next
        invalidate()
        return true
    }

    fun invalidate() { revision++ }

    fun token(): Token? = target?.let { Token(it, revision) }

    fun begin(): Token? {
        invalidate()
        return token()
    }

    fun accepts(token: Token): Boolean =
        token.target == target && token.revision == revision
}

/** Keeps a request AND its displayed suggestions bound to the original chat.
 * Worker completion does not end this lifetime; explicit dismissal, replacement
 * or navigation does. New visible rows alone must never invalidate the cards. */
internal class PinnedAnalysisTarget {
    private var pinned: ConversationSession.Target? = null

    fun pin(target: ConversationSession.Target) {
        // Titleless one-shot WeChat captures still have a concrete window and
        // visible-message signature. Keep that request pinned, but never let it
        // inherit a remembered contact or match a readable, different title.
        pinned = target
    }

    fun protects(next: ConversationSession.Target): Boolean {
        val current = pinned ?: return false
        return current.title == next.title && current.pkg == next.pkg &&
            current.windowId == next.windowId && !next.title.isNullOrBlank()
    }

    /** True only for the exact request this pin was created for. */
    fun isBoundTo(target: ConversationSession.Target): Boolean = pinned == target

    /**
     * Scrolling can briefly leave an app's accessibility tree without a title
     * or even a readable snapshot. Keep the already-captured request alive in
     * that same window, while a readable different title still invalidates it.
     */
    fun isSameWindow(pkg: String, windowId: Int): Boolean =
        pinned?.let { it.pkg == pkg && it.windowId == windowId } == true

    fun isTransientRead(target: ConversationSession.Target): Boolean =
        isSameWindow(target.pkg, target.windowId) && target.title.isNullOrBlank()

    fun clear() { pinned = null }
}

/** A tiny, bounded retry budget for a chat page whose accessibility tree is
 * briefly incomplete during an app transition or animation. */
internal class ScopeReadRetry(private val maxAttempts: Int = 2) {
    private data class State(val pkg: String, val windowId: Int, val attempts: Int)
    private val states = HashMap<String, State>()

    fun next(action: String, pkg: String, windowId: Int): Int? {
        val previous = states[action]?.takeIf { it.pkg == pkg && it.windowId == windowId }
        val attempt = (previous?.attempts ?: 0) + 1
        if (attempt > maxAttempts) {
            states.remove(action)
            return null
        }
        states[action] = State(pkg, windowId, attempt)
        return attempt
    }

    fun reset(action: String) { states.remove(action) }
}

/** Match an ordinary named chat by title. A titleless WeChat fallback is
 * allowed only for the exact visible-message signature captured for this
 * one-shot request; it cannot bind a remembered contact. */
internal fun ConversationSession.Target.matchesSnapshot(snapshot: ChatSnapshot): Boolean =
    if (title != null) snapshot.title == title
    else pkg == "com.tencent.mm" && snapshot.title.isNullOrBlank() &&
        !snapshot.messages.isEmpty() && messagesSignature == snapshot.signature()
