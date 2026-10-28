package com.jev.probe.jev

import com.jev.probe.core.ReplySuggestion
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.MessageKind
import com.jev.probe.core.pageDateAgeDays
import java.time.LocalDate

/** Fast local guard for ordinary chat suggestions; no extra network request. */
internal object ReplyPolicy {
    /** Shared by replies and openers; no second rewrite/model request. */
    const val NATURAL_VOICE =
        "先接对方实际说的一件具体事，不先总结她的情绪、性格或你们的关系。" +
        "像随手发出的口语，不像精心设计的情话或段子，不写旁白；短回可以很短，不必每句都机智、温柔或有反转。" +
        "普通小事不要升级成天赋、人设、命中注定或感情宣言；不用颁奖式夸赞、霸总式包办、客服式共情、万能比喻和自创称号。" +
        "调侃只接已有的具体细节，有来有回时轻逗一句即可；对方敷衍、疲惫或认真说事时不硬造梗。亲密可以直说，不加戏。" +
        "三条是互斥备选，用户只选一条发送；每条独立成立，不写成连续三条，也不要求每条都追问或推进关系。" +
        "参考已提供的我方发言和表达样本，只学用词、句长与停顿，不复用整句、固定开头或同一个夸赞安慰套路；事实和称呼不从样本迁移。历史我说过不等于说得好，对方明确嫌像AI或生硬的表达不能继续模仿。" +
        "不要为了显得真人而故意错字、堆哈哈和语气词，或编造生活经历。输出前去掉多余的感慨、解释和结尾拔高，只保留自然接话所需的内容。"
    const val LISTENING_AND_CONTINUITY =
        "把自己放在正在和她说话的位置，不做旁观评论员：不要把她每句话改写成结论，再附加评价。" +
        "先读完对方连续几条，分清她是在讲述、提问、开玩笑还是还没说完；她在讲经历时不每句抢答或追查背景，不把话题拐到学历收入和人脉。" +
        "倾诉时回应她在意的具体变化或一句真实感受即可；不反复判谁对谁错，不替第三人编动机，不靠骂得更狠表示理解。" +
        "她仍难过时允许难过，不催放下、不教做人，也不拿她的损失和痛处造梗；联系人偏好调侃不覆盖这一点。" +
        "避免结案式金句和整齐对仗，例如说白了就是、说到底还是、难过归难过、及时止损、你这心态挺好。不是简单换同义词，而是少做一次总结。" +
        "遇到这个、那样、找一个、他说等省略表达，沿当前话题和引用找指代，不能仅抓最近一个职业或名词作答；信息不足时简短问清，不编完整故事。" +
        "被说像AI时接住表达反馈，不争辩自己句句实话，不编没用AI的保证；不再次解释和分析为什么像。" +
        "仅作写法对照不能照抄：对方说难过还是会难过，接真喜欢过 哪有那么快就不难过；" +
        "对方说你讲话像机器人，接好像是有点 我不瞎总结了。普通的直接回应优先于设计一个漂亮句子。"
    const val WRITING_STYLE = "所有可发送的回复正文都不要标点符号，用空格分隔语气和短句；" +
        "不说客套话，不用请问、您好、谢谢、感谢、打扰了、辛苦了等礼貌模板，直接自然接话。" +
        "这条要求只作用于回复正文，JSON 结构仍须合法。"
    const val SPEAKER_AND_REFERENCE_GUIDANCE =
        "先核对每句是谁说的以及第三人称指谁。聊天中已明确纠正的人物称谓优先于旧称谓；" +
        "from=me 是用户本人，from=other 才是对方；我提到的工作、吐槽或安排不能改写成她的经历。" +
        "回扣旧话题先保留原发言者和当时的回应关系；没有明确约定，不生成替她记着、到时候别忘了或催她交代的待办式提醒。" +
        "关系档案含双方的历史，不等于对方的资料卡；来源或指代不明时换个自然开场，不补造承诺。" +
        "引用和转述不等于对方此刻对我的表达，不能把故事人物的话当成本次邀约或示爱。" +
        "人物指代不确定时省略称呼，直接回应已确认的具体细节。"
    private val punctuation = Regex("[\\p{P}\\s]+")
    private val courtesy = Regex("^(谢谢|感谢|不客气|不用谢|不用客气|请问|您好|" +
        "你好(?=$|[\\s\\p{P}呀啊])|打扰了|辛苦了|辛苦你|祝你)")

