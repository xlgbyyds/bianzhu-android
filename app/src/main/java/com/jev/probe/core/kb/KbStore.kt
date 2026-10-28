package com.jev.probe.core.kb

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.jev.FileProfileCheckpoints
import com.jev.probe.jev.fingerprint
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Note / contact / history counts, for the settings screen. */
data class KbCounts(val notes: Int, val contacts: Int, val logLines: Int)

/**
 * The knowledge-base store: three kinds of JSON file under `filesDir/kb`.
 *
 *   kb/notes.json           all notes
 *   kb/contacts.json        all contacts
 *   kb/logs/<contactId>.json per-contact chat history (≤ 300 lines)
 *
 * Single writer by construction: every read and write goes through one lock, and
 * writes land via a temp file + rename so a kill mid-write can never leave half
 * a JSON document behind. Serialization is hand-written org.json (no Gson/Moshi
 * dependency). Chat text never reaches logcat — only counts and lengths.
 */
class KbStore private constructor(context: Context) {

    private val app = context.applicationContext
    private val lock = Any()

    private val root: File get() = File(app.filesDir, "kb")
    private val notesFile: File get() = File(root, "notes.json")
    private val contactsFile: File get() = File(root, "contacts.json")
    private fun logFile(contactId: String) = File(File(root, "logs"), "$contactId.json")
    private fun screenFile(contactId: String) = File(File(root, "logs"), "$contactId.screen.json")

    private var notesCache: MutableList<Note>? = null
    private var contactsCache: MutableList<Contact>? = null
    private val logCache = HashMap<String, MutableList<LogEntry>>()

    /** Per contact: the comparison keys of the last screen written. See [appendLog]. */
    private val lastScreenCache = HashMap<String, List<String>>()
    /** Pending captures from before an opt-in change or deletion are discarded. */
    private val memoryEpochs = HashMap<String, Long>()

    // ------------------------------------------------------------------ notes

    fun notes(): List<Note> = synchronized(lock) { loadNotes().toList() }

    fun note(id: String): Note? = synchronized(lock) { loadNotes().firstOrNull { it.id == id } }

    /** Insert or replace by id. Returns false when it did not reach disk. */
    fun saveNote(note: Note): Boolean = synchronized(lock) {
        val list = loadNotes()
        val i = list.indexOfFirst { it.id == note.id }
        val stamped = note.copy(updatedAt = System.currentTimeMillis())
        if (i >= 0) list[i] = stamped else list.add(stamped)
        val ok = writeAtomic(notesFile, notesJson(list))
        if (!ok) notesCache = null   // memory must not claim a write that failed
        ok
    }

    fun deleteNote(id: String): Boolean = synchronized(lock) {
        val list = loadNotes()
        if (!list.removeAll { it.id == id }) return@synchronized true
        val ok = writeAtomic(notesFile, notesJson(list))
        if (!ok) notesCache = null
        ok
    }

    // --------------------------------------------------------------- contacts

    fun contacts(): List<Contact> = synchronized(lock) { loadContacts().toList() }

    fun contact(id: String): Contact? = synchronized(lock) { loadContacts().firstOrNull { it.id == id } }

    fun saveContact(c: Contact): Boolean = synchronized(lock) {
        val list = loadContacts()
        val i = list.indexOfFirst { it.id == c.id }
        if (i < 0 || list[i].memoryEnabled != c.memoryEnabled)
            memoryEpochs[c.id] = SystemClock.elapsedRealtime()
        val stamped = c.copy(updatedAt = System.currentTimeMillis())
        if (i >= 0) list[i] = stamped else list.add(stamped)
        val ok = writeAtomic(contactsFile, contactsJson(list))
        if (!ok) contactsCache = null
        if (ok && !c.memoryEnabled) FileProfileCheckpoints.clearContact(app, c.id)
        ok
    }

    /** Explicitly confirmed rename, scoped to an existing app. Never merge contacts or move records. */
    fun confirmAlias(contactId: String, title: String, app: String): Boolean = synchronized(lock) {
        val selected = loadContacts().firstOrNull { it.id == contactId } ?: return@synchronized false
        val normalized = normalizeName(title)
        if (app !in selected.apps || normalized.isBlank()) return@synchronized false
        if (loadContacts().any { other -> other.id != contactId && app in other.apps &&
                (other.aliases + other.appAliases[app].orEmpty() + other.name).any { normalizeName(it) == normalized } }) return@synchronized false
        if ((selected.aliases + selected.appAliases[app].orEmpty() + selected.name).any { normalizeName(it) == normalized }) return@synchronized true
        saveContact(selected.copy(appAliases = selected.appAliases +
            (app to (selected.appAliases[app].orEmpty() + title.trim()).distinct())))
    }

    /** Removes the contact and its history file. */
    fun deleteContact(id: String): Boolean = synchronized(lock) {
        val list = loadContacts()
        var ok = true
        if (list.removeAll { it.id == id }) {
            ok = writeAtomic(contactsFile, contactsJson(list))
            if (!ok) contactsCache = null
        }
        logCache.remove(id)
        lastScreenCache.remove(id)
        memoryEpochs[id] = SystemClock.elapsedRealtime()
        runCatching { logFile(id).delete() }
        runCatching { screenFile(id).delete() }
        runCatching { ConversationLedger.get(app).clearContact(id) }
        if (ok) com.jev.probe.core.ReplyVoiceStore(app).clearContact(id)
        if (ok) FileProfileCheckpoints.clearContact(app, id)
        ok
    }

