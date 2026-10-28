package com.jev.probe.capture

import android.content.res.Resources
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.MessageKind
import com.jev.probe.core.Msg

/** Soul chat rows, verified on device. Text width never determines the speaker. */
class SoulAdapter : ChatAppAdapter {
    override val pkg = PACKAGE

    override fun extract(root: AccessibilityNodeInfo, res: Resources): ChatSnapshot? {
        if (root.packageName?.toString() != pkg) return null
        val all = nodes(root)
        val toolbar = all.firstOrNull { named(it, "navigation_bar") && it.isVisibleToUser } ?: return null
        val input = all.firstOrNull { named(it, "et_sendmessage") && it.isVisibleToUser } ?: return null
        val list = all.firstOrNull { named(it, "list_conversation") && it.isVisibleToUser } ?: return null
        val title = nodes(toolbar).firstOrNull { named(it, "tv_title") && it.isVisibleToUser }
            ?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val viewport = bounds(list).apply {
            top = maxOf(top, bounds(toolbar).bottom)
            bottom = minOf(bottom, bounds(input).top,
                all.firstOrNull { named(it, "chat_media_menu") && it.isVisibleToUser }
                    ?.let { bounds(it).top } ?: bottom)
        }
        if (viewport.isEmpty) return null
        val rows = nodes(list).filter { named(it, "item_root") && visible(it, viewport) }
            .map { it to nodes(it) }.sortedBy { bounds(it.first).top }
        val times = rows.flatMap { it.second }.filter { named(it, "timestamp") && visible(it, viewport) }
            .mapNotNull { n -> n.text?.toString()?.trim()?.takeIf(::isWechatTimeLabel)
                ?.let { bounds(n).bottom to it } }.sortedBy { it.first }
        val width = res.displayMetrics.widthPixels
        val messages = rows.mapNotNull { (_, rowNodes) ->
            val content = rowNodes.firstOrNull { named(it, "layout_content") && visible(it, viewport) }
                ?: return@mapNotNull null
            val bodyNodes = nodes(content)
            val avatars = bodyNodes.filter { named(it, "chat_avatar") }.map(::bounds)
                .filter { !it.isEmpty && it.centerX() in 0 until width }
            // A clipped/covered avatar can still have useful horizontal bounds.
            // Missing or conflicting anchors stay unknown; never guess from text.
            val sides = avatars.map { when {
                it.centerX() < width * .25 -> "other"
                it.centerX() > width * .75 -> "me"
                else -> "unknown"
            } }.distinct()
            val side = sides.singleOrNull() ?: "unknown"
            val container = bodyNodes.firstOrNull { named(it, "container") && visible(it, viewport) }
                ?: return@mapNotNull null
            val textContainers = nodes(container).filter { named(it, "content_text_container") }
            val textNodes = textContainers.flatMap(::nodes).filter { named(it, "content_text") }
            // RecyclerView retains off-screen rows. A text bubble clipped by the
            // toolbar/quick-action strip is not a newly received unknown medium.
            val text = if (textContainers.isNotEmpty()) {
                val n = textNodes.singleOrNull() ?: return@mapNotNull null
                if (!fullyVisible(n, viewport)) return@mapNotNull null
                n.text?.toString()?.trim()?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
            } else {
                if (!fullyVisible(container, viewport)) return@mapNotNull null
                null
            }
            // Profile cards and gift shortcuts do not have a message text container.
            // Keep an unknown media row rather than falling back to an older message.
            if (avatars.isEmpty() && text == null) return@mapNotNull null
            val time = qianshouTimeForRow(bounds(content).top, times)
            Msg(side, text.orEmpty(), if (text == null) MessageKind.MEDIA_UNKNOWN else MessageKind.TEXT,
                time, if (time == null) "unknown" else "partial")
        }
        val time = messages.lastOrNull()?.visibleTime
        return ChatSnapshot(title, messages, note = "会话：$title · Soul\n" +
            (if (time == null) "末条发送时间未知" else "末条页面时间：$time（非逐条精确时间，不代表刚收到）") +
            if (messages.any { it.kind == MessageKind.MEDIA_UNKNOWN }) "\n含未适配的非文字消息，内容未知。" else "")
    }

    private fun nodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        while (stack.isNotEmpty() && out.size < 1200) {
            val n = stack.removeLast()
            out.add(n)
            for (i in n.childCount - 1 downTo 0) n.getChild(i)?.let(stack::addLast)
        }
        return out
    }

    private fun named(n: AccessibilityNodeInfo, name: String) = n.viewIdResourceName == "$pkg:id/$name"
    private fun bounds(n: AccessibilityNodeInfo) = Rect().also(n::getBoundsInScreen)
    private fun visible(n: AccessibilityNodeInfo, viewport: Rect) = n.isVisibleToUser &&
        bounds(n).let { it.height() >= 8 && Rect.intersects(it, viewport) }
    private fun fullyVisible(n: AccessibilityNodeInfo, viewport: Rect) = visible(n, viewport) &&
        bounds(n).let { it.top >= viewport.top && it.bottom <= viewport.bottom }

    companion object { const val PACKAGE = "cn.soulapp.android" }
}
