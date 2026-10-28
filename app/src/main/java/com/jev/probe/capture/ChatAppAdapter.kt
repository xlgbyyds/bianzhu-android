package com.jev.probe.capture

import android.content.res.Resources
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.jev.probe.core.BubbleRect
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.MessageKind
import com.jev.probe.core.Msg
import com.jev.probe.core.VoiceTranscriptRect

/**
 * Per-app capture rules. An adapter turns one messaging app's open chat window
 * into a neutral [ChatSnapshot]; everything downstream (Jev judgment, overlay,
 * fill) is app-agnostic.
 *
 * [extract]'s three-way contract (v1.3 B stage — the service depends on it):
 * - `null`            → not in this app's chat window (list screen, moments,
 *                       settings…). The service does nothing at all.
 * - messages empty    → in a chat window, but the tree carries no message text.
 *                       The service may fall back to screenshot + OCR. Each
 *                       adapter names below what proves "we are in a chat".
 * - messages non-empty→ normal capture.
 *
 * The disguised accessibility service (registered as SelectToSpeakService) lets
 * us read the node tree of apps that obfuscate it for normal services (WeChat).
 * Feishu/Lark does not obfuscate, so its adapter reads plain resource-ids.
 */
interface ChatAppAdapter {
    val pkg: String
    fun extract(root: AccessibilityNodeInfo, res: Resources): ChatSnapshot?
}

/** Qianshou keeps text, audio transcription and media in message rows. */
class QianshouAdapter : ChatAppAdapter {
    override val pkg = "com.tantan.x"

    override fun extract(root: AccessibilityNodeInfo, res: Resources): ChatSnapshot? {
        if (root.findByResourceIdCompat("com.tantan.x:id/messagesBarGreyEdt").isEmpty())
            return null
        // Recent Qianshou chat pages renamed the toolbar contact title from
        // `name` to `name_text`; requiring only the old id made a real chat
        // look like an unsupported page and blocked opener requests.
        val title = listOf("com.tantan.x:id/name_text", "com.tantan.x:id/name")
            .asSequence()
            .flatMap { root.findByResourceIdCompat(it).asSequence() }
            .firstOrNull { it.isVisibleToUser && !it.text.isNullOrBlank() }
            ?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        val width = res.displayMetrics.widthPixels
        // Qianshou renders date separators as siblings of message rows. Reading
        // `id/time` only inside a row misses labels such as "9月15日 23:52".
        // A separator applies to the following visible rows until the next one;
        // it is a page label, not a precise send time for each message.
        val timeMarkers = root.findByResourceIdCompat("com.tantan.x:id/time")
            .mapNotNull { node ->
                val value = node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() && looksLikeTimestamp(it) }
                    ?: return@mapNotNull null
                val rect = Rect().also { node.getBoundsInScreen(it) }
                if (!node.isVisibleToUser || rect.isEmpty) null else rect.bottom to value
            }.sortedBy { it.first }
        val messages = root.findByResourceIdCompat("com.tantan.x:id/content_wrapper")
            .mapNotNull { row ->
                val bounds = Rect()
                row.getBoundsInScreen(bounds)
                if (!row.isVisibleToUser || bounds.isEmpty ||
                    row.findByResourceIdCompat("com.tantan.x:id/msg_content_body").isEmpty())
                    return@mapNotNull null

                // A transcription can span almost the whole screen. Its text
                // rectangle is NOT a speaker clue; the avatar belongs to the
                // entire message row and stays on the sender's side.
                // `isVisibleToUser` flickers for avatars clipped by scrolling
                // or covered by the floating bubble, although their horizontal
                // bounds still identify the sender. Multiple same-side avatar
                // nodes are also common in recycled message rows.
                val avatarCenters = row.findByResourceIdCompat("com.tantan.x:id/profile_image")
                    .mapNotNull { avatar ->
                        val rect = Rect().also { avatar.getBoundsInScreen(it) }
                        rect.centerX().takeIf { !rect.isEmpty && it in 0 until width }
                    }
                val avatarSide = qianshouSideForAvatars(avatarCenters, width)
                val bodyRect = row.findByResourceIdCompat("com.tantan.x:id/msg_content_body")
                    .firstOrNull()?.let { node -> Rect().also { node.getBoundsInScreen(it) } }
                val side = if (avatarSide != "unknown") avatarSide else
                    bodyRect?.let { qianshouSideForShortBubble(it.left, it.right, width) } ?: "unknown"

                val text = row.findByResourceIdCompat("com.tantan.x:id/msg_item_text")
                    .firstOrNull { it.isVisibleToUser && !it.text.isNullOrBlank() }
                    ?.text?.toString()?.trim()
                val transcript = row.findByResourceIdCompat(
                    "com.tantan.x:id/audio_item_transcription_text")
                    .firstOrNull { it.isVisibleToUser && !it.text.isNullOrBlank() }
                    ?.text?.toString()?.trim()
                val time = row.findByResourceIdCompat("com.tantan.x:id/time")
                    .firstOrNull { it.isVisibleToUser && !it.text.isNullOrBlank() }
                    ?.text?.toString()?.trim()
                    ?: qianshouTimeForRow(bounds.top, timeMarkers)
                val message = when {
                    !transcript.isNullOrBlank() -> Msg(side, transcript, MessageKind.VOICE_TRANSCRIPT,
                        time, if (time != null) "partial" else "unknown")
                    !text.isNullOrBlank() -> Msg(side, text, visibleTime = time,
                        timeConfidence = if (time != null) "partial" else "unknown")
                    row.findByResourceIdCompat("com.tantan.x:id/audioAnim").isNotEmpty() ->
                        Msg(side, "", MessageKind.VOICE_UNKNOWN, time,
                            if (time != null) "partial" else "unknown")
                    row.findByResourceIdCompat("com.tantan.x:id/msg_card_sticker_image").isNotEmpty() ->
                        Msg(side, "", MessageKind.MEDIA_UNKNOWN, time,
                            if (time != null) "partial" else "unknown")
                    else -> null
                } ?: return@mapNotNull null
                bounds.top to message
            }
            .sortedBy { it.first }
            .map { it.second }
        val note = listOfNotNull(
            if (messages.any { it.kind == MessageKind.VOICE_TRANSCRIPT })
                "含语音转写；发言者按整条消息的头像位置判断" else null,
            messages.lastOrNull()?.visibleTime?.let {
                "页面显示时间：$it（可能覆盖多条消息，不等于刚收到；现在仍可接续聊天）"
            }
        ).joinToString("；").ifBlank { null }
        return ChatSnapshot(title, messages, note = note)
    }
}

