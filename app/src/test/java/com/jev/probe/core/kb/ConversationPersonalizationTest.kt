package com.jev.probe.core.kb

import com.jev.probe.core.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConversationPersonalizationTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val wx = "com.tencent.mm"
    private val soul = "cn.soulapp.android"
    @Before @After fun resetConnections() {
        val db = ConversationLedger::class.java.getDeclaredField("instance").apply { isAccessible = true }
        runCatching { (db.get(null) as? ConversationLedger)?.close() }; db.set(null, null)
        KbStore::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
    }
    @Test fun explicitSamplesAreDeduplicatedEditableAndIsolatedAcrossContactsAndApps() {
        val store = ReplyVoiceStore(context)
        val a = ReplyVoiceStore.scope(wx, "a", "名字")
        val b = ReplyVoiceStore.scope(wx, "b", "名字")
        val otherApp = ReplyVoiceStore.scope(soul, "a", "名字")
        assertTrue(store.approve(a, "名字", wx, "今晚想黏你一会"))
        assertTrue(store.approve(a, "名字", wx, "今晚想黏你一会"))
        assertEquals(1, store.samples(a).size)
        assertTrue(store.samples(b).isEmpty()); assertTrue(store.samples(otherApp).isEmpty())
        assertEquals(1, ReplyVoiceStore(context).samples(a).size)
        assertTrue(store.delete(store.samples(a).single().id)); assertTrue(store.samples(a).isEmpty())
    }
    @Test fun explicitLearningHasStorageAndPromptBudgets() {
        val store = ReplyVoiceStore(context); val scope = ReplyVoiceStore.scope(wx, "a", null)
        repeat(40) { assertTrue(store.approve(scope, "测试", wx, "这是第${it}个认可的表达")) }
        assertEquals(24, store.samples(scope).size)
        val prompt = ReplyVoicePolicy.guidance(List(20) { "明确认可第${it}句".repeat(20) }, List(30) { "历史第${it}句" })
        assertTrue(prompt.length <= ReplyVoicePolicy.MAX_CHARS)
        assertTrue(prompt.indexOf("明确认可：") < prompt.indexOf("历史我的发言：").takeIf { it >= 0 }.let { it ?: Int.MAX_VALUE })
        assertFalse(prompt.contains("明确认可第19句"))
    }
    @Test fun temporaryAnalysisChoiceReadsOnlyChosenPersonsHistoryAndDoesNotWriteAnything() {
        val kb = KbStore.get(context); val ledger = ConversationLedger.get(context)
        kb.saveContact(Contact("a", "甲", apps = listOf(wx), memoryEnabled = true, relationship = "熟悉"))
        kb.saveContact(Contact("b", "乙", apps = listOf(wx), memoryEnabled = true, relationship = "新认识"))
        ledger.record("a", wx, ChatSnapshot("甲", listOf(Msg("me", "今晚想黏你一会"), Msg("other", "我也想你"))), true)
        ledger.record("b", wx, ChatSnapshot("乙", listOf(Msg("me", "刚看到你说的那只猫"))), true)
        val files = java.io.File(context.filesDir, "kb/contacts.json").readText()
        val before = ledger.totalLineCount()
        val current = ChatSnapshot("新备注", listOf(Msg("other", "哈哈哈")))
        val ctx = ContextBuilder.build(context, current, wx, Prefs(context), "a")
        assertEquals("a", ctx.contact?.id)
        assertTrue(ctx.replyVoiceGuidance.contains("今晚想黏你一会"))
        assertFalse(ctx.replyVoiceGuidance.contains("那只猫"))
        assertNull(ContextBuilder.build(context, current, soul, Prefs(context), "a").contact)
        assertEquals(before, ledger.totalLineCount()); assertEquals(files, java.io.File(context.filesDir, "kb/contacts.json").readText())
        assertNull(kb.findContact("新备注", wx))
    }
    @Test fun confirmedRenameIsAppScopedSurvivesReloadAndNeverMovesHistory() {
        val kb = KbStore.get(context)
        kb.saveContact(Contact("a", "旧名字", apps = listOf(wx, soul), memoryEnabled = true, relationship = "原关系"))
        assertTrue(kb.confirmAlias("a", "新备注", wx))
        assertEquals("a", kb.findContact("新备注", wx)?.id)
        assertNull(kb.findContact("新备注", soul))
        assertEquals("原关系", kb.contact("a")?.relationship)
        KbStore::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        assertEquals("a", KbStore.get(context).findContact("新备注", wx)?.id)
        assertNull(KbStore.get(context).findContact("新备注", soul))
    }
    @Test fun conflictingAliasOrForeignAppCannotChangeContact() {
        val kb = KbStore.get(context)
        kb.saveContact(Contact("a", "甲", apps = listOf(wx)))
        kb.saveContact(Contact("b", "乙", apps = listOf(wx)))
        assertFalse(kb.confirmAlias("a", "乙", wx)); assertFalse(kb.confirmAlias("a", "新名字", soul))
        assertEquals("b", kb.findContact("乙", wx)?.id)
        assertTrue(kb.contact("a")!!.appAliases.isEmpty())
    }
    @Test fun ownMessageSamplingNeverReadsOtherSpeakerUnknownContentOrOtherApp() {
        val ledger = ConversationLedger.get(context)
        ledger.record("a", wx, ChatSnapshot("测试", listOf(Msg("me", "这是我的文字"),
            Msg("other", "这是对方文字"), Msg("unknown", "是谁未知"),
            Msg("me", "8秒", MessageKind.VOICE_UNKNOWN), Msg("me", "我的转写内容", MessageKind.VOICE_TRANSCRIPT))), true)
        ledger.record("a", soul, ChatSnapshot("测试", listOf(Msg("me", "另一软件的表达"))), true)
        val own = ledger.ownExpressions("a", wx)
        assertEquals(setOf("这是我的文字", "我的转写内容"), own.toSet())
        assertTrue(ledger.ownExpressions("b", wx).isEmpty())
    }
    @Test fun unfiledSameNameCannotInheritPersistentStyleFromAnotherUnfiledChat() {
        ReplyVoiceStore(context).approve(ReplyVoiceStore.scope(wx, null, "同名"), "同名", wx, "今晚想黏你一会")
        val ctx = ContextBuilder.build(context, ChatSnapshot("同名", listOf(Msg("me", "你好 刚看到你的猫"))), wx, Prefs(context))
        assertFalse(ctx.replyVoiceGuidance.contains("今晚想黏你一会"))
        assertTrue(ctx.replyVoiceGuidance.contains("你好 刚看到你的猫"))
    }
}
