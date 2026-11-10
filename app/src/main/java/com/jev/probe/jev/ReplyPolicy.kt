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
    // Real WeChat messages carry an emote in about one of every ten lines. Measured against
    // deepseek: "可以偶尔" produced zero emotes in 6/6 batches, "必须有一条" produced one in
    // 4/4 and picked a mood-appropriate name, so the guidance has to be mandatory to bite.
    const val EMOTE_GUIDANCE =
        "三条里必须有一条带一个微信表情 直接写方括号 例如 [捂脸] [旺柴] [吃瓜] [呲牙] [破涕为笑] " +
            "方括号表情不算标点 不受上面不用标点那条限制 不用自己编方括号里的字 " +
            "表情要贴本轮情绪 她难受或说正事时用 [捂脸] [叹气] 这类 不要用 [旺柴] [吃瓜] 起哄。"
    // Real-device audit 2026-11-10: 6,888 own lines are 48.3% <=10 chars and contain
    // stance-announcement phrases in 0.22%. Three live drafts produced two such phrases
    // ("这反差我认了", "这点我记住了") and pushed every short reaction to rank #3.
    // Named examples beat another abstract "be natural" rule.
    const val PLAIN_REACTION_GUIDANCE =
        "三条里其余两条也不能写成表态或叮嘱 真人几乎不发这些 " +
            "禁止表态句 我记住了 这点我记住了 我认了 我明白了 放心 交给我 我宁可 算我的 归我 我会注意的 " +
            "禁止模板叮嘱 别硬撑 注意身体 照顾好自己 多喝热水 记得吃药 " +
            "也不要把她刚说过的价格数量或原话再复述一遍当开场 然后补一句评价 " +
            "对照看 她说今天状态不能熬夜 可以回 行 早点休息 或 那先睡 明天再说 " +
            "不要回 不熬夜这点我记住了 或 那今晚就早点睡 别硬撑 " +
            "她开玩笑说反差感 可以回 真够反差的 或 哈哈 不要回 这反差我认了 或 这种趣味我宁可少点 " +
            "只给一个反应也是完整回复 不必每条都解释原因或收个漂亮尾巴。"
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
    // Real-device audit 2026-11-10: a live draft wrote "那我这女朋友身份算转正了",
    // putting her girlfriend status onto the user. Pin the person mapping once,
    // with that exact counter-example, instead of hoping the model infers it.
    const val SPEAKER_PERSON_MAP =
        "每条都是用户本人当场发给对方的话 我 只能是用户 你 只能是对方 " +
        "不替对方说话 不把对方的身份 感受或动作写到用户身上 " +
        "例如她问 那我有幸成为你的女朋友吗 可以回 你这女朋友身份算转正了 " +
        "不能回 我这女朋友身份算转正了。"
    // Last message is a photo/sticker the model cannot see. Real-device audit
    // 2026-11-10: without this rule drafts either answered the previous text or
    // invented what the picture showed.
    const val MEDIA_LAST_GUIDANCE =
        "对方最后发的是图片或表情包 你看不到具体内容 " +
        "可以只回应她的情绪 比如 哈哈 这啥 或顺着当前话题自然接一句 " +
        "不描述 不猜测 不评价图片内容 不复述前面的文字 不假装看到了图。"
    private val punctuation = Regex("[\\p{P}\\s]+")
    private val courtesy = Regex("^(谢谢|感谢|不客气|不用谢|不用客气|请问|您好|" +
        "你好(?=$|[\\s\\p{P}呀啊])|打扰了|辛苦了|辛苦你|祝你)")

    // WeChat emote tokens such as [捂脸] are bracketed, so the punctuation strip used to
    // turn them into bare words ("捂脸"). The real chat log shows emotes in ~10% of my own
    // messages, none in Jev output, which is itself a tell. Only names seen in the user's
    // own chats are kept; any other bracket group is dropped whole instead of leaking text.
    private val bracketToken = Regex("\\[([^\\[\\]]{1,6})\\]")
    private val emotes = setOf("捂脸", "吃瓜", "囧", "流汗", "破涕为笑", "旺柴", "流泪", "微笑",
        "呲牙", "拥抱", "亲亲", "偷笑", "害羞", "裂开", "Emm", "凋谢", "尴尬", "发呆", "皱眉",
        "让我看看", "转圈", "跳跳", "不敢看", "心碎", "爱心", "脸红", "傲慢", "难过", "合十",
        "玫瑰", "强", "色", "叹气", "苦涩", "调皮", "白眼", "笑脸", "汗", "机智", "撇嘴",
        "嘿哈", "右哼哼", "发怒", "惊恐", "大哭", "憨笑", "敲打", "委屈", "狗子", "笑倒地",
        "抠鼻", "吐", "天啊", "恐惧", "疑问", "坏笑", "握手", "奸笑", "无语", "抱拳", "加油",
        "666", "鼓掌", "酷", "骷髅", "炸弹", "月亮", "太阳", "礼物", "蛋糕", "啤酒", "咖啡",
        "饭", "猪头", "西瓜", "菜刀", "示爱", "胜利", "OK", "打脸", "社会社会", "好的", "翻白眼")

    fun format(text: String): String {
        if (text.isEmpty()) return text
        val kept = ArrayList<String>()
        val masked = bracketToken.replace(text) { match ->
            val name = match.groupValues[1]
            if (name in emotes) { kept.add(name); "\u0001${kept.size - 1}\u0001" } else " "
        }
        var out = masked.replace(punctuation, " ").trim()
        // Real messages glue the emote to the preceding word ("行吧[捂脸]"), so drop the
        // separator the punctuation strip inserted in front of each restored token.
        kept.forEachIndexed { index, name ->
            out = out.replace(" \u0001$index\u0001", "\u0001$index\u0001")
                .replace("\u0001$index\u0001", "[$name]")
        }
        return out.trim()
    }
    private val intrusive = Regex(
        """你.{0,12}(床上|躺着|坐着刷剧|洗澡|穿什么|睡衣|住哪|住址|一个人在家|现在在哪|具体位置|定位|身高|体重|收入|工资|前任|恋爱史|有没有男朋友)"""
    )
    private val realTimeCheck = Regex("""你(现在|这会儿|此刻)?(在干嘛|干什么|做什么|在哪里)""")
    // Filter a proposed meet-up, not any casual sentence that happens to say
    // "有空" or "一起" (for example, offering to send a game guide later).
    private val invite = Regex(
        """(?:(?:这周|周末|下次|改天|哪天|找个时间|有空|忙不忙).{0,12}(?:一起|出来|见(?:个)?(?:一)?面|吃饭|喝|散步|逛|玩|坐坐))|(?:要不要.{0,8}(?:一起|出来|见(?:个)?(?:一)?面|吃饭|咖啡|散步|逛|玩))|(?:一起.{0,8}(?:见(?:个)?(?:一)?面|吃饭|喝|逛|玩|散步|出来))|(?:约(?:个)?(?:饭|见(?:个)?(?:一)?面|时间))|(?:出来.{0,5}(?:坐坐|玩|走走))"""
    )
    private val otherInvited = Regex(
        """((一起|约|要不要|来不来|有空).{0,12}(见面|吃饭|喝|逛|玩|出来))|((见面|吃饭|喝咖啡).{0,8}(吗|吧|？|\?))"""
    )

    // ------------------------------------------------------------ known echo
    // Prompt rules alone never stopped "感冒了啊 多喝热水": the model repeats what
    // she just said before answering. This removes that known-information echo
    // deterministically, before formatting. It only trims a leading restatement,
    // never rewrites, invents or drops the actual reply.
    private val echoNoise = Regex("[\\p{P}\\s]+")
    private val echoLead = setOf('你', '哇', '诶', '哎', '嗯', '哦', '啊', '哈')
    private val echoTail = setOf('啊', '呀', '呢', '吧', '哦', '嗯', '哈', '嘛',
        '喔', '诶', '哎', '了', '哇', '呗', '咯')
    private fun compactEcho(text: String) = echoNoise.replace(text, "")

    /** Trim a leading restatement of the other person's words; keep the real reply. */
    fun stripKnownEcho(text: String, recentOther: List<String>): String {
        if (text.isBlank()) return text
        val others = recentOther.map { compactEcho(it) }.filter { it.length >= 2 }
        if (others.isEmpty()) return text
        var head = 0
        while (head < minOf(text.length, 2) && text[head] in echoLead) head++
        val rest = text.substring(head)
        var matched = 0
        for (len in minOf(rest.length, 24) downTo 2) {
            val piece = compactEcho(rest.substring(0, len))
            if (piece.length >= 2 && others.any { it.contains(piece) }) { matched = len; break }
        }
        if (matched < 2) return text
        var after = head + matched
        while (after < text.length && text[after] in echoTail) after++
        while (after < text.length && echoNoise.matches(text[after].toString())) after++
        // A bare repeat with a real continuation ("感冒了就别熬夜") must stay intact.
        if (after == head + matched) return text
        val remaining = text.substring(after).trim()
        if (compactEcho(remaining).length < 3) return text
        return remaining
    }

    fun stripKnownEcho(candidates: List<ReplySuggestion>, recentOther: List<String>): List<ReplySuggestion> =
        candidates.map { it.copy(text = stripKnownEcho(it.text, recentOther)) }

    // Real-device audit 2026-11-10 (twice, two separate runs): drafts wrote
    // "那我这女朋友身份算转正了 / 算是有着落了" — her status put on the user. Giving the
    // model a negative example in the prompt was not enough, so rewrite this one
    // template locally. "我女朋友" without 这 still means "my girlfriend" and stays.
    private val ownIdentityClaim = Regex("我(?:这|这个|这份)(女朋友|男朋友|对象)身份")

    fun fixSpeakerPerspective(text: String): String =
        if (text.isBlank()) text
        else ownIdentityClaim.replace(text) { "你这" + it.groupValues[1] + "身份" }

    fun fixSpeakerPerspective(candidates: List<ReplySuggestion>): List<ReplySuggestion> =
        candidates.map { it.copy(text = fixSpeakerPerspective(it.text)) }

    fun safe(candidates: List<String>, latestOther: String, allowInvitation: Boolean = false): List<String> {
        val canAnswerInvite = allowInvitation || otherInvited.containsMatchIn(latestOther)
        return candidates.map { it.trim() }.filter { it.isNotEmpty() }
            .filterNot { intrusive.containsMatchIn(it) || realTimeCheck.containsMatchIn(it) ||
                courtesy.containsMatchIn(it.trimStart('“', '"', '「', '『')) ||
                (!canAnswerInvite && invite.containsMatchIn(it)) }
            // Each candidate is an alternative, not another message sent in sequence.
            .map(::format).filter(String::isNotEmpty).distinct().take(3)
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
