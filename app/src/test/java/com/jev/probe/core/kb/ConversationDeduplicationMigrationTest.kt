package com.jev.probe.core.kb

import android.content.Context
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.MessageKind
import com.jev.probe.core.Msg
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConversationDeduplicationMigrationTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var ledger: ConversationLedger
    private val app = "com.tencent.mm"
    private val name = "conversation-memory.db"
    private val page = listOf("每次这个页面都会刷新", "刚才页面刷新了吗", "前面测试过这个步骤", "这个测试步骤暂时先保持原样")
        .mapIndexed { i, text -> Msg(if (i == 0) "me" else "other", text) }

    @Before fun start() { context.deleteDatabase(name); ledger = ConversationLedger(context) }
    @After fun stop() { ledger.close(); context.deleteDatabase(name) }

    private fun seedV4(pages: List<List<Msg>>, at: List<Long> = pages.indices.map { 1000L + it * 1000L },
                       apps: List<String> = pages.map { app }, contacts: List<String> = pages.map { "contact" }) {
        val db = ledger.writableDatabase
        pages.forEachIndexed { i, messages ->
            val capture = i + 1L
            db.execSQL("INSERT INTO capture(id,contact_id,app,title,fingerprint,content_fingerprint,page_keys,observed_at,historical) VALUES(?,?,?,?,?,?,?,?,?)",
                arrayOf(capture, contacts[i], apps[i], "12345", "legacy-$i", "", "", at[i], 1))
            messages.forEachIndexed { j, m ->
                val id = i * 100L + j + 1
                db.execSQL("INSERT INTO line VALUES(?,?,?,?,?,?,?,?,?)", arrayOf(id, capture, j, m.side, m.kind.name,
                    m.text, m.visibleTime, m.timeConfidence, ConversationOverlap.contentKey(m.side, m.kind.name, m.text)))
                db.execSQL("INSERT INTO page_line VALUES(?,?,?)", arrayOf(capture, j, id))
            }
        }
        db.execSQL("DROP TABLE line_alias")
        db.version = 4
        ledger.close(); ledger = ConversationLedger(context)
    }

    @Test fun repairsLegacyDuplicateCascadeAndResolvesOldEvidenceWithoutReusingIds() {
        seedV4(List(13) { page })
        assertEquals(4, ledger.lineCount("contact"))
        assertEquals("这个测试步骤暂时先保持原样", ledger.evidenceLines("contact", setOf("db:1204")).single().text)
        assertFalse(ledger.record("contact", app, ChatSnapshot("12345", page), true))
        val extended = page + Msg("me", "新的一句消息应该只保存一次")
        assertTrue(ledger.record("contact", app, ChatSnapshot("12345", extended), true))
        assertEquals(5, ledger.lineCount("contact"))
        assertTrue(ledger.items("contact", false, 50, 0).maxOf { it.id } > 1204)
        repeat(10) { assertFalse(ledger.record("contact", app, ChatSnapshot("12345", extended.drop(1)), true)) }
        assertEquals(5, ledger.lineCount("contact"))
        ledger.close(); ledger = ConversationLedger(context)
        assertEquals(5, ledger.lineCount("contact"))
        assertEquals(1, ledger.evidenceLines("contact", setOf("db:1204")).size)
    }

    @Test fun repairRetainsDifferentDatesManualRowsOtherAppsAndContacts() {
        val first = page.map { it.copy(visibleTime = "9月15日", timeConfidence = "partial") }
        val second = page.map { it.copy(visibleTime = "9月16日", timeConfidence = "partial") }
        val manual = first.map { it.copy(timeConfidence = "manual") }
        seedV4(listOf(first, second, manual, first, first),
            apps = listOf(app, app, app, "cn.soulapp.android", app),
            contacts = listOf("contact", "contact", "contact", "contact", "other-contact"))
        assertEquals(16, ledger.lineCount("contact"))
        assertEquals(4, ledger.lineCount("other-contact"))
    }

    @Test fun unknownDatesFarApartCommonFillersAndRepeatedWithinOnePageRemainSeparate() {
        seedV4(listOf(page, page, page + page), at = listOf(1000, 200000, 400000))
        assertEquals(16, ledger.lineCount("contact"))
    }

    @Test fun knownTimeEnrichesCanonicalRecordAndDeletedContactRemovesAliases() {
        seedV4(listOf(page, page.map { it.copy(visibleTime = "9月15日 16:00", timeConfidence = "partial") }))
        assertEquals(4, ledger.lineCount("contact"))
        assertTrue(ledger.relationshipSource("contact", app).lines.all { it.visibleTime == "9月15日 16:00" })
        ledger.clearContact("contact")
        assertTrue(ledger.evidenceLines("contact", setOf("db:104")).isEmpty())
        assertEquals(0, ledger.readableDatabase.rawQuery("SELECT count(*) FROM line_alias", null).use { it.moveToFirst(); it.getInt(0) })
    }

    @Test fun auditPrivatePhoneCopyAndReplayShiftedPagesWithoutChangingOriginal() {
        val source = System.getenv("JEV_DEDUP_AUDIT_DB")?.let(::File)
        assumeTrue(source != null && source.isFile)
        ledger.close()
        source!!.copyTo(context.getDatabasePath(name), overwrite = true)
        ledger = ConversationLedger(context)
        val started = System.nanoTime()
        val db = ledger.readableDatabase
        val sourceRows = android.database.sqlite.SQLiteDatabase.openDatabase(source.path, null,
            android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { old ->
            old.rawQuery("SELECT count(*) FROM line l JOIN capture c ON c.id=l.capture_id WHERE c.contact_id='bc21d128-8e1'", null)
                .use { it.moveToFirst(); it.getInt(0) }
        }
        val repaired = ledger.lineCount("bc21d128-8e1")
        assertTrue(repaired < sourceRows)
        val count = db.rawQuery("SELECT count(*) FROM page_line p LEFT JOIN line l ON l.id=p.line_id WHERE l.id IS NULL", null)
            .use { it.moveToFirst(); it.getInt(0) }
        assertEquals(0, count)
        assertEquals("ok", db.rawQuery("PRAGMA quick_check", null).use { it.moveToFirst(); it.getString(0) })
        var trials = 0
        var additions = 0
        val captured = ArrayList<Triple<Long, String, Long>>()
        db.rawQuery("SELECT id,title,observed_at FROM capture WHERE contact_id='bc21d128-8e1' ORDER BY id DESC", null).use { c ->
            while (c.moveToNext()) captured.add(Triple(c.getLong(0), c.getString(1), c.getLong(2)))
        }
        for ((id, title, observedAt) in captured) {
            val messages = ArrayList<Msg>()
            db.rawQuery("SELECT l.side,l.body,l.kind,l.visible_time,l.time_confidence,owner.observed_at FROM page_line p JOIN line l ON l.id=p.line_id JOIN capture owner ON owner.id=l.capture_id WHERE p.capture_id=? ORDER BY p.ordinal",
                arrayOf(id.toString())).use { c -> while (c.moveToNext()) messages.add(Msg(c.getString(0), c.getString(1),
                    MessageKind.valueOf(c.getString(2)), if(c.isNull(3)) null else
                        MessageTimeEvidence.freezeRelative(c.getString(3), c.getLong(5)), c.getString(4))) }
            if (messages.size < 5) continue
            val shifted = listOf(messages.drop(1), messages.dropLast(1))
            for (replay in shifted + shifted.map { window -> window.map { it.copy(visibleTime = null, timeConfidence = "unknown") } }) {
                if (replay.count { ConversationOverlap.distinctive(it.text) } < 2) continue
                val before = ledger.lineCount("bc21d128-8e1")
                // Replay labels at their original observation, including relative
                // dates. Robolectric's default epoch is not the phone's date.
                ledger.record("bc21d128-8e1", app, ChatSnapshot(title, replay), true, observedAt)
                additions += ledger.lineCount("bc21d128-8e1") - before
                trials++
            }
        }
        println("PRIVATE_DEDUP_AUDIT before=$sourceRows after=$repaired removed=${sourceRows-repaired} replayTrials=$trials replayAdded=$additions elapsedMs=${(System.nanoTime()-started)/1000000}")
        assertTrue(trials > 0)
        // This assertion intentionally exposes any remaining growth on the real
        // captured windows; no private text or fixture is committed to the repo.
        assertEquals(0, additions)
    }
}
