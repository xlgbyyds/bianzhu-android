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
class WeChatAdapterTest {
    private val res get() = RuntimeEnvironment.getApplication().resources
    private val width get() = res.displayMetrics.widthPixels

    @Test fun titleOcrUsesNameChildInsteadOfAdjacentWechatStatus() {
        val toolbar = node("gp", null, 40, 150, .25, .75)
        val name = node("obq", null, 52, 100, .42, .58)
        shadowOf(toolbar).addChild(name)
        shadowOf(toolbar).addChild(node("nqu", "emo", 92, 160, .43, .57))
        val root = node(null, null, 0, 1600).apply {
            shadowOf(this).addChild(toolbar)
            seal(this)
        }
        val toolbarBounds = Rect().also(toolbar::getBoundsInScreen)
        val nameBounds = Rect().also(name::getBoundsInScreen)

        assertEquals(nameBounds, wechatNameOcrRegion(root, toolbarBounds))
        assertNull(findWeChatTitle(root, Int.MAX_VALUE, width, res))
    }

    @Test fun readableWechatNameWinsOverReadableStatus() {
        val toolbar = node("gp", null, 40, 150, .25, .75)
        shadowOf(toolbar).addChild(node("obq", "12345", 52, 100, .42, .58))
        shadowOf(toolbar).addChild(node("nqu", "emo", 92, 160, .43, .57))
        val root = node(null, null, 0, 1600).apply {
            shadowOf(this).addChild(toolbar)
            seal(this)
        }

        assertEquals("12345", findWeChatTitle(root, Int.MAX_VALUE, width, res))
    }

    @Test fun titleOcrKeepsToolbarWhenNameChildIsMissingOrOutsideIt() {
        val toolbar = node("gp", null, 40, 150, .25, .75)
        val root = node(null, null, 0, 1600).apply {
            shadowOf(this).addChild(toolbar)
            shadowOf(this).addChild(node("obq", null, 300, 360, .42, .58))
            seal(this)
        }
        val toolbarBounds = Rect().also(toolbar::getBoundsInScreen)

        assertEquals(toolbarBounds, wechatNameOcrRegion(root, toolbarBounds))
    }

    @Test fun longOutgoingAndIncomingBubblesUseTheirActualRowAvatars() {
        val snapshot = WeChatAdapter().extract(page(
            row("me", "跨中线的宽文字框", 300, .12, .82),
            row("other", "另一侧的宽文字框", 500, .18, .90)), res)!!
        assertEquals(listOf("me", "other"), snapshot.messages.map { it.side })
    }

    @Test fun centeredQuoteWithoutAChatRowIsNotAnExtraMessage() {
        val snapshot = WeChatAdapter().extract(page(
            row("other", "真正的对方消息", 300, .12, .35),
            node("bkl", "被引用的旧消息", 450, 500, .36, .66)), res)!!
        assertEquals(listOf("真正的对方消息"), snapshot.messages.map { it.text })
    }

    @Test fun clearlyAlignedShortTextKeepsItsSideAndVisibleTime() {
        val snapshot = WeChatAdapter().extract(page(
            node("time", "9月15日 16:00", 240, 280, .4, .6),
            row("other", "对方短消息", 300, .12, .35),
            row("me", "我的短消息", 450, .65, .88)), res)!!
        assertEquals(listOf("other", "me"), snapshot.messages.map { it.side })
        assertTrue(snapshot.messages.all { it.visibleTime == "9月15日 16:00" && it.timeConfidence == "partial" })
    }

    @Test fun missingAvatarRemainsUnknownAndMustNotBeGuessedFromTextCenter() {
        val snapshot = WeChatAdapter().extract(page(
            row(null, "长文本不能猜发言方", 300, .12, .88)), res)!!
        assertEquals("unknown", snapshot.messages.single().side)
    }

    @Test fun senderMissingAtClippedBoundaryDoesNotBlockCompleteRowsButMiddleUncertaintyRemains() {
        val snapshot = WeChatAdapter().extract(page(
            row(null, "顶部只剩消息末尾", 120, .12, .88),
            row("other", "完整消息按头像判定", 300, .12, .35),
            row(null, "中间仍不能猜发言方", 500, .12, .88)), res)!!
        assertEquals(listOf("完整消息按头像判定", "中间仍不能猜发言方"), snapshot.messages.map { it.text })
        assertEquals(listOf("other", "unknown"), snapshot.messages.map { it.side })
    }