    /**
     * Match a conversation title to a contact by normalized name or alias.
     * Never creates anything: an unknown title simply has no contact (v1.3
     * revision — contacts are only ever created by the user).
     *
     * @param app package name of the chat app the title came from; used only to
     *        prefer a contact that already knows this app when two match.
     */
    fun findContact(title: String?, app: String): Contact? {
        if (title.isNullOrBlank()) return null
        // Ivy permits names such as ".". They cannot establish who owns memory,
        // including when a legacy/manual alias happens to match that punctuation.
        if (app == "com.wepie.ivy" && title.none { it.isLetterOrDigit() }) return null
        synchronized(lock) {
            val want = normalizeName(title)
            if (want.isEmpty()) return null
            val hits = loadContacts().filter { c ->
                normalizeName(c.name) == want ||
                    (c.aliases + c.appAliases[app].orEmpty()).any { normalizeName(it) == want }
            }
            if (hits.isEmpty()) return null
            // Names are not global identities. A same-name contact from another
            // app must never silently receive this conversation's memory.
            val inApp = hits.filter { app.isNotBlank() && it.apps.contains(app) }
            if (inApp.isNotEmpty()) return inApp.singleOrNull()
            return hits.filter { it.apps.isEmpty() }.singleOrNull()
        }
    }

    /**
     * Create a contact from a conversation title, or fold the title/app into the
     * one that already matches. Returns a message for the toast.
     */
    fun saveOrMergeContact(title: String, app: String): String {
        val display = displayName(title)
        if (display.isEmpty()) return "当前会话没有标题，存不了"
        // This method is called only after the user explicitly chooses
        // “把当前会话存为联系人”. For apps whose passive visible-message
        // capture is implemented, that action also opts this person in so the
        // user does not have to visit Settings to enable the same permission.
        val enableMemory = app in VISIBLE_MEMORY_APPS
        // A contact with the same display name in another app is a different
        // person until the user explicitly merges them.
        val matchesHere = contacts().filter { c ->
            c.apps.contains(app) && (normalizeName(c.name) == normalizeName(title) ||
                (c.aliases + c.appAliases[app].orEmpty()).any { normalizeName(it) == normalizeName(title) })
        }
        if (matchesHere.size > 1) return "同名联系人不止一个，请到联系人页确认后再保存"
        val existing = matchesHere.singleOrNull()
        if (existing == null) {
            val aliases = if (displayName(title) != title.trim()) listOf(title.trim()) else emptyList()
            val saved = saveContact(Contact(
                id = newId(),
                name = display,
                aliases = aliases,
                apps = if (app.isBlank()) emptyList() else listOf(app),
                memoryEnabled = enableMemory
            ))
            return if (saved) contactSavedMessage(display, enableMemory) else "保存联系人失败，请重试"
        }
        val apps = if (app.isBlank() || existing.apps.contains(app)) existing.apps else existing.apps + app
        val raw = title.trim()
        val known = (listOf(existing.name) + existing.aliases + existing.appAliases[app].orEmpty()).map { normalizeName(it) }
        val aliases = if (raw.isNotEmpty() && normalizeName(raw) !in known)
            existing.aliases + raw else existing.aliases
        val memoryEnabled = existing.memoryEnabled || enableMemory
        if (apps == existing.apps && aliases == existing.aliases && memoryEnabled == existing.memoryEnabled)
            return contactSavedMessage(existing.name, existing.memoryEnabled, alreadyExists = true)
        return if (saveContact(existing.copy(apps = apps, aliases = aliases, memoryEnabled = memoryEnabled)))
            contactSavedMessage(existing.name, memoryEnabled, merged = apps != existing.apps || aliases != existing.aliases)
        else "保存联系人失败，请重试"
    }

    private fun contactSavedMessage(name: String, memoryEnabled: Boolean,
                                    alreadyExists: Boolean = false, merged: Boolean = false): String = when {
        memoryEnabled -> "${if (alreadyExists) "联系人「$name」已存在" else if (merged) "已并入联系人「$name」" else "已存为联系人「$name」"}；聊天记忆已开启（只存本机）"
        alreadyExists -> "联系人「$name」已存在；此 App 暂不支持可见聊天记忆"
        merged -> "已并入联系人「$name」；此 App 暂不支持可见聊天记忆"
        else -> "已存为联系人「$name」；此 App 暂不支持可见聊天记忆"
    }

    // ---------------------------------------------------------------- history