/** Keep resource-id lookups working on builds whose accessibility provider
 * exposes nodes to tree traversal but returns no matches from Android's id
 * query (also makes adapter tests mirror the real hierarchy reliably). */
private fun AccessibilityNodeInfo.findByResourceIdCompat(resourceId: String): List<AccessibilityNodeInfo> {
    val direct = runCatching { findAccessibilityNodeInfosByViewId(resourceId) }.getOrNull()
    if (!direct.isNullOrEmpty()) return direct
    val found = ArrayList<AccessibilityNodeInfo>()
    val stack = ArrayDeque<AccessibilityNodeInfo>()
    stack.addLast(this)
    var visited = 0
    while (stack.isNotEmpty() && visited < 5000) {
        val node = stack.removeLast()
        visited++
        if (node.viewIdResourceName == resourceId) found.add(node)
        for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let(stack::addLast)
    }
    return found
}

/** Uki chat detection only: its built-in suggestion cards are not chat messages. */
class UkiAdapter : ChatAppAdapter {
    override val pkg = "cn.neoclub.uki"

    override fun extract(root: AccessibilityNodeInfo, res: Resources): ChatSnapshot? {
        if (root.findAccessibilityNodeInfosByViewId("cn.neoclub.uki:id/et_input").isEmpty() ||
            root.findAccessibilityNodeInfosByViewId("cn.neoclub.uki:id/recyclerview").isEmpty())
            return null
        val title = root.findAccessibilityNodeInfosByViewId("cn.neoclub.uki:id/tv_name")
            .firstOrNull { it.isVisibleToUser && !it.text.isNullOrBlank() }
            ?.text?.toString()?.trim() ?: return null
        return ChatSnapshot(title, emptyList(), note = "Uki 当前仅识别聊天页；消息内容尚未适配")
    }
}

/** The avatar belongs to its message row, unlike a long transcription's bounds. */
internal fun qianshouSideForAvatar(centerX: Int, width: Int): String = when {
    width <= 0 -> "unknown"
    centerX < width * 0.25f -> "other"
    centerX > width * 0.75f -> "me"
    else -> "unknown"
}

internal fun qianshouSideForAvatars(centers: List<Int>, width: Int): String =
    centers.map { qianshouSideForAvatar(it, width) }
        .filter { it != "unknown" }.distinct().singleOrNull() ?: "unknown"

/** A fallback only when one bubble edge clearly hugs one side of the screen. */
internal fun qianshouSideForShortBubble(left: Int, right: Int, width: Int): String {
    if (width <= 0 || left < 0 || right > width || left >= right) return "unknown"
    val leftAnchored = left < width * 0.3f
    val rightAnchored = right > width * 0.7f
    return when {
        leftAnchored && !rightAnchored -> "other"
        rightAnchored && !leftAnchored -> "me"
        else -> "unknown"
    }
}

/** Return only a visible separator above this row; never invent a missing date. */
internal fun qianshouTimeForRow(rowTop: Int, markers: List<Pair<Int, String>>): String? =
    markers.lastOrNull { (bottom, _) -> bottom <= rowTop }?.second

/** Shared helpers. */
private fun looksLikeTimestamp(t: String): Boolean =
    Regex("""\d{1,2}[:：]\d{2}""").containsMatchIn(t) ||
        Regex("""\d+月\d+日""").containsMatchIn(t) ||
        t == "昨天" || t == "今天"

/**
 * Conversation title in the top action bar: the topmost short, roughly centered
 * text above the first message bubble. Constrained so we never grab an in-chat
 * timestamp. Used by QQ as a fallback when its title id is absent, by X, and by
 * the bubble menu's manual OCR capture. WeChat has its own [findWeChatTitle]
 * (group titles need extra filtering this generic version does not do).
 */
