package com.jev.probe.jev

import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.MessageKind
import com.jev.probe.core.ReplySuggestion
import com.jev.probe.core.kb.ChatContext
import org.json.JSONObject

/** A separate, user-triggered purpose; never an instruction to send a message. */
enum class ConversationPurpose(val label: String, val key: String) {
    REPLY("接话", "reply"), OPENING("开场", "opener"), INVITATION("邀约", "invitation")
}

data class InvitationEvidence(val id: String, val side: String, val text: String, val source: String) {
    fun display(): String = "[$id] $source from=$side ${if (side == "me") "我" else "对方"}：$text"
}

enum class InvitationAction(val label: String) {
    INVITE("可以提出具体邀约"), REFRAME("换个切口再邀请"), CONFIRM("落实时间与活动"),
    CLARIFY("先问清一个实际条件"), WAIT("本轮先正常接话"), STOP("停止当前邀约方向")
}

data class InvitationPlan(
    val action: InvitationAction,
    val basis: InvitationEvidence?,
    val obstacle: String,
    val evidence: List<InvitationEvidence>
) {
    fun prompt(): String = buildString {
        append("Jev 本轮先行决策：").append(action.name).append(" ").append(action.label).append('\n')
        append("主要条件：").append(obstacle).append('\n')
        append("依据：").append(basis?.display() ?: "未选到可靠的对方原话 不推定其见面意愿").append('\n')
        append("证据只覆盖本轮可见消息与相关记忆 不是全量记录或对方真实心理的证明\n")
    }
}

data class InvitationResult(val plan: InvitationPlan, val replies: List<ReplySuggestion>)

/** Same bounded evidence is sent to both models, with provenance and speaker intact. */
object InvitationPolicy {
    fun evidence(snapshot: ChatSnapshot, ctx: ChatContext?): List<InvitationEvidence> {
        val history = ctx?.history.orEmpty().takeLast(24).filter {
            it.side in setOf("me", "other") && it.kind in setOf("TEXT", "VOICE_TRANSCRIPT")
        }.map { Triple(it.side, it.modelText().take(420), "本地记忆 采集顺序不等于发送顺序") }
        val page = snapshot.messages.takeLast(16).filter {
            it.side in setOf("me", "other") && it.kind in setOf(MessageKind.TEXT, MessageKind.VOICE_TRANSCRIPT)
        }.map { Triple(it.side, it.modelText().take(420), "点击时可见消息 不代表刚收到") }
        return (history + page).mapIndexed { i, row ->
            InvitationEvidence("e$i", row.first, row.second, row.third)
        }
    }

    fun questions(evidence: List<InvitationEvidence>): JSONObject {
        fun choice(instructions: String, options: Map<String, String>) = JSONObject()
            .put("type", "choice").put("instructions", instructions).put("criteria", JSONObject(options))
        val basisOptions = linkedMapOf("none" to "No reliable other-person quote supports a next step.")
        evidence.filter { it.side == "other" }.forEach { basisOptions[it.id] = it.display() }
        return JSONObject()
            .put("invitation_action", choice(
                "The user explicitly asks for help arranging a date. Choose ONE next action using only the supplied evidence. " +
                    "A first low-pressure invitation can be proposed without proof of romantic interest. " +
                    "After ambiguity allow another invitation with a new activity, feasible time or an addressed concern. " +
                    "Do not treat 'busy', 'too fast' or 'maybe' as agreement or automatically as permanent rejection. " +
                    "Do not repeatedly pursue an unanswered invitation in the same exchange. If nothing changed, WAIT. " +
                    "An explicit don't want to meet/develop this/don't ask again means STOP. " +
                    "CONFIRM only for the other person's actual agreement, not the user's wish, teasing or a profile's speculation. " +
                    "A concrete time refusal with an alternative is CONFIRM. Explicit refusal of a past date is not a permanent ban. " +
                    "Use context to distinguish negation, quotes and third parties. Old schedules are not current availability. " +
                    "Visible/captured chronology may be incomplete; unknown times stay unknown. Inconsistent speaker/time evidence calls for CLARIFY. " +
                    "Latest actual boundary takes priority over an old optimistic relationship profile.",
                linkedMapOf("invite" to "Propose a first concrete invitation or use a new shared interest/time opening.",
                    "reframe" to "Address a stated concern or offer a genuinely new activity/time after hesitation.",
                    "confirm" to "They agreed to meeting or offered a real alternative: settle logistics.",
                    "clarify" to "One missing practical condition or uncertain evidence must be clarified.",
                    "wait" to "Listen/respond normally; no new basis after an unanswered attempt, or current distress needs attention.",
                    "stop" to "They explicitly reject this direction or ask to stop invitations.")))
            .put("invitation_basis", choice(
                "Choose the single strongest original quote FROM THE OTHER PERSON for the action, " +
                    "including a hesitation or refusal. Prefer specific current-page evidence over stale memory. " +
                    "A quote's existence is evidence, not proof of feelings or present availability. Never select the user's own words.", basisOptions))
            .put("invitation_obstacle", choice(
                "What practical issue should this next message address? Do not guess hidden motives or invent body-image concerns.",
                linkedMapOf("activity" to "Find a mutually relevant activity.", "time" to "Find or confirm a feasible time.",
                    "pace" to "They explicitly expressed moving too quickly or wanting more familiarity.",
                    "comfort" to "They explicitly expressed awkwardness, appearance worry or another comfort concern.",
                    "boundary" to "A refusal or request to stop.", "unknown" to "No confirmed obstacle.")))
    }

