package com.jev.probe.capture

import android.content.res.Resources
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.MessageKind
import com.jev.probe.core.Msg
import com.jev.probe.core.RoleChatMetadata

/**
 * 瓶子说 (com.pingzishuo.www) — an anonymous one-shot chat built on uni-app/DCloud.
 * Every page is a WebView whose semantic text names the route, e.g.
 * "pages/chat/chat[2]"; message text is exposed as plain TextViews, so no OCR.
 *
 * Verified on the personal device (v2.0.32, 2026-11-10):
 * - chat page = a WebView labelled pages/chat/chat + a bottom EditText editor
 *   (when the peer leaves, the editor is replaced by a "离开聊天" button, so
 *   extract() correctly returns null on a dead conversation);
 * - a banner "对方信息: 男生 18-23岁" states an unverified sex/age bracket;
 * - incoming bubbles are anchored left, own bubbles right (red background);
 * - state lines such as "对方离开了。" render as left-anchored TextViews too,
 *   so they must be filtered by text, never mistaken for the peer's words.
 *
 * Anonymous by design: no durable identity exists, so the title is a fixed
 * label and memory capture must never engage (see ChatCaptureService).
 */
class PingziAdapter : ChatAppAdapter {
    override val pkg = PACKAGE

    override fun extract(root: AccessibilityNodeInfo, res: Resources): ChatSnapshot? {
        if (root.packageName?.toString() != pkg) return null
        val all = descendants(root).toList()
        val chatPage = all.firstOrNull {
            it.className?.toString() == "android.webkit.WebView" &&
                label(it).startsWith("pages/chat/chat")
        } ?: return null
        val editor = all.firstOrNull {
            it.className?.toString() == "android.widget.EditText" && it.isVisibleToUser
        } ?: return null
        val width = res.displayMetrics.widthPixels
        val height = res.displayMetrics.heightPixels

        // Messages live between the tab strip and the editor.
        val tabBottom = all.filter { it.isVisibleToUser && label(it) in TABS }
            .mapNotNull { bounds(it).takeIf { b -> !b.isEmpty }?.bottom }.maxOrNull()
        val top = tabBottom ?: (height * TOP_FALLBACK).toInt()
        val bottom = bounds(editor).top

        // Unverified sex/age bracket banner; the low bound feeds the under-18 guard.
        val banner = all.asSequence().map(::label)
            .firstOrNull { BANNER.matches(it) }
        val bracketAge = banner?.let { BANNER.find(it)?.groupValues?.get(2)?.toIntOrNull() }

        val seen = ArrayList<Pair<String, Int>>() // text + top, to kill mirrored nodes
        val messages = all.mapNotNull { node ->
            val text = label(node)
            if (!node.isVisibleToUser || text.isBlank() || text in TABS) return@mapNotNull null
            // WebView/EditText semantic labels are route names ("pages/chat/chat"),
            // never chat text; icons live in the Unicode private-use area.
            val cls = node.className?.toString().orEmpty()
            if (cls.contains("WebView") || cls.contains("EditText")) return@mapNotNull null
            if (text.startsWith("pages/")) return@mapNotNull null
            if (text.any { it in '\uE000'..'\uF8FF' }) return@mapNotNull null
            // The sex/age bracket banner is parsed separately, never a message.
            if (BANNER.matches(text)) return@mapNotNull null
            val rect = bounds(node)
            if (rect.isEmpty || rect.top < top || rect.bottom > bottom ||
                SYSTEM_LINE.any { it.containsMatchIn(text) }) return@mapNotNull null
            if (seen.any { (t, y) -> t == text && kotlin.math.abs(y - rect.top) <= 8 }) return@mapNotNull null
            seen.add(text to rect.top)
            val side = when {
                rect.left >= width * 0.30f && rect.right >= width * 0.72f -> "me"
                rect.left <= width * 0.25f -> "other"
                else -> return@mapNotNull null // centered/ambiguous chrome is not a message
            }
            val kind = if (VOICE.matches(text)) MessageKind.VOICE_UNKNOWN else MessageKind.TEXT
            rect.top to Msg(side, if (kind == MessageKind.TEXT) text else "", kind,
                visibleTime = null, timeConfidence = "unknown")
        }.sortedBy { it.first }.map { it.second }

        return ChatSnapshot(TITLE, messages,
            note = "瓶子说 · 匿名聊天\n" +
                "对方离开即断线，离开后无法继续；仅使用当前可见消息，不关联任何联系人记忆。" +
                "资料横幅（${banner ?: "未显示"}）为自述未核实。发言者按气泡左右判断；图片和语音内容未知。",
            roleChat = RoleChatMetadata(banner?.let { "性别年龄横幅：$it" }, bracketAge))
    }

    private fun label(n: AccessibilityNodeInfo) = (n.text?.toString()?.takeIf(String::isNotBlank)
        ?: n.contentDescription?.toString()).orEmpty().trim()
    private fun bounds(n: AccessibilityNodeInfo) = Rect().also(n::getBoundsInScreen)

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

    companion object {
        const val PACKAGE = "com.pingzishuo.www"
        const val TITLE = "瓶子说·匿名对话"

        /** Fixed chrome labels that must never become messages. */
        private val TABS = setOf("探索", "聊天", "暗号", "寻人", "离开", "离开聊天")
        private val BANNER = Regex("^(男生|女生)\\s*(\\d{1,2})\\s*[-–~～]?\\s*(\\d{0,3})\\s*岁$")
        private val VOICE = Regex("(?:语音\\s*)?\\d{1,3}\\s*(?:秒|[\"″'])")
        private val TOP_FALLBACK = 0.16f
        private val SYSTEM_LINE = listOf(
            Regex("^对方信息[:：]"),
            Regex("^提示[:：]"),
            Regex("^聊天前3分钟内发送不雅词会被过滤。?"),
            Regex("(?:绿色聊天|广告[,，]?谩骂)"),
            Regex("^对方离开了。?"),
            Regex("^您断开了连线。?"),
            Regex("VIP"),
            Regex("^(?:已读|未读)$"),
            Regex("^\\d{1,2}[:：]\\d{2}$"),
            Regex("^瓶子说.*匿名聊天网$"),
            Regex("^pingzishuo\\.com$"),
            Regex("^发送消息.*$"),
            Regex("^(?:我已阅读并同意|隐私协议|当前版本).*"),
            Regex("^(?:我听说，每个漂流瓶|都是在这颗孤独星球上迷失的人)$"),
            Regex("^(?:我是男生|我是女生|捡一个瓶子|分区)$"),
            Regex("^(?:立即开通|取消)$")
        )
    }
}