internal fun findTitleInActionBar(
    root: AccessibilityNodeInfo,
    firstBubbleTop: Int,
    width: Int,
    res: Resources,
    minCenterRatio: Double = 0.25,
    maxCenterRatio: Double = 0.75
): String? {
    val actionBarMax = minOf(firstBubbleTop, (res.displayMetrics.heightPixels * 0.14).toInt())
    val minCenterX = (width * minCenterRatio).toInt()
    val maxCenterX = (width * maxCenterRatio).toInt()
    val stack = ArrayDeque<AccessibilityNodeInfo>()
    stack.addLast(root)
    var best: String? = null
    var bestTop = Int.MAX_VALUE
    var guard = 0
    while (stack.isNotEmpty() && guard < 5000) {
        guard++
        val node = stack.removeLast()
        val text = node.text?.toString()
        if (!text.isNullOrBlank() && text.length <= 24 && !looksLikeTimestamp(text)) {
            val b = Rect(); node.getBoundsInScreen(b)
            if (b.bottom in 1 until actionBarMax && b.centerX() in minCenterX..maxCenterX) {
                if (b.top < bestTop) { bestTop = b.top; best = text }
            }
        }
        for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.addLast(it) }
    }
    return best
}

/** Chinese sentence punctuation — a real message/announcement line has it, a
 *  title never does. */
private val WECHAT_TITLE_EXCLUDE_PUNCT = Regex("""[，。？！、]""")

/** A WeChat group title's "(N)" member-count suffix, half- or full-width. */
private val WECHAT_GROUP_COUNT_SUFFIX = Regex("""[（(]\d+[）)]""")

/**
 * WeChat conversation title (v1.3 fix): a group's pinned announcement or a
 * stray message can sit in the same "topmost, short, centered" search
 * [findTitleInActionBar] does and get mistaken for the title (seen picking up
 * `我有企微，但是用不习惯`, a chat line). A candidate must not read like a
 * sentence (no Chinese punctuation) and must sit above the first bubble; among
 * what is left, a group title's trailing "(N)" member count wins when present.
 * Nothing qualifying → null (the caller's `lastGoodTitle` then carries the
 * previous stable title forward instead of guessing).
 */
internal fun findWeChatTitle(
    root: AccessibilityNodeInfo,
    firstBubbleTop: Int,
    width: Int,
    res: Resources
): String? {
    // The current WeChat toolbar has a real container even when its text is
    // hidden. Never select a quoted message just below it as the contact name.
    val titleRegion = root.findAccessibilityNodeInfosByViewId("com.tencent.mm:id/gp")
        .firstOrNull { it.isVisibleToUser }?.let { Rect().also(it::getBoundsInScreen) }
        ?.takeUnless { it.isEmpty } ?: wechatNodeBounds(root, "com.tencent.mm:id/gp")
    val nameRegion = titleRegion?.let { wechatNameOcrRegion(root, it) }
    val actionBarMax = minOf(firstBubbleTop, (res.displayMetrics.heightPixels * 0.14).toInt())
    val minCenterX = (width * 0.25).toInt()
    val maxCenterX = (width * 0.75).toInt()
    val stack = ArrayDeque<AccessibilityNodeInfo>()
    stack.addLast(root)
    var bestPlain: String? = null
    var bestPlainTop = Int.MAX_VALUE
    var bestCounted: String? = null
    var bestCountedTop = Int.MAX_VALUE
    var guard = 0
    while (stack.isNotEmpty() && guard < 5000) {
        guard++
        val node = stack.removeLast()
        val text = node.text?.toString()
        if (!text.isNullOrBlank() && text.length <= 24 && !looksLikeTimestamp(text) &&
            !WECHAT_TITLE_EXCLUDE_PUNCT.containsMatchIn(text)
        ) {
            val b = Rect(); node.getBoundsInScreen(b)
            if (node.isVisibleToUser && !b.isEmpty &&
                (nameRegion?.contains(b) ?: (b.bottom in 1 until actionBarMax && b.bottom < firstBubbleTop)) &&
                b.centerX() in minCenterX..maxCenterX
            ) {
                if (WECHAT_GROUP_COUNT_SUFFIX.containsMatchIn(text)) {
                    if (b.top < bestCountedTop) { bestCountedTop = b.top; bestCounted = text }
                } else if (b.top < bestPlainTop) { bestPlainTop = b.top; bestPlain = text }
            }
        }
        for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.addLast(it) }
    }
    return bestCounted ?: bestPlain
}

/** WeChat draws the contact name and the optional user status in separate
 * toolbar children. OCR only the name child: joining the whole toolbar turns
 * a title such as `12345` plus status `emo` into an unknown contact. Keep the
 * old toolbar crop for layouts without a trustworthy name child. */
private fun wechatNodeBounds(root: AccessibilityNodeInfo, id: String): Rect? {
    val stack = ArrayDeque<AccessibilityNodeInfo>()
    stack.addLast(root)
    var seen = 0
    while (stack.isNotEmpty() && seen++ < 5000) {
        val node = stack.removeLast()
        if (node.viewIdResourceName == id && node.isVisibleToUser) {
            val bounds = Rect().also(node::getBoundsInScreen)
            if (!bounds.isEmpty) return bounds
        }
        for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let(stack::addLast)
    }
    return null
}