    /**
     * Append one screenful of messages, keeping only the newest [MAX_LOG].
     *
     * The unit is a SEQUENCE, not a set of lines. A capture gives us the whole
     * visible screen S, top to bottom; P is whatever screen we last wrote for
     * this contact. Two identical short lines on one screen are two positions and
     * get two entries — they are not folded together, and nothing is dropped for
     * being "too short to dedupe on" or for having been said before.
     *
     * The rules, in order:
     *  - S equals P            → the same screen again, write nothing.
     *  - log is empty          → write all of S.
     *  - log tail matches the first k lines of S (k > 0) → the screen scrolled by
     *    (S.size - k) lines; append only that new tail.
     *  - k is 0 and S shares nothing with P → the user scrolled up into old
     *    messages we already hold; this round writes nothing rather than
     *    duplicating history at the end of the file.
     *  - anything else         → append all of S.
     *
     * @param screenBatch true for a capture (the rules above). False for a
     *        deliberate single-entry injection that is NOT a screen read, which
     *        is appended as-is.
     */
    fun appendLog(contactId: String, entries: List<LogEntry>, screenBatch: Boolean = true): Boolean {
        if (entries.isEmpty()) return true
        synchronized(lock) {
            val screen = entries.filter { it.text.isNotBlank() }
            if (screen.isEmpty()) return true
            val list = loadLog(contactId)
            val keys = screen.map { key(it.side, it.text) }
            val prev = if (screenBatch) loadLastScreen(contactId) else emptyList()

            // Same screen as last time: nothing happened worth recording.
            if (screenBatch && prev.isNotEmpty() && prev == keys) return true

            // How much of S the log already ends with.
            var k = 0
            val maxK = minOf(list.size, keys.size)
            for (cand in maxK downTo 1) {
                var match = true
                for (i in 0 until cand) {
                    val e = list[list.size - cand + i]
                    if (key(e.side, e.text) != keys[i]) { match = false; break }
                }
                if (match) { k = cand; break }
            }

            val tail: List<LogEntry> = when {
                !screenBatch -> screen
                list.isEmpty() -> screen
                k > 0 -> screen.drop(k)
                // Nothing in common with the screen we last wrote → we are looking
                // at older messages, not newer ones. Leave the log alone.
                prev.isNotEmpty() && keys.none { it in prev } -> {
                    Log.d(TAG, "appendLog contact=$contactId skipped: scrolled off the last screen")
                    return true
                }
                else -> screen
            }
            if (tail.isEmpty()) {
                if (screenBatch) saveLastScreen(contactId, keys)
                return true
            }

            list.addAll(tail)
            while (list.size > MAX_LOG) list.removeAt(0)
            val ok = writeAtomic(logFile(contactId), logJson(list))
            if (!ok) logCache.remove(contactId)
            if (ok && screenBatch) saveLastScreen(contactId, keys)
            Log.d(TAG, "appendLog contact=$contactId added=${tail.size} overlap=$k total=${list.size} ok=$ok")
            return ok
        }
    }

    /** The newest [n] entries, oldest first. */
    fun recentLog(contactId: String, n: Int): List<LogEntry> {
        if (n <= 0) return emptyList()
        synchronized(lock) {
            val list = loadLog(contactId)
            return if (list.size <= n) list.toList()
            else list.subList(list.size - n, list.size).toList()
        }
    }

    fun logSize(contactId: String): Int = synchronized(lock) {
        loadLog(contactId).size + ConversationLedger.get(app).lineCount(contactId)
    }

    /** Same count shown in history links, fetched together off the UI thread. */
    fun contactMemoryCounts(): Map<String, Int> = synchronized(lock) {
        val counts = ConversationLedger.get(app).lineCounts()
        loadContacts().associate { it.id to (loadLog(it.id).size + (counts[it.id] ?: 0)) }
    }

    fun captureStats(contactId: String): CaptureStats = synchronized(lock) {
        ConversationLedger.get(app).stats(contactId)
    }

    /** Full history is read only for the explicit relationship-profile action. */
    fun relationshipSource(contactId: String, appName: String): RelationshipSource = synchronized(lock) {
        val contact = loadContacts().firstOrNull { it.id == contactId }
            ?: return@synchronized RelationshipSource(appName, 0, emptyList(), 0L, 0L)
        if (!contact.memoryEnabled || appName !in contact.apps)
            return@synchronized RelationshipSource(appName, 0, emptyList(), 0L, 0L)
        val ledgerSource = ConversationLedger.get(app).relationshipSource(contactId, appName)
        if (ledgerSource.lines.isNotEmpty()) return@synchronized ledgerSource
        // Older app versions kept at most 300 rows in JSON. Keep this migration
        // fallback explicit in coverage (pages=0); never mix it with SQLite rows.
        val old = loadLog(contactId).filter { it.app == appName }
        val lines = old.mapIndexed { index, item -> ProfileLine(
            ref = "legacy:$index", side = item.side, text = item.text, observedAt = item.ts,
            app = item.app, kind = item.kind, visibleTime = item.visibleTime,
            timeConfidence = item.timeConfidence, historical = item.historical
        ) }
        RelationshipSource(appName, 0, lines, lines.minOfOrNull { it.observedAt } ?: 0L,
            lines.maxOfOrNull { it.observedAt } ?: 0L)
    }

    fun relationshipProfileNeedsUpdate(contact: Contact, appName: String): Boolean {
        val profile = contact.relationshipProfiles[appName] ?: return false
        if (appName !in contact.apps) return false
        val latest = ConversationLedger.get(app).latestObservedAt(contact.id, appName)
        return latest > profile.sourceLatestObservedAt
    }

