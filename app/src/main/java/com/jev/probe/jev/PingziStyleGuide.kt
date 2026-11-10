package com.jev.probe.jev

import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.MessageKind

/** Original, bounded writing examples distilled from user-supplied screenshots.
 * No screenshot, account, biography or sexual script is bundled with the app.
 * The scene prefix is produced by RoleChatGuide only for the Pingzi package.
 */
internal object PingziStyleGuide {
    private const val SCENE = "角色沟通场景规则：仅适用于当前瓶子说匿名对话。"
    const val MARKER = "【瓶子说本轮写法】"
    const val BACKGROUND = "瓶子说写法：短句直说，一次一个意思；有问题先答，不复述已知信息，不写人物点评或纯爱宣言。" +
        "允许平实短反应；有双方接梗的语境才轻逗。截图样例只学表达，不继承其中的年龄、城市、经历、账号或角色身份。" +
        "加联系方式与见面分别判断，不把回复快、未离开、标签或暧昧话当作同意；不假装纯情或资深，不拉踩他人，不用压力或露骨性话术换取回应。\n"

    private val contactOffer = Regex("加.{0,4}(?:微信|绿泡泡|联系方式|[qQvV]|你)|(?:微信|[qQvV]|联系方式).{0,4}(?:给你|发你)")
    private val intent = Regex("来这.{0,3}(?:干嘛|做什么)|找什么|想找|想认识|谈恋爱|什么关系")
    private val personalFact = Regex("你.{0,4}(?:多大|几岁|哪里人|哪的人|多高|多重|做什么工作|干什么工作|经验|谈过|几次)")
    private val banter = Regex("这么直接|挺直接|嘴甜|会说|会聊|油嘴|调皮|不听话")
    private val greeting = Regex("^(你好呀?|哈[喽啰咯]|嗨|hi|hello|在吗)[！!？?~～\\s]*$", RegexOption.IGNORE_CASE)

    fun active(snapshot: ChatSnapshot, scene: String): Boolean =
        snapshot.roleChat != null && scene.startsWith(SCENE) &&
            (snapshot.roleChat.profileAge ?: 18) >= 18 &&
            ReplyTurnPolicy.mode(snapshot) == ReplyTurnPolicy.Mode.ORDINARY &&
            snapshot.messages.lastOrNull { it.side == "other" }?.kind.let {
                it == null || it in setOf(MessageKind.TEXT, MessageKind.VOICE_TRANSCRIPT)
            }

    fun replyInstruction(snapshot: ChatSnapshot, scene: String, opener: Boolean): String {
        if (!active(snapshot, scene)) return ""
        val others = snapshot.messages.filter { it.side == "other" }.takeLast(3)
        val latest = others.lastOrNull()?.text.orEmpty()
        if (latest.isBlank() && !opener) return ""
        val cautious = SocialTutorialGuide.hasConcern(snapshot)
        val lesson = SocialTutorialGuide.select(snapshot, roles = true)
        val example = when {
            lesson?.id in setOf("text_only", "role_mismatch", "payment_boundary") -> lesson!!.writing
            cautious -> "本轮有顾虑或不愿意的表达：短句接住，不追加角色玩笑、联系方式或见面请求。" +
                "写法示例：她说先不加 就在这聊，可回 行 就在这聊。"
            contactOffer.containsMatchIn(latest) -> "本轮对方主动提出换联系方式：直接回应她选的渠道，只处理交换方式。" +
                "写法示例：她说加q吧，可回 可以 你发我。不能凭空给账号、说已经添加，也不顺带约见面。" +
                "三条可以都是平实确认，不为凑 follow_detail 编造我不常用QQ、我很少上线、我刚下班等习惯或状态。"
            personalFact.containsMatchIn(latest) -> "本轮在问用户的具体事实：有当前用户资料或我方原话才回答对应事实，别用反问回避已知问题。" +
                "没有资料就不要编数字、城市、工作、经历或经验，也不拿教学样例里的人设代答。" +
                "不要把缺资料包装成你猜、保密、猜中了告诉你的游戏，不要三条都绕开问题。"
            intent.containsMatchIn(latest) -> "本轮在问交友意图：依据用户实际选择直接表达想认识或继续聊，不假装长期承诺。" +
                "虚构写法示例：想认识聊得来的人 合适就继续。用户意图未提供就不替他选长期或短期关系。"
            banter.containsMatchIn(latest) -> "本轮可以轻轻接梗：说自己的兴趣或反应，不评价她性格，不靠压过她证明主导。" +
                "虚构写法示例：她说你怎么这么直接，可回 想认识你 就直说了。每组最多一条轻逗，其余平实。"
            lesson != null -> "参考类型=${lesson.id}。" + lesson.writing
            greeting.matches(latest.trim()) -> "本轮只是互相招呼：直接表达想认识或抛一个容易接的小话题，不评论她的招呼。" +
                "虚构写法示例：想找个人聊两句 你呢。不要写这句好正式、这么高冷、终于等到你；playful 没有共同细节就平实写。"
            latest.isBlank() -> "本轮尚无对方消息：给简单招呼或真实认识意愿，不查户口，不凭平台给对方分配角色。" +
                "虚构写法示例：想找个人聊两句 你呢。"
            else -> "本轮直接接当前原话；生活话题就正常聊，角色玩笑按已明确偏好回应，不硬加称呼。" +
                "短反应也可以，不把每句变成问题、安慰或推进关系。"
        }
        return MARKER + "只用于当前匿名会话。三条是互斥候选，每条一两个口语短句，一次最多一个问题。" +
            "不复述她刚说的事实，不把样例写成用户经历，不输出讲解或策略。" +
            (if (opener) "即使点击帮我开场，对方有尚未回答的问题也先回答；仅在没有待接话时才另起话题。" +
                "fresh_opening 直接接话或表达认识意愿，light_callback 只接当前匿名会话的轻松细节，playful 有语境才轻逗，不强制换题。" else "") +
            example + "示例只参考句长和回应方式，不固定照抄，不用谐音暗号绕开内容限制。"
    }

    fun invitationInstruction(snapshot: ChatSnapshot, scene: String, action: InvitationAction): String {
        if (!active(snapshot, scene)) return ""
        val direction = when (action) {
            InvitationAction.WAIT, InvitationAction.STOP -> "本轮执行 ${action.name}，只简短接话，不追加加账号或见面要求。"
            InvitationAction.CLARIFY -> "只问清 Jev 指定的一个实际条件，不捆绑加微信或见面。"
            InvitationAction.CONFIRM -> "只落实对方已同意见面的未定细节；答应加微信不是答应见面，不借机换成私密地点。"
            else -> if (SocialTutorialGuide.hasConcern(snapshot)) "先处理当前顾虑，不默认对方已答应，不套固定邀约范文。"
            else "按 Jev 判断提出一个具体、可调整的公开活动，不把见面默认成亲密接触。" +
                "虚构写法示例：想和你见一面 找个咖啡店坐坐。没有同城、时间等事实就别编地点和行程。"
        }
        return MARKER + "邀约用短句直接说明意愿，一次一个目标，不展示技巧或宣告主导。" + direction +
            SocialTutorialGuide.invitationDetail(snapshot, action)
    }
}