internal fun wechatNameOcrRegion(root: AccessibilityNodeInfo, toolbar: Rect): Rect {
    val bounds = wechatNodeBounds(root, "com.tencent.mm:id/obq") ?: return toolbar
    return if (bounds.width() >= 16 && bounds.height() >= 16 && toolbar.contains(bounds) &&
        kotlin.math.abs(bounds.centerX() - toolbar.centerX()) <= toolbar.width() / 3) bounds else toolbar
}

/** WeChat (com.tencent.mm). Use the message row (`bkj`) and its edge avatar
 *  (`bk1`) for speaker ownership; body text (`bkl`) may span most of the screen.
 *
 *  "In a chat window" = a `id/bkl` bubble container exists (even with its text
 *  stripped by the obfuscation) — nothing else counts, so a list screen's
 *  editable search box can no longer pass for a chat window (v1.3 fix: it was
 *  triggering OCR fallback on the conversation list). WeChat 8.0.52+ hides
 *  node text from ordinary services, so an empty read here (a `bkl` with no
 *  text) is exactly the case OCR fallback exists for. */
class WeChatAdapter : ChatAppAdapter {
    override val pkg = "com.tencent.mm"

    override fun extract(root: AccessibilityNodeInfo, res: Resources): ChatSnapshot? {
        val width = res.displayMetrics.widthPixels
        data class BubbleText(val bounds: Rect, val text: String)
        data class RowMessage(
            val rowNode: AccessibilityNodeInfo,
            val rowBounds: Rect,
            val side: String,
            val bubbles: MutableList<BubbleText>
        )
        data class Visit(val node: AccessibilityNodeInfo, val row: AccessibilityNodeInfo?, val side: String)
        val bubbles = ArrayList<RowMessage>()
        val rowsByKey = LinkedHashMap<String, RowMessage>()
        val timeMarkers = ArrayList<Pair<Int, String>>()
        var firstBubbleTop = Int.MAX_VALUE
        var isChat = false
        var viewportTop = 0
        var viewportBottom = res.displayMetrics.heightPixels

        val stack = ArrayDeque<Visit>()
        stack.addLast(Visit(root, null, "unknown"))
        var guard = 0
        while (stack.isNotEmpty() && guard < 5000) {
            guard++
            val visit = stack.removeLast()
            val node = visit.node
            val id = node.viewIdResourceName
            val text = node.text?.toString()
            val bounds = Rect().also { node.getBoundsInScreen(it) }
            // bkj also appears inside WeChat's quoted-reply layout. Only replace
            // the owning row when this node has its own avatar; otherwise keep
            // the outer message row so a quote's nested container cannot erase
            // the actual sender or split one reply into multiple messages.
            val rowSide = if (id == ROW_ID) avatarSide(node, width) else "unknown"
            val ownsMessageRow = id == ROW_ID && (rowSide != "unknown" || visit.row == null)
            val row = if (ownsMessageRow) node else visit.row
            val side = if (ownsMessageRow) rowSide else visit.side
            if (id == ROW_ID) isChat = true
            if (node.isVisibleToUser && !bounds.isEmpty) {
                if (id == "com.tencent.mm:id/gp" && bounds.bottom < res.displayMetrics.heightPixels * 0.14)
                    viewportTop = maxOf(viewportTop, bounds.bottom)
                if (node.isEditable && bounds.top >= res.displayMetrics.heightPixels / 2)
                    viewportBottom = minOf(viewportBottom, bounds.top)
            }
            if (node.isVisibleToUser && !bounds.isEmpty && id != BUBBLE_ID &&
                text != null && isWechatTimeLabel(text) &&
                bounds.centerX() in (width * 0.4).toInt()..(width * 0.6).toInt()) {
                timeMarkers.add(bounds.bottom to text.trim())
            }
            if (id == BUBBLE_ID) {
                isChat = true
                if (!text.isNullOrBlank() && node.isVisibleToUser && bounds.height() >= 8 && row != null) {
                    val rowBounds = Rect().also { row.getBoundsInScreen(it) }
                    val rowKey = "${rowBounds.left}:${rowBounds.top}:${rowBounds.right}:${rowBounds.bottom}"
                    val messageRow = rowsByKey.getOrPut(rowKey) {
                        RowMessage(row, Rect(rowBounds), side, mutableListOf())
                    }
                    val firstInRow = messageRow.bubbles.isEmpty()
                    messageRow.bubbles.add(BubbleText(Rect(bounds), text))
                    if (firstInRow) {
                        bubbles.add(messageRow)
                        firstBubbleTop = minOf(firstBubbleTop, rowBounds.top)
                    }
                }
                // A nested quote is part of this bubble, not another message.
                continue
            }
            for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let {
                stack.addLast(Visit(it, row, side))
            }
        }
        val title = findWeChatTitle(root, firstBubbleTop, width, res)
        // In a chat but nothing readable → empty snapshot, the OCR fallback cue.
        if (bubbles.isEmpty()) return if (isChat) ChatSnapshot(title, emptyList()) else null
        bubbles.removeAll { it.rowBounds.top < viewportTop || it.rowBounds.bottom > viewportBottom ||
            (it.side == "unknown" && ((viewportTop > 0 && it.rowBounds.top <= viewportTop + 2) ||
                it.rowBounds.bottom >= viewportBottom - 2)) }
        timeMarkers.removeAll { (bottom, _) -> bottom < viewportTop || bottom > viewportBottom }
        bubbles.sortBy { it.rowBounds.top }
        timeMarkers.sortBy { it.first }
        val voiceRegions = ArrayList<VoiceTranscriptRect>()
        val msgs = bubbles.mapIndexed { index, message ->
            val time = qianshouTimeForRow(message.rowBounds.top, timeMarkers)
            val texts = message.bubbles.sortedBy { it.bounds.top }.map { it.text.trim() }.toMutableList()
            if (texts.any(WECHAT_VOICE_DURATION::containsMatchIn)) {
                val rowNodes = ArrayDeque<AccessibilityNodeInfo>()
                for (i in message.rowNode.childCount - 1 downTo 0)
                    message.rowNode.getChild(i)?.let(rowNodes::addLast)
                var visited = 0
                while (rowNodes.isNotEmpty() && visited++ < 500) {
                    val child = rowNodes.removeLast()
                    val childText = child.text?.toString()?.trim()
                    val childBounds = Rect().also { child.getBoundsInScreen(it) }
                    if (child.isVisibleToUser && !childBounds.isEmpty &&
                        childBounds.top >= message.rowBounds.top && childBounds.bottom <= message.rowBounds.bottom &&
                        !childText.isNullOrBlank() && childText !in texts && !isWechatTimeLabel(childText)) {
                        texts.add(childText)
                    }
                    for (i in child.childCount - 1 downTo 0) child.getChild(i)?.let(rowNodes::addLast)
                }
            }
            val voice = wechatVoiceTranscript(texts)
            if (voice.kind == MessageKind.VOICE_UNKNOWN) {
                // Current WeChat draws the expanded transcription in bru;
                // that node has bounds but no accessibility text. OCR only
                // this region and keep the row avatar's sender attribution.
                val stack = ArrayDeque<AccessibilityNodeInfo>()
                stack.add(message.rowNode)
                var visited = 0
                while (stack.isNotEmpty() && visited++ < 500) {
                    val node = stack.removeLast()
                    val rect = Rect().also(node::getBoundsInScreen)
                    if (node.viewIdResourceName == "com.tencent.mm:id/bru" && node.isVisibleToUser &&
                        rect.width() >= 8 && rect.height() >= 8 && rect.top >= message.rowBounds.top &&
                        rect.bottom <= message.rowBounds.bottom && rect.top >= viewportTop && rect.bottom <= viewportBottom)
                        voiceRegions.add(VoiceTranscriptRect(index, rect))
                    for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let(stack::addLast)
                }
            }
            Msg(message.side, voice.text, voice.kind,
                visibleTime = time, timeConfidence = if (time == null) "unknown" else "partial")
        }
        val note = if (msgs.any { it.kind == MessageKind.VOICE_TRANSCRIPT })
            "含语音转写；发言者按整条消息的头像位置判断" else null
        return ChatSnapshot(title, msgs, note = note, voiceTranscriptRects = voiceRegions)
    }

