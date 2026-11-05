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
        "你正在参与聊天，不是在替旁观者写人物点评。不先总结她的情绪，不写旁白。先处理她这轮真正说的事：有问题先回答；分享接一个具体细节；倾诉接她在意的变化；没有要补充时短回即可。" +
        "具体不是把原话重复一遍：少用复述加评价，不写听起来你很、说明你是、难怪你、你这眼光在线来代替接话。不凭吃饭工作等小事推导她的性格魅力或别人多信任她；她明确请你评价时才答她问的那一点。" +
        "一条只表达一个意思。先保证有贴题的内容，再考虑好听；能套给任何人的漂亮话、信任比糖甜式比喻和结尾拔高直接省去。关心可以具体，喜欢可以直说，不必写成宣言。" +
        "对方纠正我时先接住纠正，不继续科普她比我清楚的事。不能把她已经说明的事实又当作新结论告诉她，也不能假装记得未提供的照片。" +
        "不要为具体而编细节、经历、承诺，也不把每次吐槽变成解决方案或追问。那、这、确实、真是可以自然使用；问题在整句是否空泛，不是单字。" +
        "三条是互斥备选，每条独立成立；有合适角度才变化，不为凑风格制造夸赞、金句或反转。轻逗只接已有细节，不拿痛处造梗。" +
        "历史发过不等于好范文；只学用词句长，不复制事实、亲密称呼和反复出现的夸赞安慰套路。输出前删掉复述和漂亮结尾，留下真正想对她说的那一句。"
    // Placed after context so the model treats profiles/history as evidence, not wording templates.
    const val TURN_WRITING_CHECK =
        "最后核对写法：这句话是我在回应她 还是旁观者在点评她。优先直接说我的选择 意愿 好奇或感受 不替她下结论。" +
        "对方已知的复述前半句删掉 空泛夸赞和漂亮结尾删掉 不把普通物品拟人化凑俏皮。" +
        "例如收到点心可聊口味或想尝尝 别推导送的人惦记她；被纠正流程可承认弄混了 别又讲一遍流程。" +
        "每条优先6到22字 内容够了就停 不硬凑字数 风格标签不要求每条都问或都逗。只输出回复JSON。"
    const val LISTENING_AND_CONTINUITY =
        "先读完对方连续几条，分清她是在讲述、提问、开玩笑还是还没说完；她在讲经历时不每句抢答或追查背景，不把话题拐到学历收入和人脉。" +
        "倾诉时回应她在意的具体变化或一句真实感受即可；不反复判谁对谁错，不替第三人编动机，不靠骂得更狠表示理解。" +
        "她仍难过时允许难过，不催放下、不教做人，也不拿她的损失和痛处造梗；联系人偏好调侃不覆盖这一点。" +
        "避免结案式金句和整齐对仗，例如说白了就是、说到底还是、难过归难过、及时止损、你这心态挺好。不是简单换同义词，而是少做一次总结。" +
        "遇到这个、那样、找一个、他说等省略表达，沿当前话题和引用找指代，不能仅抓最近一个职业或名词作答；信息不足时简短问清，不编完整故事。" +
        "被说像AI时接住表达反馈，不争辩自己句句实话，不编没用AI的保证；不再次解释和分析为什么像。" +
        "她请我选时依据已有信息表达选择，不夸她品味好；她纠正我时承认自己弄混了；" +
        "她说你讲话像机器人，简短接住反馈就停，不再分析原因。没有具体信息时也允许符合关系的简单回应，不硬凑信息量。"
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

    // Empty or unreadable conversations cannot substantiate shared-history claims.
    // This guard checks concrete references, not every possible invented memory.
    private val sharedPast = Regex("(?:上次|之前|前几天|昨天|那天).{0,5}(?:聊得|聊过|你说|你讲|你提|咱们|我们)|" +
        "你(?:上次|之前).{0,4}(?:说|讲|提)|记得你(?:那句|说过|上次)")
    fun withGroundedConversationReferences(candidates: List<ReplySuggestion>,
                                           hasDialogueEvidence: Boolean): List<ReplySuggestion> =
        if (hasDialogueEvidence) candidates else candidates.filterNot { sharedPast.containsMatchIn(it.text) }

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
