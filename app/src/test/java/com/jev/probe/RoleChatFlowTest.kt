package com.jev.probe

import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.jev.probe.capture.PupcallAdapter
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Msg
import com.jev.probe.core.Prefs
import com.jev.probe.core.RoleChatMetadata
import com.jev.probe.core.RoleChatPreference
import com.jev.probe.core.RoleChatStore
import com.jev.probe.core.kb.ContextBuilder
import com.jev.probe.core.kb.KbStore
import com.jev.probe.core.kb.Contact
import com.jev.probe.jev.ApiException
import com.jev.probe.jev.JudgeClient
import com.jev.probe.jev.ReplyClient
import com.jev.probe.jev.RoleChatGuide
import com.jev.probe.jev.Route
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoleChatFlowTest {
    private lateinit var context: Context
    private lateinit var store: RoleChatStore
    private val shot = ChatSnapshot("样例联系人", listOf(Msg("other", "想找个温柔的Daddy")),
        roleChat = RoleChatMetadata("Dom", 25))
    @Before fun setup() {
        resetConnections()
        context = RuntimeEnvironment.getApplication(); store = RoleChatStore(context)
        store.clear(); KbStore.get(context).clearAll()
    }
    @After fun cleanup() { store.clear(); KbStore.get(context).clearAll(); resetConnections() }
    private fun resetConnections() {
        val field = com.jev.probe.core.kb.ConversationLedger::class.java.getDeclaredField("instance").apply { isAccessible = true }
        runCatching { (field.get(null) as? com.jev.probe.core.kb.ConversationLedger)?.close() }
        field.set(null, null)
        KbStore::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
    }
    @Test fun defaultsCannotAssignTheSameOtherRoleToEveryStrangerAndContactOverridesStaySeparate() {
        assertTrue(store.save(RoleChatPreference(myRole = "偏照顾与引导", otherRole = "Sub")))
        assertEquals("未确认", store.load().otherRole)
        assertTrue(store.save(RoleChatPreference(myRole = "可以切换", otherRole = "本人明确偏主导"), "contact-a"))
        assertEquals("本人明确偏主导", RoleChatStore(context).load("contact-a").otherRole)
        assertEquals("未确认", store.load("contact-b").otherRole)
        assertEquals("偏照顾与引导", store.load("contact-b").myRole)
        store.reset("contact-a")
        assertEquals("未确认", store.load("contact-a").otherRole)
    }
    @Test fun strangerSceneOverridesGlobalCoupleDefaultButNotExplicitPerContactRelationship() {
        val prefs = Prefs(context, "role-flow")
        val scene = ContextBuilder.build(context, shot, PupcallAdapter.PACKAGE, prefs)
        assertEquals(RoleChatGuide.DEFAULT_RELATIONSHIP, scene.effectiveRelationship("已经是情侣"))
        assertFalse(scene.isEmpty())
        assertTrue(scene.background("").contains("资料标签：Dom"))
        assertTrue(KbStore.get(context).saveContact(Contact("role-a", shot.title!!,
            apps = listOf(PupcallAdapter.PACKAGE), relationship = "朋友 先普通认识")))
        val contact = ContextBuilder.build(context, shot, PupcallAdapter.PACKAGE, prefs)
        assertEquals("朋友 先普通认识", contact.effectiveRelationship("已经是情侣"))
        val otherApp = ContextBuilder.build(context, shot, "com.tencent.mm", prefs)
        assertEquals("", otherApp.sceneGuidance)
        assertEquals("全局关系", otherApp.effectiveRelationship("全局关系"))
    }
    @Test fun bothRoutesReceiveTheSceneAndJudgeNeverSilentlyDropsItOnError() {
        val prefs = Prefs(context, "role-model-flow")
        val ctx = ContextBuilder.build(context, shot, PupcallAdapter.PACKAGE, prefs)
        val relation = ctx.effectiveRelationship("全局情侣")
        var replies = 0
        val client = ReplyClient(prefs) { _, _, body ->
            replies++
            val prompt = body.getJSONArray("messages").toString()
            assertTrue(prompt.contains("角色沟通场景规则"))
            assertTrue(prompt.contains("关系尚未确认"))
            assertTrue(prompt.contains("from=other"))
            JSONObject().put("choices", org.json.JSONArray().put(JSONObject().put("message",
                JSONObject().put("content", "{\"replies\":[{\"style\":\"natural\",\"text\":\"先认识一下 你平时喜欢聊什么\"},{\"style\":\"follow_detail\",\"text\":\"你说的温柔是哪一种\"},{\"style\":\"playful\",\"text\":\"刚来就收到你的招呼了\"}]}"))))
        }
        assertTrue(client.draft(shot, relation, ctx).isNotEmpty())
        assertEquals(1, replies)
        var judges = 0
        val judge = JudgeClient(prefs) { _, _, body ->
            judges++
            assertTrue(body.getJSONObject("state").getString("background").contains("角色沟通场景规则"))
            throw ApiException(Route.JUDGE, 422, "synthetic context rejection")
        }
        assertNotNull(judge.judge(shot, relation, ctx).error)
        assertEquals(1, judges)
    }
    @Test fun reviewRoundTripPreservesRoleEvidenceAndSpeakerCorrections() {
        val decoded = SnapshotReviewCodec.decode(SnapshotReviewCodec.encode(shot))!!
        assertEquals(shot.roleChat, decoded.roleChat)
        assertEquals(shot.roleChat, decoded.copy(messages = listOf(Msg("me", "修正归属"))).roleChat)
    }
    @Test fun roleReplyAndOpenerUseSystemDirectionWhileOrdinaryAppsAndOptOutKeepOriginalTask() {
        val prefs = Prefs(context, "role-writing-flow")
        store.save(RoleChatPreference(myRole = "偏照顾与引导", tone = "轻松调侃"))
        val roleShot = shot.copy(messages = listOf(Msg("other", "爸爸喵喵喵")))
        var calls = 0
        for (app in listOf(PupcallAdapter.PACKAGE, "com.tencent.mm", "com.tantan.x", "cn.soulapp.android")) {
            val ctx = ContextBuilder.build(context, roleShot, app, prefs)
            for (opener in listOf(false, true)) {
                val client = ReplyClient(prefs) { _, _, body ->
                    calls++
                    val messages = body.getJSONArray("messages")
                    val sys = messages.getJSONObject(0).getString("content")
                    val user = messages.getJSONObject(1).getString("content")
                    val scoped = app == PupcallAdapter.PACKAGE
                    assertEquals(scoped, sys.contains("【呼叫小狗本轮写法】"))
                    if (opener) assertEquals(!scoped, user.contains("fresh_opening 必须换到轻松的新话题"))
                    val styles = if (opener) listOf("fresh_opening", "light_callback", "playful")
                        else listOf("natural", "follow_detail", "playful")
                    val replies = org.json.JSONArray()
                    styles.forEachIndexed { i, style -> replies.put(JSONObject().put("style", style).put("text", listOf("轻松接话", "温柔回应", "自然聊聊")[i])) }
                    JSONObject().put("choices", org.json.JSONArray().put(JSONObject().put("message",
                        JSONObject().put("content", JSONObject().put("replies", replies).toString()))))
                }
                val result = if (opener) client.openers(roleShot, ctx.effectiveRelationship("普通认识"), ctx)
                    else client.draft(roleShot, ctx.effectiveRelationship("普通认识"), ctx)
                assertEquals(3, result.size)
            }
        }
        assertEquals(8, calls)
        store.save(RoleChatPreference(enabled = false))
        val disabled = ContextBuilder.build(context, roleShot, PupcallAdapter.PACKAGE, prefs)
        assertEquals("", RoleChatGuide.replyInstruction(roleShot, disabled.sceneGuidance, false))
    }
    @Test fun localExplanationOpensWithoutModelsAndContactDeletionRemovesItsPreference() {
        val kb = KbStore.get(context)
        kb.saveContact(Contact("role-a", shot.title!!, apps = listOf(PupcallAdapter.PACKAGE)))
        store.save(RoleChatPreference(otherRole = "已确认的偏好"), "role-a")
        val activity = Robolectric.buildActivity(RoleChatActivity::class.java,
            Intent(context, RoleChatActivity::class.java).putExtra("contactId", "role-a")
                .putExtra("snapshot", SnapshotReviewCodec.encode(shot))).setup()
        fun texts(v: View): List<String> = (if (v is TextView) listOf(v.text.toString()) else emptyList()) +
            if (v is ViewGroup) (0 until v.childCount).flatMap { texts(v.getChildAt(it)) } else emptyList()
        val shown = texts(activity.get().window.decorView).joinToString()
        assertTrue(shown.contains("这句话怎么理解"))
        assertTrue(shown.contains("资料标签：Dom"))
        assertTrue(shown.contains("用户") || shown.contains("我的相处偏好"))
        activity.pause().stop().destroy()
        kb.deleteContact("role-a")
        assertEquals("未确认", store.load("role-a").otherRole)
    }

    @Test fun firmPreferenceSurvivesReloadAndIsScopedAcrossReplyOpenerAndInvitation() {
        assertPreferenceAcrossRoutes(RoleChatPreference(myRole = RoleChatStore.FIRM_ROLE), "【明确主导写法】")
    }

    @Test fun reciprocalDirectPreferenceSurvivesReloadAndIsScopedAcrossAllThreeRoutes() {
        assertPreferenceAcrossRoutes(RoleChatPreference(myRole = RoleChatStore.RECIPROCAL_ROLE,
            tone = RoleChatStore.DIRECT_TONE), "【直球调侃写法】")
    }

    @Test fun emptyPageOpenerFiltersInventedSharedPastWithoutAnotherRequest() {
        val prefs = Prefs(context, "empty-opener-flow")
        store.save(RoleChatPreference(myRole = RoleChatStore.RECIPROCAL_ROLE, tone = RoleChatStore.DIRECT_TONE))
        val page = shot.copy(messages = emptyList())
        val ctx = ContextBuilder.build(context, page, PupcallAdapter.PACKAGE, prefs)
        var calls = 0
        val client = ReplyClient(prefs) { _, _, body ->
            calls++
            assertTrue(body.getJSONArray("messages").getJSONObject(1).getString("content").contains("没有读到可用聊天正文"))
            JSONObject().put("choices", org.json.JSONArray().put(JSONObject().put("message", JSONObject()
                .put("content", """{"replies":[{"style":"fresh_opening","text":"来找你聊两句"},
                    {"style":"light_callback","text":"上次聊得挺开心 想接着聊"},
                    {"style":"playful","text":"看你这招呼 我也想认识你"}]}"""))))
        }
        val replies = client.openers(page, "尚未确认", ctx)
        assertEquals(1, calls)
        assertEquals(2, replies.size)
        assertFalse(replies.any { it.text.contains("上次聊得") })
    }

    private fun assertPreferenceAcrossRoutes(preference: RoleChatPreference, marker: String) {
        val prefs = Prefs(context, "firm-role-flow")
        assertTrue(store.save(preference))
        assertEquals(preference.myRole, RoleChatStore(context).load().myRole)
        assertEquals(preference.tone, RoleChatStore(context).load().tone)
        val page = shot.copy(messages = listOf(Msg("other", "今天下课了")))
        var calls = 0
        for (app in listOf(PupcallAdapter.PACKAGE, "com.tencent.mm", "com.tantan.x", "cn.soulapp.android")) {
            val ctx = ContextBuilder.build(context, page, app, prefs)
            for (purpose in listOf("reply", "opener", "invitation")) {
                val styles = when (purpose) {
                    "opener" -> listOf("fresh_opening", "light_callback", "playful")
                    "invitation" -> listOf("invite_direct", "invite_context", "invite_playful")
                    else -> listOf("natural", "follow_detail", "playful")
                }
                val client = ReplyClient(prefs) { _, _, body ->
                    calls++
                    val messages = body.getJSONArray("messages")
                    val system = messages.getJSONObject(0).getString("content")
                    assertEquals(app == PupcallAdapter.PACKAGE, system.contains(marker))
                    if (purpose == "opener") {
                        assertTrue(messages.getJSONObject(1).getString("content").contains("fresh_opening 必须换到轻松的新话题"))
                        assertFalse(system.contains("【呼叫小狗本轮写法】"))
                    }
                    val replies = org.json.JSONArray()
                    styles.forEachIndexed { i, style -> replies.put(JSONObject().put("style", style)
                        .put("text", listOf("轻松接话", "自然回应", "聊聊近况")[i])) }
                    JSONObject().put("choices", org.json.JSONArray().put(JSONObject().put("message",
                        JSONObject().put("content", JSONObject().put("replies", replies).toString()))))
                }
                when (purpose) {
                    "opener" -> client.openers(page, "普通认识", ctx)
                    "invitation" -> client.invitations(page, "普通认识", ctx,
                        com.jev.probe.jev.InvitationPlan(com.jev.probe.jev.InvitationAction.INVITE, null, "", emptyList()))
                    else -> client.draft(page, "普通认识", ctx)
                }
            }
        }
        assertEquals(12, calls)
    }
}
