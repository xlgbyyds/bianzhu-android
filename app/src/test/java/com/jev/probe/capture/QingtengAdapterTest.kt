package com.jev.probe.capture

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.jev.probe.core.MessageKind
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h800dp-xhdpi")
class QingtengAdapterTest {
    private val res get() = RuntimeEnvironment.getApplication().resources
    private val adapter = QingtengAdapter()
    private lateinit var root: AccessibilityNodeInfo
    private lateinit var list: AccessibilityNodeInfo
    private lateinit var title: AccessibilityNodeInfo
    private lateinit var input: AccessibilityNodeInfo
    private val width get() = res.displayMetrics.widthPixels

    @Before fun page() {
        title = node("title_tv", ".", 0, 120, 100, 220)
        val toolbar = node("title_bar_root", children = arrayOf(title))
        list = node("recycler_view", top = 120, bottom = 1400)
        val chat = node("friend_chat_container", children = arrayOf(list))
        input = node("send_view", top = 1400, bottom = 1550)
        root = node(children = arrayOf(toolbar, chat, input))
    }

    @Test fun readsBothSidesAndDateButExcludesTipsAndDuplicateTitleIds() {
        add(list, node("chat_tips_ll", children = arrayOf(node("title_tv", "聊天小贴士"),
            node("content_tv", "提示内容不能进入对话"))))
        add(list, row("other", "一条较长的对方消息，气泡跨过了屏幕中心", 200))
        add(list, node("chat_time_tv", "2025年11月24日", 330, 365))
        add(list, row("me", "这是我的消息", 400))
        add(list, row("other", "收到回复啦", 540))
        add(list, node("chat_time_tv", "2025年11月25日", 670, 700))
        add(list, row("other", "第二天的新话题", 730))
        val result = adapter.extract(root, res)!!
        assertEquals(".", result.title)
        assertEquals(listOf("other", "me", "other", "other"), result.messages.map { it.side })
        assertNull(result.messages[0].visibleTime)
        assertEquals(listOf("2025年11月24日", "2025年11月24日", "2025年11月25日"),
            result.messages.drop(1).map { it.visibleTime })
        assertTrue(result.messages.drop(1).all { it.timeConfidence == "partial" })
        assertFalse(result.messages.any { it.text.contains("提示内容") })
    }

    @Test fun profileOrListPageWithATitleCannotPassAsChat() {
        input.isVisibleToUser = false
        assertNull(adapter.extract(root, res))
    }

    @Test fun missingToolbarNameCannotFallBackToTipsTitle() {
        title.text = null
        add(list, node("title_tv", "聊天小贴士"))
        assertNull(adapter.extract(root, res))
    }

    @Test fun unreadableIncomingRowStaysLastAndBlocksGuessingFromOlderText() {
        add(list, row("me", "之前我说的话", 200))
        add(list, row("other", null, 400))
        val messages = adapter.extract(root, res)!!.messages
        assertEquals(2, messages.size)
        assertEquals("other", messages.last().side)
        assertEquals(MessageKind.MEDIA_UNKNOWN, messages.last().kind)
        assertEquals("", messages.last().text)
    }

    @Test fun invisibleRowsAndTextInsideAnOutgoingCardAreNotPlainMessages() {
        add(list, row("other", "不可见消息", 200).apply { isVisibleToUser = false })
        val card = row("me", null, 400)
        add(card, node("profile_card", children = arrayOf(node(text = "资料卡介绍"))))
        add(list, card)
        val messages = adapter.extract(root, res)!!.messages
        assertEquals(1, messages.size)
        assertEquals(MessageKind.MEDIA_UNKNOWN, messages.single().kind)
    }

    @Test fun ambiguousNicknameCannotCreateDurableMemoryAssociation() {
        assertFalse(QingtengAdapter.canRemember("."))
        assertFalse(QingtengAdapter.canRemember("……"))
        assertTrue(QingtengAdapter.canRemember("小林"))
    }

    private fun row(side: String, text: String?, top: Int): AccessibilityNodeInfo {
        val avatar = node("head_icon_view", top = top, bottom = top + 80,
            left = if (side == "me") width * 9 / 10 else width / 20,
            right = if (side == "me") width * 19 / 20 else width / 10)
        val row = node("chat_message_ll", top = top, bottom = top + 100, children = arrayOf(avatar))
        if (text != null) add(row, node(if (side == "other") "chat_text_tv" else null,
            text, top, top + 100, width / 5, width * 7 / 8))
        return row
    }

    private fun node(id: String? = null, text: String? = null, top: Int = 0, bottom: Int = 1600,
                     left: Int = 0, right: Int = width,
                     children: Array<AccessibilityNodeInfo> = emptyArray()): AccessibilityNodeInfo =
        AccessibilityNodeInfo.obtain().apply {
            packageName = QingtengAdapter.PACKAGE
            viewIdResourceName = id?.let { "${QingtengAdapter.PACKAGE}:id/$it" }
            this.text = text
            className = if (text != null) "android.widget.TextView" else "android.widget.LinearLayout"
            isVisibleToUser = true
            setBoundsInScreen(Rect(left, top, right, bottom))
            children.forEach { add(this, it) }
        }

    private fun add(parent: AccessibilityNodeInfo, child: AccessibilityNodeInfo) {
        shadowOf(parent).addChild(child)
    }
}