    companion object {
        private const val ROW_ID = "com.tencent.mm:id/bkj"
        private const val AVATAR_ID = "com.tencent.mm:id/bk1"
        private const val BUBBLE_ID = "com.tencent.mm:id/bkl"

        /** The sender avatar is pinned to its side's outer edge. */
        private fun avatarSide(row: AccessibilityNodeInfo, width: Int): String {
            if (width <= 0) return "unknown"
            var avatar: Rect? = null
            val stack = ArrayDeque<AccessibilityNodeInfo>()
            for (i in row.childCount - 1 downTo 0) row.getChild(i)?.let { stack.addLast(it) }
            var guard = 0
            while (stack.isNotEmpty() && guard++ < 500) {
                val node = stack.removeLast()
                if (node.viewIdResourceName == AVATAR_ID && node.isVisibleToUser) {
                    if (avatar != null) return "unknown"
                    avatar = Rect().also { node.getBoundsInScreen(it) }
                }
                for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.addLast(it) }
            }
            val bounds = avatar ?: return "unknown"
            if (bounds.isEmpty || bounds.width() > width * .25f) return "unknown"
            return when {
                bounds.right < width * .25f -> "other"
                bounds.left > width * .75f -> "me"
                else -> "unknown"
            }
        }
    }
}

/** A voice duration is metadata, not message text. WeChat can expose the
 * recognized transcript as a second text bubble inside the same message row. */
internal data class WechatVoiceText(val kind: MessageKind, val text: String)

internal fun wechatVoiceTranscript(bubbleTexts: List<String>): WechatVoiceText {
    val cleaned = bubbleTexts.map { text ->
        WECHAT_VOICE_DURATION.replace(text, " ").replace(Regex("\\s+"), " ").trim()
    }.filter {
        it.isNotBlank() && !isWechatTimeLabel(it) && it !in WECHAT_VOICE_CONTROL_TEXT
    }
    val hasDuration = bubbleTexts.any { WECHAT_VOICE_DURATION.containsMatchIn(it) }
    if (!hasDuration) return WechatVoiceText(MessageKind.TEXT, bubbleTexts.firstOrNull().orEmpty())
    val transcript = cleaned.distinct().joinToString(" ").trim()
    return if (transcript.isBlank()) WechatVoiceText(MessageKind.VOICE_UNKNOWN, "")
    else WechatVoiceText(MessageKind.VOICE_TRANSCRIPT, transcript)
}

