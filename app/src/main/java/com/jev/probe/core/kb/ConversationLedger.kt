package com.jev.probe.core.kb

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Msg
import com.jev.probe.core.MessageKind
import java.security.MessageDigest

data class CaptureStats(val pages: Int, val lines: Int)

data class CaptureResult(val added: Int = 0, val timeUpdated: Int = 0,
                         val reason: String? = null, val needsReview: Boolean = false) {
    val saved get() = reason == null
    fun status(): String = when {
        reason != null -> reason
        added > 0 -> "本屏新增 $added 条消息" + if (timeUpdated > 0) " 补全 $timeUpdated 条时间" else ""
        timeUpdated > 0 -> "本屏补全 $timeUpdated 条时间 没有重复存入正文"
        else -> "本屏已核对 没有新增消息"
    }
}

/** One saved line. A grouped item represents identical observations on overlapping screens. */
data class MemoryItem(
    val id: Long, val side: String, val kind: String, val body: String,
    val visibleTime: String?, val timeConfidence: String,
    val observedAt: Long, val historical: Boolean, val copies: Int
)

/** Local record of visible chat pages. Overlapping observations share one stored message row. */
class ConversationLedger internal constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "conversation-memory.db", null, 5) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE capture (
            id INTEGER PRIMARY KEY, contact_id TEXT NOT NULL, app TEXT NOT NULL,
            title TEXT NOT NULL, fingerprint TEXT NOT NULL, content_fingerprint TEXT NOT NULL DEFAULT '', page_keys TEXT NOT NULL,
            observed_at INTEGER NOT NULL,
            historical INTEGER NOT NULL, UNIQUE(contact_id, fingerprint))""")
        db.execSQL("""CREATE TABLE line (
            id INTEGER PRIMARY KEY, capture_id INTEGER NOT NULL, ordinal INTEGER NOT NULL,
            side TEXT NOT NULL, kind TEXT NOT NULL, body TEXT NOT NULL,
            visible_time TEXT, time_confidence TEXT NOT NULL, content_key TEXT NOT NULL DEFAULT '',
            FOREIGN KEY(capture_id) REFERENCES capture(id) ON DELETE CASCADE)""")
        db.execSQL("CREATE INDEX capture_recent ON capture(contact_id, historical, id)")
        db.execSQL("CREATE INDEX capture_lookup ON capture(contact_id, app, id)")
        db.execSQL("CREATE INDEX capture_content ON capture(contact_id, app, content_fingerprint)")
        db.execSQL("CREATE INDEX line_capture ON line(capture_id, ordinal)")
        createPageLinks(db)
        createAliases(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        require(oldVersion in 1..4 && newVersion == 5) { "ConversationLedger migration required" }
        if (oldVersion < 4) upgradeToVersion4(db, oldVersion)
        createAliases(db)
        repairRepeatedObservations(db)
    }

    private fun upgradeToVersion4(db: SQLiteDatabase, oldVersion: Int) {
        if (oldVersion < 3) {
            if (oldVersion == 1) upgradeToVersion2(db)
            db.execSQL("ALTER TABLE line ADD COLUMN content_key TEXT NOT NULL DEFAULT ''")
            createPageLinks(db)
            // Keep every existing message and manual correction. V2 saved complete
            // page hashes but only the new body rows; reconstruct references where
            // an old hash has one identifiable owner, leaving ambiguous gaps intact.
            data class Row(val id: Long, val capture: Long, val ordinal: Int, val contact: String,
                           val app: String, val legacyKey: String)
            val rows = ArrayList<Row>()
            db.rawQuery("""SELECT l.id,l.capture_id,l.ordinal,c.contact_id,c.app,
                l.side,l.kind,l.body,l.visible_time FROM line l JOIN capture c ON c.id=l.capture_id""", null).use { c ->
                while (c.moveToNext()) {
                    rows.add(Row(c.getLong(0), c.getLong(1), c.getInt(2), c.getString(3), c.getString(4),
                        messageKey(c.getString(5), c.getString(6), c.getString(7), if (c.isNull(8)) null else c.getString(8))))
                    db.execSQL("UPDATE line SET content_key=? WHERE id=?", arrayOf(
                        ConversationOverlap.contentKey(c.getString(5), c.getString(6), c.getString(7)), c.getLong(0)))
                }
            }
            val byCapture = rows.groupBy { it.capture }
            val byKey = rows.groupBy { Triple(it.contact, it.app, it.legacyKey) }
            db.rawQuery("SELECT id,contact_id,app,page_keys FROM capture", null).use { c ->
                while (c.moveToNext()) {
                    val capture = c.getLong(0)
                    val own = byCapture[capture].orEmpty().associateBy { it.ordinal }
                    val keys = c.getString(3).split(',').filter { it.isNotEmpty() }
                    for (ordinal in 0 until maxOf(keys.size, (own.keys.maxOrNull() ?: -1) + 1)) {
                        val row = own[ordinal] ?: keys.getOrNull(ordinal)?.let { key ->
                            byKey[Triple(c.getString(1), c.getString(2), key)]?.singleOrNull()
                        }
                        if (row != null) insertPageLink(db, capture, ordinal, row.id)
                    }
                }
            }
        }
        db.execSQL("ALTER TABLE capture ADD COLUMN content_fingerprint TEXT NOT NULL DEFAULT ''")
        db.execSQL("CREATE INDEX capture_content ON capture(contact_id, app, content_fingerprint)")
        val captures = ArrayList<Triple<Long, String, String>>()
        db.rawQuery("SELECT id,app FROM capture", null).use { c ->
            while (c.moveToNext()) captures.add(Triple(c.getLong(0), c.getString(1), ""))
        }
        captures.forEach { (captureId, app, _) ->
            val rows = ArrayList<Triple<String, String, String>>()
            db.rawQuery("""SELECT l.side,l.kind,l.body FROM page_line p JOIN line l ON l.id=p.line_id
                WHERE p.capture_id=? ORDER BY p.ordinal""", arrayOf(captureId.toString())).use { c ->
                while (c.moveToNext()) rows.add(Triple(c.getString(0), c.getString(1), c.getString(2)))
            }
            if (rows.isNotEmpty()) db.execSQL("UPDATE capture SET content_fingerprint=? WHERE id=?",
                arrayOf(pageContentFingerprint(app, rows), captureId))
        }
    }

    private fun createAliases(db: SQLiteDatabase) {
        // Tiny identifiers preserve existing relationship-profile evidence links;
        // the repeated message bodies themselves are removed, not just hidden.
        db.execSQL("CREATE TABLE line_alias(old_id INTEGER PRIMARY KEY, line_id INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX line_alias_target ON line_alias(line_id)")
    }

    /** One-time, conservative V4 repair. Capture time is NEVER used as send time. */
    private fun repairRepeatedObservations(db: SQLiteDatabase) {
        data class Page(val id: Long, val contact: String, val app: String, val at: Long)
        val pages = ArrayList<Page>()
        db.rawQuery("SELECT id,contact_id,app,observed_at FROM capture ORDER BY observed_at,id", null).use { c ->
            while (c.moveToNext()) pages.add(Page(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3)))
        }
        val recent = HashMap<Pair<String, String>, MutableList<Pair<Long, List<ConversationOverlap.Line?>>>>()
        fun load(id: Long): List<ConversationOverlap.Line?> {
            val result = ArrayList<ConversationOverlap.Line?>()
            db.rawQuery("""SELECT p.ordinal,l.id,l.side,l.kind,l.body,l.visible_time,l.time_confidence
                FROM page_line p JOIN line l ON l.id=p.line_id WHERE p.capture_id=? ORDER BY p.ordinal""",
                arrayOf(id.toString())).use { c ->
                while (c.moveToNext()) {
                    while (result.size <= c.getInt(0)) result.add(null)
                    result[c.getInt(0)] = ConversationOverlap.Line(c.getLong(1), c.getString(2), c.getString(3),
                        c.getString(4), if (c.isNull(5)) null else c.getString(5), c.getString(6))
                }
            }
            return result
        }
        for (page in pages) {
            val prior = recent.getOrPut(page.contact to page.app) { ArrayList() }
            prior.removeAll { page.at - it.first > 120_000L }
            val rows = load(page.id)
            // Missing positions, manual corrections and unreadable media cannot
            // establish a repair. At least four ordered messages and two rich
            // anchors must agree with a recently observed page of THIS contact/app.
            val messages = rows.map { row ->
                if (row == null || row.confidence == "manual" || !ConversationOverlap.readable(row.side, row.kind, row.body))
                    Msg("unknown", "", MessageKind.MEDIA_UNKNOWN)
                else Msg(row.side, row.body, MessageKind.valueOf(row.kind), row.time, row.confidence)
            }
            val matches = ConversationOverlap.match(messages, prior.map { it.second }, 4, 2)
            val replacements = HashMap<Long, ConversationOverlap.Line>()
            for ((position, canonical) in matches) {
                val duplicate = rows[position] ?: continue
                if (duplicate.id == canonical.id || duplicate.confidence == "manual" || canonical.confidence == "manual") continue
                if (canonical.time.isNullOrBlank() && !duplicate.time.isNullOrBlank()) {
                    db.execSQL("UPDATE line SET visible_time=?,time_confidence=? WHERE id=?",
                        arrayOf(duplicate.time, duplicate.confidence, canonical.id))
                }
                db.execSQL("UPDATE page_line SET line_id=? WHERE line_id=?", arrayOf(canonical.id, duplicate.id))
                db.execSQL("UPDATE line_alias SET line_id=? WHERE line_id=?", arrayOf(canonical.id, duplicate.id))
                db.execSQL("INSERT OR REPLACE INTO line_alias(old_id,line_id) VALUES(?,?)", arrayOf(duplicate.id, canonical.id))
                db.delete("line", "id=?", arrayOf(duplicate.id.toString()))
                val enriched = if (canonical.time.isNullOrBlank() && !duplicate.time.isNullOrBlank())
                    canonical.copy(time = duplicate.time, confidence = duplicate.confidence) else canonical
                replacements[duplicate.id] = enriched
                replacements[canonical.id] = enriched
            }
            // Reload so subsequent pages see enriched times and canonical IDs,
            // rather than the stale rows that caused the ambiguity cascade.
            if (replacements.isNotEmpty()) {
                for (i in prior.indices) {
                    val old = prior[i]
                    val refreshed = old.second.map { line -> if (line == null) null else replacements[line.id] ?: line }
                    prior[i] = old.first to refreshed
                }
            }
            prior.add(page.at to load(page.id))
        }
    }

    private fun createPageLinks(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX line_content ON line(content_key)")
        db.execSQL("""CREATE TABLE page_line (
            capture_id INTEGER NOT NULL, ordinal INTEGER NOT NULL, line_id INTEGER NOT NULL,
            PRIMARY KEY(capture_id, ordinal))""")
        db.execSQL("CREATE INDEX page_line_message ON page_line(line_id, capture_id)")
    }

    private fun insertPageLink(db: SQLiteDatabase, capture: Long, ordinal: Int, line: Long) {
        db.execSQL("INSERT INTO page_line(capture_id,ordinal,line_id) VALUES(?,?,?)", arrayOf(capture, ordinal, line))
    }

    private fun upgradeToVersion2(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE capture ADD COLUMN page_keys TEXT NOT NULL DEFAULT ''")
        db.execSQL("CREATE INDEX capture_lookup ON capture(contact_id, app, id)")
        // Rebuild compact page signatures before removing rows. Only messages in a
        // continuous, distinctive overlap are removed; ambiguous repeats stay.
        val captures = ArrayList<Triple<Long, String, String>>()
        db.rawQuery("SELECT id,contact_id,app FROM capture ORDER BY id", null).use { c ->
            while (c.moveToNext()) captures.add(Triple(c.getLong(0), c.getString(1), c.getString(2)))
        }
        val prior = HashMap<Pair<String, String>, MutableList<List<String>>>()
        for ((captureId, contactId, app) in captures) {
            val rows = ArrayList<Pair<Long, String>>()
            db.rawQuery("""SELECT id,side,kind,body,visible_time FROM line
                WHERE capture_id=? ORDER BY ordinal""", arrayOf(captureId.toString())).use { c ->
                while (c.moveToNext()) rows.add(c.getLong(0) to messageKey(
                    c.getString(1), c.getString(2), c.getString(3),
                    if (c.isNull(4)) null else c.getString(4)))
            }
            val keys = rows.map { it.second }
            val previous = prior.getOrPut(contactId to app) { ArrayList() }
            val covered = coveredByEarlierPages(keys, previous)
            rows.forEachIndexed { index, row ->
                if (index in covered) db.delete("line", "id=?", arrayOf(row.first.toString()))
            }
            if (covered.size == rows.size) {
                db.delete("capture", "id=?", arrayOf(captureId.toString()))
            } else {
                db.execSQL("UPDATE capture SET page_keys=? WHERE id=?",
                    arrayOf(keys.joinToString(","), captureId))
                previous.add(keys)
            }
        }
    }

    /** Returns true only when a new visible page was stored. Runs off the UI thread. */
    fun record(contactId: String, app: String, snapshot: ChatSnapshot,
               historical: Boolean, observedAt: Long = System.currentTimeMillis()): Boolean =
        recordDetailed(contactId, app, snapshot, historical, observedAt).added > 0

    fun recordDetailed(contactId: String, app: String, incoming: ChatSnapshot,
                       historical: Boolean, observedAt: Long = System.currentTimeMillis()): CaptureResult {
        if (incoming.messages.isEmpty() || incoming.title.isNullOrBlank())
            return CaptureResult(reason = "本屏没有可保存的会话内容")
        val snapshot = incoming.copy(messages = incoming.messages.map {
            it.copy(visibleTime = MessageTimeEvidence.freezeRelative(it.visibleTime, observedAt))
        })
        val years = snapshot.messages.mapNotNull { MessageTimeEvidence.parts(it.visibleTime, observedAt).day?.year }.distinct()
        val fingerprint = fingerprint(app, snapshot) + if (years.isEmpty()) "" else ":${years.joinToString(",") }"
        val contentFingerprint = pageContentFingerprint(app, snapshot.messages.map {
            Triple(it.side, it.kind.name, it.text)
        })
        val db = writableDatabase
        db.beginTransaction()
        return try {
            val alreadySaved = db.rawQuery("""SELECT observed_at FROM capture
                WHERE contact_id=? AND fingerprint=? LIMIT 1""",
                arrayOf(contactId, fingerprint)).use { if (it.moveToFirst()) it.getLong(0) else null }
            val weakPage = snapshot.messages.none { ConversationOverlap.distinctive(it.text) } &&
                snapshot.messages.none { MessageTimeEvidence.parts(it.visibleTime, observedAt).day != null }
            if (alreadySaved != null && snapshot.messages.none {
                    MessageTimeEvidence.parts(it.visibleTime, observedAt).day != null }) {
                db.setTransactionSuccessful()
                return if (weakPage && kotlin.math.abs(observedAt - alreadySaved) > 120_000)
                    CaptureResult(reason = "只有重复短句且日期不明 请展开完整时间后重试 本屏未保存", needsReview = true)
                else CaptureResult()
            }
            val samePage = sameSavedPage(db, contactId, app, contentFingerprint, snapshot, observedAt, weakPage)
            if (samePage != null) {
                db.setTransactionSuccessful()
                return samePage
            }
            val keys = snapshot.messages.map { m ->
                messageKey(m.side, m.kind.name, m.text, m.visibleTime)
            }
            val candidates = matchingPages(db, contactId, app, snapshot, observedAt)
            val matched = ConversationOverlap.match(snapshot.messages, candidates, referenceAt = observedAt)
            val coverage = ConversationOverlap.representedPositions(snapshot.messages, candidates, observedAt, matched)
            if ((coverage.conflictingPositions - matched.keys).isNotEmpty()) {
                db.setTransactionSuccessful()
                return CaptureResult(reason = "相同片段对应不同时间 请展开完整时间后重试 本屏未保存", needsReview = true)
            }
            val represented = matched.keys + coverage.positions
            if (matched.size != keys.size && (represented.size == keys.size ||
                    ConversationOverlap.coveredBySinglePage(snapshot.messages, candidates, observedAt))) {
                db.setTransactionSuccessful()
                return CaptureResult()
            }
            // Some legacy pages contain unresolved positions. Their full raw key
            // sequence still proves an identical sub-window was already observed.
            // This does not assign canonical IDs or fabricate missing timestamps.
            val rawWindowSeen = ConversationOverlap.informativeWindow(snapshot.messages, observedAt) &&
                db.rawQuery("""SELECT 1 FROM capture WHERE contact_id=? AND app=?
                    AND instr(',' || page_keys || ',', ?) > 0 LIMIT 1""",
                    arrayOf(contactId, app, "," + keys.joinToString(",") + ",")).use { it.moveToFirst() }
            var timeUpdated = 0
            for ((position, old) in matched) {
                val message = snapshot.messages[position]
                // Missing labels do not erase known times. Enrich only previously
                // unknown time; never overwrite a user's supplied timestamp.
                if (MessageTimeEvidence.enriches(old.time, message.visibleTime, old.observedAt, observedAt) && old.confidence != "manual") {
                    db.execSQL("UPDATE line SET visible_time=?,time_confidence=? WHERE id=?",
                        arrayOf(message.visibleTime, message.timeConfidence, old.id))
                    timeUpdated++
                }
            }
            if (matched.size == keys.size || rawWindowSeen) { db.setTransactionSuccessful(); return CaptureResult(timeUpdated = timeUpdated) }
            val values = ContentValues().apply {
                put("contact_id", contactId); put("app", app); put("title", snapshot.title)
                put("fingerprint", fingerprint); put("content_fingerprint", contentFingerprint)
                put("page_keys", keys.joinToString(","))
                put("observed_at", observedAt)
                put("historical", if (historical) 1 else 0)
            }
            val captureId = db.insertWithOnConflict("capture", null, values, SQLiteDatabase.CONFLICT_IGNORE)
            if (captureId < 0) { db.setTransactionSuccessful(); CaptureResult(timeUpdated = timeUpdated) }
            else {
                // SQLite may reuse the largest deleted INTEGER PRIMARY KEY.
                // Evidence aliases reserve those IDs permanently until deletion.
                var nextLineId = db.rawQuery("SELECT MAX(n)+1 FROM (SELECT COALESCE(MAX(id),0) n FROM line UNION ALL SELECT COALESCE(MAX(old_id),0) n FROM line_alias)", null)
                    .use { c -> c.moveToFirst(); c.getLong(0) }
                snapshot.messages.forEachIndexed { index, m ->
                    if (index in coverage.positions && index !in matched) return@forEachIndexed
                    val lineId = matched[index]?.id ?: run {
                        val row = ContentValues().apply {
                            put("id", nextLineId++)
                            put("capture_id", captureId); put("ordinal", index)
                            put("side", m.side); put("kind", m.kind.name); put("body", m.text)
                            put("visible_time", m.visibleTime); put("time_confidence", m.timeConfidence)
                            put("content_key", ConversationOverlap.contentKey(m.side, m.kind.name, m.text))
                        }
                        db.insertOrThrow("line", null, row)
                    }
                    insertPageLink(db, captureId, index, lineId)
                }
                db.setTransactionSuccessful()
                CaptureResult(added = keys.size - represented.size, timeUpdated = timeUpdated)
            }
        } finally { db.endTransaction() }
    }

    /** Indexed anchors select candidate pages; do not load the whole contact history. */
    private fun matchingPages(db: SQLiteDatabase, contactId: String, app: String,
                              snapshot: ChatSnapshot, observedAt: Long): List<List<ConversationOverlap.Line?>> {
        val readable = snapshot.messages.filter { ConversationOverlap.readable(it.side, it.kind.name, it.text) }
        val distinctive = readable.filter { ConversationOverlap.distinctive(it.text) }
        // Dated edge messages may belong to a different saved page than the
        // rich body anchors. Include them in the indexed lookup so a split
        // window does not silently reinsert its short but timestamped tail.
        val dated = readable.filter {
            MessageTimeEvidence.parts(it.visibleTime, observedAt).let { time -> time.day != null && time.minute != null }
        }
        val orderedShort = if (ConversationOverlap.structuredShortSequence(snapshot.messages))
            readable.sortedByDescending { it.text.length }.take(4) else emptyList()
        val anchorMessages = distinctive.take(20) + (dated.take(4) + dated.takeLast(4)).distinct() + orderedShort
        val anchors = anchorMessages
            .sortedByDescending { it.text.length }.map { ConversationOverlap.contentKey(it.side, it.kind.name, it.text) }
            .distinct().take(32)
        if (anchors.isEmpty()) return emptyList()
        val marks = anchors.joinToString(",") { "?" }
        val ids = ArrayList<Long>()
        db.rawQuery("""SELECT DISTINCT c.id FROM line l
            JOIN page_line p ON p.line_id=l.id JOIN capture c ON c.id=p.capture_id
            WHERE l.content_key IN ($marks) AND c.contact_id=? AND c.app=?
            ORDER BY c.id DESC""", (anchors + contactId + app).toTypedArray()).use { c ->
            while (c.moveToNext()) ids.add(c.getLong(0))
        }
        if (ids.isEmpty()) return emptyList()
        val pages = LinkedHashMap<Long, MutableList<ConversationOverlap.Line?>>()
        // Stay below SQLite's bind-variable limit even when an old conversation
        // repeats the same distinctive phrase across thousands of pages.
        ids.chunked(400).forEach { batch ->
            db.rawQuery("""SELECT p.capture_id,p.ordinal,l.id,l.side,l.kind,l.body,l.visible_time,l.time_confidence,c.observed_at,c.page_keys,l.ordinal
                FROM page_line p JOIN line l ON l.id=p.line_id
                JOIN capture c ON c.id=l.capture_id
                WHERE p.capture_id IN (${batch.joinToString(",") { "?" }}) ORDER BY p.capture_id,p.ordinal""",
                batch.map { it.toString() }.toTypedArray()).use { c ->
                while (c.moveToNext()) {
                    val page = pages.getOrPut(c.getLong(0)) { ArrayList() }
                    val ordinal = c.getInt(1)
                    while (page.size <= ordinal) page.add(null)
                    val time = if (c.isNull(6)) null else c.getString(6)
                    page[ordinal] = ConversationOverlap.Line(c.getLong(2), c.getString(3), c.getString(4),
                        c.getString(5), time, c.getString(7), timeReference(c.getString(3), c.getString(4),
                            c.getString(5), time, c.getString(9), c.getInt(10), c.getLong(8)))
                }
            }
        }
        return pages.values.toList()
    }

    /**
     * Replaying an identical visible page with its transient time labels missing
     * is still the same observation. Conflicting known timestamps remain separate
     * records so identical text sent on different days is never collapsed.
     */
    private fun sameSavedPage(db: SQLiteDatabase, contactId: String, app: String,
                              contentFingerprint: String, snapshot: ChatSnapshot,
                              observedAt: Long, weakPage: Boolean): CaptureResult? {
        val candidates = ArrayList<Long>()
        db.rawQuery("""SELECT id FROM capture WHERE contact_id=? AND app=? AND content_fingerprint=?
            ORDER BY id DESC LIMIT 32""", arrayOf(contactId, app, contentFingerprint)).use { c ->
            while (c.moveToNext()) candidates.add(c.getLong(0))
        }
        for (captureId in candidates) {
            val old = ArrayList<ConversationOverlap.Line>()
            db.rawQuery("""SELECT l.id,l.side,l.kind,l.body,l.visible_time,l.time_confidence,c.observed_at,c.page_keys,l.ordinal
                FROM page_line p JOIN line l ON l.id=p.line_id
                JOIN capture c ON c.id=l.capture_id
                WHERE p.capture_id=? ORDER BY p.ordinal""", arrayOf(captureId.toString())).use { c ->
                while (c.moveToNext()) {
                    val time = if (c.isNull(4)) null else c.getString(4)
                    old.add(ConversationOverlap.Line(c.getLong(0), c.getString(1), c.getString(2), c.getString(3),
                        time, c.getString(5), timeReference(c.getString(1), c.getString(2), c.getString(3),
                            time, c.getString(7), c.getInt(8), c.getLong(6))))
                }
            }
            if (old.size != snapshot.messages.size) continue
            val sameText = snapshot.messages.indices.all { index ->
                val now = snapshot.messages[index]
                val was = old[index]
                now.side == was.side && now.kind.name == was.kind && now.text == was.body
            }
            if (!sameText) continue
            val knownTimeConflict = snapshot.messages.indices.any { index ->
                val now = snapshot.messages[index].visibleTime?.takeIf(String::isNotBlank)
                val was = old[index].time?.takeIf(String::isNotBlank)
                MessageTimeEvidence.conflicts(now, was, observedAt, old[index].observedAt)
            }
            if (knownTimeConflict) continue
            // A new dated short greeting cannot establish that an old undated
            // greeting was the same occurrence. Retain the new occurrence.
            if (snapshot.messages.none { ConversationOverlap.distinctive(it.text) } &&
                snapshot.messages.any { MessageTimeEvidence.parts(it.visibleTime, observedAt).day != null } &&
                old.none { MessageTimeEvidence.parts(it.time, it.observedAt).day != null } &&
                old.any { kotlin.math.abs(observedAt - it.observedAt) > 120_000 }) continue
            if (weakPage && old.any { kotlin.math.abs(observedAt - it.observedAt) > 120_000 })
                return CaptureResult(reason = "重复短句的日期无法核实 请展开完整时间后重试 本屏未保存", needsReview = true)
            var timeUpdated = 0
            snapshot.messages.forEachIndexed { index, now ->
                val was = old[index]
                if (MessageTimeEvidence.enriches(was.time, now.visibleTime, was.observedAt, observedAt) && was.confidence != "manual") {
                    db.execSQL("UPDATE line SET visible_time=?,time_confidence=? WHERE id=?",
                        arrayOf(now.visibleTime, now.timeConfidence, was.id))
                    timeUpdated++
                }
            }
            return CaptureResult(timeUpdated = timeUpdated)
        }
        return null
    }

    /** Old releases could enrich a relative label without retaining its observation day. */
    private fun timeReference(side: String, kind: String, body: String, time: String?,
                              originalKeys: String, ordinal: Int, observedAt: Long): Long =
        if (time != null && Regex("今天|昨天|前天").containsMatchIn(time) &&
            originalKeys.split(',').getOrNull(ordinal) != messageKey(side, kind, body, time)) 0
        else observedAt

    private fun interpretedTime(side: String, kind: String, body: String, time: String?,
                                originalKeys: String, ordinal: Int, observedAt: Long): String? {
        if (time == null || !Regex("今天|昨天|前天").containsMatchIn(time)) return time
        val reference = timeReference(side, kind, body, time, originalKeys, ordinal, observedAt)
        return if (reference > 0) MessageTimeEvidence.freezeRelative(time, reference)
            else "日期未知 " + time.replace(Regex("今天|昨天|前天"), "").trim()
    }

    /** Recent live pages only. Historical backfill can be searched separately. */
    fun recent(contactId: String, app: String, limit: Int): List<LogEntry> {
        if (limit <= 0) return emptyList()
        val db = readableDatabase
        val ids = ArrayList<Long>()
        db.rawQuery("SELECT id FROM capture WHERE contact_id=? AND app=? AND historical=0 ORDER BY id DESC LIMIT 12",
            arrayOf(contactId, app)).use { c -> while (c.moveToNext()) ids.add(c.getLong(0)) }
        val merged = ArrayList<LogEntry>()
        for (id in ids.asReversed()) {
            val page = ArrayList<LogEntry>()
            db.rawQuery("""SELECT l.side,l.kind,l.body,l.visible_time,l.time_confidence,
                c.observed_at,c.app,c.page_keys,l.ordinal FROM line l JOIN capture c ON c.id=l.capture_id
                WHERE l.capture_id=? ORDER BY l.ordinal""", arrayOf(id.toString())).use { c ->
                while (c.moveToNext()) page.add(LogEntry(
                    c.getString(0), c.getString(2), c.getLong(5), c.getString(6),
                    c.getString(1), interpretedTime(c.getString(0), c.getString(1), c.getString(2),
                        if (c.isNull(3)) null else c.getString(3), c.getString(7), c.getInt(8), c.getLong(5)), c.getString(4)))
            }
            val overlap = longestOverlap(merged, page)
            merged.addAll(page.drop(overlap))
        }
        return merged.takeLast(limit)
    }

    /** Bounded invitation evidence including backfill; never treats capture order as send time. */
    fun invitationHistory(contactId: String, app: String): List<LogEntry> {
        val rows = ArrayList<LogEntry>()
        readableDatabase.rawQuery("""SELECT l.side,l.kind,l.body,l.visible_time,l.time_confidence,
            c.observed_at,c.app,c.historical,c.page_keys,l.ordinal FROM capture c JOIN line l ON l.capture_id=c.id
            WHERE c.contact_id=? AND c.app=? AND l.kind IN ('TEXT','VOICE_TRANSCRIPT')
            ORDER BY c.id DESC,l.ordinal DESC LIMIT 500""", arrayOf(contactId, app)).use { c ->
            while (c.moveToNext()) rows.add(LogEntry(c.getString(0), c.getString(2), c.getLong(5),
                c.getString(6), c.getString(1), interpretedTime(c.getString(0), c.getString(1), c.getString(2),
                    if (c.isNull(3)) null else c.getString(3), c.getString(8), c.getInt(9), c.getLong(5)),
                c.getString(4), c.getInt(7) != 0))
        }
        val terms = Regex("见面|约|周末|有空|不方便|没时间|下次|再说|太快|尴尬|河边|公园|咖啡|看展|散步|加班")
        val selected = linkedSetOf<Int>()
        rows.forEachIndexed { i, row ->
            if (selected.size < 20 && terms.containsMatchIn(row.text)) {
                if (i > 0) selected.add(i - 1)
                selected.add(i)
                if (i + 1 < rows.size) selected.add(i + 1)
            }
        }
        // Also include the tail so a new boundary can override an older optimistic plan.
        (0 until minOf(6, rows.size)).forEach { selected.add(it) }
        return selected.sortedDescending().map { rows[it] }.takeLast(24)
    }

    /** Bounded and app/contact scoped; does not mutate history or send another request. */
    fun hasRecentStyleComplaint(contactId: String, app: String): Boolean =
        readableDatabase.rawQuery("""SELECT side,body FROM (
            SELECT l.side,l.body FROM capture c JOIN line l ON l.capture_id=c.id
            WHERE c.contact_id=? AND c.app=? AND l.kind IN ('TEXT','VOICE_TRANSCRIPT')
            ORDER BY c.id DESC,l.ordinal DESC LIMIT 500)
            WHERE side='other'""", arrayOf(contactId, app)).use { c ->
            var found = false
            while (c.moveToNext() && !found)
                found = com.jev.probe.core.ReplyVoicePolicy.isStyleComplaint(c.getString(1))
            found
        }

    /** Read a bounded sample of this person's own messages, including backfill; no extra model call. */
    fun ownExpressions(contactId: String, app: String): List<String> {
        data class Sample(val text: String, val day: Long, val minute: Int, val order: Long)
        val out = ArrayList<Sample>()
        val structured = Regex("https?://|(?:姓名|电话|地址)[:：]|\\d{7,}")
        readableDatabase.rawQuery("""SELECT l.body,l.visible_time,c.observed_at,c.historical,l.id FROM capture c JOIN line l ON l.capture_id=c.id
            WHERE c.contact_id=? AND c.app=? AND l.side='me' AND l.kind IN ('TEXT','VOICE_TRANSCRIPT')
              AND length(l.body) BETWEEN 3 AND 100
            ORDER BY c.id DESC,l.ordinal DESC LIMIT 2500""", arrayOf(contactId, app)).use { c ->
            while (c.moveToNext()) {
                if (structured.containsMatchIn(c.getString(0))) continue
                val evidence = MessageTimeEvidence.parts(if (c.isNull(1)) null else c.getString(1), c.getLong(2))
                // Observation day only ranks live samples; it is never a claimed send date.
                val day = evidence.day?.toEpochDay() ?: if (c.getInt(3) == 0)
                    java.time.Instant.ofEpochMilli(c.getLong(2)).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toEpochDay()
                    else Long.MIN_VALUE
                out.add(Sample(c.getString(0), day, evidence.minute ?: -1, c.getLong(4)))
            }
        }
        val sorted = out.sortedWith(compareByDescending<Sample> { it.day }.thenByDescending { it.minute }
            .thenByDescending { it.order }).distinctBy { it.text }
        val buckets = HashMap<Pair<Long, Int>, Int>()
        val varied = sorted.filter { sample ->
            val bucket = sample.day to (sample.minute / 360)
            val count = buckets[bucket] ?: 0
            if (sample.text.length < 8 || count >= 2) false
            else { buckets[bucket] = count + 1; true }
        }
        return (varied + sorted).map { it.text }.distinct().take(8)
    }

    /** Bounded older-page search; never scans the whole growing ledger. */
    fun relevant(contactId: String, app: String, snapshot: ChatSnapshot, limit: Int): List<LogEntry> {
        if (limit <= 0) return emptyList()
        val query = grams(snapshot.messages.takeLast(4).joinToString(" ") { it.text })
        if (query.isEmpty()) return emptyList()
        val hits = ArrayList<Pair<Int, LogEntry>>()
        readableDatabase.rawQuery("""SELECT l.side,l.kind,l.body,l.visible_time,l.time_confidence,
            c.observed_at,c.app,c.page_keys,l.ordinal FROM line l JOIN capture c ON c.id=l.capture_id
            WHERE c.contact_id=? AND c.app=? AND c.historical=1
            ORDER BY c.id DESC,l.ordinal DESC LIMIT 2000""", arrayOf(contactId, app)).use { c ->
            while (c.moveToNext()) {
                val body = c.getString(2)
                val score = grams(body).count { it in query }
                if (score > 0) hits.add(score to LogEntry(
                    c.getString(0), body, c.getLong(5), c.getString(6),
                    c.getString(1), interpretedTime(c.getString(0), c.getString(1), body,
                        if (c.isNull(3)) null else c.getString(3), c.getString(7), c.getInt(8), c.getLong(5)), c.getString(4), true))
            }
        }
        return hits.sortedWith(compareByDescending<Pair<Int, LogEntry>> { it.first }
            .thenByDescending { it.second.ts })
            .distinctBy { (_, entry) -> listOf(entry.side, entry.kind, entry.text, entry.visibleTime) }
            .take(limit).map { it.second }.sortedBy { it.ts }
    }

    fun lineCount(contactId: String): Int = readableDatabase.rawQuery(
        "SELECT count(*) FROM line WHERE capture_id IN (SELECT id FROM capture WHERE contact_id=?)",
        arrayOf(contactId)).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    /** One aggregate for the contact list; never fetch message bodies just to sort. */
    fun lineCounts(): Map<String, Int> = readableDatabase.rawQuery(
        "SELECT c.contact_id,COUNT(*) FROM capture c JOIN line l ON l.capture_id=c.id GROUP BY c.contact_id",
        null).use { cursor -> buildMap {
            while (cursor.moveToNext()) put(cursor.getString(0), cursor.getInt(1))
        } }

    /** Full, deduplicated history for an explicit profile-generation action. */
    fun relationshipSource(contactId: String, app: String): RelationshipSource {
        val db = readableDatabase
        val pages = db.rawQuery(
            "SELECT count(DISTINCT id) FROM capture WHERE contact_id=? AND app=?",
            arrayOf(contactId, app)).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        val observedRange = db.rawQuery(
            "SELECT COALESCE(MIN(observed_at),0),COALESCE(MAX(observed_at),0) FROM capture WHERE contact_id=? AND app=?",
            arrayOf(contactId, app)).use { c -> if (c.moveToFirst()) c.getLong(0) to c.getLong(1) else 0L to 0L }
        val lines = ArrayList<ProfileLine>()
        db.rawQuery("""SELECT l.id,l.side,l.body,c.observed_at,c.app,l.kind,l.visible_time,
                l.time_confidence,c.historical,c.page_keys,l.ordinal
            FROM line l JOIN capture c ON c.id=l.capture_id
            WHERE c.contact_id=? AND c.app=? ORDER BY c.id ASC,l.ordinal ASC""",
            arrayOf(contactId, app)).use { c ->
            while (c.moveToNext()) lines.add(ProfileLine(
                ref = "db:${c.getLong(0)}",
                side = c.getString(1),
                text = c.getString(2),
                observedAt = c.getLong(3),
                app = c.getString(4),
                kind = c.getString(5),
                visibleTime = interpretedTime(c.getString(1), c.getString(5), c.getString(2),
                    if (c.isNull(6)) null else c.getString(6), c.getString(9), c.getInt(10), c.getLong(3)),
                timeConfidence = c.getString(7),
                historical = c.getInt(8) != 0
            ))
        }
        return RelationshipSource(app, pages, lines, observedRange.first, observedRange.second)
    }

    fun latestObservedAt(contactId: String, app: String): Long = readableDatabase.rawQuery(
        "SELECT COALESCE(MAX(observed_at),0) FROM capture WHERE contact_id=? AND app=?",
        arrayOf(contactId, app)).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }

    /** Resolve only model-cited rows; raw history stays on-device unless generation is requested. */
    fun evidenceLines(contactId: String, refs: Set<String>): List<ProfileLine> {
        val ids = refs.mapNotNull { it.removePrefix("db:").toLongOrNull() }.distinct()
        if (ids.isEmpty()) return emptyList()
        val rows = ArrayList<ProfileLine>()
        readableDatabase.rawQuery("""SELECT l.id,l.side,l.body,c.observed_at,c.app,l.kind,l.visible_time,
                l.time_confidence,c.historical
            FROM line l JOIN capture c ON c.id=l.capture_id
            WHERE c.contact_id=? AND (l.id IN (${ids.joinToString(",") { "?" }}) OR
                l.id IN (SELECT line_id FROM line_alias WHERE old_id IN (${ids.joinToString(",") { "?" }})))""",
            arrayOf(contactId, *ids.map { it.toString() }.toTypedArray(), *ids.map { it.toString() }.toTypedArray())).use { c ->
            while (c.moveToNext()) rows.add(ProfileLine(
                "db:${c.getLong(0)}", c.getString(1), c.getString(2), c.getLong(3), c.getString(4),
                c.getString(5), if (c.isNull(6)) null else c.getString(6), c.getString(7), c.getInt(8) != 0
            ))
        }
        val aliases = HashMap<Long, Long>()
        readableDatabase.rawQuery("SELECT old_id,line_id FROM line_alias WHERE old_id IN (${ids.joinToString(",") { "?" }})",
            ids.map { it.toString() }.toTypedArray()).use { c -> while (c.moveToNext()) aliases[c.getLong(0)] = c.getLong(1) }
        return ids.mapNotNull { id -> rows.firstOrNull { it.ref == "db:${aliases[id] ?: id}" }?.copy(ref = "db:$id") }
    }

    fun stats(contactId: String): CaptureStats = readableDatabase.rawQuery("""
        SELECT count(DISTINCT c.id), count(l.id) FROM capture c
        JOIN line l ON l.capture_id=c.id WHERE c.contact_id=?""",
        arrayOf(contactId)).use { c ->
        if (c.moveToFirst()) CaptureStats(c.getInt(0), c.getInt(1)) else CaptureStats(0, 0)
    }

    fun groupedCount(contactId: String): Int = readableDatabase.rawQuery("""
        SELECT count(*) FROM (SELECT 1 FROM line l JOIN capture c ON c.id=l.capture_id
        WHERE c.contact_id=? GROUP BY l.side,l.kind,l.body,IFNULL(l.visible_time,''),l.time_confidence)""",
        arrayOf(contactId)).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    /** Newest observed first. This is capture order, never claimed to be send order. */
    fun items(contactId: String, grouped: Boolean, limit: Int, offset: Int): List<MemoryItem> {
        val sql = if (grouped) """
            SELECT MAX(l.id),l.side,l.kind,l.body,l.visible_time,l.time_confidence,
                MAX(c.observed_at),MAX(c.historical),count(*)
            FROM line l JOIN capture c ON c.id=l.capture_id WHERE c.contact_id=?
            GROUP BY l.side,l.kind,l.body,IFNULL(l.visible_time,''),l.time_confidence
            ORDER BY MAX(c.id) DESC,MAX(l.ordinal) DESC LIMIT ? OFFSET ?"""
        else """
            SELECT l.id,l.side,l.kind,l.body,l.visible_time,l.time_confidence,
                c.observed_at,c.historical,1
            FROM line l JOIN capture c ON c.id=l.capture_id WHERE c.contact_id=?
            ORDER BY c.id DESC,l.ordinal DESC LIMIT ? OFFSET ?"""
        val out = ArrayList<MemoryItem>()
        readableDatabase.rawQuery(sql, arrayOf(contactId, limit.coerceIn(1, 50).toString(),
            offset.coerceAtLeast(0).toString())).use { c ->
            while (c.moveToNext()) out.add(MemoryItem(
                c.getLong(0), c.getString(1), c.getString(2), c.getString(3),
                if (c.isNull(4)) null else c.getString(4), c.getString(5),
                c.getLong(6), c.getInt(7) != 0, c.getInt(8)))
        }
        return out
    }

    /** A grouped edit changes every exact copy from overlapping captures of this contact. */
    fun updateItem(contactId: String, itemId: Long, grouped: Boolean, side: String,
                   kind: String, body: String, visibleTime: String?, confidence: String): Int {
        val old = itemById(contactId, itemId) ?: return 0
        val values = ContentValues().apply {
            put("side", side); put("kind", kind); put("body", body)
            put("visible_time", visibleTime); put("time_confidence", confidence)
            put("content_key", ConversationOverlap.contentKey(side, kind, body))
        }
        val (where, args) = itemSelection(contactId, itemId, grouped, old)
        return writableDatabase.update("line", values, where, args)
    }

    fun deleteItem(contactId: String, itemId: Long, grouped: Boolean): Int {
        val old = itemById(contactId, itemId) ?: return 0
        val (where, args) = itemSelection(contactId, itemId, grouped, old)
        val db = writableDatabase
        db.beginTransaction()
        return try {
            db.execSQL("DELETE FROM line_alias WHERE line_id IN (SELECT id FROM line WHERE $where)", args)
            db.execSQL("DELETE FROM page_line WHERE line_id IN (SELECT id FROM line WHERE $where)", args)
            val deleted = db.delete("line", where, args)
            db.setTransactionSuccessful()
            deleted
        } finally { db.endTransaction() }
    }

    private fun itemById(contactId: String, id: Long): MemoryItem? = readableDatabase.rawQuery("""
        SELECT l.id,l.side,l.kind,l.body,l.visible_time,l.time_confidence,
            c.observed_at,c.historical,1 FROM line l JOIN capture c ON c.id=l.capture_id
        WHERE c.contact_id=? AND l.id=?""", arrayOf(contactId, id.toString())).use { c ->
        if (!c.moveToFirst()) null else MemoryItem(c.getLong(0), c.getString(1), c.getString(2),
            c.getString(3), if (c.isNull(4)) null else c.getString(4), c.getString(5),
            c.getLong(6), c.getInt(7) != 0, 1)
    }

    private fun itemSelection(contactId: String, id: Long, grouped: Boolean,
                              old: MemoryItem): Pair<String, Array<String>> = if (grouped) {
        "capture_id IN (SELECT id FROM capture WHERE contact_id=?) AND side=? AND kind=? " +
            "AND body=? AND IFNULL(visible_time,'')=? AND time_confidence=?" to
            arrayOf(contactId, old.side, old.kind, old.body, old.visibleTime.orEmpty(), old.timeConfidence)
    } else {
        "id=? AND capture_id IN (SELECT id FROM capture WHERE contact_id=?)" to
            arrayOf(id.toString(), contactId)
    }

    fun totalLineCount(): Int = readableDatabase.rawQuery("SELECT count(*) FROM line", null)
        .use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    fun clearContact(contactId: String) {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.execSQL("DELETE FROM line_alias WHERE line_id IN (SELECT id FROM line WHERE capture_id IN (SELECT id FROM capture WHERE contact_id=?))", arrayOf(contactId))
            writableDatabase.execSQL("DELETE FROM page_line WHERE capture_id IN (SELECT id FROM capture WHERE contact_id=?)",
                arrayOf(contactId))
            writableDatabase.execSQL("DELETE FROM line WHERE capture_id IN (SELECT id FROM capture WHERE contact_id=?)",
                arrayOf(contactId))
            writableDatabase.delete("capture", "contact_id=?", arrayOf(contactId))
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
    }

    fun clearAll() {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete("line_alias", null, null)
            writableDatabase.delete("page_line", null, null)
            writableDatabase.delete("line", null, null)
            writableDatabase.delete("capture", null, null)
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
    }

    private fun fingerprint(app: String, snapshot: ChatSnapshot): String {
        val bytes = buildString {
            append(app).append('\u0000')
            snapshot.messages.forEach { m ->
                append(m.side.length).append(':').append(m.side)
                append(m.kind.name.length).append(':').append(m.kind.name)
                append(m.text.length).append(':').append(m.text)
                append(m.visibleTime.orEmpty().length).append(':').append(m.visibleTime.orEmpty())
            }
        }.toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun pageContentFingerprint(app: String, messages: List<Triple<String, String, String>>): String {
        val bytes = buildString {
            append(app).append('\u0000')
            messages.forEach { (side, kind, body) ->
                append(side.length).append(':').append(side)
                append(kind.length).append(':').append(kind)
                append(body.length).append(':').append(body)
            }
        }.toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun messageKey(side: String, kind: String, body: String, time: String?): String {
        val encoded = buildString {
            for (part in listOf(side, kind, body, time.orEmpty()))
                append(part.length).append(':').append(part)
        }.toByteArray(Charsets.UTF_8)
        val hash = MessageDigest.getInstance("SHA-256").digest(encoded)
            .take(8).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return body.length.coerceAtMost(0xffff).toString(16).padStart(4, '0') + hash
    }

    companion object {
        private val GRAM_SEPARATORS = Regex("[\\s\\p{Punct}，。？！、；：‘’“”]+")
        private val GRAM_IGNORE = setOf("哈哈", "好的", "是的", "这个", "那个", "我们", "你们")
        @Volatile private var instance: ConversationLedger? = null
        fun get(context: Context): ConversationLedger = instance ?: synchronized(this) {
            instance ?: ConversationLedger(context).also { instance = it }
        }

        /** Do not fold two identical short messages unless their page positions overlap. */
        internal fun longestOverlap(old: List<LogEntry>, page: List<LogEntry>): Int {
            for (n in minOf(old.size, page.size) downTo 1) {
                if ((0 until n).all { i ->
                    val a = old[old.size - n + i]; val b = page[i]
                    a.side == b.side && a.kind == b.kind && a.text == b.text &&
                        a.visibleTime == b.visibleTime
                }) return n
            }
            return 0
        }

        /** Match screen sequences, never bare text equality: repeated messages can be real. */
        internal fun coveredByEarlierPages(page: List<String>, previous: List<List<String>>): Set<Int> {
            val covered = HashSet<Int>()
            for (old in previous) for (i in page.indices) for (j in old.indices) {
                var n = 0
                while (i + n < page.size && j + n < old.size && page[i + n] == old[j + n]) n++
                if (n < 2) continue
                val distinctive = (i until i + n).any { page[it].take(4).toInt(16) >= 6 } ||
                    (n >= 3 && page.subList(i, i + n).toSet().size >= 2)
                if (distinctive) for (index in i until i + n) covered.add(index)
            }
            return covered
        }

        /** Character pairs work for Chinese without a tokenizer; trivial fillers are ignored. */
        internal fun grams(text: String): Set<String> {
            val compact = text.lowercase().replace(GRAM_SEPARATORS, "")
            if (compact.length < 2) return emptySet()
            return (0 until compact.length - 1).map { compact.substring(it, it + 2) }
                .filterNot { it in GRAM_IGNORE }.toSet()
        }
    }
}