    fun profileEvidence(contactId: String, refs: Set<String>): List<ProfileLine> = synchronized(lock) {
        val ledgerRows = ConversationLedger.get(app).evidenceLines(contactId, refs)
        val found = ledgerRows.mapTo(HashSet()) { it.ref }
        val legacy = refs.filter { it.startsWith("legacy:") && it !in found }.mapNotNull { ref ->
            val index = ref.removePrefix("legacy:").toIntOrNull() ?: return@mapNotNull null
            loadLog(contactId).getOrNull(index)?.let { item -> ProfileLine(
                ref, item.side, item.text, item.ts, item.app, item.kind, item.visibleTime,
                item.timeConfidence, item.historical
            ) }
        }
        ledgerRows + legacy
    }

    fun saveRelationshipProfileDraft(contactId: String, appName: String, profile: RelationshipProfile): Boolean = synchronized(lock) {
        val c = loadContacts().firstOrNull { it.id == contactId } ?: return@synchronized false
        if (!c.memoryEnabled || appName !in c.apps || profile.sourceApp != appName || profile.sourceLineCount <= 0)
            return@synchronized false
        val currentSource = relationshipSource(contactId, appName)
        if (currentSource.lines.size != profile.sourceLineCount ||
            currentSource.latestObservedAt != profile.sourceLatestObservedAt) return@synchronized false
        if (profile.sourceFingerprint.isNotBlank() &&
            currentSource.fingerprint() != profile.sourceFingerprint) return@synchronized false
        val list = loadContacts()
        val i = list.indexOfFirst { it.id == contactId }
        if (i < 0) return@synchronized false
        list[i] = c.copy(relationshipProfileDrafts = c.relationshipProfileDrafts + (appName to profile))
        val ok = writeAtomic(contactsFile, contactsJson(list))
        if (ok) contactsCache = list else contactsCache = null
        ok
    }

    /**
     * Save a generated or user-edited profile directly for this contact and App.
     * ContextBuilder will then include it automatically on a later matching
     * conversation. Recheck opt-in, App ownership, and source freshness when
     * committing so a long model request cannot attach stale or cross-App data.
     */
    fun saveGeneratedRelationshipProfile(contactId: String, appName: String, profile: RelationshipProfile): Boolean =
        saveRelationshipProfile(contactId, appName, profile, preserveUserEdits = true)

    fun saveRelationshipProfile(contactId: String, appName: String, profile: RelationshipProfile,
                                preserveUserEdits: Boolean = false): Boolean = synchronized(lock) {
        val c = loadContacts().firstOrNull { it.id == contactId } ?: return@synchronized false
        if (!c.memoryEnabled || appName !in c.apps || profile.sourceApp != appName || profile.sourceLineCount <= 0)
            return@synchronized false
        val currentSource = relationshipSource(contactId, appName)
        if (currentSource.lines.size != profile.sourceLineCount ||
            currentSource.latestObservedAt != profile.sourceLatestObservedAt) return@synchronized false
        if (profile.sourceFingerprint.isNotBlank() &&
            currentSource.fingerprint() != profile.sourceFingerprint) return@synchronized false
        val list = loadContacts()
        val i = list.indexOfFirst { it.id == contactId }
        if (i < 0) return@synchronized false
        val saved = if (preserveUserEdits) profile.retainingUserEdits(c.relationshipProfiles[appName]) else profile
        list[i] = c.copy(
            relationshipProfiles = c.relationshipProfiles + (appName to saved),
            relationshipProfileDrafts = c.relationshipProfileDrafts - appName
        )
        val ok = writeAtomic(contactsFile, contactsJson(list))
        if (ok) contactsCache = list else contactsCache = null
        ok
    }

    fun acceptRelationshipProfile(contactId: String, appName: String): Boolean = synchronized(lock) {
        val list = loadContacts()
        val i = list.indexOfFirst { it.id == contactId }
        if (i < 0) return@synchronized false
        val draft = list[i].relationshipProfileDrafts[appName] ?: return@synchronized false
        list[i] = list[i].copy(
            relationshipProfiles = list[i].relationshipProfiles +
                (appName to draft.retainingUserEdits(list[i].relationshipProfiles[appName])),
            relationshipProfileDrafts = list[i].relationshipProfileDrafts - appName
        )
        val ok = writeAtomic(contactsFile, contactsJson(list))
        if (ok) contactsCache = list else contactsCache = null
        ok
    }

    fun historyGroupCount(contactId: String): Int = synchronized(lock) {
        ConversationLedger.get(app).groupedCount(contactId)
    }

    fun historyItems(contactId: String, grouped: Boolean, limit: Int, offset: Int): List<MemoryItem> =
        synchronized(lock) { ConversationLedger.get(app).items(contactId, grouped, limit, offset) }

    fun editHistoryItem(contactId: String, itemId: Long, grouped: Boolean, side: String,
                        kind: String, body: String, visibleTime: String?, confidence: String): Int =
        synchronized(lock) {
            memoryEpochs[contactId] = SystemClock.elapsedRealtime()
            ConversationLedger.get(app).updateItem(contactId, itemId, grouped,
                side, kind, body, visibleTime, confidence)
        }

    fun deleteHistoryItem(contactId: String, itemId: Long, grouped: Boolean): Int =
        synchronized(lock) {
            memoryEpochs[contactId] = SystemClock.elapsedRealtime()
            ConversationLedger.get(app).deleteItem(contactId, itemId, grouped)
        }