private val WECHAT_VOICE_DURATION = Regex("""(?<!\d)\d{1,3}\s*(?:["″＂'’秒])""")
private val WECHAT_VOICE_CONTROL_TEXT = setOf("转文字", "语音转文字", "播放", "继续播放", "重播")

/** Match a complete separator, never a chat sentence merely mentioning a time. */
internal fun isWechatTimeLabel(text: String): Boolean = text.isNotBlank() &&
    WECHAT_TIME_LABEL.matches(text.trim())

private val WECHAT_TIME_LABEL = Regex(
    """(?:(?:\d{4}年)?\d{1,2}月\d{1,2}日|今天|昨天|前天)?(?:\s*(?:星期|周)[一二三四五六日天])?(?:\s*(?:凌晨|早上|上午|中午|下午|晚上)?\s*\d{1,2}[:：]\d{2})?""")

/**
 * Mobile QQ (com.tencent.mobileqq). Nodes are NOT obfuscated (verified on QQ
 * 9.3.50 / Xiaomi 14, 1200x2670): message bodies are plain TextViews carrying
 * `id/mjn`, so collecting only that id already excludes timestamps, sender
 * nicknames (`id/mjq`) and the full-width system notice strips.
 *
 * The whole app lives under one SplashActivity (fragment architecture), so
 * "are we in a chat window" can only be answered by the tree itself — here, by
 * the chat input box `id/input`. No input box → not a chat → null; input box
 * but no `id/mjn` bodies → empty snapshot (OCR fallback's cue).
 *
 * Sender side: QQ pins the avatar to the outer edge of its own side (others on
 * the left at x≈156/1200 ≈ 13% of width, me on the right at width−156). A long
 * incoming message can push its center past mid-screen, so we compare which
 * edge of the bubble hugs its avatar column instead of using the center point.
 */
class QQAdapter : ChatAppAdapter {
    override val pkg = "com.tencent.mobileqq"

    override fun extract(root: AccessibilityNodeInfo, res: Resources): ChatSnapshot? {
        val width = res.displayMetrics.widthPixels
        // top, left, right, text
        val bubbles = ArrayList<Bubble>()
        var firstBubbleTop = Int.MAX_VALUE
        var title: String? = null
        var hasInput = false

        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var guard = 0
        while (stack.isNotEmpty() && guard < 5000) {
            guard++
            val node = stack.removeLast()
            val id = node.viewIdResourceName
            val text = node.text?.toString()
            if (id == BUBBLE_ID && !text.isNullOrBlank()) {
                val b = Rect(); node.getBoundsInScreen(b)
                bubbles.add(Bubble(b.top, b.left, b.right, text))
                if (b.top < firstBubbleTop) firstBubbleTop = b.top
            }
            if (!hasInput && id == INPUT_ID) hasInput = true
            if (id == TITLE_ID && title == null) text?.let { if (it.isNotBlank()) title = it }
            for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.addLast(it) }
        }
        if (bubbles.isEmpty() && !hasInput) return null

        if (title == null) title = findTitleInActionBar(root, firstBubbleTop, width, res)
        if (bubbles.isEmpty()) return ChatSnapshot(title, emptyList())

        val avatarEdge = (width * 0.13).toInt()
        bubbles.sortBy { it.top }
        val msgs = bubbles.map { b ->
            val dl = kotlin.math.abs(b.left - avatarEdge)
            val dr = kotlin.math.abs((width - avatarEdge) - b.right)
            Msg(if (dr < dl) "me" else "other", b.text)
        }
        return ChatSnapshot(title, msgs)
    }

    private data class Bubble(val top: Int, val left: Int, val right: Int, val text: String)

    companion object {
        private const val BUBBLE_ID = "com.tencent.mobileqq:id/mjn"
        private const val TITLE_ID = "com.tencent.mobileqq:id/371"
        private const val INPUT_ID = "com.tencent.mobileqq:id/input"
    }
}

/** Only my own Feishu bubbles carry the sent/read strip. */
private const val FEISHU_READ_STATE_ID = "time_read_state_container_align_bubble"

/** Does this bubble carry the "sent / read" strip that only mine have? */
private fun feishuHasReadState(bubble: AccessibilityNodeInfo): Boolean {
    val stack = ArrayDeque<AccessibilityNodeInfo>()
    stack.addLast(bubble)
    var guard = 0
    while (stack.isNotEmpty() && guard < 400) {
        guard++
        val node = stack.removeLast()
        val id = node.viewIdResourceName ?: ""
        if (id.endsWith(FEISHU_READ_STATE_ID)) return true
        for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.addLast(it) }
    }
    return false
}

/**
 * Feishu bubble rectangles in SCREEN coordinates, top to bottom, with the side
 * the read-receipt strip implies.
 *
 * Split out of [FeishuAdapter.extract] so the capture service can call it again
 * from inside the screenshot callback: several hundred ms pass between reading
 * the tree and the picture arriving (debounce + overlay hide + the shot itself),
 * and a list that scrolled in between would make us crop the wrong rows.
 */
