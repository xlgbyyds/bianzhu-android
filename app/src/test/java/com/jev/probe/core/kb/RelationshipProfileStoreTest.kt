package com.jev.probe.core.kb

import android.content.Context
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.MessageKind
import com.jev.probe.core.Msg
import com.jev.probe.core.Prefs
import com.jev.probe.jev.FileProfileCheckpoints
import com.jev.probe.jev.fingerprint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RelationshipProfileStoreTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test fun generatedRefreshPreservesCurrentManualStageAndFactsButUserCanEditAgain() {
        val store = KbStore.get(context)
        val app = "com.tencent.mm"
        val id = "protected-profile"
        store.saveContact(Contact(id, "人工确认测试", apps = listOf(app), memoryEnabled = true,
            relationship = "已经正式成为情侣"))
        try {
            ConversationLedger.get(context).record(id, app, ChatSnapshot("人工确认测试", listOf(
                Msg("other", "旧记录里还没有确认"))), true, 100L)
            val source = store.relationshipSource(id, app)
            val fresh = profile(app, 5).copy(sourceLineCount = source.lines.size,
                sourceLatestObservedAt = source.latestObservedAt, sourceFingerprint = source.fingerprint())
            val manual = fresh.copy(relationshipStage = ProfileClaim("已经正式成为情侣", userEdited = true),
                knownFacts = listOf(ProfileClaim("用户补充已线下见面", userEdited = true)))
            assertTrue(store.saveRelationshipProfile(id, app, manual))
            assertTrue(store.saveGeneratedRelationshipProfile(id, app, fresh))
            var saved = store.contact(id)!!
            assertEquals("已经正式成为情侣", saved.relationshipProfiles[app]!!.relationshipStage.text)
            assertEquals("用户补充已线下见面", saved.relationshipProfiles[app]!!.knownFacts.first().text)
            assertEquals("已经正式成为情侣", saved.relationship)
            assertTrue(saved.profileUserContext(app).contains("已线下见面"))
            assertEquals("", saved.profileUserContext("cn.soulapp.android"))
            assertTrue(store.saveRelationshipProfileDraft(id, app, fresh))
            assertTrue(store.acceptRelationshipProfile(id, app))
            assertEquals("已经正式成为情侣", store.contact(id)!!.relationshipProfiles[app]!!.relationshipStage.text)
            val changed = manual.copy(relationshipStage = ProfileClaim("用户之后修改的新阶段", userEdited = true))
            assertTrue(store.saveRelationshipProfile(id, app, changed))
            assertTrue(store.saveGeneratedRelationshipProfile(id, app, fresh))
            saved = store.contact(id)!!
            assertEquals("用户之后修改的新阶段", saved.relationshipProfiles[app]!!.relationshipStage.text)
        } finally { store.deleteContact(id) }
    }

    @Test fun sameCountCorrectionBlocksStaleCommitAndClearRemovesCheckpoints() {
        val store = KbStore.get(context)
        val id = "checkpoint-store-${System.nanoTime()}"
        val app = "com.tencent.mm"
        store.saveContact(Contact(id, "测试", apps = listOf(app), memoryEnabled = true))
        try {
            ConversationLedger.get(context).record(id, app, ChatSnapshot("测试", listOf(
                Msg("me", "原始消息", MessageKind.TEXT)
            )), true, 100L)
            val source = store.relationshipSource(id, app)
            val generated = profile(app, 1).copy(sourceLineCount = source.lines.size,
                sourceLatestObservedAt = source.latestObservedAt, sourceFingerprint = source.fingerprint())
            assertTrue(store.saveRelationshipProfile(id, app, generated))
            assertEquals(source.fingerprint(), store.contact(id)!!.relationshipProfiles[app]!!.sourceFingerprint)
            store.editHistoryItem(id, source.lines.first().ref.removePrefix("db:").toLong(),
                false, "other", "TEXT", "已修正消息", null, "unknown")
            assertFalse(store.saveRelationshipProfile(id, app, generated))
            assertFalse(store.saveRelationshipProfileDraft(id, app, generated))
            val checkpoint = FileProfileCheckpoints(context, id, app, "route")
            checkpoint.put("node", "{}")
            store.clearLog(id)
            assertNull(FileProfileCheckpoints(context, id, app, "route").get("node"))
            assertTrue(runCatching { checkpoint.put("late", "{}") }.isFailure)
        } finally {
            store.deleteContact(id)
        }
    }

    @Before @After fun resetLedgerConnectionBetweenRobolectricSandboxes() {
        // Robolectric replaces SQLite's connection registry between tests, while
        // this Kotlin singleton can outlive that registry in the shared sandbox.
        val field = ConversationLedger::class.java.getDeclaredField("instance")
        field.isAccessible = true
        runCatching { (field.get(null) as? ConversationLedger)?.close() }
        field.set(null, null)
        // KbStore retains the previous Application/filesDir too. Each sandbox
        // needs a fresh store so checkpoint creation and clearing share a root.
        KbStore::class.java.getDeclaredField("instance").apply {
            isAccessible = true
            set(null, null)
        }
    }

    private fun profile(app: String, generated: Long) = RelationshipProfile(
        relationshipStage = ProfileClaim("持续交流，关系未确认", listOf("db:1"), .7),
        attitudeAssessment = ProfileClaim("态度有积极信号但不足以确认恋爱意愿", listOf("db:2"), .55),
        knownFacts = listOf(ProfileClaim("她提及喜欢散步", listOf("db:3"), .9)),
        interactionPatterns = emptyList(), pacingAdvice = "继续轻松双向交流",
        unknowns = listOf("是否愿意确定关系未知"), sourceApp = app,
        sourceLineCount = 3, sourcePageCount = 1, sourceMeCount = 1, sourceOtherCount = 2,
        sourceUnknownSideCount = 0, sourceVisibleTimeCount = 1, sourceHistoricalCount = 3,
        sourceFirstObservedAt = 100L, sourceLatestObservedAt = 200L, generatedAt = generated
    )

    @Test fun longStageAndAttitudeDoNotCrowdOutDirectionAndPacing() {
        val detailed = profile("com.tencent.mm", 1).copy(
            relationshipStage = ProfileClaim("很长的历史阶段".repeat(150)),
            attitudeAssessment = ProfileClaim("很长的历史态度".repeat(150)),
            conversationDirection = ProfileClaim("先具体回应眼前内容 再增加自然变化"),
            pacingAdvice = "情侣方式相处 尚未正式确认 周末首次见面时间地点待商量")
        val text = detailed.contextText()
        assertTrue(text.contains("先具体回应眼前内容"))
        assertTrue(text.contains("尚未正式确认"))
        assertTrue(text.contains("尚不能判断"))
        assertTrue(text.length <= 1300)
        val ctx = ChatContext(Contact("a", "甲"), emptyList(), emptyList(), detailed)
        assertEquals(detailed.relationshipStage.text.take(300), ctx.effectiveRelationship("刚认识"))
        assertEquals("我明确填写的方向", ctx.copy(contact = Contact("a", "甲", relationship = "我明确填写的方向"))
            .effectiveRelationship("刚认识"))
    }

    @Test fun draftRequiresAcceptanceAndRefreshNeverOverwritesAcceptedProfile() {
        val store = KbStore.get(context)
        val id = "profile-test-${System.nanoTime()}"
        val app = "com.tencent.mm"
        assertTrue(store.saveContact(Contact(id, "档案测试", apps = listOf(app), memoryEnabled = true)))
        try {
            val ledger = ConversationLedger.get(context)
            assertTrue(ledger.record(id, app, ChatSnapshot("档案测试", listOf(
                Msg("me", "我先发的一句", MessageKind.TEXT),
                Msg("other", "她说的一句", MessageKind.TEXT, "9月15日", "partial")
            )), true, 100L))
            assertTrue(ledger.record(id, app, ChatSnapshot("档案测试", listOf(
                Msg("other", "她后续的一句", MessageKind.TEXT)
            )), true, 200L))
            val first = profile(app, 1L)
            assertTrue(store.saveRelationshipProfileDraft(id, app, first))
            var saved = store.contact(id)!!
            assertNull(saved.relationshipProfiles[app])
            assertEquals(first, saved.relationshipProfileDrafts[app])

            assertTrue(store.acceptRelationshipProfile(id, app))
            saved = store.contact(id)!!
            assertEquals(first, saved.relationshipProfiles[app])
            assertNull(saved.relationshipProfileDrafts[app])

            val refresh = profile(app, 2L)
            assertTrue(store.saveRelationshipProfileDraft(id, app, refresh))
            saved = store.contact(id)!!
            assertEquals(first, saved.relationshipProfiles[app])
            assertEquals(refresh, saved.relationshipProfileDrafts[app])

            // A generated/edited profile is committed directly to this contact+App,
            // then ContextBuilder automatically selects it only for that App.
            val autoProfile = profile(app, 4L)
            assertTrue(store.saveRelationshipProfile(id, app, autoProfile))
            saved = store.contact(id)!!
            assertEquals(autoProfile, saved.relationshipProfiles[app])
            assertNull(saved.relationshipProfileDrafts[app])
            val prefs = Prefs(context)
            val wechatContext = ContextBuilder.build(context,
                ChatSnapshot("档案测试", listOf(Msg("other", "当前消息", MessageKind.TEXT))), app, prefs)
            val soulContext = ContextBuilder.build(context,
                ChatSnapshot("档案测试", listOf(Msg("other", "当前消息", MessageKind.TEXT))),
                "cn.soulapp.android", prefs)
            assertEquals(autoProfile, wechatContext.relationshipProfile)
            assertNull(soulContext.relationshipProfile)

            // Newer stored history makes an in-flight stale result ineligible to overwrite.
            assertTrue(ledger.record(id, app, ChatSnapshot("档案测试", listOf(
                Msg("other", "更新后出现的新消息", MessageKind.TEXT)
            )), true, 300L))
            assertFalse(store.saveRelationshipProfile(id, app, profile(app, 5L)))
            assertEquals(autoProfile, store.contact(id)!!.relationshipProfiles[app])

            val soul = "cn.soulapp.android"
            assertTrue(store.saveContact(saved.copy(apps = listOf(app, soul))))
            assertTrue(ledger.record(id, soul, ChatSnapshot("档案测试", listOf(
                Msg("me", "我在 Soul 发的一句", MessageKind.TEXT),
                Msg("other", "她在 Soul 说的一句", MessageKind.TEXT, "9月15日", "partial")
            )), true, 100L))
            assertTrue(ledger.record(id, soul, ChatSnapshot("档案测试", listOf(
                Msg("other", "她在 Soul 后续的一句", MessageKind.TEXT)
            )), true, 200L))
            assertTrue(store.saveRelationshipProfileDraft(id, soul, profile(soul, 3L)))
            saved = store.contact(id)!!
            assertEquals(autoProfile, saved.relationshipProfiles[app])
            assertTrue(saved.relationshipProfileDrafts.containsKey(soul))
        } finally {
            store.deleteContact(id)
        }
    }

}
