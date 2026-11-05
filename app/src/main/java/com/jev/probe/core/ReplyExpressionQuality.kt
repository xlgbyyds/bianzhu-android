package com.jev.probe.core

/** Conservative writing hints, not an AI detector or a message-validity check.
 * Only affects implicit style examples and candidate order. Never edits chat history,
 * removes words, rejects all drafts, or adds a model request.
 */
object ReplyExpressionQuality {
    private val spacing = Regex("[\\p{P}\\s]+")
    private fun compact(text: String) = text.take(500).replace(spacing, "")
    private val persona = Regex(
        "(?:看来|看得出|听起来|说明|难怪).{0,18}(?:眼光|要求|性格|心态|品味|靠谱|实在|成熟|有想法|独立|自律|会生活|信你|信任你|能干|说话.{0,3}直接)|" +
            "你.{0,8}(?:眼光|心态|品味).{0,5}(?:在线|真好|挺好|不错)|" +
            "你.{0,8}(?:挺有自己想法|很有自己想法|挺有面子|挺实在|挺靠谱)"
    )
    private val ornamentalAffection = Regex(
        "(?:心|灵魂).{0,8}(?:停在你|停靠|港湾|归宿)|" +
            "(?:流浪|四海为家).{0,14}(?:码头|港湾|归宿)|" +
            "(?:信任|认可|陪伴|温柔).{0,5}(?:比糖甜|最好的礼物|最美的答案)|" +
            "我这[儿里].{0,4}永远.{0,4}(?:位置|港湾)"
    )
    private val stockBookkeeping = Regex(
        "(?:这句|这话|你这份认可|你这份信任).{0,4}(?:记着|记下|记上)|" +
            "(?:放鸽子|反悔).{0,6}记小本本"
    )
    private val genericTail = Regex(
        "^[啊呀哦哈那这你还也真是确实看来听着我都就很挺太有点的]{0,20}" +
            "(?:有面子|有想法|靠谱|实在|厉害|不错|不容易|挺好|会生活|让人放心)[啊呀了的呢哈]{0,10}$"
    )
    private val opinionRequest = Regex("(?:你觉得|你看我|你认为|评价.{0,3}我|我.{0,8}(?:是不是|算不算|吗|怎么样))")
    private val traitQuestion = Regex("(?:性格|眼光|品味|心态|成熟|靠谱|独立|自律|有想法|实在)")

    /** Scores complete constructions, never isolated 那/这/确实/真是 or ordinary affection. */
    fun penalty(text: String, recentOther: List<String> = emptyList()): Int {
        val line = compact(text)
        if (line.isBlank()) return 0
        // A direct request for an opinion is different from unsolicited character analysis.
        val askedForOpinion = recentOther.takeLast(3).any {
            opinionRequest.containsMatchIn(it) && traitQuestion.containsMatchIn(it)
        }
        var score = 0
        if (!askedForOpinion && persona.containsMatchIn(line)) score += 2
        if (ornamentalAffection.containsMatchIn(line)) score += 2
        if (stockBookkeeping.containsMatchIn(line)) score++
        if (recentOther.takeLast(3).any { redundantEcho(line, compact(it)) }) score += 2
        return score
    }

    private fun redundantEcho(reply: String, other: String): Boolean {
        if (other.length < 4) return false
        val start = reply.removePrefix("那").removePrefix("这")
        // A repeated detail followed by a real question/answer is allowed. A bare repeat
        // can confirm a plan or reciprocate affection; only mark a canned-verdict tail.
        var shared = 0
        while (shared < minOf(start.length, other.length, 30) && start[shared] == other[shared]) shared++
        if (shared < 4) return false
        val tail = start.substring(shared)
        return tail.isNotEmpty() && genericTail.matches(tail)
    }

    fun implicitSampleAllowed(text: String): Boolean = penalty(text) == 0

    /** Stable preference only: keeping all candidates avoids a new "no reply" failure. */
    fun preferConcrete(candidates: List<ReplySuggestion>, recentOther: List<String>): List<ReplySuggestion> =
        candidates.sortedBy { penalty(it.text, recentOther) }
}