    fun parse(answers: JSONObject, evidence: List<InvitationEvidence>): InvitationPlan {
        fun value(key: String) = answers.optJSONObject(key)?.optString("choice").orEmpty()
        val chosenAction = when (value("invitation_action")) {
            "invite" -> InvitationAction.INVITE; "reframe" -> InvitationAction.REFRAME
            "confirm" -> InvitationAction.CONFIRM; "clarify" -> InvitationAction.CLARIFY
            "wait" -> InvitationAction.WAIT; "stop" -> InvitationAction.STOP
            else -> throw IllegalStateException("Jev 未返回有效邀约决策 本次未继续生成")
        }
        val latestOther = evidence.lastOrNull { it.side == "other" && it.source.startsWith("点击时可见") }
        val directStop = latestOther?.takeIf { isDirectStop(it.text) }
        val action = if (directStop != null) InvitationAction.STOP else chosenAction
        val selected = directStop ?: evidence.firstOrNull { it.id == value("invitation_basis") && it.side == "other" }
        // Independent choice questions can pick different parts of a long history.
        // A deferral such as 再说吧 cannot substantiate CONFIRM. For that action,
        // prefer an explicit, locally checked answer on the visible page.
        val basis = if (action == InvitationAction.CONFIRM) {
            val pageAcceptance = evidence.lastOrNull { it.side == "other" &&
                it.source.startsWith("点击时可见") && isAcceptance(it.text) }
            pageAcceptance ?: selected?.takeIf { isAcceptance(it.text) }
        } else selected
        val safeAction = if (basis == null && action in setOf(InvitationAction.CONFIRM, InvitationAction.REFRAME))
            InvitationAction.CLARIFY else action
        val obstacle = when (value("invitation_obstacle")) {
            "activity" -> "共同活动"; "time" -> "时间安排"; "pace" -> "相处节奏"
            "comfort" -> "对方明确表达的顾虑"; "boundary" -> "对方边界"; else -> "尚不明确"
        }
        return InvitationPlan(safeAction, basis, obstacle, evidence)
    }

    internal fun isAcceptance(text: String): Boolean {
        val plain = text.replace(Regex("\\[[^]]*]"), "").trim()
        if (Regex("不行|不可以|不方便|算了|再说|看看|太快|下次|不一定|没答应|还没|如果|假如|她说|他说|不想|不要|但是|不过|可能|可以吧").containsMatchIn(plain))
            return false
        return Regex("^(可以|好啊|好呀|好哇|好哒|行啊|行呀|行吧|行的|没问题|愿意|那就|就这么定)").containsMatchIn(plain) ||
            Regex("(周[一二三四五六日天]|星期[一二三四五六日天]|明天|后天|下午|晚上).{0,8}(可以|有空|方便|能去)").containsMatchIn(plain)
    }

    private fun isDirectStop(text: String): Boolean {
        val plain = text.replace(Regex("\\[[^]]*]"), "").replace(Regex("[\\p{P}\\s]"), "")
        return Regex("^(我)?(暂时|现在|真的)?(不想见面|不想见你|不想和你见面|不想发展|别再约我|不要再约我|不要再联系)(了|啦|哈|吧)?$")
            .matches(plain)
    }

    fun followUps(action: InvitationAction): List<String> = when (action) {
        InvitationAction.STOP -> listOf("对方已明确拒绝当前方向 不继续劝说或换话术追问")
        InvitationAction.WAIT -> listOf("这轮正常聊 后续有新的活动 时间或互动依据时再考虑邀约")
        else -> listOf("她答应：只落实一个尚未确定的时间或地点 别重复说服",
            "时间不合适：有替代时间就接住 没有则先收住本轮",
            "她犹豫：回应她明确说出的顾虑 可换更轻松的方案 不替她猜心思",
            "仍含糊或没接：本轮先收住 有新依据时可再邀请 明确不想见则停止")
    }
}
