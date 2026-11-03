package com.jev.probe.jev

import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Msg
import com.jev.probe.core.RoleChatMetadata
import com.jev.probe.core.RoleChatPreference
import org.junit.Assert.*
import org.junit.Test

class RoleChatGuideTest {
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
}
