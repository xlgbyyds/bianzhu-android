package com.jev.probe.jev

import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.ReplyVoicePolicy

/** Small local routing decision, not a diagnosis of emotion or an extra model round. */
internal object ReplyTurnPolicy {
    enum class Mode { ORDINARY, STYLE_FEEDBACK, HURT }
    fun mode(snapshot: ChatSnapshot): Mode {
        val others = snapshot.messages.filter { it.side == "other" }.takeLast(3)
        if (others.any { ReplyVoicePolicy.isStyleComplaint(it.text) }) return Mode.STYLE_FEEDBACK
        val tail = others.joinToString(" ") { it.text }
        return if (Regex("难过|伤心|掉眼泪|想哭|哭了|好委屈|被.{0,4}伤.{0,3}深").containsMatchIn(tail) &&
            !Regex("(?:不|没)(?:再|有|那么)?难过(?:了|啦)").containsMatchIn(tail)) Mode.HURT else Mode.ORDINARY
    }
    fun styles(mode: Mode, ordinary: List<String>): List<String> =
        if (mode == Mode.ORDINARY) ordinary else listOf("natural", "follow_detail", "simple")

    fun instruction(mode: Mode, opener: Boolean): String = when {
        opener -> "本轮是用户主动重新开场，不是让你替他继续评价最后一屏。fresh_opening 必须换到轻松的新话题；" +
            "只用已有兴趣或一个普通问候，不能编造今天经历。light_callback 只能回扣轻松话题，不能重提前任、创伤、分手、户口或催对方交代；没有合适旧话题就再给一种普通开场。" +
            "资料卡自述不能写成你上次跟我说过，除非聊天原文确实支持。三条最多一条问句，另两条用简单表达兴趣或招呼，不堆问卷。" +
            "三条不得围着同一件旧事打转。不要把档案写成对她说的分析报告。"
        mode == Mode.STYLE_FEEDBACK -> "本轮她在评价我的说话方式，三条只给简短接受反馈的不同说法。" +
            "不继续分析先前故事，不辩解、不讲道理、不玩模型梗、不拉第三人作比较，也不谎称没用过AI；一小句就停。simple 也是平实回应。"
        mode == Mode.HURT -> "本轮她仍然难过，三条都回应此刻这句话，语气平实，允许她还会难过。" +
            "不要转去讲利益、财物、职业或人脉，不劝立刻放下，不评价她做得对，不编比喻和笑话，不从旧话翻出梗。" +
            "不必三种策略，给同一合适态度的三种简短表达即可；follow_detail 也只接当前感受，simple 是平实回应。"
        else -> "先确认最新一组对方消息的意思，再回答；不要把每条消息都加工成点评。" +
            "省略的指代要从前文找，缺依据宁可问清，不拿邻近名词乱接。"
    }
}