    fun format(text: String): String = text.replace(punctuation, " ").trim()
    private val intrusive = Regex(
        """你.{0,12}(床上|躺着|坐着刷剧|洗澡|穿什么|睡衣|住哪|住址|一个人在家|现在在哪|具体位置|定位|身高|体重|收入|工资|前任|恋爱史|有没有男朋友)"""
    )
    private val realTimeCheck = Regex("""你(现在|这会儿|此刻)?(在干嘛|干什么|做什么|在哪里)""")
    private val invite = Regex(
        """(有空|忙不忙|约饭|约个饭|见面|见个面|见一面|出来坐|出来玩|出来走|一起吃|一起喝|一起逛|一起玩|一起去|喝咖啡|约个时间|要不要出来)"""
    )
    private val otherInvited = Regex(
        """((一起|约|要不要|来不来|有空).{0,12}(见面|吃饭|喝|逛|玩|出来))|((见面|吃饭|喝咖啡).{0,8}(吗|吧|？|\?))"""
    )
    private val interrogative = Regex("""[?？]|(吗|呢|么)\s*$|你.{0,12}(怎么|什么|哪|有没有|要不要|是不是|能不能|会不会)""")

    fun safe(candidates: List<String>, latestOther: String, allowInvitation: Boolean = false): List<String> {
        val canAnswerInvite = allowInvitation || otherInvited.containsMatchIn(latestOther)
        var questionCount = 0
        return candidates.map { it.trim() }.filter { it.isNotEmpty() }
            .filterNot { intrusive.containsMatchIn(it) || realTimeCheck.containsMatchIn(it) ||
                courtesy.containsMatchIn(it.trimStart('“', '"', '「', '『')) ||
                (!canAnswerInvite && invite.containsMatchIn(it)) }
            .map { format(it) to interrogative.containsMatchIn(it) }
            .filter { it.first.isNotEmpty() }.distinctBy { it.first }
            .filter {
                if (allowInvitation || !it.second) true
                else if (questionCount++ == 0) true else false
            }.take(3).map { it.first }
    }

    fun safeSuggestions(candidates: List<ReplySuggestion>, latestOther: String,
                        allowInvitation: Boolean = false): List<ReplySuggestion> {
        val allowed = safe(candidates.map { it.text }, latestOther, allowInvitation).toSet()
        return candidates.map { it.copy(text = format(it.text)) }
            .filter { it.text in allowed }.distinctBy { it.text }.take(3)
    }

    // Known bad real-device outputs: an old "喝西北风" joke became today's
    // weather, and a past "等会回你" became a made-up "忙完了" status.
    // This deliberately covers concrete assertions, not every possible fact.
    private val currentWeather = Regex("(?:今天|现在|外面|这会儿).{0,3}(风(?:很|好|挺|真|太)?大|很冷|好冷|真冷|很热|好热|下雨了|下雪了|降温了)(?![吗么])")
    private val ownActivity = Regex("(?:^|\\s)(?:我)?(?:现在|这会儿|终于|刚刚|刚|已经)?(忙完(?:了)?|下班(?:了)?|到家(?:了)?|吃完(?:了)?|睡醒(?:了)?)(?![吗么没]|没有)")
    fun withGroundedCurrentFacts(candidates: List<ReplySuggestion>, snapshot: ChatSnapshot,
                                today: LocalDate = LocalDate.now()): List<ReplySuggestion> {
        fun normalize(text: String) = format(text).replace(" ", "")
        fun supported(fact: String, own: Boolean): Boolean = snapshot.messages.any {
            it.kind in setOf(MessageKind.TEXT, MessageKind.VOICE_TRANSCRIPT) &&
                (if (own) it.side == "me" else it.side in setOf("me", "other")) &&
                pageDateAgeDays(it.visibleTime, today) == 0L &&
                normalize(it.text).contains(normalize(fact))
        }
        return candidates.filter { candidate ->
            currentWeather.findAll(candidate.text).all { supported(it.groupValues[1], false) } &&
                ownActivity.findAll(candidate.text).all { supported(it.groupValues[1], true) }
        }
    }
}
