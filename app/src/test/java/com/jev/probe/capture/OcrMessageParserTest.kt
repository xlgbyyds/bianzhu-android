package com.jev.probe.capture

import android.graphics.Rect
import com.jev.probe.capture.ocr.OcrLine
import com.jev.probe.core.Msg
import com.jev.probe.core.Prefs
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h800dp-xhdpi")
class OcrMessageParserTest {
    private val service = Robolectric.buildService(ChatCaptureService::class.java).create().get().also {
        ChatCaptureService::class.java.getDeclaredField("prefs").apply { isAccessible = true }.set(it, Prefs(it))
    }
    private val width get() = service.resources.displayMetrics.widthPixels
    @After fun close() { service.onDestroy() }

    @Test fun shortFinalLineCannotTurnAmbiguousWideMessageIntoOtherSpeech() {
        val messages = parse(line("很长的消息第一行", .12, .90, 300), line("短尾行", .12, .27, 350))
        assertEquals(1, messages.size)
        assertEquals("unknown", messages.single().side)
    }

    @Test fun uncertainContinuationCannotInheritAPrecedingSpeaker() {
        val messages = parse(line("短首行", .12, .27, 300), line("宽幅第二行", .12, .90, 350))
        assertEquals(1, messages.size)
        assertEquals("unknown", messages.single().side)
    }

    @Test fun centredDateSeparatesMessagesAndOnlyDatesFollowingText() {
        val messages = parse(line("前面的文字", .12, .27, 300),
            line("9月15日 16:00", .40, .60, 400), line("后面的文字", .12, .27, 500))
        assertEquals(listOf("前面的文字", "后面的文字"), messages.map { it.text })
        assertNull(messages.first().visibleTime)
        assertEquals("9月15日 16:00", messages.last().visibleTime)
        assertEquals("partial", messages.last().timeConfidence)
    }

    @Test fun timeInASideAlignedMessageIsNotDiscarded() {
        val messages = parse(line("16:00", .12, .25, 300), line("明天10:00见", .65, .88, 500))
        assertEquals(listOf("16:00", "明天10:00见"), messages.map { it.text })
        assertTrue(messages.all { it.visibleTime == null })
    }

    @Test fun differentScreensNeverInheritAnEarlierTimeLabel() {
        parse(line("昨天 16:00", .40, .60, 300), line("昨天的文字", .12, .27, 400))
        assertNull(parse(line("翻页后文字", .12, .27, 300)).single().visibleTime)
    }

    @Test fun closeMessagesOnOppositeSidesStaySeparate() {
        val messages = parse(line("我说的", .65, .88, 300), line("对方说的", .12, .27, 350))
        assertEquals(listOf("me", "other"), messages.map { it.side })
    }

    @Suppress("UNCHECKED_CAST")
    private fun parse(vararg lines: OcrLine): List<Msg> =
        ChatCaptureService::class.java.getDeclaredMethod("groupOcrLines", List::class.java)
            .apply { isAccessible = true }.invoke(service, lines.toList()) as List<Msg>

    private fun line(text: String, left: Double, right: Double, top: Int) =
        OcrLine(text, Rect((width * left).toInt(), top, (width * right).toInt(), top + 40))
}
