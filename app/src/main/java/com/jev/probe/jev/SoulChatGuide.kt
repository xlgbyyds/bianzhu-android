package com.jev.probe.jev

import com.jev.probe.core.ChatSnapshot

/** Ordinary Soul social chat. No anonymous/role-play relationship is installed here. */
internal object SoulChatGuide {
    const val PREFIX = "Soul社交场景规则："
    const val MARKER = "【Soul本轮接话】"
    fun shouldAnswer(snapshot: ChatSnapshot): Boolean = snapshot.messages.lastOrNull()?.side == "other" &&
        (SocialTutorialGuide.select(snapshot, roles = false)?.id == "style_reset" ||
            Regex("[?？]|(?:吗|么|呢|多大|哪里|什么|咋样)$").containsMatchIn(SocialTutorialGuide.latest(snapshot)))
    fun guidance(snapshot: ChatSnapshot): String {
        val lesson = SocialTutorialGuide.select(snapshot, roles = false)
        return PREFIX + "已有联系人记忆与人工关系优先，不因软件名假定陌生、单身或恋人。" +
            "先接明确的兴趣、动态或认识缘由；资料卡是自述，点赞不是喜欢或答应见面。" +
            "只展示本人真实经历，不编收入学历、资产、创业或感情伤史。转微信不改变原有边界，不自动绑定跨App联系人。" +
            "普通回复不强推见面；主动邀约由邀约判断决定，未接招不追问或按天数循环再约。" +
            lesson?.let { "本轮重点：${it.decision}" }.orEmpty()
    }
    fun replyInstruction(snapshot: ChatSnapshot, scene: String, opener: Boolean): String {
        if (!scene.startsWith(PREFIX)) return ""
        val lesson = SocialTutorialGuide.select(snapshot, roles = false) ?: return ""
        return MARKER + "参考类型=${lesson.id}。" + lesson.writing +
            if (opener) "若末条是对方尚未回答的问题先答，不强行转成新的招呼；已收尾则可自然另起轻松话题。" else ""
    }
    fun invitationInstruction(snapshot: ChatSnapshot, scene: String, action: InvitationAction): String =
        if (scene.startsWith(PREFIX)) "【Soul邀约写法】执行Jev本轮决策，先回应实际条件，不用虚构人设、付款或假承诺推动见面。" +
            SocialTutorialGuide.invitationDetail(snapshot, action) else ""
}
