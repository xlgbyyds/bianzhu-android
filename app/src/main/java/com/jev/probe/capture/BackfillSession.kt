package com.jev.probe.capture

/** Explicit recording consent belongs to one contact and one run, never a cached page. */
internal class BackfillSession {
    data class Run(val generation: Long, val target: ConversationSession.Target, val contactId: String,
                   val progress: BackfillProgress = BackfillProgress())
    private var generation = 0L
    var active: Run? = null
        private set

    fun start(target: ConversationSession.Target, contactId: String): Run? {
        if (target.title.isNullOrBlank() || contactId.isBlank()) return null
        return Run(++generation, target, contactId).also { active = it }
    }

    fun isCurrent(run: Run) = active == run
    fun accepts(run: Run, live: ConversationSession.Target?) = isCurrent(run) && run.target == live
    enum class WindowState { TARGET, WAIT, LEFT }
    fun windowState(run: Run, pkg: String?, windowId: Int?): WindowState = when {
        !isCurrent(run) -> WindowState.LEFT
        pkg == null || pkg == "com.android.systemui" -> WindowState.WAIT
        pkg == run.target.pkg && windowId == run.target.windowId -> WindowState.TARGET
        else -> WindowState.LEFT
    }
    fun stop(): Run? = active.also { active = null; generation++ }
}

internal class BackfillProgress {
    private val checked = HashSet<String>()
    private val pending = HashSet<String>()
    var added: Int = 0; private set
    var timeUpdated: Int = 0; private set
    fun accept(snapshot: com.jev.probe.core.ChatSnapshot, result: com.jev.probe.core.kb.CaptureResult) {
        val content = snapshot.messages.joinToString("|") {
            "${it.side}:${it.kind}:${it.text.length}:${it.text}"
        }
        accept(content, result)
    }
    fun accept(page: String, result: com.jev.probe.core.kb.CaptureResult) {
        if (result.saved) { checked.add(page); pending.remove(page) }
        else pending.add(page)
        added += result.added
        timeUpdated += result.timeUpdated
    }
    fun label() = "本次核对 ${checked.size} 屏 新增 $added 条 补时间 $timeUpdated 条 待核实 ${pending.size} 屏"
}
