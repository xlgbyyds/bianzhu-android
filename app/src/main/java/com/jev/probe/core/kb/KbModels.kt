package com.jev.probe.core.kb

/**
 * Local knowledge base data model (v1.3, D stage).
 *
 * Contacts and notes use app-private JSON; opt-in visible conversation memory
 * uses an app-private SQLite database. Settings can wipe both stores.
 */

/** One free-text knowledge note the user maintains by hand. */
data class Note(
    val id: String,
    val title: String,
    val content: String,
    val tags: List<String> = emptyList(),
    /** Always injected regardless of what the conversation is about. */
    val alwaysOn: Boolean = false,
    val enabled: Boolean = true,
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * One person (or group) the user chats with. A matching name in another app is
 * not enough to merge identities; [apps] binds only explicitly saved profiles.
 */
data class Contact(
    val id: String,
    val name: String,
    val aliases: List<String> = emptyList(),
    /** Package names this contact has been seen in, e.g. com.tencent.mm. */
    val apps: List<String> = emptyList(),
    /** Explicit per-person consent for retaining visible chat messages. */
    val memoryEnabled: Boolean = false,
    val relationship: String = "",
    val notes: String = "",
    /** Reserved for the (deferred) auto-summary; never written in v1.3. */
    val autoSummary: String = "",
    /** Separate accepted profiles per App; a profile is never shared across apps. */
    val relationshipProfiles: Map<String, RelationshipProfile> = emptyMap(),
    /** Generated on first use or manual refresh; drafts are never injected. */
    val relationshipProfileDrafts: Map<String, RelationshipProfile> = emptyMap(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** Explicitly confirmed remarks are local to the originating App. */
    val appAliases: Map<String, List<String>> = emptyMap()
)

/** Offline information and user-maintained statements, separate from chat evidence. */
fun Contact.profileUserContext(appName: String): String {
    if (appName !in apps) return ""
    val profile = relationshipProfiles[appName]
    return buildString {
        if (relationship.isNotBlank()) append("联系人专属关系与偏好：").append(relationship.take(2400)).append('\n')
        if (notes.isNotBlank()) append("联系人备注：").append(notes.take(1200)).append('\n')
        if (profile != null) {
            val claims = listOf(profile.relationshipStage, profile.attitudeAssessment, profile.conversationDirection) +
                profile.knownFacts + profile.interactionPatterns
            claims.filter { it.userEdited && it.text.isNotBlank() }.distinctBy { it.text }.forEach {
                append("人工保留：").append(it.text.take(500)).append('\n')
            }
        }
    }.trim().take(6000)
}

/** A profile statement with local message references the user can inspect. */
data class ProfileClaim(
    val text: String,
    val sourceRefs: List<String> = emptyList(),
    /** Model confidence, clamped to 0..1; it is not a probability of someone's feelings. */
    val confidence: Double = 0.5,
    val userEdited: Boolean = false
)

/** Evidence-based, editable relationship profile generated through the configured reply-model route. */
data class RelationshipProfile(
    val relationshipStage: ProfileClaim,
    val attitudeAssessment: ProfileClaim,
    val knownFacts: List<ProfileClaim>,
    val interactionPatterns: List<ProfileClaim>,
    /** Shared conversation direction grounded in both speakers' messages. */
    val conversationDirection: ProfileClaim = ProfileClaim(
        "优先沿着双方都愿意延续的现有话题自然交流；若缺少共同话题，先回应眼前对话，不预设关系目标。",
        emptyList(), 0.2
    ),
    val pacingAdvice: String,
    val unknowns: List<String>,
    val sourceApp: String,
    val sourceLineCount: Int,
    val sourcePageCount: Int,
    val sourceMeCount: Int,
    val sourceOtherCount: Int,
    val sourceUnknownSideCount: Int,
    val sourceVisibleTimeCount: Int,
    val sourceHistoricalCount: Int,
    /** Capture time coverage, not message send time. */
    val sourceFirstObservedAt: Long,
    val sourceLatestObservedAt: Long,
    val generatedAt: Long = System.currentTimeMillis(),
    val promptVersion: Int = 3,
    /** Exact evidence identity for new profiles; empty for legacy/manual profiles. */
    val sourceFingerprint: String = ""
) {
    /** Model refreshes cannot erase explicit edits; manual edits use the normal save path. */
    fun retainingUserEdits(previous: RelationshipProfile?): RelationshipProfile {
        if (previous == null || previous.sourceApp != sourceApp) return this
        fun keep(old: ProfileClaim, fresh: ProfileClaim) = if (old.userEdited) old else fresh
        fun keepList(old: List<ProfileClaim>, fresh: List<ProfileClaim>) =
            (old.filter { it.userEdited } + fresh).distinctBy { it.text.trim() }
        return copy(
            relationshipStage = keep(previous.relationshipStage, relationshipStage),
            attitudeAssessment = keep(previous.attitudeAssessment, attitudeAssessment),
            conversationDirection = keep(previous.conversationDirection, conversationDirection),
            knownFacts = keepList(previous.knownFacts, knownFacts),
            interactionPatterns = keepList(previous.interactionPatterns, interactionPatterns)
        )
    }

    fun coverageLabel(): String = buildString {
        append("基于 ").append(sourceLineCount).append(" 条记录")
        if (sourcePageCount > 0) append(" / ").append(sourcePageCount).append(" 屏")
        append("；页面时间标签 ").append(sourceVisibleTimeCount).append(" 条")
        if (sourceHistoricalCount > 0) append("；补录 ").append(sourceHistoricalCount).append(" 条")
        append("。采集顺序不等于发送顺序，未标出的发送时间未知。")
    }

    fun contextText(): String = buildString {
        append(if (relationshipStage.userEdited) "用户补充的当前关系阶段（可含线下事实，未由聊天独立核实）："
            else "关系阶段（模型推测，非对方确认）：").append(relationshipStage.text.trim().take(150)).append('\n')
        if (attitudeAssessment.userEdited) {
            append("用户维护的态度判断（主观记录，非对方确认）：")
        } else {
            append("对方态度（模型推测，置信度 ").append(percent(attitudeAssessment.confidence)).append("）：")
        }
        append(attitudeAssessment.text.trim().take(180)).append('\n')
        if (knownFacts.isNotEmpty()) {
            append(if (knownFacts.any { it.userEdited }) "用户维护的信息（未由 Jev 独立核实）："
                else "聊天中可核对的事实：")
            append(knownFacts.take(3).joinToString("；") { it.text.trim().take(60) }).append('\n')
        }
        if (interactionPatterns.isNotEmpty()) {
            append("互动模式（推测）：")
            append(interactionPatterns.take(2).joinToString("；") { it.text.trim().take(50) }).append('\n')
        }
        if (conversationDirection.text.isNotBlank()) {
            append(if (conversationDirection.userEdited) "用户维护的聊天方向：" else "可参考的聊天方向（模型推测）：")
                .append(conversationDirection.text.trim().take(120)).append('\n')
        }
        if (pacingAdvice.isNotBlank()) append("建议节奏：").append(pacingAdvice.trim().take(120)).append('\n')
        if (unknowns.isNotEmpty()) append("尚不能判断：").append(unknowns.take(2).joinToString("；") { it.take(45) }).append('\n')
        append("档案覆盖：").append(coverageLabel().take(80))
    }.trim().take(1_300)

    private fun percent(value: Double) = "${(value.coerceIn(0.0, 1.0) * 100).toInt()}%"
}

/** One unique locally stored visible message used as profile-generation input. */
data class ProfileLine(
    val ref: String,
    val side: String,
    val text: String,
    val observedAt: Long,
    val app: String,
    val kind: String,
    val visibleTime: String?,
    val timeConfidence: String,
    val historical: Boolean
)

/** Isolated to one contact and one App; lines remain in capture order. */
data class RelationshipSource(
    val app: String,
    val pages: Int,
    val lines: List<ProfileLine>,
    val firstObservedAt: Long,
    val latestObservedAt: Long
) {
    val meCount get() = lines.count { it.side == "me" }
    val otherCount get() = lines.count { it.side == "other" }
    val unknownSideCount get() = lines.size - meCount - otherCount
    val visibleTimeCount get() = lines.count { !it.visibleTime.isNullOrBlank() }
    val historicalCount get() = lines.count { it.historical }
}

/** ts is when the phone observed the line; visibleTime is a separate UI label. */
data class LogEntry(
    val side: String, val text: String, val ts: Long, val app: String,
    val kind: String = "TEXT", val visibleTime: String? = null,
    val timeConfidence: String = "unknown", val historical: Boolean = false
) {
    fun modelText(): String = buildString {
        if (historical) append("[补录旧消息] ")
        if (kind == "VOICE_TRANSCRIPT") append("[语音转写] ")
        if (!visibleTime.isNullOrBlank()) append("[页面时间：$visibleTime；" + when (timeConfidence) {
            "manual" -> "用户人工填写"
            "partial" -> "页面标签，非逐条精确时间"
            "exact" -> "页面显示的精确时间"
            else -> "时间精度未知"
        } + "] ")
        else append("[发送时间未知] ")
        append(when (kind) {
            "VOICE_UNKNOWN" -> "[语音消息，内容未知]"
            "MEDIA_UNKNOWN" -> "[非文字消息，内容未知]"
            else -> text
        })
    }
}

/**
 * What one analysis gets to see beyond the on-screen messages: who the other
 * party is, older history for them, and the knowledge notes that matched.
 */
data class ChatContext(
    val contact: Contact?,
    val history: List<LogEntry>,
    val notes: List<Note>,
    /** App-scoped accepted profile selected for this analysis. */
    val relationshipProfile: RelationshipProfile? = null,
    val replyVoiceGuidance: String = ""
) {

    /** User-authored per-person direction wins; an accepted profile precedes the global default. */
    fun effectiveRelationship(defaultRelationship: String): String =
        contact?.relationship?.trim()?.takeIf { it.isNotBlank() }
            ?: relationshipProfile?.relationshipStage?.text?.trim()?.take(300)?.takeIf { it.isNotBlank() }
            ?: defaultRelationship

    /** True when there is nothing extra to inject (then no field is sent at all). */
    fun isEmpty(): Boolean = history.isEmpty() && notes.isEmpty() &&
        (contact == null || (contact.relationship.isBlank() &&
            contact.notes.isBlank() && contact.autoSummary.isBlank() && relationshipProfile == null))

    /**
     * The `background` string injected into Jev's state and the reply prompt:
     * relationship + contact notes + auto-summary + each matched note as
     * "title: content". Blank when there is nothing to say — callers must then
     * omit the field entirely rather than send an empty one.
     *
     * @param defaultRelationship unused when the contact carries no relationship
     *        of its own — that global default already goes out separately as
     *        `chat.relationship`, so repeating it here would just duplicate it.
     *        A contact with no relationship set simply omits the "关系：" line.
     */
    fun background(defaultRelationship: String, includeRelationship: Boolean = true): String {
        val sb = StringBuilder()
        contact?.let { c ->
            val rel = c.relationship.trim()
            if (includeRelationship && rel.isNotEmpty()) sb.append("关系：").append(rel).append('\n')
            if (c.notes.isNotBlank()) sb.append("关于").append(c.name).append("：")
                .append(c.notes.trim()).append('\n')
            if (c.autoSummary.isNotBlank()) sb.append("过往摘要：")
                .append(c.autoSummary.trim()).append('\n')
            relationshipProfile?.let { profile ->
                sb.append("自动关联的联系人关系档案（模型推测，非对方确认；不要把态度推测当成事实）：\n")
                    .append(profile.contextText()).append('\n')
            }
        }
        notes.forEach { n ->
            sb.append(n.title.trim()).append(": ").append(n.content.trim()).append('\n')
        }
        return sb.toString().trim()
    }
}
