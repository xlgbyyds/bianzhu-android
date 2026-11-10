package com.jev.probe.jev

import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.MessageKind

/** Curated communication principles, not imported biographies or a script sequence.
 * Selection is local, bounded and based on actual text. It never infers consent or identity.
 */
internal object SocialTutorialGuide {
    data class Lesson(val id: String, val decision: String, val writing: String)
    private val readable = setOf(MessageKind.TEXT, MessageKind.VOICE_TRANSCRIPT)
    private val question = Regex("[?？]|(?:吗|么|呢|没|多大|哪里|为什么|咋样|什么)$")
    private val roleWord = Regex("(?i)(?<![a-z])(?:dom|sub|brat|switch|sm|ds|s|m)(?![a-z])|圈内|圈子|属性")
    private val contact = Regex("微信|绿泡泡|联系方式|加[个下]?[qQvV]|[qQvV]号")
    private val relationshipConcern = Regex(
        "不(?:想|愿意|方便)?(?:加(?!班|工|薪|油|热)(?:你|人|好友|微信|[qQvV])?|见面|出来|发(?:照片|自拍)|换联系方式)|" +
            "不要(?:加|约|催)|别(?:催|问了|这样|叫我)|先别|太快|太急|还不熟|慢慢来|防备心|" +
            "(?:有点|有些|还是|我很|我会)?害怕|(?:有点|还是|我很)怕|^不敢[了吧呀啊]?[^\\p{L}]*$|只.{0,3}这.{0,2}聊|只聊|^算了[吧啊]?$")

    fun latest(snapshot: ChatSnapshot): String = snapshot.messages.lastOrNull { it.side == "other" }
        ?.takeIf { it.kind in readable }?.text?.trim().orEmpty()

    fun hasConcern(snapshot: ChatSnapshot): Boolean = relationshipConcern.containsMatchIn(latest(snapshot))

    fun select(snapshot: ChatSnapshot, roles: Boolean): Lesson? {
        val text = latest(snapshot)
        if (text.isBlank()) return null
        return when {
            ReplyTurnPolicy.mode(snapshot) == ReplyTurnPolicy.Mode.BOUNDARY -> null
            ReplyTurnPolicy.mode(snapshot) == ReplyTurnPolicy.Mode.STYLE_FEEDBACK ||
                Regex("话术|套路|你问我答|查户口|像.{0,4}(?:客服|机器人)|换.{0,4}微信.{0,5}变").containsMatchIn(text) -> Lesson(
                "style_reset", "对方在反馈交流方式，先修正接法，不判作好感或故意考验。",
                "简短接下反馈，不谎称没用AI，不反问她是否被套路过。例：好 我换个说法。只有记录里确实连续提问，才能说刚才问太密；别编造自己之前做过什么，一小句就停。")
            ReplyTurnPolicy.mode(snapshot) == ReplyTurnPolicy.Mode.HURT -> null
            Regex("不方便.{0,3}(?:语音|视频|说话)|只能.{0,2}(?:打字|文字)|先.{0,2}(?:文字|打字)|宿舍.{0,6}(?:人|说话|语音)").containsMatchIn(text) -> Lesson(
                "text_only", "这是沟通方式的现实条件，不等于拒绝认识，也不是换渠道的理由。",
                "顺着她方便的文字方式聊，不劝戴耳机、偷开语音或换软件。例：打字就行。")
            roles && Regex("不(?:是|当|做|喜欢当)[mMsS]|不玩圈|圈外|不想.{0,3}(?:角色|认主)").containsMatchIn(text) -> Lesson(
                "role_mismatch", "以本人说明为准，角色不合不等于需要说服或转换她。",
                "回到普通认识，不猜她其实是某种角色。例：也可以正常聊。不要为了留下她假装换成另一身份。")
            hasConcern(snapshot) -> null // Existing contact/meeting boundary handling has priority.
            Regex("(?:转|付|交|发).{0,5}(?:定金|押金|房费|红包|验证码)|订房链接|付款链接").containsMatchIn(text) -> Lesson(
                "payment_boundary", "出现付款或验证码要求，停止升级邀约；仅凭这一句不能断言对方是骗子。",
                "不提供账号验证码，不承诺转账或打开对方给的付款链接，也不辱骂。例：先不转账 就在这聊。")
            roles && roleWord.containsMatchIn(snapshot.messages.takeLast(4).joinToString(" ") { it.text }) &&
                Regex("什么意思|是什么|不懂|没听过|解释|什么区别").containsMatchIn(text) -> Lesson(
                "role_explain", "解释当前问到的用语，不把好奇或不懂当作认同某种角色。",
                "用一两句普通话说明相处偏好，可参考已提供的用语解读；别卖资历、做人格测试或借机让她认领身份。不懂也可以普通聊。")
            Regex("只是想约|只想.{0,3}(?:约|睡)|只为.{0,3}(?:约|性)|是不是.{0,5}(?:骗|海王)|图什么").containsMatchIn(text) -> Lesson(
                "intent_doubt", "这是对意图或可信度的疑问，不自动解释成兴趣或撒娇。",
                "按真实意图直说，不许诺专一恋爱换信任，不拉踩别人。例：想认识你 看合不合拍。若用户的长期意图未知，就别代他承诺。")
            Regex("照片|自拍|看看你|长啥样|长什么样").containsMatchIn(text) -> Lesson(
                "photo_exchange", "普通照片交换需双方愿意，不推导外貌评价或身份已核实。",
                "只提议使用本人真实普通近照，是否发由用户操作；不谎称已发、不拿别人照片当本人、不催她回图。例：可以 换张近照。")
            contact.containsMatchIn(text) && Regex("加|换|发|给|留").containsMatchIn(text) -> Lesson(
                "contact_exchange", "换联系方式只确认渠道和交换方式，不等于已答应见面或亲密关系。",
                "对方已提出就直接接住，别重复销售自己或一次要两种账号。例：可以 你发我。未提供真实账号不要编，也不要说已经加好。")
            oneWayQuestions(snapshot) -> Lesson(
                "reciprocity", "连续两轮我方提问、对方简短作答，只说明交流单向，不能确定她没兴趣。",
                "这一轮停止追问，用一句真实的自身偏好或短反应；没提供自己的经历就别编。不说她高冷、惜字如金，也不凭短回推进邀约。")
            Regex("周末|下周|没空|有空|太远|不顺路|赶时间|几点|哪里见|在哪见").containsMatchIn(text) -> Lesson(
                "logistics", "区分现实时间地点条件与意愿；忙、距离远不自动等于拒绝，也不等于同意。",
                "只回应当前一个条件，别给她安排一整天。普通回复不擅自邀约；点击邀约时由Jev决定提出方案、澄清还是等待。")
            Regex("加班|下班|上班|好累|饿了|考试|作业").containsMatchIn(text) -> Lesson(
                "daily_detail", "按她说出的生活事实回应，不将话题自动成人化或当成邀约窗口。",
                "不喜欢加班是偏好，不是正在加班；没说今天加班，不能问今天又加班或加到几点。已说今天加班也不代表此刻仍在公司。给短反应或接已有细节，少写漂亮安慰和人物点评。")
            Regex("动态|帖子|广场|评论|你发的|这首歌|这家店|(?<!不)喜欢|想去|想看|爱好").containsMatchIn(text) -> Lesson(
                "shared_topic", "从明确谈到的兴趣或来聊的原因接话；点赞和评论只证明一次互动。",
                "抓一件具体事接，不赞美她有品位、不假装也去过或也喜欢；自己有真实体验再分享。普通聊先接兴趣，邀约入口才考虑相关活动。")
            else -> null
        }
    }

