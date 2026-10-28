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
class SoulAdapterTest {
    private val res get() = RuntimeEnvironment.getApplication().resources
    private val width get() = res.displayMetrics.widthPixels
    private val adapter = SoulAdapter()
    private lateinit var root: AccessibilityNodeInfo
    private lateinit var list: AccessibilityNodeInfo
    private lateinit var title: AccessibilityNodeInfo
    private lateinit var input: AccessibilityNodeInfo

    @Before fun page() {
        title = node("tv_title", "测试联系人", 10, 80)
        val toolbar = node("navigation_bar", top = 0, bottom = 100, children = arrayOf(title,
            node("tv_online_label", "10分钟前", 20, 80)))
        list = node("list_conversation", top = 100, bottom = 1400)
        input = node("et_sendmessage", top = 1450, bottom = 1550)
        root = node(children = arrayOf(toolbar, list, input,
            node("chat_media_menu", top = 1400, bottom = 1600)))
    }

    @Test fun longOutgoingAndIncomingTextUseAvatarsInsteadOfBubbleCentre() {
        add(list, row("me", "很长的我方消息", 180))
        add(list, row("other", "同样很长的对方消息", 360))
        val m = adapter.extract(root, res)!!.messages
        assertEquals(listOf("me", "other"), m.map { it.side })
        assertEquals(listOf("很长的我方消息", "同样很长的对方消息"), m.map { it.text })
    }

    @Test fun timestampBelongsToFollowingMessagesAndOnlineStatusIsNotChatTime() {
        add(list, row("me", "较早的消息", 180))
        val timed = row("other", "时间之后的消息", 400)
        add(timed, node("timestamp", "14:33", 370, 395))
        add(list, timed)
        add(list, row("me", "我接着说", 600))
        val snap = adapter.extract(root, res)!!
        assertEquals("测试联系人", snap.title)
        assertNull(snap.messages.first().visibleTime)
        assertEquals(listOf("14:33", "14:33"), snap.messages.drop(1).map { it.visibleTime })
        assertTrue(snap.messages.drop(1).all { it.timeConfidence == "partial" })
    }

    @Test fun profileCardsShortcutsAndQuotedTextDoNotBecomeMessages() {
        add(root, node("content_text", "顶部资料卡", 100, 180))
        val card = row("other", null, 400)
        val body = card.getChild(0).getChild(1)
        add(body, node("content_text", "卡片介绍不能作为聊天", 420, 480))
        add(list, card)
        add(list, row("me", "真正的文字", 650))
        val m = adapter.extract(root, res)!!.messages
        assertEquals(2, m.size)
        assertEquals(MessageKind.MEDIA_UNKNOWN, m.first().kind)
        assertEquals("", m.first().text)
        assertEquals("真正的文字", m.last().text)
    }

    @Test fun missingAvatarRemainsUnknownAndCoveredAvatarStillIdentifiesSpeaker() {
        add(list, row(null, "发言者待确认", 180))
        val covered = row("me", "头像被悬浮球遮挡", 400)
        covered.getChild(0).getChild(0).isVisibleToUser = false
        add(list, covered)
        assertEquals(listOf("unknown", "me"), adapter.extract(root, res)!!.messages.map { it.side })
    }

    @Test fun unreadableLastRowDoesNotFallBackToPreviousOutgoingText() {
        add(list, row("me", "我之前发的", 180))
        add(list, row("other", null, 400))
        val m = adapter.extract(root, res)!!.messages
        assertEquals("other", m.last().side)
        assertEquals(MessageKind.MEDIA_UNKNOWN, m.last().kind)
    }

    @Test fun invisibleRowsAndRowsBelowInputAreExcluded() {
        add(list, row("me", "不可见", 180).apply { isVisibleToUser = false })
        add(list, row("other", "在输入区后面", 1450))
        assertTrue(adapter.extract(root, res)!!.messages.isEmpty())
        input.isVisibleToUser = false
        assertNull(adapter.extract(root, res))
    }

    @Test fun missingToolbarTitleCannotUseACardTitle() {
        title.text = null
        add(list, node("tv_title", "资料卡里的昵称", 200, 250))
        assertNull(adapter.extract(root, res))
    }

    @Test fun clippedTextDoesNotBecomeAnUnknownMediaRecord() {
        add(list, row("other", "上边缘半截文字", 70))
        add(list, row("me", "完整的文字", 300))
        add(list, row("other", "下边缘半截文字", 1370))
        add(list, row("other", null, 1390))
        val m = adapter.extract(root, res)!!.messages
        assertEquals(listOf("完整的文字"), m.map { it.text })
        assertEquals(MessageKind.TEXT, m.single().kind)
    }

    private fun row(side: String?, text: String?, top: Int): AccessibilityNodeInfo {
        val children = ArrayList<AccessibilityNodeInfo>()
        if (side != null) children.add(node("chat_avatar", top = top, bottom = top + 90,
            left = if (side == "me") width * 9 / 10 else 5,
            right = if (side == "me") width - 5 else width / 10))
        // Deliberately place me text left of centre and other text right of centre.
        val textLeft = if (side == "me") width / 10 else width / 4
        val textRight = if (side == "me") width * 3 / 4 else width * 9 / 10
        val bubble = node("container", top = top, bottom = top + 110)
        if (text != null) add(bubble, node("content_text_container", top = top, bottom = top + 110,
            children = arrayOf(node("content_text", text, top, top + 90, textLeft, textRight))))
        children.add(bubble)
        return node("item_root", top = top, bottom = top + 120, children = arrayOf(
            node("layout_content", top = top, bottom = top + 120, children = children.toTypedArray())))
    }

    private fun node(id: String? = null, text: String? = null, top: Int = 0, bottom: Int = 1600,
                     left: Int = 0, right: Int = width,
                     children: Array<AccessibilityNodeInfo> = emptyArray()): AccessibilityNodeInfo =
        AccessibilityNodeInfo.obtain().apply {
            packageName = SoulAdapter.PACKAGE
            viewIdResourceName = id?.let { "${SoulAdapter.PACKAGE}:id/$it" }
            this.text = text
            isVisibleToUser = true
            setBoundsInScreen(Rect(left, top, right, bottom))
            children.forEach { add(this, it) }
        }

    private fun add(parent: AccessibilityNodeInfo, child: AccessibilityNodeInfo) { shadowOf(parent).addChild(child) }
}
