package com.jev.probe.jev

import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Msg
import com.jev.probe.core.RoleChatMetadata
import com.jev.probe.core.RoleChatPreference
import com.jev.probe.core.RoleChatStore
import org.junit.Assert.*
import org.junit.Test

class RoleChatGuideTest {
    private fun directScene(shot: ChatSnapshot) = RoleChatGuide.guidance(shot,
        RoleChatPreference(myRole = RoleChatStore.RECIPROCAL_ROLE, tone = RoleChatStore.DIRECT_TONE))

    @Test fun directToneWorksWithoutRoleKeywordsAndKeepsPersonalFactsGrounded() {
        val shot = snapshot("你对亲密关系怎么看")
        val text = RoleChatGuide.replyInstruction(shot, directScene(shot), false)
        assertTrue(text.contains("【直球调侃写法】"))
        assertTrue(text.contains("不编个人经历"))
        assertFalse(text.contains("【明确主导写法】"))
        assertTrue(directScene(shot).contains("互相带节奏"))
        val empty = shot.copy(messages = emptyList())
        assertTrue(RoleChatGuide.replyInstruction(empty, directScene(empty), true).contains("【直球调侃写法】"))
        assertEquals("", RoleChatGuide.replyInstruction(empty, directScene(empty), false))
    }

    @Test fun reciprocalBanterDoesNotImposeSubmissionOnEitherPerson() {
        val shot = snapshot("嫲嫲来了 还不快打招呼")
        val text = RoleChatGuide.replyInstruction(shot, directScene(shot), true)
        assertTrue(text.contains("互相斗嘴"))
        assertFalse(text.contains("我更习惯照顾人"))
        assertFalse(text.contains("我先看看你怎么照顾人"))
    }

    @Test fun directToneStillYieldsToBoundariesFeedbackUnknownContentAndDecision() {
        for (text in listOf("别这样", "别叫我小狗", "好难过 想抱抱", "你说话像机器人")) {
            val shot = snapshot(text)
            assertEquals(text, "", RoleChatGuide.replyInstruction(shot, directScene(shot), true))
        }
        val shot = snapshot("你好")
        assertEquals("", RoleChatGuide.replyInstruction(shot,
            RoleChatGuide.guidance(shot, RoleChatPreference(enabled = false, tone = RoleChatStore.DIRECT_TONE)), true))
        val unknown = shot.copy(messages = listOf(Msg("other", "内容未知", kind = com.jev.probe.core.MessageKind.VOICE_UNKNOWN)))
        assertEquals("", RoleChatGuide.replyInstruction(unknown, directScene(unknown), false))
        for (action in listOf(InvitationAction.WAIT, InvitationAction.STOP, InvitationAction.CLARIFY))
            assertFalse(RoleChatGuide.invitationInstruction(shot, directScene(shot), action).contains("【直球调侃写法】"))
        assertTrue(RoleChatGuide.invitationInstruction(shot, directScene(shot), InvitationAction.INVITE)
            .contains("【直球调侃写法】"))
        val minor = shot.copy(roleChat = RoleChatMetadata("Sub", 17))
        assertEquals("", RoleChatGuide.replyInstruction(minor, directScene(minor), true))
    }

    private fun firmScene(shot: ChatSnapshot) = RoleChatGuide.guidance(shot,
        RoleChatPreference(myRole = RoleChatStore.FIRM_ROLE))

    @Test fun firmVoiceNeedsExplicitPreferenceAndCanOpenWithoutIncomingRoleKeywords() {
        val shot = snapshot("今天下课了")
        assertEquals("", RoleChatGuide.replyInstruction(shot,
            RoleChatGuide.guidance(shot, RoleChatPreference()), false))
        assertTrue(RoleChatGuide.replyInstruction(shot, firmScene(shot), false).contains("【明确主导写法】"))
        val empty = shot.copy(messages = emptyList())
        assertTrue(RoleChatGuide.replyInstruction(empty, firmScene(empty), true).contains("【明确主导写法】"))
        assertEquals("", RoleChatGuide.replyInstruction(empty, firmScene(empty), false))
        assertEquals("", RoleChatGuide.replyInstruction(shot, "", true))
        val tagOnly = snapshot("今天下课了", "Dom\n用户明确偏好：明确主导；")
        assertEquals("", RoleChatGuide.replyInstruction(tagOnly,
            RoleChatGuide.guidance(tagOnly, RoleChatPreference()), false))
    }

