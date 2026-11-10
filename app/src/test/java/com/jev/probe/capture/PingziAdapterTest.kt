package com.jev.probe.capture

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.jev.probe.core.MessageKind
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h800dp-xhdpi")
class PingziAdapterTest {
    private val res get() = RuntimeEnvironment.getApplication().resources
    private val width get() = res.displayMetrics.widthPixels

    private val adapter get() = PingziAdapter()

    @Test fun chatPageExtractsBothSidesAndFiltersSystemLines() {
        val snapshot = adapter.extract(page(
            line("对方信息:", 400, 460, .12, .30),
            line("男生 18-23岁", 400, 460, .32, .55),
            line("提示: 聊天前3分钟内发送不雅词会被过滤。", 480, 540, .12, .90),
            line("绿色聊天,拒绝低俗,本站24h进行举报审核。", 560, 620, .15, .90),
            line("在吗", 700, 780, .11, .35),
            line("哈哈 是不是没睡", 900, 980, .11, .60),
            line("刚到家", 1000, 1080, .62, .88),
            line("已读", 1100, 1140, .85, .93),
            line("对方离开了。", 1200, 1280, .11, .36)
        ), res)!!

        assertEquals(PingziAdapter.TITLE, snapshot.title)
        assertEquals(listOf("other", "other", "me"), snapshot.messages.map { it.side })
        assertEquals(listOf("在吗", "哈哈 是不是没睡", "刚到家"), snapshot.messages.map { it.text })
        assertTrue(snapshot.messages.all { it.kind == MessageKind.TEXT })
    }

    @Test fun bannerBracketFeedsRoleChatAgeAndLabel() {
        val snapshot = adapter.extract(page(
            line("对方信息:", 400, 460, .12, .30),
            line("女生 24-30岁", 400, 460, .32, .55),
            line("在吗", 700, 780, .11, .35)
        ), res)!!

        assertEquals(24, snapshot.roleChat?.profileAge)
        assertEquals("性别年龄横幅：女生 24-30岁", snapshot.roleChat?.profileRole)
    }

    @Test fun underEighteenBracketIsStillReportedForTheGuard() {
        val snapshot = adapter.extract(page(
            line("女生 16岁", 400, 460, .32, .55),
            line("在吗", 700, 780, .11, .35)
        ), res)!!

        assertEquals(16, snapshot.roleChat?.profileAge)
        assertTrue(snapshot.messages.map { it.text } == listOf("在吗"))
    }

    @Test fun longIncomingBubbleStaysOtherAndLongOutgoingStaysMe() {
        val snapshot = adapter.extract(page(
            line("今天一直在想要不要跟你说这件事 其实挺纠结的", 700, 780, .11, .68),
            line("嗯我在听 你想说什么都可以跟我说", 900, 980, .32, .89)
        ), res)!!

        assertEquals(listOf("other", "me"), snapshot.messages.map { it.side })
    }

    @Test fun mirroredNodeWithSameTextAtSameTopIsCountedOnce() {
        val bubble = line("在吗", 700, 780, .11, .35)
        val mirror = line("在吗", 704, 784, .11, .35)

        val snapshot = adapter.extract(page(bubble, mirror), res)!!
        assertEquals(listOf("在吗"), snapshot.messages.map { it.text })
    }

    @Test fun voiceDurationLineIsUnknownVoiceNotSpeech() {
        val snapshot = adapter.extract(page(
            line("12秒", 700, 780, .11, .35)
        ), res)!!

        val message = snapshot.messages.single()
        assertEquals("other", message.side)
        assertEquals(MessageKind.VOICE_UNKNOWN, message.kind)
        assertEquals("", message.text)
    }

    @Test fun centeredAmbiguousLineIsDroppedInsteadOfGuessed() {
        val snapshot = adapter.extract(page(
            line("一条居中的系统横幅", 700, 780, .40, .60)
        ), res)!!

        assertTrue(snapshot.messages.isEmpty())
    }

    @Test fun nonChatPageWithoutEditorReturnsNull() {
        val root = node("android.widget.FrameLayout", null, 0, 1600, 0.0, 1.0).apply {
            shadowOf(this).addChild(node("android.webkit.WebView", "pages/index/index[1]", 0, 1600, 0.0, 1.0).apply {
                shadowOf(this).addChild(line("捡一个瓶子", 1735, 1865, .24, .75))
                shadowOf(this).addChild(line("我是男生", 1508, 1576, .28, .43))
            })
            seal(this)
        }
        assertNull(adapter.extract(root, res))
    }

    @Test fun deadConversationWithLeaveButtonInsteadOfEditorReturnsNull() {
        val root = node("android.widget.FrameLayout", null, 0, 1600, 0.0, 1.0).apply {
            shadowOf(this).addChild(node("android.webkit.WebView", "pages/chat/chat[2]", 0, 1600, 0.0, 1.0).apply {
                addTabs(this)
                shadowOf(this).addChild(line("对方离开了。", 640, 724, .11, .36))
                shadowOf(this).addChild(line("离开聊天", 2414, 2496, .41, .59))
            })
            seal(this)
        }
        assertNull(adapter.extract(root, res))
    }

    @Test fun packageMismatchReturnsNull() {
        val root = node("android.widget.FrameLayout", null, 0, 1600, 0.0, 1.0).apply {
            packageName = "com.tencent.mm"
            seal(this)
        }
        assertNull(adapter.extract(root, res))
    }

    // ------------------------------------------------------------ helpers

    private fun page(vararg children: AccessibilityNodeInfo): AccessibilityNodeInfo =
        node("android.widget.FrameLayout", null, 0, 1600, 0.0, 1.0).apply {
            shadowOf(this).addChild(node("android.webkit.WebView", "pages/chat/chat[2]", 0, 1600, 0.0, 1.0).apply {
                addTabs(this)
                children.forEach { shadowOf(this).addChild(it) }
                shadowOf(this).addChild(
                    node("android.widget.EditText", null, 1500, 1580, .24, .71))
            })
            seal(this)
        }

    private fun addTabs(web: AccessibilityNodeInfo) {
        shadowOf(web).addChild(line("探索", 312, 393, .20, .30))
        shadowOf(web).addChild(line("聊天", 312, 393, .70, .79))
        shadowOf(web).addChild(line("暗号", 318, 383, .84, .92))
    }

    private fun line(text: String, top: Int, bottom: Int, left: Double, right: Double) =
        node("android.widget.TextView", text, top, bottom, left, right)

    private fun node(cls: String, text: String?, top: Int, bottom: Int,
                     left: Double, right: Double) = AccessibilityNodeInfo.obtain().apply {
        packageName = PingziAdapter.PACKAGE
        className = cls
        this.text = text
        isVisibleToUser = true
        setBoundsInScreen(Rect((width * left).toInt(), top, (width * right).toInt(), bottom))
    }

    private fun seal(root: AccessibilityNodeInfo) {
        org.robolectric.util.ReflectionHelpers.callInstanceMethod<Void>(root, "setSealed",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, true))
    }
}
