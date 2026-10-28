package com.jev.probe.jev

import com.jev.probe.core.kb.ProfileLine
import com.jev.probe.core.kb.RelationshipSource
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProfileCheckpointTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val app = "com.tencent.mm"
    private fun source(count: Int = 1200) = RelationshipSource(app, 20, (1..count).map {
        ProfileLine("db:$it", if (it % 2 == 0) "other" else "me", "测试内容 $it",
            it.toLong(), app, "TEXT", null, "unknown", true)
    }, 1, count.toLong())
    private fun cache(id: String, route: String = "direct-model") =
        FileProfileCheckpoints(context, id, app, route)

    @Test fun changingOfflineFactsReusesHistoryChunksAndOnlyResynthesizesFinalProfile() {
        val prompts = mutableListOf<String>()
        val id = "offline-context-cache"
        fun run(facts: String) = RelationshipProfileGenerator({ _, user, _ -> prompts.add(user); response(user) },
            1, cache(id), userContext = facts).generate(source(300))
        run("此前关系尚未确认")
        prompts.clear()
        run("用户今天明确补充 已线下见面并确立情侣关系")
        assertEquals(1, prompts.size)
        assertTrue(prompts.single().startsWith("任务：将下方分段证据摘要"))
        assertTrue(prompts.single().contains("已线下见面并确立情侣关系"))
        assertTrue(prompts.single().contains("不编造消息引用"))
        prompts.clear()
        run("用户今天明确补充 已线下见面并确立情侣关系")
        assertTrue(prompts.isEmpty())
    }

    /** Distinct summaries preserve the dependency graph when evidence changes. */
    private fun response(user: String): String {
        val refs = Regex("""\[db:\d+]""").findAll(user).map { it.value.trim('[', ']') }.take(4).toList()
        return JSONObject().put("facts", JSONArray().put(JSONObject()
            .put("text", profileDigest(user)).put("sourceRefs", JSONArray(refs))))
            .put("patterns", JSONArray()).put("attitude", JSONObject.NULL).put("themes", JSONArray()).toString()
    }

    @Test fun processRecreationReusesAllTasksAndAppendOnlyRecomputesChangedBranch() {
        val id = "incremental"
        var calls = 0
        val first = source()
        fun run(s: RelationshipSource) = RelationshipProfileGenerator(
            { _, user, _ -> calls++; response(user) }, 1, cache(id)).generate(s)
        run(first)
        val full = calls
        assertTrue(full > 8)
        calls = 0
        val repeated = run(first)
        assertEquals(0, calls)
        assertEquals(first.fingerprint(), repeated.sourceFingerprint)
        val progress = mutableListOf<ProfileGenerationProgress>()
        val added = source(1201)
        RelationshipProfileGenerator({ _, user, _ -> calls++; response(user) }, 1, cache(id))
            .generate(added) { progress += it }
        assertTrue("incremental=$calls full=$full", calls in 2 until full)
        assertTrue(progress.last().phase.contains("本地复用"))
        assertEquals(1201, added.lines.size)
        calls = 0
        run(added)
        assertEquals(0, calls)
    }

    @Test fun failurePersistsCompletedChunksAndOnlyMissingWorkRunsAfterReopen() {
        val id = "resume"
        var attempts = 0
        val successfulPrompts = mutableSetOf<String>()
        val result = runCatching {
            RelationshipProfileGenerator({ _, user, _ ->
                if (++attempts >= 4) throw IllegalStateException("simulated offline")
                successfulPrompts += user
                response(user)
            }, 1, cache(id)).generate(source())
        }
        assertTrue(result.isFailure)
        assertTrue(successfulPrompts.size >= 3)
        val resumed = mutableListOf<String>()
        RelationshipProfileGenerator({ _, user, _ ->
            resumed += user; response(user)
        }, 1, cache(id)).generate(source())
        assertTrue(resumed.isNotEmpty())
        assertTrue(resumed.none { it in successfulPrompts })
    }

    @Test fun finalFailureRetainsAllSummariesAndRetriesOnlyFinal() {
        val id = "final-failure"
        assertTrue(runCatching {
            RelationshipProfileGenerator({ _, user, _ ->
                if (user.startsWith("任务：将下方")) throw IllegalStateException("offline")
                response(user)
            }, 1, cache(id)).generate(source())
        }.isFailure)
        var count = 0
        RelationshipProfileGenerator({ _, user, _ ->
            count++
            assertTrue(user.startsWith("任务：将下方"))
            response(user)
        }, 1, cache(id)).generate(source())
        assertEquals(1, count)
    }

    @Test fun correctionsAndDeletionInvalidateAffectedEvidenceButReuseOtherChunks() {
        val id = "corrections"
        var calls = 0
        fun run(s: RelationshipSource) = RelationshipProfileGenerator(
            { _, user, _ -> calls++; response(user) }, 1, cache(id)).generate(s)
        val original = source()
        run(original)
        val full = calls
        for (variant in listOf(
            original.copy(lines = original.lines.mapIndexed { i, l -> if (i == 14) l.copy(side = "other") else l }),
            original.copy(lines = original.lines.mapIndexed { i, l -> if (i == 14) l.copy(visibleTime = "10月20日", timeConfidence = "exact") else l }),
            original.copy(lines = original.lines.mapIndexed { i, l -> if (i == 14) l.copy(text = "人工纠正正文") else l }),
            original.copy(lines = original.lines.filterIndexed { i, _ -> i != 14 })
        )) {
            calls = 0
            val profile = run(variant)
            assertTrue("changed=$calls full=$full", calls in 2 until full)
            assertNotEquals(original.fingerprint(), profile.sourceFingerprint)
        }
    }

    @Test fun contactAppAndModelAreIsolatedAndClearInvalidatesInFlightWriters() {
        val id = "isolation"
        val first = cache(id)
        first.put("node", """{"facts":[]}""")
        assertNotNull(cache(id).get("node"))
        assertNull(cache("different-contact").get("node"))
        assertNull(cache(id, "different-model").get("node"))
        assertNull(FileProfileCheckpoints(context, id, "cn.soulapp.android", "direct-model").get("node"))
        FileProfileCheckpoints.clearContact(context, id)
        assertTrue(runCatching { first.put("late", "{}") }.isFailure)
        assertNull(cache(id).get("node"))
        assertTrue(runCatching { cache(id).put("invalid", "not json") }.isFailure)
    }

    @Test fun interruptedCallerDoesNotRunModelOrWriteFinal() {
        val count = AtomicInteger()
        Thread.currentThread().interrupt()
        try {
            assertTrue(runCatching {
                RelationshipProfileGenerator({ _, user, _ ->
                    count.incrementAndGet(); response(user)
                }, 1, cache("cancelled")).generate(source(2))
            }.isFailure)
        } finally {
            Thread.interrupted()
        }
        // The coordinator checks cancellation before launching any workers.
        assertEquals(0, count.get())
    }

    @Test fun parallelWorkersPersistEveryCompletedNode() {
        val calls = AtomicInteger()
        fun run() = RelationshipProfileGenerator({ _, user, _ ->
            calls.incrementAndGet(); response(user)
        }, 2, cache("parallel")).generate(source())
        run()
        assertTrue(calls.get() > 5)
        calls.set(0)
        run()
        assertEquals(0, calls.get())
    }

    @Test fun corruptedCacheRebuildsInsteadOfTreatingItAsAValidSummary() {
        val id = "corrupt"
        val first = cache(id)
        first.put("node", "{}")
        val folder = java.io.File(java.io.File(context.noBackupFilesDir, "profile-checkpoints"), profileDigest(id))
        folder.listFiles()!!.single().writeText("{partial")
        assertNull(cache(id).get("node"))
        var calls = 0
        RelationshipProfileGenerator({ _, user, _ -> calls++; response(user) }, 1, cache(id))
            .generate(source(2))
        assertEquals(2, calls)
    }
}