internal fun collectFeishuBubbleRects(
    root: AccessibilityNodeInfo,
    res: Resources
): List<BubbleRect> {
    val height = res.displayMetrics.heightPixels
    val topBand = (height * 0.14).toInt()      // action bar + tab row
    val bottomBand = (height * 0.84).toInt()   // input box + keyboard
    val rects = ArrayList<BubbleRect>()
    val stack = ArrayDeque<AccessibilityNodeInfo>()
    stack.addLast(root)
    var guard = 0
    while (stack.isNotEmpty() && guard < 6000) {
        guard++
        val node = stack.removeLast()
        val id = node.viewIdResourceName ?: ""
        if (id.endsWith(":id/bubble_content_container")) {
            val b = Rect(); node.getBoundsInScreen(b)
            if (b.width() > 0 && b.height() > 0 && b.bottom > topBand && b.top < bottomBand) {
                rects.add(BubbleRect(Rect(b), if (feishuHasReadState(node)) "me" else "other"))
            }
        }
        for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.addLast(it) }
    }
    rects.sortBy { it.rect.top }
    return rects
}

/**
 * Feishu / Lark (com.ss.android.lark). Nodes are not obfuscated, but the message
 * text is DRAWN, not laid out as views (verified 2026-09-21): the tree gives us
 * bubble rectangles and chrome, and almost never a body. So this adapter is a
 * hybrid — it reports what it can read as messages (usually nothing) and always
 * reports the bubble geometry in [ChatSnapshot.bubbleRects] for the service to
 * OCR rect by rect.
 *
 * "In a chat window" = the tree has `id/message`, `id/bubble_content_container`
 * or the `id/kb_rich_text_content` input box.
 *
 * Side: Feishu left-aligns everyone, so geometry says nothing. What does say
 * something is the read-receipt strip (`…time_read_state_container_align_bubble`)
 * that only hangs off MY bubbles — present → "me", absent → "other". Unverified
 * on a real device (see the B-stage report's gaps).
 */
class FeishuAdapter : ChatAppAdapter {
    override val pkg = "com.ss.android.lark"

    override fun extract(root: AccessibilityNodeInfo, res: Resources): ChatSnapshot? {
        val width = res.displayMetrics.widthPixels
        val height = res.displayMetrics.heightPixels
        val topBand = (height * 0.14).toInt()      // action bar + tab row
        val bottomBand = (height * 0.84).toInt()   // input box + keyboard

        var isChat = false
        var title: String? = null
        val items = ArrayList<Triple<Int, Int, String>>() // top, centerX, text
        // Same collection the service re-runs inside the screenshot callback.
        val rects = collectFeishuBubbleRects(root, res)

        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var guard = 0
        while (stack.isNotEmpty() && guard < 6000) {
            guard++
            val node = stack.removeLast()
            val id = node.viewIdResourceName ?: ""
            if (id.endsWith(":id/message") || id.endsWith(":id/bubble_content_container") ||
                id.endsWith(":id/kb_rich_text_content")) isChat = true
            if (id.endsWith(":id/group_name")) node.text?.toString()?.let { if (title == null) title = it }

            val text = node.text?.toString()
            val cls = node.className?.toString()
            if (!text.isNullOrBlank() && cls == "android.widget.TextView" && !isChrome(id) && !looksLikeTimestamp(text)) {
                val b = Rect(); node.getBoundsInScreen(b)
                if (b.top in (topBand + 1) until bottomBand) {
                    items.add(Triple(b.top, b.centerX(), text.trim()))
                }
            }
            for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.addLast(it) }
        }
        if (!isChat) return null

        if (items.isEmpty()) return ChatSnapshot(title, emptyList(), rects)

        items.sortBy { it.first }
        val msgs = items.map { (_, cx, text) ->
            Msg(if (cx > width / 2) "me" else "other", text)
        }
        return ChatSnapshot(title, msgs, rects)
    }

    /** Non-message UI text to skip: title, sender name, time, system notices,
     *  the input EditText. Bodies have no id (bare TextView) so they pass. */
    private fun isChrome(id: String): Boolean =
        id.endsWith(":id/group_name") ||
            id.endsWith(":id/name_tv") ||
            id.endsWith(":id/date_tv") ||
            id.endsWith(":id/system_label") ||
            id.endsWith(":id/kb_rich_text_content") ||
            id.endsWith(":id/thread_title_tv") ||
            id.endsWith(":id/thread_subtitle_tv")
}

/** Trailing "8:11 上午" / "10:29 下午" / "8:11 AM" stamp X glues onto a message. */
private val X_TAIL_TIME = Regex("""\d{1,2}[:：]\d{2}\s*(上午|下午|AM|PM|am|pm)?$""")

/** X uses "。" as a field separator, so a message can end with a run of them. */
private val X_TRAILING_DOTS = Regex("""。+$""")

/**
 * Split one X DM row's contentDescription into (sender, body).
 *
 * "你：你这个说的就是那个虚拟人物，是吗？。8:11 上午。Read。"
 *      → ("你", "你这个说的就是那个虚拟人物，是吗？")
 * "你：他这个东西开源应该问题不大。。。Read。"
 *      → ("你", "他这个东西开源应该问题不大")
 * "All-In：附加的帖子。。"          → ("All-In", "附加的帖子")
 *
 * The sender is everything before the FIRST separator (full-width "：" in the
 * Chinese UI, ": " as a rough fallback elsewhere); the rest is the body plus
 * chrome — the read receipt, the timestamp, and the "。" gluing them on — which
 * is stripped from the tail in that order. Punctuation the user actually typed
 * ("是吗？") survives. Null when there is no separator or nothing is left.
 */
