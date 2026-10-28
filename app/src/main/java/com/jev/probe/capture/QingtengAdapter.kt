package com.jev.probe.capture

import android.content.res.Resources
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.MessageKind
import com.jev.probe.core.Msg

/** Verified against the personal device's FriendChatActivity; never read tips/profile cards. */
class QingtengAdapter : ChatAppAdapter {
    override val pkg = PACKAGE

    override fun extract(root: AccessibilityNodeInfo, res: Resources): ChatSnapshot? {
        if (root.packageName?.toString() != pkg) return null
        val all = descendants(root).toList()
        val toolbar = all.firstOrNull { it.viewIdResourceName == id("title_bar_root") && it.isVisibleToUser }
            ?: return null
        val chat = all.firstOrNull { it.viewIdResourceName == id("friend_chat_container") && it.isVisibleToUser }
            ?: return null
        val input = all.firstOrNull { it.viewIdResourceName == id("send_view") && it.isVisibleToUser }
            ?: return null
        val title = descendants(toolbar).firstOrNull { it.viewIdResourceName == id("title_tv") && it.isVisibleToUser }
            ?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val list = descendants(chat).firstOrNull { it.viewIdResourceName == id("recycler_view") && it.isVisibleToUser }
            ?: return null
        val viewport = bounds(list)
        viewport.bottom = minOf(viewport.bottom, bounds(input).top)
        if (viewport.isEmpty) return null
        val nodes = descendants(list).toList()
        val times = nodes.filter { it.viewIdResourceName == id("chat_time_tv") && visibleIn(it, viewport) }
            .mapNotNull { node -> node.text?.toString()?.trim()?.takeIf(::isWechatTimeLabel)
                ?.let { bounds(node).bottom to it } }.sortedBy { it.first }
        val width = res.displayMetrics.widthPixels
        val messages = nodes.filter { it.viewIdResourceName == id("chat_message_ll") && visibleIn(it, viewport) }
            .mapNotNull { row ->
                val rowNodes = descendants(row).toList()
                // Long bubbles may cross the screen centre; use the row's avatar.
                val avatars = rowNodes.filter { it.viewIdResourceName == id("head_icon_view") }
                    .map(::bounds).filterNot { it.isEmpty }
                if (avatars.isEmpty()) return@mapNotNull null
                val sides = avatars.map { when {
                    it.centerX() < width * .3 -> "other"
                    it.centerX() > width * .7 -> "me"
                    else -> "unknown"
                } }.distinct()
                val side = sides.singleOrNull() ?: "unknown"
                val knownText = rowNodes.filter { it.viewIdResourceName == id("chat_text_tv") && visibleIn(it, viewport) }
                // The outgoing text view has no resource-id in this version.
                // Accept only the direct text bubble beside its avatar, not arbitrary descendants.
                val outgoingText = if (side == "me") (0 until row.childCount).mapNotNull(row::getChild)
                    .filter { node ->
                        val rect = bounds(node)
                        node.viewIdResourceName.isNullOrEmpty() && node.className?.toString() == "android.widget.TextView" &&
                            visibleIn(node, viewport) && avatars.any { avatar ->
                                kotlin.math.abs(rect.top - avatar.top) <= (res.displayMetrics.density * 8).toInt() &&
                                    rect.right <= avatar.left && rect.right > width * .6
                            }
                    } else emptyList()
                val text = (knownText.ifEmpty { outgoingText }).singleOrNull()?.text?.toString()?.trim()
                    ?.takeIf { it.isNotEmpty() }
                val kind = when {
                    text == null -> MessageKind.MEDIA_UNKNOWN
                    VOICE_DURATION.matches(text) -> MessageKind.VOICE_UNKNOWN
                    else -> MessageKind.TEXT
                }
                val top = bounds(row).top
                val time = qianshouTimeForRow(top, times)
                top to Msg(side, if (kind == MessageKind.TEXT) text.orEmpty() else "", kind,
                    time, if (time == null) "unknown" else "partial")
            }.sortedBy { it.first }.map { it.second }
        val time = messages.lastOrNull()?.visibleTime
        return ChatSnapshot(title, messages, note = "会话：$title · 青藤之恋\n" +
            (if (time == null) "末条发送时间未知" else "末条页面时间：$time（非逐条精确时间，不代表刚收到）") +
            if (messages.any { it.kind != MessageKind.TEXT }) "\n含未适配的非文字消息，内容未知。" else "")
    }

    private fun descendants(root: AccessibilityNodeInfo) = sequence {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        var visited = 0
        while (stack.isNotEmpty() && visited++ < 1500) {
            val node = stack.removeLast()
            yield(node)
            for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let(stack::addLast)
        }
    }

    private fun bounds(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen)
    private fun visibleIn(node: AccessibilityNodeInfo, viewport: Rect) =
        node.isVisibleToUser && Rect.intersects(bounds(node), viewport)
    private fun id(name: String) = "$pkg:id/$name"

    companion object {
        const val PACKAGE = "com.wepie.ivy"
        private val VOICE_DURATION = Regex("\\d{1,3}\\s*[\"″'’秒]")
        /** Punctuation-only nicknames cannot safely identify a durable memory file. */
        fun canRemember(title: String?) = title?.any { it.isLetterOrDigit() } == true
    }
}