    private fun oneWayQuestions(snapshot: ChatSnapshot): Boolean {
        val tail = snapshot.messages.takeLast(4)
        return tail.size == 4 && tail.map { it.side } == listOf("me", "other", "me", "other") &&
            tail.all { it.kind in readable } && listOf(tail[0], tail[2]).all { question.containsMatchIn(it.text.trim()) } &&
            listOf(tail[1], tail[3]).all { it.text.trim().length in 1..6 && !question.containsMatchIn(it.text.trim()) }
    }

    /** Examples are proposed activities, never claims that a venue or time has been confirmed. */
    fun invitationDetail(snapshot: ChatSnapshot, action: InvitationAction): String {
        if (action !in setOf(InvitationAction.INVITE, InvitationAction.REFRAME)) return ""
        if (hasConcern(snapshot)) return "先回应顾虑，不使用活动范文劝进；不将犹豫解释成默许。"
        val text = latest(snapshot)
        if (Regex("太远|不顺路|赶时间|没空|周末不行").containsMatchIn(text))
            return "若Jev允许换切口，只调整对方提过的距离或时间条件；候选方案不是既定约定，不催她解释。"
        val activity = listOf("看展" to "一起去看个展", "咖啡" to "找个咖啡店坐坐", "电影" to "挑部都想看的电影",
            "桌游" to "去桌游店玩一会", "散步" to "找个公园走走").firstOrNull { (topic, _) ->
            Regex("(?:喜欢|想去|想看|有兴趣|打算).{0,8}${Regex.escape(topic)}").containsMatchIn(text) &&
                !Regex("不.{0,5}${Regex.escape(topic)}|不想|不喜欢").containsMatchIn(text)
        }?.second ?: return ""
        return "可参考当前兴趣提出一个公开活动：$activity。只提方案，不编自己也喜欢、很久没去、已查店买票订好等经历；不借兴趣把地点改成住所或酒店。"
    }
}