    @Test fun unreadableToolbarAndInputClippedRowsDoNotEnterHistory() {
        val snapshot = WeChatAdapter().extract(page(
            row("other", "顶部被遮住的半条", 80, .12, .35),
            row("me", "完整可见", 300, .65, .88),
            row("other", "底部被遮住的半条", 1380, .12, .35)), res)!!
        assertEquals(listOf("完整可见"), snapshot.messages.map { it.text })
    }

    @Test fun nestedBubbleTextIsNotAnotherSpeakerOrDuplicateMessage() {
        val outer = node("bkl", "我的完整消息", 300, 500, .55, .88)
        shadowOf(outer).addChild(node("bkl", "引用的旧消息", 390, 460, .56, .82))
        val snapshot = WeChatAdapter().extract(page(messageRow("me", outer)), res)!!
        assertEquals(listOf("我的完整消息"), snapshot.messages.map { it.text })
    }

    @Test fun quotedReplyNestedRowKeepsOuterSenderAndDoesNotCreateQuoteMessage() {
        val quotedText = node("bjp", "quoted message", 340, 380, .60, .90)
        val reply = node("bkl", "my reply", 300, 420, .55, .90).apply {
            shadowOf(this).addChild(quotedText)
        }
        val nestedLayout = node("bkj", null, 300, 430, .50, .94).apply {
            shadowOf(this).addChild(reply)
        }
        val outerRow = node("bkj", null, 290, 440, 0.0, 1.0).apply {
            shadowOf(this).addChild(node("bk1", null, 300, 340, .86, .98))
            shadowOf(this).addChild(nestedLayout)
        }

        val snapshot = WeChatAdapter().extract(page(outerRow), res)!!
        assertEquals(listOf("my reply"), snapshot.messages.map { it.text })
        assertEquals(listOf("me"), snapshot.messages.map { it.side })
    }

    @Test fun voiceTranscriptBelowDurationInTheSameRowIsCapturedAsSpeech() {
        val duration = node("bkl", "8″", 300, 350, .12, .35)
        val row = messageRow("other", duration).apply {
            setBoundsInScreen(Rect(0, 290, width, 500))
            shadowOf(this).addChild(node("bkl", "今天路上有点堵", 355, 450, .12, .62))
        }

        val message = WeChatAdapter().extract(page(row), res)!!.messages.single()

        assertEquals("other", message.side)
        assertEquals(MessageKind.VOICE_TRANSCRIPT, message.kind)
        assertEquals("今天路上有点堵", message.text)
    }

    @Test fun voiceDurationAloneIsUnknownContentRatherThanAChatSentence() {
        val snapshot = WeChatAdapter().extract(page(row("other", "8″", 300, .12, .35)), res)!!
        val message = snapshot.messages.single()

        assertEquals("other", message.side)
        assertEquals(MessageKind.VOICE_UNKNOWN, message.kind)
        assertEquals("", message.text)
    }

    @Test fun voiceConversionActionLabelIsNotMistakenForRecognizedSpeech() {
        val message = wechatVoiceTranscript(listOf("8″", "转文字"))

        assertEquals(MessageKind.VOICE_UNKNOWN, message.kind)
        assertEquals("", message.text)
    }

    @Test fun transcriptTextDoesNotNeedTheBubbleResourceId() {
        val row = messageRow("other", node("bkl", "8″", 300, 350, .12, .35)).apply {
            setBoundsInScreen(Rect(0, 290, width, 500))
            shadowOf(this).addChild(node("audio_transcript", "我刚到家", 355, 450, .12, .62))
        }

        val message = WeChatAdapter().extract(page(row), res)!!.messages.single()

        assertEquals(MessageKind.VOICE_TRANSCRIPT, message.kind)
        assertEquals("我刚到家", message.text)
    }