    @Test fun firmVoiceKeepsOwnPerspectiveWhenOtherUsesLeadingRole() {
        val shot = snapshot("嫲嫲来了 还不快打招呼")
        val instruction = RoleChatGuide.replyInstruction(shot, firmScene(shot), false)
        assertTrue(instruction.contains("我更习惯照顾人"))
        assertFalse(instruction.contains("我先看看你怎么照顾人"))
        assertTrue(instruction.contains("不能强迫她换角色"))
    }

    @Test fun firmPreferenceDoesNotOverrideCurrentBoundaryFeedbackOrAge() {
        for (text in listOf("别命令我 主人", "不喜欢被管", "好难过 想抱抱", "你说话像机器人", "我家小狗打疫苗了")) {
            val shot = snapshot(text)
            assertEquals(text, "", RoleChatGuide.replyInstruction(shot, firmScene(shot), true))
        }
        val minor = snapshot("你好").copy(roleChat = RoleChatMetadata("Sub", 17))
        assertEquals("", RoleChatGuide.replyInstruction(minor, firmScene(minor), true))
        assertEquals("", RoleChatGuide.invitationInstruction(minor, firmScene(minor), InvitationAction.INVITE))
    }

    @Test fun strongerInvitationVoiceCannotTurnWaitStopOrClarifyIntoAnotherRequest() {
        val shot = snapshot("这周末可以")
        for (action in listOf(InvitationAction.WAIT, InvitationAction.STOP, InvitationAction.CLARIFY)) {
            val text = RoleChatGuide.invitationInstruction(shot, firmScene(shot), action)
            assertFalse(text.contains("【明确主导写法】"))
            assertTrue(text.contains("加微信"))
        }
        assertTrue(RoleChatGuide.invitationInstruction(shot, firmScene(shot), InvitationAction.CONFIRM)
            .contains("只落实已答应见面的未定细节"))
        assertTrue(RoleChatGuide.invitationInstruction(shot, firmScene(shot), InvitationAction.INVITE)
            .contains("不捆绑加微信与见面"))
        assertEquals("", RoleChatGuide.invitationInstruction(shot, "", InvitationAction.INVITE))
    }
    private fun snapshot(text: String, role: String = "Dom") = ChatSnapshot("示例联系人",
        listOf(Msg("other", text)), roleChat = RoleChatMetadata(role, 25))
    @Test fun profileAndCurrentSpeechRemainSeparateAndDoNotDeclareBotsOrRelationships() {
        val shot = snapshot("求个温柔的Daddy 别对我大声说话")
        val notes = RoleChatGuide.observations(shot).joinToString()
        assertTrue(notes.contains("资料自述"))
        assertTrue(notes.contains("可能不同"))
        assertTrue(notes.contains("无法判断真人"))
        assertTrue(RoleChatGuide.guidance(shot, RoleChatPreference()).contains("不替用户认主人"))
    }
    @Test fun nonSceneSnapshotsAndUnmatchedEnglishFragmentsGetNoRoleInjection() {
        assertEquals("", RoleChatGuide.guidance(ChatSnapshot("普通人", listOf(Msg("other", "你好"))), RoleChatPreference()))
        assertTrue(RoleChatGuide.matched(snapshot("random submission", "")).isEmpty())
        assertTrue(RoleChatGuide.matched(snapshot("Daddy 你好", "")).any { it.name == "Daddy / Mommy" })
    }
    @Test fun repeatedLongSentenceIsEvidenceOnlyNotAnIdentityVerdict() {
        val shot = snapshot("今天也希望和你一起好好聊天", "").let { it.copy(messages = it.messages + it.messages) }
        assertTrue(RoleChatGuide.observations(shot).any { it.contains("重复长句") && it.contains("不是机器人鉴定") })
    }
    @Test fun disablingSceneKeepsOrdinaryConversationAndUnknownRoleStaysUnknown() {
        val text = RoleChatGuide.guidance(snapshot("你好", "K1"), RoleChatPreference(enabled = false))
        assertTrue(text.contains("普通认识聊天"))
        assertFalse(RoleChatGuide.matched(snapshot("你好", "K1")).any { it.name == "K1" })
    }
    @Test fun reportedMinorOnlyGetsOrdinaryNonFlirtatiousConversation() {
        val shot = snapshot("主人").copy(roleChat = RoleChatMetadata("Sub", 17))
        assertTrue(RoleChatGuide.guidance(shot, RoleChatPreference()).contains("仅普通非暧昧交流"))
    }
    @Test fun roleWritingUsesActualIncomingCueAndNotOnlyProfileOrOwnWords() {
        val shot = snapshot("爸爸喵喵喵", "")
        val scene = RoleChatGuide.guidance(shot, RoleChatPreference(myRole = "偏照顾与引导"))
        assertTrue(RoleChatGuide.replyInstruction(shot, scene, false).contains("温柔主导"))
        assertTrue(RoleChatGuide.replyInstruction(shot, scene, true).contains("不强制跳到工作"))
        assertEquals("", RoleChatGuide.replyInstruction(snapshot("今天上课了", "Sub"), scene, false))
        assertEquals("", RoleChatGuide.replyInstruction(shot.copy(messages = listOf(Msg("me", "爸爸喵喵喵"))), scene, false))
    }
    @Test fun sceneOffMissingContextAndMinorNeverActivateRoleWriting() {
        val shot = snapshot("求个温柔的Daddy")
        assertEquals("", RoleChatGuide.replyInstruction(shot, "", false))
        assertEquals("", RoleChatGuide.replyInstruction(shot,
            RoleChatGuide.guidance(shot, RoleChatPreference(enabled = false)), false))
        assertEquals("", RoleChatGuide.replyInstruction(shot.copy(roleChat = RoleChatMetadata("Sub", 17)),
            RoleChatGuide.guidance(shot, RoleChatPreference()), false))
    }
    @Test fun hurtFeedbackBoundariesAndLiteralFamilyOrPetsDoNotGetRolePlay() {
        val scene = RoleChatGuide.guidance(snapshot("你好"), RoleChatPreference())
        listOf("别叫我小狗", "不要叫我主人", "我好难过 想抱抱", "你说话像机器人啊 主人", "爸爸今天生日", "我家小狗打疫苗了")
            .forEach { assertEquals(it, "", RoleChatGuide.replyInstruction(snapshot(it), scene, true)) }
    }
    @Test fun incomingLeadingRoleIsNotInvertedByAContradictoryProfileTag() {
        val shot = snapshot("嫲嫲来了 还不快打招呼", "Sub")
        val text = RoleChatGuide.replyInstruction(shot, RoleChatGuide.guidance(shot, RoleChatPreference()), false)
        assertTrue(text.contains("对方偏引导或照顾"))
        assertTrue(text.contains("不能只凭标签把她反过来当小狗"))
    }

