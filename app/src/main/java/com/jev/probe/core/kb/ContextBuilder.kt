package com.jev.probe.core.kb

import android.content.Context
import android.util.Log
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Prefs

/**
 * Turns "what is on screen right now" into the extra context one analysis gets:
 * which contact this is, their older history, and which knowledge notes apply.
 *
 * Deliberately dumb and cheap (v1.3 revision): exact name/alias matching, plain
 * substring note matching, a hard character budget. No scoring, no embeddings,
 * no auto-created contacts, no network.
 */
object ContextBuilder {

    /** Notes + history together may not exceed this many characters. */
    const val BUDGET_CHARS = 1500

    /** At most this many matched (non-always-on) notes. */
    const val MAX_HIT_NOTES = 5

    /** Only the last few messages are searched for note keywords. */
    private const val MATCH_WINDOW = 6

    /** Identical on-screen copies this short are not worth de-duplicating. */
    private const val DEDUPE_MIN_LEN = 4

    /** Invitation-only retrieval adds a bounded sample of previous attempts and their responses. */
    fun buildInvitation(context: Context, snapshot: ChatSnapshot, app: String, prefs: Prefs,
                        selectedContactId: String? = null): ChatContext {
        val ctx = build(context, snapshot, app, prefs, selectedContactId)
        val contact = ctx.contact ?: return ctx
        if (!contact.memoryEnabled || app !in contact.apps) return ctx
        val evidence = ConversationLedger.get(context).invitationHistory(contact.id, app)
        return ctx.copy(history = (evidence + ctx.history.takeLast(6)).takeLast(30))
    }

    /**
     * @param app package name of the foreground chat app (may be blank).
     * @return context for this snapshot; every field may be empty, which is the
     *         normal state before the user has built a knowledge base.
     */
    fun build(context: Context, snapshot: ChatSnapshot, app: String, prefs: Prefs, selectedContactId: String? = null): ChatContext {
        val store = KbStore.get(context)
        val title = snapshot.title ?: ""

        // 1. Contact — matched only, never created here.
        val matchedContact = if (selectedContactId != null)
            store.contact(selectedContactId)?.takeIf { app in it.apps }
        else store.findContact(title, app)
        // Profiles are deliberately app-scoped. A WeChat history must never be
        // silently applied to a same-named person in another app.
        val contact = matchedContact
        val profile = contact?.relationshipProfiles?.get(app)

        // 2. History — recorded and injected only with the user's opt-in.
        val history = when {
            contact?.memoryEnabled == true && contact.apps.contains(app) -> {
                val ledger = ConversationLedger.get(context)
                val n = prefs.contextHistoryCount.coerceIn(0, 100)
                val legacy = if (prefs.contextEnabled)
                    historyFor(store, contact, snapshot, app, prefs).takeLast(minOf(6, n / 3))
                else emptyList()
                val related = ledger.relevant(contact.id, app, snapshot, minOf(6, n / 3))
                    .filterNot { old -> snapshot.messages.any { now ->
                        now.side == old.side && now.kind.name == old.kind && now.text == old.text } }
                val recent = trimVisibleTail(ledger.recent(contact.id, app,
                    n + snapshot.messages.size), snapshot)
                legacy + related + recent.takeLast((n - legacy.size - related.size).coerceAtLeast(0))
            }
            else -> emptyList()
        }

        // 3. Notes — always-on ones plus keyword hits.
        val enabled = store.notes().filter { it.enabled }
        val alwaysOn = enabled.filter { it.alwaysOn }
        val hits = matchNotes(enabled.filter { !it.alwaysOn }, snapshot)

        // 4. Budget: always-on notes are exempt; the rest share BUDGET_CHARS,
        //    dropping oldest history first, then whole notes (never half a note).
        val trimmedHistory = ArrayList(history)
        val trimmedHits = ArrayList(hits)
        while (cost(trimmedHits, trimmedHistory) > BUDGET_CHARS && trimmedHistory.isNotEmpty())
            trimmedHistory.removeAt(0)
        while (cost(trimmedHits, trimmedHistory) > BUDGET_CHARS && trimmedHits.isNotEmpty())
            trimmedHits.removeAt(trimmedHits.size - 1)

        Log.d(TAG, "context: contact=${contact != null} profile=${profile != null} notes=${alwaysOn.size + trimmedHits.size} " +
            "history=${trimmedHistory.size}")
        val scope = com.jev.probe.core.ReplyVoiceStore.scope(app, contact?.id, snapshot.title)
        val approved = if (contact != null) com.jev.probe.core.ReplyVoiceStore(context).samples(scope).map { it.text }
            else emptyList()
        val styleComplaint = snapshot.messages.any { it.side == "other" &&
            com.jev.probe.core.ReplyVoicePolicy.isStyleComplaint(it.text) } ||
            (contact?.memoryEnabled == true && app in contact.apps &&
                ConversationLedger.get(context).hasRecentStyleComplaint(contact.id, app))
        val own = if (styleComplaint) emptyList() else if (contact?.memoryEnabled == true && app in contact.apps)
            ConversationLedger.get(context).ownExpressions(contact.id, app) else snapshot.messages
                .filter { it.side == "me" && it.kind in setOf(com.jev.probe.core.MessageKind.TEXT,
                    com.jev.probe.core.MessageKind.VOICE_TRANSCRIPT) }.map { it.text }.asReversed()
        val voice = com.jev.probe.core.ReplyVoicePolicy.guidance(approved, own, styleComplaint)
        return ChatContext(contact, trimmedHistory, alwaysOn + trimmedHits, profile, voice)
    }