    @Test fun customDrawnTranscriptsExposeOnlyTheirOwningVoiceRegionsForBothSenders() {
        fun voice(side: String, top: Int) = messageRow(side,
            node("bkl", "2\"", top, top + 65, if (side == "me") .65 else .12,
                if (side == "me") .88 else .35)).apply {
            setBoundsInScreen(Rect(0, top, width, top + 200))
            shadowOf(this).addChild(node("bru", null, top + 70, top + 190,
                if (side == "me") .40 else .12, if (side == "me") .88 else .65))
        }
        val snapshot = WeChatAdapter().extract(page(voice("other", 300), voice("me", 600)), res)!!
        assertEquals(listOf("other", "me"), snapshot.messages.map { it.side })
        assertEquals(listOf(0, 1), snapshot.voiceTranscriptRects.map { it.messageIndex })
        assertTrue(snapshot.messages.all { it.kind == MessageKind.VOICE_UNKNOWN })
    }

    @Test fun clippedOrUnrelatedCustomTextIsNeverAVoiceCrop() {
        val voice = messageRow("other", node("bkl", "2\"", 300, 370, .12, .35)).apply {
            shadowOf(this).addChild(node("bru", null, 600, 690, .12, .65))
        }
        val text = messageRow("me", node("bkl", "普通回复", 800, 890, .65, .88)).apply {
            shadowOf(this).addChild(node("bru", null, 810, 880, .40, .88))
        }
        val snapshot = WeChatAdapter().extract(page(voice, text), res)!!
        assertTrue(snapshot.voiceTranscriptRects.isEmpty())
        assertEquals(MessageKind.TEXT, snapshot.messages.last().kind)
    }

    @Test fun textOutsideAChatAndRowReturnsNull() {
        val root = node(null, null, 0, 1600).apply {
            shadowOf(this).addChild(node("bkl", "列表预览", 300, 370, .12, .35))
            org.robolectric.util.ReflectionHelpers.callInstanceMethod<Void>(this, "setSealed",
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, true))
        }
        assertTrue(WeChatAdapter().extract(root, res)!!.messages.isEmpty())
    }

    private fun page(vararg rows: AccessibilityNodeInfo): AccessibilityNodeInfo = node(null, null, 0, 1600).apply {
        shadowOf(this).addChild(node("gp", null, 40, 120))
        rows.forEach { shadowOf(this).addChild(it) }
        shadowOf(this).addChild(node("input", null, 1400, 1550).apply { isEditable = true; isEnabled = true })
        org.robolectric.util.ReflectionHelpers.callInstanceMethod<Void>(this, "setSealed",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, true))
    }

    private fun row(side: String?, text: String, top: Int, left: Double, right: Double): AccessibilityNodeInfo =
        messageRow(side, node("bkl", text, top, top + 90, left, right))

    private fun messageRow(side: String?, bubble: AccessibilityNodeInfo): AccessibilityNodeInfo {
        val box = Rect().also { bubble.getBoundsInScreen(it) }
        return node("bkj", null, box.top, box.bottom, 0.0, 1.0).apply {
        side?.let { owner ->
            val avatarLeft = if (owner == "me") width * 86 / 100 else width * 2 / 100
            val avatarRight = if (owner == "me") width * 98 / 100 else width * 14 / 100
            shadowOf(this).addChild(node("bk1", null, box.top, box.bottom, avatarLeft.toDouble() / width,
                avatarRight.toDouble() / width))
        }
        shadowOf(this).addChild(bubble)
    }
    }

    private fun node(id: String?, text: String?, top: Int, bottom: Int,
                     left: Double = 0.0, right: Double = 1.0) = AccessibilityNodeInfo.obtain().apply {
        packageName = "com.tencent.mm"
        viewIdResourceName = id?.let { "com.tencent.mm:id/$it" }
        this.text = text
        isVisibleToUser = true
        setBoundsInScreen(Rect((width * left).toInt(), top, (width * right).toInt(), bottom))
    }

    private fun seal(root: AccessibilityNodeInfo) {
        org.robolectric.util.ReflectionHelpers.callInstanceMethod<Void>(root, "setSealed",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, true))
    }
}
