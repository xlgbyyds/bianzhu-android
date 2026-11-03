package com.jev.probe.capture

import android.content.res.Resources
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.jev.probe.core.MessageKind
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w375dp-h834dp-520dpi")
class PupcallAdapterTest {
    private val res = Resources.getSystem()
    private val w get() = res.displayMetrics.widthPixels
    private val h get() = res.displayMetrics.heightPixels
    private val adapter = PupcallAdapter()
    private fun node(desc: String = "", left: Double = 0.0, top: Double = 0.0,
                     right: Double = 1.0, bottom: Double = 1.0, image: Boolean = false,
                     clickable: Boolean = false) = AccessibilityNodeInfo.obtain().apply {
        packageName = PupcallAdapter.PACKAGE; isVisibleToUser = true; isClickable = clickable
        className = if (image) "android.widget.ImageView" else "android.view.View"
        contentDescription = desc
        setBoundsInScreen(Rect((w * left).toInt(), (h * top).toInt(), (w * right).toInt(), (h * bottom).toInt()))
    }
    private fun add(parent: AccessibilityNodeInfo, child: AccessibilityNodeInfo) { shadowOf(parent).addChild(child) }
    private fun chat(): AccessibilityNodeInfo = node().apply {
        add(this, node("测试联系人", .23, .057, .43, .082))
        add(this, node("刚刚活跃", .23, .083, .40, .099))
        add(this, node("Dom", .54, .058, .69, .081, image = true))
        add(this, node("你好呀！很高兴认识你！\n属性 Dom · 25岁\n恭喜你们，茫茫人海中彼此相遇！", top = .115, bottom = .35))
        add(this, node(top = .83, bottom = .88, left = .18, right = .85).apply { className = "android.widget.EditText" })
    }
    private fun row(side: String, desc: String, top: Double, image: Boolean = false): AccessibilityNodeInfo =
        node(top = top, bottom = top + .11).apply {
            val other = side == "other"
            add(this, node(left = if (other) .043 else .85, right = if (other) .149 else .956,
                top = top + .01, bottom = top + .058, clickable = true).apply {
                add(this, node(image = true))
            })
            // Both long bodies can cross the center. Only the avatar proves ownership.
            add(this, node(desc, left = if (other) .181 else .10, right = if (other) .85 else .819,
                top = top + .01, bottom = top + .095, image = image, clickable = true))
        }

    @Test fun semanticsTextIsReadAndWideBodiesUseTheirOwnAvatarSide() {
        val root = chat()
        add(root, node("昨天 16:29", top = .36, bottom = .49))
        add(root, row("other", "今天读书了吗", .39))
        add(root, row("me", "刚看完一章 准备出去走走", .53))
        val result = adapter.extract(root, res)!!
        assertEquals("测试联系人", result.title)
        assertEquals(listOf("other", "me"), result.messages.map { it.side })
        assertEquals(listOf("今天读书了吗", "刚看完一章 准备出去走走"), result.messages.map { it.text })
        assertTrue(result.messages.all { it.visibleTime == "昨天 16:29" && it.timeConfidence == "partial" })
        assertEquals("Dom", result.roleChat?.profileRole)
        assertEquals(25, result.roleChat?.profileAge)
    }
    @Test fun profileWelcomeGiftAndListSummariesAreNotSpeech() {
        val root = chat()
        add(root, node("恭喜你们，立即回复开启缘分之旅!", top = .36, bottom = .4))
        add(root, node("收到\n坚果 X1", top = .5, bottom = .6, image = true))
        add(root, row("other", "先认识一下", .41))
        assertEquals(listOf("先认识一下"), adapter.extract(root, res)!!.messages.map { it.text })
        val list = node().apply {
            add(this, node("搭讪消息", .4, .06, .6, .09))
            add(this, node("测试联系人\n主人你好\n16:00", top = .2, bottom = .3))
        }
        assertNull(adapter.extract(list, res))
    }
    @Test fun missingTitleNeverFallsBackToOnlineStatus() {
        val root = node().apply {
            add(this, node("刚刚活跃", .23, .083, .40, .099))
            add(this, node(top = .83, bottom = .88).apply { className = "android.widget.EditText" })
            add(this, row("other", "看书", .4))
        }
        assertNull(adapter.extract(root, res))
    }
    @Test fun finalImageAndVoiceCannotMakeAnOlderTextLookLikeTheLatestMessage() {
        val root = chat()
        add(root, row("me", "上条文字", .4))
        add(root, row("other", "", .53, image = true))
        add(root, row("other", "12秒", .66))
        val messages = adapter.extract(root, res)!!.messages
        assertEquals(listOf(MessageKind.TEXT, MessageKind.MEDIA_UNKNOWN, MessageKind.VOICE_UNKNOWN), messages.map { it.kind })
        assertEquals("other", messages.last().side)
    }
    @Test fun imageClassWithReadableSemanticsIsTextButMediaAndVoiceLabelsStayUnknown() {
        val root = chat()
        add(root, row("other", "刚忙完 你呢[坏笑]", .36, image = true))
        add(root, row("me", "准备出门走走", .48, image = true))
        add(root, row("other", "[图片]", .60, image = true))
        add(root, row("other", "语音 12秒", .72, image = true))
        val messages = adapter.extract(root, res)!!.messages
        assertEquals(listOf(MessageKind.TEXT, MessageKind.TEXT, MessageKind.MEDIA_UNKNOWN,
            MessageKind.VOICE_UNKNOWN), messages.map { it.kind })
        assertEquals(listOf("刚忙完 你呢[坏笑]", "准备出门走走", "", ""), messages.map { it.text })
        assertEquals(listOf("other", "me", "other", "other"), messages.map { it.side })
    }
    @Test fun clippedMessageIsNotRecordedAsComplete() {
        val root = chat()
        add(root, row("other", "完整文字", .4))
        add(root, row("other", "正文已被输入框遮住", .78))
        assertEquals(listOf("完整文字"), adapter.extract(root, res)!!.messages.map { it.text })
    }

    @Test fun timestampOnWholeIncomingRowAndNestedReadReceiptKeepTheActualLastSpeaker() {
        val root = chat()
        val outgoing = row("me", "前面是我说的", .4)
        val body = outgoing.getChild(1)
        add(body, node("查看消息已读", .55, .46, .819, .49, image = true))
        add(root, outgoing)
        add(root, row("other", "这句才是她刚发的", .6).apply { contentDescription = "15:56" })
        val result = adapter.extract(root, res)!!
        assertEquals(listOf("me", "other"), result.messages.map { it.side })
        assertEquals("这句才是她刚发的", result.messages.last().text)
        assertEquals("15:56", result.messages.last().visibleTime)
        assertFalse(result.messages.any { it.text.contains("已读") })
    }
}
