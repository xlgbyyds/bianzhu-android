package com.jev.probe.core.kb

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.MessageKind
import com.jev.probe.core.Msg
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConversationLedgerDatabaseTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var ledger: ConversationLedger
    private val name = "conversation-memory.db"
    private val app = "cn.soulapp.android"
    private fun msg(side: String, text: String, time: String? = null) =
        Msg(side, text, MessageKind.TEXT, time, if (time == null) "unknown" else "partial")
    private val original = listOf(msg("me", "较早的一句独特开场白"), msg("me", "你在长沙不"),
        msg("other", "在的"), msg("me", "这是一条跨过了屏幕中线的较长消息", "14:33"))
    private fun save(messages: List<Msg>, contact: String = "contact", pkg: String = app) =
        ledger.record(contact, pkg, ChatSnapshot("同一个名字", messages), true)

    @Before fun start() { context.deleteDatabase(name); ledger = ConversationLedger(context) }
    @After fun stop() { ledger.close(); context.deleteDatabase(name) }

    @Test fun oldAndNewAlgorithmsComparedOnTheSameTimeLossExample() {
        val scrolled = original.drop(1).map { it.copy(visibleTime = null, timeConfidence = "unknown") }
        val oldCovered = ConversationLedger.coveredByEarlierPages(scrolled.map(::oldKey), listOf(original.map(::oldKey)))
        val oldCount = original.size + scrolled.size - oldCovered.size
        assertEquals(7, oldCount)
        assertTrue(save(original)); assertFalse(save(scrolled))
        assertEquals(4, ledger.lineCount("contact"))
        assertEquals("14:33", ledger.items("contact", false, 50, 0).first { it.body == original.last().text }.visibleTime)
        println("TIME_LABEL_LOSS old=$oldCount new=${ledger.lineCount("contact")} expected=4")
    }

    @Test fun overlappingPagesStoreOnlyNewMessagesAndSurviveReopen() {
        save(original)
        val shifted = original.drop(1).map { it.copy(visibleTime = null) } + msg("other", "真正新的一句消息")
        assertTrue(save(shifted)); assertEquals(5, ledger.lineCount("contact"))
        ledger.close(); ledger = ConversationLedger(context)
        assertFalse(save(shifted)); assertEquals(5, ledger.lineCount("contact"))
        assertFalse(save(original.drop(1).map { it.copy(visibleTime = null) }))
        assertEquals(5, ledger.lineCount("contact"))
    }

    @Test fun shortButDistinctiveAdjacentPairDoesNotGrowAcrossSplitWindows() {
        val pair = listOf(msg("me", "这句话就有点意思了"), msg("me", "那可不嘛"))
        save(pair + msg("other", "哈哈哈"))
        val previous = listOf(msg("other", "?"), msg("other", "怎么讲"))
        save(previous.map { it.copy(visibleTime = "10月12日 上午9:14") })
        val next = previous + pair
        assertEquals(2, ledger.recordDetailed("contact", app, ChatSnapshot("同一个名字", next), true).added)
        assertEquals(7, ledger.lineCount("contact"))
        assertFalse(save(next))
    }

    @Test fun datedAdjacentLegacyCopiesDoNotRequireChoosingTheirCanonicalId() {
        val morning = listOf(msg("other", "早上好呀", "10月12日 上午9:10"),
            msg("other", "?", "10月12日 上午9:14"), msg("other", "啥意思", "10月12日 上午9:14"))
        save(morning); seedDuplicate(morning)
        val evening = original.mapIndexed { i, m -> m.copy(visibleTime = if (i < 3) "10月12日 凌晨1:33" else "10月12日 上午8:56") }
        save(evening)
        assertEquals(0, ledger.recordDetailed("contact", app, ChatSnapshot("同一个名字", evening + morning.first()), true).added)
        assertEquals(10, ledger.lineCount("contact"))
    }

    @Test fun longOrderedShortConversationDeduplicatesWithoutMergingNewDatedOccurrences() {
        val short = listOf(msg("other", "这局不玩了"), msg("other", "所以"), msg("other", "你还在吗"),
            msg("me", "马上来"), msg("other", "嗯嗯"), msg("me", "逆风局"))
        save(short.map { it.copy(visibleTime = "10月11日 下午5:43") })
        assertFalse(save(short))
        assertEquals(6, ledger.lineCount("contact"))
        assertTrue(save(short.map { it.copy(visibleTime = "10月12日 下午5:43") }))
        assertEquals(12, ledger.lineCount("contact"))
        val unclear = ledger.recordDetailed("contact", app, ChatSnapshot("同一个名字", short + msg("other", "新来的消息")), true)
        assertTrue(unclear.needsReview)
        assertEquals(12, ledger.lineCount("contact"))
    }

    @Test fun severalShortMessagesWithTheSameFullDateAndClockNeedNoNewCopies() {
        val short = listOf(msg("other", "你干嘛"), msg("me", "没干嘛啊"), msg("me", "你咋挂了"), msg("other", "那你挂了"))
            .map { it.copy(visibleTime = "10月11日 晚上8:22") }
        save(short); seedDuplicate(short)
        assertFalse(save(short.drop(1)))
        assertEquals(8, ledger.lineCount("contact"))
        assertTrue(save(short.map { it.copy(visibleTime = "10月12日 晚上8:22") }))
        assertEquals(12, ledger.lineCount("contact"))
    }

    private fun seedDuplicate(page: List<Msg>) {
        val db = ledger.writableDatabase
        db.execSQL("""INSERT INTO capture(contact_id,app,title,fingerprint,content_fingerprint,page_keys,observed_at,historical)
            VALUES(?,?,?,?,?,?,?,?)""", arrayOf("contact", app, "同一个名字", "legacy-copy", "legacy-copy",
            page.joinToString(",") { oldKey(it) }, System.currentTimeMillis(), 1))
        val capture = db.rawQuery("SELECT last_insert_rowid()", null).use { it.moveToFirst(); it.getLong(0) }
        page.forEachIndexed { index, m ->
            db.execSQL("""INSERT INTO line(capture_id,ordinal,side,kind,body,visible_time,time_confidence,content_key)
                VALUES(?,?,?,?,?,?,?,?)""", arrayOf(capture, index, m.side, m.kind.name, m.text, m.visibleTime,
                m.timeConfidence, ConversationOverlap.contentKey(m.side, m.kind.name, m.text)))
            val id = db.rawQuery("SELECT last_insert_rowid()", null).use { it.moveToFirst(); it.getLong(0) }
            db.execSQL("INSERT INTO page_line VALUES(?,?,?)", arrayOf(capture, index, id))
        }
    }

    @Test fun ambiguousLegacyCopiesDoNotGrowWhenAWindowIncludesANewTail() {
        val dated = original.map { it.copy(visibleTime = "10月11日 晚上8:11") }
        save(original.map { it.copy(visibleTime = "晚上8:11") })
        seedDuplicate(dated)
        val incoming = original.map { it.copy(visibleTime = null) } + msg("other", "只有这一句是真正首次出现的新消息")
        val added = ledger.recordDetailed("contact", app, ChatSnapshot("同一个名字", incoming), true)
        assertEquals(1, added.added)
        assertEquals(9, ledger.lineCount("contact"))
        assertFalse(save(incoming.drop(1)))
        ledger.close(); ledger = ConversationLedger(context)
        assertFalse(save(incoming))
        assertFalse(save(incoming.drop(1)))
        assertEquals(9, ledger.lineCount("contact"))
    }

    @Test fun ambiguousCopiesFromDifferentDaysRequireTimeInsteadOfDroppingOrDuplicating() {
        save(original.map { it.copy(visibleTime = "10月10日 晚上8:11") })
        seedDuplicate(original.map { it.copy(visibleTime = "10月11日 晚上8:11") })
        val incoming = original.map { it.copy(visibleTime = null) } + msg("other", "这一句无法排除属于其中另一天的可能")
        val result = ledger.recordDetailed("contact", app, ChatSnapshot("同一个名字", incoming), true)
        assertTrue(result.needsReview)
        assertFalse(result.saved)
        assertEquals(8, ledger.lineCount("contact"))
        val clarified = incoming.map { it.copy(visibleTime = "10月11日 晚上8:11") }
        assertEquals(1, ledger.recordDetailed("contact", app, ChatSnapshot("同一个名字", clarified), true).added)
        assertEquals(9, ledger.lineCount("contact"))
    }

    @Test fun bulkCountsMatchIndividualCountsAcrossAppsAndAfterClearingOneContact() {
        save(original, "first", app)
        save(original, "first", "com.tencent.mm")
        save(original.take(2), "second", app)
        val counts = ledger.lineCounts()
        assertEquals(mapOf("first" to 8, "second" to 2), counts)
        assertEquals(ledger.lineCount("first"), counts["first"])
        ledger.clearContact("first")
        assertEquals(mapOf("second" to 2), ledger.lineCounts())
    }

    @Test fun newlyVisibleTimeEnrichesOriginalWithoutAnotherBodyOrCapture() {
        save(original.map { it.copy(visibleTime = null, timeConfidence = "unknown") })
        assertFalse(save(original))
        assertEquals(CaptureStats(1, 4), ledger.stats("contact"))
        assertEquals("14:33", ledger.items("contact", false, 50, 0).first { it.body == original.last().text }.visibleTime)
        assertFalse(save(original.map { it.copy(visibleTime = null) }))
        assertEquals("14:33", ledger.items("contact", false, 50, 0).first { it.body == original.last().text }.visibleTime)
    }

    @Test fun sameNameAcrossAppsOrContactsDoesNotMergeAndDifferentDatesStaySeparate() {
        save(original, "first", app)
        save(original, "second", app)
        save(original, "first", "com.tencent.mm")
        assertEquals(8, ledger.lineCount("first")); assertEquals(4, ledger.lineCount("second"))
        val day1 = original.map { it.copy(visibleTime = "9月15日 14:33") }
        val day2 = original.map { it.copy(visibleTime = "9月16日 14:33") }
        save(day1, "dated"); save(day2, "dated")
        assertEquals(8, ledger.lineCount("dated"))
    }

    @Test fun genuineRepeatedSequenceAndCommonShortMessagesAreRetained() {
        val short = listOf(msg("me", "哈哈"), msg("other", "好的"), msg("me", "嗯"))
        save(short)
        save(short + msg("other", "下次聊"))
        assertEquals(7, ledger.lineCount("contact"))
        save(original, "repeat")
        save(original + original, "repeat")
        assertEquals(12, ledger.lineCount("repeat"))
    }

    @Test fun editsAreUsedByMatcherAndManualTimeIsNotOverwritten() {
        save(original)
        val row = ledger.items("contact", false, 50, 0).first { it.body == original.last().text }
        ledger.updateItem("contact", row.id, false, "me", "TEXT", "人工改过的一条很长的聊天文字", "9月15日 14:33", "manual")
        val corrected = original.dropLast(1) + msg("me", "人工改过的一条很长的聊天文字")
        assertFalse(save(corrected)); assertEquals(4, ledger.lineCount("contact"))
        assertEquals("manual", ledger.items("contact", false, 50, 0).first { it.id == row.id }.timeConfidence)
        assertEquals("9月15日 14:33", ledger.items("contact", false, 50, 0).first { it.id == row.id }.visibleTime)
        ledger.deleteItem("contact", row.id, false)
        assertEquals(0, ledger.readableDatabase.rawQuery("SELECT count(*) FROM page_line WHERE line_id=?", arrayOf(row.id.toString())).use { it.moveToFirst(); it.getInt(0) })
    }

    @Test fun versionTwoMigrationKeepsAllBodiesAndConnectsPrunedOverlap() {
        ledger.close(); context.deleteDatabase(name)
        val db = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null)
        db.execSQL("CREATE TABLE capture(id INTEGER PRIMARY KEY,contact_id TEXT,app TEXT,title TEXT,fingerprint TEXT,page_keys TEXT,observed_at INTEGER,historical INTEGER,UNIQUE(contact_id,fingerprint))")
        db.execSQL("CREATE TABLE line(id INTEGER PRIMARY KEY,capture_id INTEGER,ordinal INTEGER,side TEXT,kind TEXT,body TEXT,visible_time TEXT,time_confidence TEXT)")
        val next = original.drop(1) + msg("other", "最后多出来的真正新消息")
        for ((id, page) in listOf(1 to original, 2 to next)) {
            db.execSQL("INSERT INTO capture VALUES(?,?,?,?,?,?,?,?)", arrayOf(id, "contact", app, "同一个名字", "old$id", page.joinToString(",") { oldKey(it) }, 1, 1))
            page.forEachIndexed { index, m ->
                if (id == 1 || index == page.lastIndex) db.execSQL("INSERT INTO line(capture_id,ordinal,side,kind,body,visible_time,time_confidence) VALUES(?,?,?,?,?,?,?)",
                    arrayOf(id, index, m.side, m.kind.name, m.text, m.visibleTime, m.timeConfidence))
            }
        }
        db.version = 2; db.close()
        ledger = ConversationLedger(context)
        assertEquals(5, ledger.lineCount("contact"))
        assertFalse(save(next.map { it.copy(visibleTime = null) }))
        assertEquals(5, ledger.lineCount("contact"))
        assertEquals(5, ledger.readableDatabase.version)
        assertEquals("ok", ledger.readableDatabase.rawQuery("PRAGMA integrity_check", null).use { it.moveToFirst(); it.getString(0) })
    }

    @Test fun versionThreeMigrationBuildsTimeIndependentPageFingerprint() {
        ledger.close(); context.deleteDatabase(name)
        val db = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null)
        db.execSQL("""CREATE TABLE capture(id INTEGER PRIMARY KEY,contact_id TEXT NOT NULL,app TEXT NOT NULL,
            title TEXT NOT NULL,fingerprint TEXT NOT NULL,page_keys TEXT NOT NULL,observed_at INTEGER NOT NULL,
            historical INTEGER NOT NULL,UNIQUE(contact_id,fingerprint))""")
        db.execSQL("""CREATE TABLE line(id INTEGER PRIMARY KEY,capture_id INTEGER NOT NULL,ordinal INTEGER NOT NULL,
            side TEXT NOT NULL,kind TEXT NOT NULL,body TEXT NOT NULL,visible_time TEXT,
            time_confidence TEXT NOT NULL,content_key TEXT NOT NULL DEFAULT '')""")
        db.execSQL("CREATE TABLE page_line(capture_id INTEGER NOT NULL,ordinal INTEGER NOT NULL,line_id INTEGER NOT NULL,PRIMARY KEY(capture_id,ordinal))")
        val page = listOf(msg("other", "周末你说的那家店", "9月15日"), msg("me", "对，我也想再去看看", "9月15日"))
        db.execSQL("INSERT INTO capture VALUES(?,?,?,?,?,?,?,?)",
            arrayOf(1, "v3-contact", app, "name", "old-fingerprint", "", 1, 1))
        page.forEachIndexed { index, item ->
            db.execSQL("INSERT INTO line(id,capture_id,ordinal,side,kind,body,visible_time,time_confidence,content_key) VALUES(?,?,?,?,?,?,?,?,?)",
                arrayOf(index + 1, 1, index, item.side, item.kind.name, item.text, item.visibleTime, item.timeConfidence, ""))
            db.execSQL("INSERT INTO page_line VALUES(?,?,?)", arrayOf(1, index, index + 1))
        }
        db.version = 3
        db.close()
        ledger = ConversationLedger(context)

        assertEquals(5, ledger.readableDatabase.version)
        assertFalse(save(page.map { it.copy(visibleTime = null, timeConfidence = "unknown") }, "v3-contact"))
        assertEquals(2, ledger.lineCount("v3-contact"))
    }

    @Test fun candidateLookupFindsOldPagesAmongManyUnrelatedRecords() {
        save(original)
        val db = ledger.writableDatabase
        db.beginTransaction()
        try {
            repeat(1000) { i ->
                val rows = List(10) { j -> if (j == 0) msg("other", "在的") else
                    msg(if (j % 2 == 0) "me" else "other", "其他聊天片段-$i-第${j}条内容") }
                assertTrue(save(rows))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        val start = System.nanoTime()
        assertFalse(save(original.map { it.copy(visibleTime = null) }))
        val elapsed = (System.nanoTime() - start) / 1_000_000
        assertEquals(10004, ledger.lineCount("contact"))
        println("INDEXED_REPLAY records=10004 elapsed_ms=$elapsed")
    }

    @Test fun rerunningAnOldBackfillDoesNotDuplicatePagesBeyondRecentCandidateWindow() {
        val a = msg("me", "昨天路上看到那家很特别的小店")
        val b = msg("other", "你说的是街角新开的那一家吗")
        val c = msg("me", "对，就是门口摆了好多植物的那家", "9月15日 14:33")
        val originalPage = listOf(a, b, c)
        assertTrue(save(originalPage, "long-history"))

        repeat(1010) { i ->
            // Flood the indexed candidates with the same anchors, but do not
            // repeat the adjacent sequence that identifies the original page.
            val distractor = listOf(
                a,
                msg("other", "无关上下文甲-$i"),
                b,
                msg("me", "无关上下文乙-$i"),
                c,
                msg("other", "无关上下文丙-$i")
            )
            assertTrue(save(distractor, "long-history"))
        }
        val before = ledger.lineCount("long-history")
        val replay = originalPage.map { it.copy(visibleTime = null, timeConfidence = "unknown") }
        assertFalse(save(replay, "long-history"))
        assertEquals(before, ledger.lineCount("long-history"))
        val extended = replay + msg("other", "涓€鏉℃洿鏂扮殑鏈€鏂版秷鎭?")
        assertTrue(save(extended, "long-history"))
        assertEquals(before + 1, ledger.lineCount("long-history"))
    }

    @Test fun replayingSameWholePageWithoutTransientTimesDoesNotAddCopies() {
        val page = listOf(msg("other", "好呀", "9月15日"), msg("me", "那就这么定", "9月15日"))
        assertTrue(save(page, "replay"))
        val replay = page.map { it.copy(visibleTime = null, timeConfidence = "unknown") }
        assertFalse(save(replay, "replay"))
        assertEquals(2, ledger.lineCount("replay"))
    }

    @Test fun identicalTextAtConflictingKnownTimesRemainsSeparate() {
        val first = listOf(msg("other", "我们下次再聊", "9月15日"), msg("me", "好，回头聊", "9月15日"))
        val second = listOf(msg("other", "我们下次再聊", "10月2日"), msg("me", "好，回头聊", "10月2日"))
        assertTrue(save(first, "same-words"))
        assertTrue(save(second, "same-words"))
        assertEquals(4, ledger.lineCount("same-words"))
    }

    @Test fun profileSourceIsIsolatedByContactAndAppAndCarriesCoverage() {
        val wechat = "com.tencent.mm"
        val first = ChatSnapshot("12345", listOf(
            Msg("other", "对方的一条消息", MessageKind.TEXT, "9月15日", "partial")
        ))
        val second = ChatSnapshot("12345", listOf(
            Msg("me", "我的另一条消息", MessageKind.VOICE_TRANSCRIPT, null, "unknown")
        ))
        assertTrue(ledger.record("profile-contact", wechat, first, true, 1_000L))
        assertTrue(ledger.record("profile-contact", wechat, second, true, 2_000L))
        assertTrue(ledger.record("different-contact", wechat, second, true, 3_000L))
        assertTrue(ledger.record("profile-contact", app, first, true, 4_000L))

        val source = ledger.relationshipSource("profile-contact", wechat)
        assertEquals(2, source.pages)
        assertEquals(2, source.lines.size)
        assertEquals(1, source.otherCount)
        assertEquals(1, source.meCount)
        assertEquals(1, source.visibleTimeCount)
        assertEquals(2, source.historicalCount)
        assertEquals(1_000L, source.firstObservedAt)
        assertEquals(2_000L, source.latestObservedAt)
        assertTrue(source.lines.all { it.app == wechat })
        assertEquals(0, ledger.relationshipSource("different-contact", "cn.soulapp.android").lines.size)
    }

    private fun oldKey(m: Msg): String {
        val text = buildString {
            for (part in listOf(m.side, m.kind.name, m.text, m.visibleTime.orEmpty())) append(part.length).append(':').append(part)
        }
        val hash = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .take(8).joinToString("") { "%02x".format(it.toInt() and 255) }
        return m.text.length.toString(16).padStart(4, '0') + hash
    }
}