    fun legacyLogSize(contactId: String): Int = synchronized(lock) { loadLog(contactId).size }

    fun legacyLogPage(contactId: String, limit: Int, offset: Int): List<Pair<Int, LogEntry>> =
        synchronized(lock) {
            loadLog(contactId).withIndex().toList().asReversed()
                .drop(offset.coerceAtLeast(0)).take(limit.coerceIn(1, 50))
                .map { it.index to it.value }
        }

    fun editLegacyLog(contactId: String, index: Int, entry: LogEntry): Boolean = synchronized(lock) {
        val list = loadLog(contactId)
        if (index !in list.indices) return@synchronized false
        memoryEpochs[contactId] = SystemClock.elapsedRealtime()
        list[index] = entry
        val ok = writeAtomic(logFile(contactId), logJson(list))
        if (!ok) logCache.remove(contactId)
        ok
    }

    fun deleteLegacyLog(contactId: String, index: Int): Boolean = synchronized(lock) {
        val list = loadLog(contactId)
        if (index !in list.indices) return@synchronized false
        memoryEpochs[contactId] = SystemClock.elapsedRealtime()
        list.removeAt(index)
        val ok = writeAtomic(logFile(contactId), logJson(list))
        if (!ok) logCache.remove(contactId)
        ok
    }

    /** Serializes capture against delete/clear, so erased history cannot reappear from a queue. */
    fun captureVisible(snapshot: ChatSnapshot, appName: String, historical: Boolean,
                       observedAt: Long, queuedAtElapsed: Long): Boolean =
        captureVisibleDetailed(null, snapshot, appName, historical, observedAt, queuedAtElapsed).added > 0

    fun captureVisibleDetailed(contactId: String?, snapshot: ChatSnapshot, appName: String,
                               historical: Boolean, observedAt: Long, queuedAtElapsed: Long): CaptureResult = synchronized(lock) {
        // Defense at the storage boundary as well as in the capture service.
        // Manually edited history remains editable, but uncertain automatic
        // attribution must never become a remembered fact.
        if (appName in setOf("com.tencent.mm", "cn.soulapp.android") &&
            snapshot.messages.any { it.side != "me" && it.side != "other" })
            return@synchronized CaptureResult(reason = "本屏有发言者未确认的消息 请先核实我和对方 本屏未保存", needsReview = true)
        val title = snapshot.title ?: return@synchronized CaptureResult(reason = "当前联系人名称未确认 本屏未保存")
        val contact = findContact(title, appName)
            ?: return@synchronized CaptureResult(reason = "当前会话未匹配联系人 本屏未保存")
        if (contactId != null && contact.id != contactId)
            return@synchronized CaptureResult(reason = "当前联系人发生变化 本屏未保存")
        if (!contact.memoryEnabled || appName !in contact.apps)
            return@synchronized CaptureResult(reason = "此联系人的聊天记忆未开启 本屏未保存")
        if (queuedAtElapsed <= (memoryEpochs[contact.id] ?: Long.MIN_VALUE))
            return@synchronized CaptureResult(reason = "联系人记忆已被编辑或清除 请重新采集 本屏未保存")
        ConversationLedger.get(app).recordDetailed(contact.id, appName, snapshot, historical, observedAt)
    }

    /** Backfill is explicitly bound to a contact at start; keep writes on that id. */
    fun captureVisible(contactId: String, snapshot: ChatSnapshot, appName: String,
                       historical: Boolean, observedAt: Long, queuedAtElapsed: Long): Boolean =
        captureVisibleDetailed(contactId, snapshot, appName, historical, observedAt, queuedAtElapsed).added > 0

    fun clearLog(contactId: String) = synchronized(lock) {
        FileProfileCheckpoints.clearContact(app, contactId)
        memoryEpochs[contactId] = SystemClock.elapsedRealtime()
        val contacts = loadContacts()
        val contactIndex = contacts.indexOfFirst { it.id == contactId }
        if (contactIndex >= 0) {
            val c = contacts[contactIndex]
            if (c.relationshipProfiles.isNotEmpty() || c.relationshipProfileDrafts.isNotEmpty()) {
                contacts[contactIndex] = c.copy(relationshipProfiles = emptyMap(), relationshipProfileDrafts = emptyMap())
                if (writeAtomic(contactsFile, contactsJson(contacts))) contactsCache = contacts
                else contactsCache = null
            }
        }
        logCache.remove(contactId)
        lastScreenCache.remove(contactId)
        runCatching { logFile(contactId).delete() }
        runCatching { screenFile(contactId).delete() }
        ConversationLedger.get(app).clearContact(contactId)
        Unit
    }

    /**
     * The (side, text) sequence of the last screen written for this contact, as
     * comparison keys. Kept on disk as well as in memory so that re-opening a
     * chat that has not moved since does not append the same screen again.
     */
    private fun loadLastScreen(contactId: String): List<String> {
        lastScreenCache[contactId]?.let { return it }
        val out = ArrayList<String>()
        val loaded = readJsonArray(screenFile(contactId))
        loaded.arr?.let { arr ->
            for (i in 0 until arr.length()) {
                val s = arr.optString(i)
                if (s.isNotEmpty()) out.add(s)
            }
        }
        if (loaded.trustworthy) lastScreenCache[contactId] = out
        return out
    }