private fun parseXDesc(desc: String): Pair<String, String>? {
    val full = desc.indexOf('：')
    val half = desc.indexOf(": ")
    val cut: Int
    val skip: Int
    when {
        full >= 0 && (half < 0 || full <= half) -> { cut = full; skip = 1 }
        half >= 0 -> { cut = half; skip = 2 }
        else -> return null
    }
    val sender = desc.substring(0, cut).trim()
    var body = desc.substring(cut + skip).trim()
    for (tail in arrayOf("Read。", "Read", "已读。", "已读")) {
        if (body.endsWith(tail)) { body = body.removeSuffix(tail).trim(); break }
    }
    body = X_TRAILING_DOTS.replace(body, "").trim()
    X_TAIL_TIME.find(body)?.let { body = body.substring(0, it.range.first).trim() }
    body = X_TRAILING_DOTS.replace(body, "").trim()
    if (sender.isEmpty() || body.isEmpty()) return null
    return sender to body
}

/**
 * X / Twitter (com.twitter.android) direct messages. Verified on X 12.25.2 /
 * Xiaomi 14 (1200x2670), Chinese system language.
 *
 * The DM thread is Compose UI: each message is a bare `android.view.View` with
 * NO resource-id, full screen width and empty text — the whole message lives in
 * contentDescription ("All-In：重新写了一个😂。10:29 下午。"). The date divider is a
 * TextView with no "：", so filtering on class + full width + a separator keeps
 * it out. An attachment row ("All-In：附加的帖子。。") nests the quoted post's own
 * TextViews; we only take the row View's own desc, never its children.
 *
 * Every screen runs under the same MainActivity, so "are we in a DM thread" can
 * only be answered by the tree: a thread has the message EditText, the DM list
 * does not. The list's rows look similar but read
 * "All-In, @all_in_2026, 你这个说的就是那…", so ", @" is an extra guard.
 *
 * Side comes from the sender label ("你" / "You"), not geometry — every row is
 * full width no matter who spoke.
 */
class XAdapter : ChatAppAdapter {
    override val pkg = "com.twitter.android"

    override fun extract(root: AccessibilityNodeInfo, res: Resources): ChatSnapshot? {
        val width = res.displayMetrics.widthPixels
        val rows = ArrayList<Row>()
        var firstRowTop = Int.MAX_VALUE
        var hasInput = false
        // A full-width View whose desc has a separator ("：" / ": ") — the shape
        // of a message row, whether or not parseXDesc could fully parse it.
        var hasMessageRowShape = false
        // A thread with zero messages still carries this placeholder text.
        var hasDmLabel = false
        // DM-list-only signals: the "compose new DM" affordance, or a "聊天/
        // Messages" heading with nothing under it (parsed as an actual row).
        var hasNewDmMarker = false
        var sawListHeading = false

        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var guard = 0
        while (stack.isNotEmpty() && guard < 6000) {
            guard++
            val node = stack.removeLast()
            val cls = node.className?.toString()
            if (!hasInput && (node.isEditable || cls == "android.widget.EditText")) hasInput = true

            val desc = node.contentDescription?.toString()
            if (desc == "新私信" || desc == "New message") hasNewDmMarker = true
            if (cls == "android.view.View" && !desc.isNullOrBlank() && !desc.contains(", @")) {
                val b = Rect(); node.getBoundsInScreen(b)
                if (b.left == 0 && b.right == width) {
                    if (desc.contains('：') || desc.contains(": ")) hasMessageRowShape = true
                    val parsed = parseXDesc(desc)
                    if (parsed != null) {
                        rows.add(Row(b.top, parsed.first, parsed.second))
                        if (b.top < firstRowTop) firstRowTop = b.top
                    }
                }
            }

            if (cls == "android.widget.TextView") {
                val text = node.text?.toString()?.trim()
                if (text == "私信" || text == "Message" || text == "发送私信") hasDmLabel = true
                if (text == "聊天" || text == "Messages") sawListHeading = true
            }
            for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.addLast(it) }
        }
        // Explicit "this is the DM list, not a thread" signals — checked before
        // the generic rule below so they win even if a search box's EditText
        // would otherwise have counted as "hasInput".
        if (hasNewDmMarker || (sawListHeading && rows.isEmpty())) return null

        // A chat window needs BOTH an input box AND something that only a
        // thread has: an actual message-row shape, or the "私信" placeholder an
        // empty thread shows. A list screen's search box has an EditText too,
        // so hasInput alone used to misfire OCR fallback on the DM list.
        if (!hasInput || !(hasMessageRowShape || hasDmLabel)) return null

        // X left-aligns the thread title (x≈300..443 of 1200), so widen the
        // shared helper's "roughly centered" band for this app.
        val title = findTitleInActionBar(root, firstRowTop, width, res, 0.15, 0.85)
        // In a DM thread but no rows parsed → empty snapshot (OCR fallback cue).
        if (rows.isEmpty()) return ChatSnapshot(title, emptyList())
        rows.sortBy { it.top }
        val msgs = rows.map { Msg(if (it.sender == "你" || it.sender == "You") "me" else "other", it.text) }
        return ChatSnapshot(title, msgs)
    }

    private data class Row(val top: Int, val sender: String, val text: String)
}