    // ------------------------------------------------------------ 瓶子说 (anonymous role-chat app)
    private val pingziPkg = "com.pingzishuo.www"

    @Test fun pingziSceneKeepsAnonymousChannelRulesAndPressureGuard() {
        val shot = snapshot("在吗").copy(roleChat = RoleChatMetadata("性别年龄横幅：女生 24-30岁", 24))
        val scene = RoleChatGuide.guidance(shot, RoleChatPreference(), pingziPkg)
        assertTrue(scene.contains("仅适用于当前瓶子说匿名对话"))
        assertTrue(scene.contains("对方随时可能离开"))
        assertTrue(scene.contains("不把犹豫当默许"))
        assertFalse(scene.contains("呼叫小狗联系人"))
        assertEquals(RoleChatGuide.PINGZI_RELATIONSHIP, RoleChatGuide.defaultRelationship(pingziPkg))
        assertEquals(RoleChatGuide.DEFAULT_RELATIONSHIP, RoleChatGuide.defaultRelationship("com.yixiangweilai.yuebaobao"))
    }

    @Test fun pingziDisabledPreferenceSpeaksOfBottleAppNotPupcall() {
        val shot = snapshot("你好").copy(roleChat = RoleChatMetadata(null, null))
        val scene = RoleChatGuide.guidance(shot, RoleChatPreference(enabled = false), pingziPkg)
        assertTrue(scene.contains("瓶子说匿名聊天场景"))
        assertEquals("", RoleChatGuide.guidance(shot.copy(roleChat = null),
            RoleChatPreference(), pingziPkg))
    }
}