    private fun saveLastScreen(contactId: String, keys: List<String>) {
        lastScreenCache[contactId] = keys
        val arr = JSONArray()
        keys.forEach { arr.put(it) }
        if (!writeAtomic(screenFile(contactId), arr.toString())) lastScreenCache.remove(contactId)
    }

    // ------------------------------------------------------------------ admin

    fun counts(): KbCounts = synchronized(lock) {
        val contacts = loadContacts()
        var lines = 0
        contacts.forEach { lines += loadLog(it.id).size }
        KbCounts(loadNotes().size, contacts.size,
            lines + ConversationLedger.get(app).totalLineCount())
    }

    /**
     * Wipe both legacy JSON and the opt-in conversation ledger. API keys,
     * whitelist and every other SharedPreferences value are untouched.
     */
    fun clearAll() = synchronized(lock) {
        notesCache = null
        contactsCache = null
        logCache.clear()
        lastScreenCache.clear()
        memoryEpochs.clear()
        runCatching { root.deleteRecursively() }
        ConversationLedger.get(app).clearAll()
        com.jev.probe.core.ReplyVoiceStore(app).clearAll()
        Log.i(TAG, "kb cleared")
        Unit
    }

    // ------------------------------------------------------------------ io

    private fun loadNotes(): MutableList<Note> {
        notesCache?.let { return it }
        val list = ArrayList<Note>()
        val loaded = readJsonArray(notesFile)
        loaded.arr?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                list.add(Note(
                    id = o.optString("id").ifBlank { newId() },
                    title = o.optString("title"),
                    content = o.optString("content"),
                    tags = strList(o.optJSONArray("tags")),
                    alwaysOn = o.optBoolean("alwaysOn", false),
                    enabled = o.optBoolean("enabled", true),
                    updatedAt = o.optLong("updatedAt", 0L)
                ))
            }
        }
        if (loaded.trustworthy) notesCache = list
        return list
    }

    private fun loadContacts(): MutableList<Contact> {
        contactsCache?.let { return it }
        val list = ArrayList<Contact>()
        val loaded = readJsonArray(contactsFile)
        loaded.arr?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                list.add(Contact(
                    id = o.optString("id").ifBlank { newId() },
                    name = o.optString("name"),
                    aliases = strList(o.optJSONArray("aliases")),
                    apps = strList(o.optJSONArray("apps")),
                    memoryEnabled = o.optBoolean("memoryEnabled", false),
                    relationship = o.optString("relationship"),
                    notes = o.optString("notes"),
                    autoSummary = o.optString("autoSummary"),
                    relationshipProfiles = profileMapFromJson(o.optJSONObject("relationshipProfiles")),
                    relationshipProfileDrafts = profileMapFromJson(o.optJSONObject("relationshipProfileDrafts")),
                    updatedAt = o.optLong("updatedAt", 0L),
                    appAliases = o.optJSONObject("appAliases")?.let { aliases ->
                        aliases.keys().asSequence().associateWith { strList(aliases.optJSONArray(it)) }
                    }.orEmpty()
                ))
            }
        }
        if (loaded.trustworthy) contactsCache = list
        return list
    }

    private fun loadLog(contactId: String): MutableList<LogEntry> {
        logCache[contactId]?.let { return it }
        val list = ArrayList<LogEntry>()
        val loaded = readJsonArray(logFile(contactId))
        loaded.arr?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                list.add(LogEntry(
                    side = o.optString("side", "other"),
                    text = o.optString("text"),
                    ts = o.optLong("ts", 0L),
                    app = o.optString("app")
                ))
            }
        }
        if (loaded.trustworthy) logCache[contactId] = list
        return list
    }

    private fun notesJson(list: List<Note>): String {
        val arr = JSONArray()
        list.forEach { n ->
            arr.put(JSONObject()
                .put("id", n.id)
                .put("title", n.title)
                .put("content", n.content)
                .put("tags", JSONArray(n.tags))
                .put("alwaysOn", n.alwaysOn)
                .put("enabled", n.enabled)
                .put("updatedAt", n.updatedAt))
        }
        return arr.toString()
    }

    private fun contactsJson(list: List<Contact>): String {
        val arr = JSONArray()
        list.forEach { c ->
            arr.put(JSONObject()
                .put("id", c.id)
                .put("name", c.name)
                .put("aliases", JSONArray(c.aliases))
                .put("appAliases", JSONObject().also { o -> c.appAliases.forEach { (app, names) ->
                    o.put(app, JSONArray(names)) } })
                .put("apps", JSONArray(c.apps))
                .put("memoryEnabled", c.memoryEnabled)
                .put("relationship", c.relationship)
                .put("notes", c.notes)
                .put("autoSummary", c.autoSummary)
                .put("relationshipProfiles", profileMapToJson(c.relationshipProfiles))
                .put("relationshipProfileDrafts", profileMapToJson(c.relationshipProfileDrafts))
                .put("updatedAt", c.updatedAt))
        }
        return arr.toString()
    }

    private fun profileToJson(profile: RelationshipProfile?): JSONObject? {
        profile ?: return null
        return JSONObject()
            .put("relationshipStage", claimToJson(profile.relationshipStage))
            .put("attitudeAssessment", claimToJson(profile.attitudeAssessment))
            .put("knownFacts", claimsToJson(profile.knownFacts))
            .put("interactionPatterns", claimsToJson(profile.interactionPatterns))
            .put("conversationDirection", claimToJson(profile.conversationDirection))
            .put("pacingAdvice", profile.pacingAdvice)
            .put("unknowns", JSONArray(profile.unknowns))
            .put("sourceApp", profile.sourceApp)
            .put("sourceLineCount", profile.sourceLineCount)
            .put("sourcePageCount", profile.sourcePageCount)
            .put("sourceMeCount", profile.sourceMeCount)
            .put("sourceOtherCount", profile.sourceOtherCount)
            .put("sourceUnknownSideCount", profile.sourceUnknownSideCount)
            .put("sourceVisibleTimeCount", profile.sourceVisibleTimeCount)
            .put("sourceHistoricalCount", profile.sourceHistoricalCount)
            .put("sourceFirstObservedAt", profile.sourceFirstObservedAt)
            .put("sourceLatestObservedAt", profile.sourceLatestObservedAt)
            .put("generatedAt", profile.generatedAt)
            .put("promptVersion", profile.promptVersion)
            .put("sourceFingerprint", profile.sourceFingerprint)
    }

    private fun profileMapToJson(profiles: Map<String, RelationshipProfile>) = JSONObject().apply {
        profiles.forEach { (appName, profile) -> put(appName, profileToJson(profile)) }
    }

    private fun profileMapFromJson(o: JSONObject?): Map<String, RelationshipProfile> {
        if (o == null) return emptyMap()
        val out = LinkedHashMap<String, RelationshipProfile>()
        val keys = o.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            profileFromJson(o.optJSONObject(key))?.takeIf { it.sourceApp == key }?.let { out[key] = it }
        }
        return out
    }

    private fun profileFromJson(o: JSONObject?): RelationshipProfile? = runCatching {
        o ?: return null
        RelationshipProfile(
            relationshipStage = claimFromJson(o.optJSONObject("relationshipStage")),
            attitudeAssessment = claimFromJson(o.optJSONObject("attitudeAssessment")),
            knownFacts = claimsFromJson(o.optJSONArray("knownFacts")),
            interactionPatterns = claimsFromJson(o.optJSONArray("interactionPatterns")),
            conversationDirection = o.optJSONObject("conversationDirection")?.let(::claimFromJson)
                ?: ProfileClaim("优先沿着双方都愿意延续的现有话题自然交流；若缺少共同话题，先回应眼前对话，不预设关系目标。", emptyList(), 0.2),
            pacingAdvice = o.optString("pacingAdvice"),
            unknowns = strList(o.optJSONArray("unknowns")),
            sourceApp = o.optString("sourceApp"),
            sourceLineCount = o.optInt("sourceLineCount"),
            sourcePageCount = o.optInt("sourcePageCount"),
            sourceMeCount = o.optInt("sourceMeCount"),
            sourceOtherCount = o.optInt("sourceOtherCount"),
            sourceUnknownSideCount = o.optInt("sourceUnknownSideCount"),
            sourceVisibleTimeCount = o.optInt("sourceVisibleTimeCount"),
            sourceHistoricalCount = o.optInt("sourceHistoricalCount"),
            sourceFirstObservedAt = o.optLong("sourceFirstObservedAt"),
            sourceLatestObservedAt = o.optLong("sourceLatestObservedAt"),
            generatedAt = o.optLong("generatedAt", System.currentTimeMillis()),
            promptVersion = o.optInt("promptVersion", 1),
            sourceFingerprint = o.optString("sourceFingerprint")
        )
    }.getOrNull()

    private fun claimToJson(claim: ProfileClaim) = JSONObject()
        .put("text", claim.text).put("sourceRefs", JSONArray(claim.sourceRefs))
        .put("confidence", claim.confidence.coerceIn(0.0, 1.0)).put("userEdited", claim.userEdited)

    private fun claimsToJson(claims: List<ProfileClaim>) = JSONArray().apply {
        claims.forEach { put(claimToJson(it)) }
    }

    private fun claimFromJson(o: JSONObject?): ProfileClaim {
        o ?: return ProfileClaim("")
        return ProfileClaim(o.optString("text"), strList(o.optJSONArray("sourceRefs")),
            o.optDouble("confidence", 0.5).coerceIn(0.0, 1.0), o.optBoolean("userEdited", false))
    }

    private fun claimsFromJson(arr: JSONArray?): List<ProfileClaim> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(::claimFromJson) }
            .filter { it.text.isNotBlank() }
    }

    private fun logJson(list: List<LogEntry>): String {
        val arr = JSONArray()
        list.forEach { e ->
            arr.put(JSONObject()
                .put("side", e.side)
                .put("text", e.text)
                .put("ts", e.ts)
                .put("app", e.app))
        }
        return arr.toString()
    }

    /**
     * Result of reading one JSON file. [trustworthy] is false only in the one
     * nasty case: the file exists, does not parse, AND could not be moved aside
     * — then an empty list is a guess, so it must not be cached and must not be
     * written over the user's data.
     */
    private class Loaded(val arr: JSONArray?, val trustworthy: Boolean)

    /** Files that failed to parse and could not be preserved; never overwrite. */
    private val unreadable = HashSet<String>()

    private fun readJsonArray(f: File): Loaded {
        if (!f.exists()) { unreadable.remove(f.absolutePath); return Loaded(null, true) }
        return try {
            val arr = JSONArray(f.readText(Charsets.UTF_8))
            unreadable.remove(f.absolutePath)
            Loaded(arr, true)
        } catch (e: Exception) {
            // Damaged file: set it aside under a dated name rather than let the
            // next save silently write over it. Starting empty is only safe once
            // the original is actually preserved.
            val backup = File(f.parentFile, "${f.name}.corrupt.${System.currentTimeMillis()}")
            val kept = runCatching { f.renameTo(backup) }.getOrDefault(false)
            if (kept) unreadable.remove(f.absolutePath) else unreadable.add(f.absolutePath)
            Log.w(TAG, "unreadable ${f.name}: ${e.javaClass.simpleName} preserved=$kept")
            Loaded(null, kept)
        }
    }

    /**
     * Temp file + rename, so a crash never leaves a half-written document.
     *
     * The rename REPLACES the destination in one step (POSIX semantics, same
     * directory) — deleting the old file first would mean a kill in between
     * loses everything. Returns false when the data did not reach disk; callers
     * drop their cache so the next read goes back to the file.
     */
    private fun writeAtomic(f: File, text: String): Boolean {
        if (f.absolutePath in unreadable) {
            Log.w(TAG, "refusing to overwrite unparsable ${f.name}")
            return false
        }
        val tmp = File(f.parentFile, f.name + ".tmp")
        return try {
            f.parentFile?.mkdirs()
            tmp.writeText(text, Charsets.UTF_8)
            if (tmp.renameTo(f)) return true
            // Same-directory rename should not fail. If it somehow does, an
            // in-place overwrite is the only way left — not atomic, so say so.
            Log.w(TAG, "rename failed, overwriting ${f.name} in place")
            f.writeText(text, Charsets.UTF_8)
            runCatching { tmp.delete() }
            true
        } catch (e: Exception) {
            runCatching { tmp.delete() }
            Log.w(TAG, "write failed ${f.name}: ${e.javaClass.simpleName}")
            false
        }
    }

    private fun strList(arr: JSONArray?): List<String> {
        arr ?: return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val s = arr.optString(i).trim()
            if (s.isNotEmpty()) out.add(s)
        }
        return out
    }

    private fun key(side: String, text: String) = side + "\u0000" + text

    companion object {
        private const val TAG = "JEVASSIST"
        const val MAX_LOG = 300
        private val VISIBLE_MEMORY_APPS = setOf(
            "com.tencent.mm", "com.tantan.x", "cn.soulapp.android", "com.wepie.ivy"
        )

        @Volatile private var instance: KbStore? = null

        fun get(context: Context): KbStore =
            instance ?: synchronized(this) {
                instance ?: KbStore(context).also { instance = it }
            }

        fun newId(): String = java.util.UUID.randomUUID().toString().substring(0, 12)

        /**
         * Compile a pattern without ever taking the class down with it. A
         * `Regex(...)` straight in a `val` runs during `<clinit>`, so one bad
         * pattern turns into `ExceptionInInitializerError` and every call into
         * [KbStore] dies with it — which is exactly what happened on device
         * (Android's ICU engine rejected the old member-count pattern). A null
         * here only means that one cleanup step is skipped.
         */
        private fun safeRegex(pattern: String): Regex? =
            runCatching { Regex(pattern) }.getOrElse {
                Log.w(TAG, "regex init failed: ${it.javaClass.simpleName} ${it.message ?: ""}")
                null
            }

        private val ZERO_WIDTH = safeRegex("[\\u200B-\\u200D\\uFEFF]")

        /**
         * Trailing group member count. Written as an alternation of escaped code
         * points rather than a character class holding brackets: ICU on device
         * read `[(...)]` as an unterminated class ("missing closing bracket").
         * No literal full-width bracket in the source, on purpose.
         */
        private val TRAILING_COUNT =
            safeRegex("\\s*(?:\\(|\\uFF08)\\s*\\d+\\s*(?:\\)|\\uFF09)\\s*$")

        private fun stripZeroWidth(s: String): String = ZERO_WIDTH?.replace(s, "") ?: s

        private fun stripTrailingCount(s: String): String =
            TRAILING_COUNT?.replace(s, "")?.trim() ?: s

        /**
         * Name key for matching: trimmed, zero-width characters removed, the
         * group member count `(12)` (half- or full-width) dropped, lower-cased.
         * If the patterns failed to compile this degrades to trim + lowercase.
         */
        fun normalizeName(s: String?): String {
            if (s.isNullOrEmpty()) return ""
            val t = stripTrailingCount(stripZeroWidth(s).trim())
            return t.trim().lowercase()
        }

        /** Same cleanup as [normalizeName] but keeps the original casing, for display. */
        fun displayName(s: String?): String {
            if (s.isNullOrEmpty()) return ""
            return stripTrailingCount(stripZeroWidth(s).trim()).trim()
        }

        /** Loose key for substring matching (no member-count stripping). */
        fun normalizeText(s: String?): String {
            if (s.isNullOrEmpty()) return ""
            return stripZeroWidth(s).trim().lowercase()
        }
    }
}
