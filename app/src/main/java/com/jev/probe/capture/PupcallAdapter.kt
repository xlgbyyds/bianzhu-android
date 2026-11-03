package com.jev.probe.capture

import android.content.res.Resources
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.MessageKind
import com.jev.probe.core.Msg
import com.jev.probe.core.RoleChatMetadata

/** Flutter semantics, verified on 1.3.0. This app's secure surface cannot use OCR. */
class PupcallAdapter : ChatAppAdapter {
    override val pkg = PACKAGE

    override fun extract(root: AccessibilityNodeInfo, res: Resources): ChatSnapshot? {
        if (root.packageName?.toString() != pkg) return null
        val all = nodes(root)
        val width = res.displayMetrics.widthPixels
        val height = res.displayMetrics.heightPixels
        val editor = all.singleOrNull { it.className?.toString() == "android.widget.EditText" && it.isVisibleToUser }
            ?: return null
        val header = all.filter { n ->
            val b = bounds(n)
            n.isVisibleToUser && b.top >= height * .03 && b.bottom < height * .14 &&
                b.left >= width * .15 && b.right <= width * .85
        }
        val titleNode = header.filter { n ->
            val t = label(n)
            n.className?.toString() == "android.view.View" && t.isNotBlank() &&
                t.length <= 60 && !t.contains('\n') && !STATUS.matches(t) && !isWechatTimeLabel(t)
        }.minByOrNull { bounds(it).top } ?: return null
        val title = label(titleNode)
        if (title in setOf("消息", "达人消息", "搭讪消息", "陌生人消息", "发现", "个人资料")) return null
        val top = header.maxOfOrNull { bounds(it).bottom } ?: bounds(titleNode).bottom
        val bottom = bounds(editor).top
        val markers = all.mapNotNull { n ->
            val b = bounds(n)
            label(n).takeIf { b.width() > width * .8 && b.top >= top &&
                isWechatTimeLabel(it) }?.let { b.top to it }
        }.sortedBy { it.first }
        val messages = all.mapNotNull { row ->
            val rb = bounds(row)
            if (!row.isVisibleToUser || rb.width() < width * .9 || rb.bottom <= top ||
                rb.top >= bottom || (label(row).isNotBlank() && !isWechatTimeLabel(label(row)))) return@mapNotNull null
            val children = (0 until row.childCount).mapNotNull(row::getChild)
            val avatars = children.filter { n ->
                val b = bounds(n)
                n.isClickable && label(n).isBlank() && b.width() in (width / 18)..(width / 6) &&
                    b.height() in (width / 18)..(width / 6) &&
                    (b.centerX() < width * .2 || b.centerX() > width * .8) &&
                    nodes(n).any { it.className?.toString() == "android.widget.ImageView" }
            }.map(::bounds)
            if (avatars.isEmpty()) return@mapNotNull null
            val body = children.filter { n ->
                val b = bounds(n)
                n.isVisibleToUser && (label(n).isNotBlank() || n.className?.toString() == "android.widget.ImageView") &&
                    b.top >= top && b.bottom <= bottom &&
                    avatars.any { a -> Rect.intersects(Rect(0, a.top, width, rb.bottom), b) &&
                        if (a.centerX() < width / 2) b.left >= a.right else b.right <= a.left }
            }.singleOrNull() ?: return@mapNotNull null
            val sides = avatars.map { if (it.centerX() < width / 2) "other" else "me" }.distinct()
            val text = label(body)
            val kind = when {
                body.className?.toString() == "android.widget.ImageView" -> MessageKind.MEDIA_UNKNOWN
                VOICE.matches(text) -> MessageKind.VOICE_UNKNOWN
                else -> MessageKind.TEXT
            }
            val time = markers.lastOrNull { it.first <= rb.top }?.second
            rb.top to Msg(sides.singleOrNull() ?: "unknown", if (kind == MessageKind.TEXT) text else "",
                kind, time, if (time == null) "unknown" else "partial")
        }.sortedBy { it.first }.map { it.second }
        val profile = all.asSequence().map(::label).mapNotNull { PROFILE.find(it) }.firstOrNull()
        val role = header.firstOrNull { it.className?.toString() == "android.widget.ImageView" &&
            label(it).isNotBlank() }?.let(::label) ?: profile?.groupValues?.get(1)?.trim()?.takeUnless { it == "无" }
        return ChatSnapshot(title, messages, note = "呼叫小狗 · 直接读取可见文字，不使用截屏\n" +
            "资料卡、礼物提示不作对方发言；未显示日期的时间只标部分时间，未知内容不猜测。",
            roleChat = RoleChatMetadata(role, profile?.groupValues?.get(2)?.toIntOrNull()))
    }

    private fun label(n: AccessibilityNodeInfo) = (n.text?.toString()?.takeIf(String::isNotBlank)
        ?: n.contentDescription?.toString()).orEmpty().trim()
    private fun bounds(n: AccessibilityNodeInfo) = Rect().also(n::getBoundsInScreen)
    private fun nodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val result = ArrayList<AccessibilityNodeInfo>()
        val stack = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        while (stack.isNotEmpty() && result.size < 1800) {
            val n = stack.removeLast(); result.add(n)
            for (i in n.childCount - 1 downTo 0) n.getChild(i)?.let(stack::add)
        }
        return result
    }
    companion object {
        const val PACKAGE = "com.yixiangweilai.yuebaobao"
        private val PROFILE = Regex("属性\\s+([^\\n·]+)\\s*·\\s*(\\d{1,3})岁")
        private val STATUS = Regex("(?:在线|刚刚(?:在线|活跃)|离线|.*(?:分钟前|小时前|天前)(?:活跃|在线)|.*正在输入.*)")
        private val VOICE = Regex("(?:语音\\s*)?\\d{1,3}\\s*(?:秒|[\"″])")
    }
}