    /** Drop only a page at the end, preserving older repeated short messages. */
    internal fun trimVisibleTail(history: List<LogEntry>, snapshot: ChatSnapshot): List<LogEntry> {
        val current = snapshot.messages
        var matching = 0
        for (n in 1..minOf(history.size, current.size)) {
            if ((0 until n).all { i ->
                val old = history[history.size - n + i]
                val now = current[current.size - n + i]
                old.side == now.side && old.kind == now.kind.name && old.text == now.text &&
                    old.visibleTime == now.visibleTime
            }) matching = n
        }
        return history.dropLast(matching)
    }

    /** Read older legacy history without creating new records outside per-contact opt-in. */
    private fun historyFor(
        store: KbStore,
        contact: Contact,
        snapshot: ChatSnapshot,
        app: String,
        prefs: Prefs
    ): List<LogEntry> {
        val n = prefs.contextHistoryCount.coerceIn(0, 100)
        if (n == 0) return emptyList()
        val onScreen = snapshot.messages
            .filter { it.text.length >= DEDUPE_MIN_LEN }
            .map { it.side + " " + it.text }
            .toHashSet()
        // Filter the widest window FIRST, then take n. The other order lets the
        // messages currently on screen eat into the quota — ask for 30 lines of
        // history, get 30 minus however many are already visible.
        return store.recentLog(contact.id, KbStore.MAX_LOG)
            .filter { it.app == app }
            .filter { (it.side + " " + it.text) !in onScreen }
            .takeLast(n)
    }

    /**
     * A note matches when any of its tags, or its title, appears in the
     * conversation title or in the last few messages. Newest notes win the
     * [MAX_HIT_NOTES] cap.
     */
    private fun matchNotes(candidates: List<Note>, snapshot: ChatSnapshot): List<Note> {
        if (candidates.isEmpty()) return emptyList()
        val haystack = buildString {
            append(KbStore.normalizeText(snapshot.title))
            snapshot.messages.takeLast(MATCH_WINDOW).forEach {
                append('\n').append(KbStore.normalizeText(it.text))
            }
        }
        if (haystack.isBlank()) return emptyList()
        return candidates
            .filter { note ->
                (note.tags + note.title).any { raw ->
                    val needle = KbStore.normalizeText(raw)
                    needle.isNotEmpty() && haystack.contains(needle)
                }
            }
            .sortedByDescending { it.updatedAt }
            .take(MAX_HIT_NOTES)
    }

    private fun cost(notes: List<Note>, history: List<LogEntry>): Int =
        notes.sumOf { it.title.length + it.content.length + 2 } +
            history.sumOf { it.text.length + 3 }

    private const val TAG = "JEVASSIST"
}
