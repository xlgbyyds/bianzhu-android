package com.jev.probe.capture

import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Msg
import com.jev.probe.core.kb.CaptureResult
import org.junit.Assert.assertTrue
import org.junit.Test

class BackfillProgressTest {
    @Test fun expandingDateClearsPendingPageAndDoesNotCountThePageTwice() {
        val page = ChatSnapshot("同一人", listOf(Msg("other", "早上好呀", visibleTime = "上午9:10")))
        val progress = BackfillProgress()
        progress.accept(page, CaptureResult(reason = "日期不明", needsReview = true))
        assertTrue(progress.label().contains("待核实 1 屏"))
        val dated = page.copy(messages = page.messages.map { it.copy(visibleTime = "10月12日 上午9:10") })
        progress.accept(dated, CaptureResult(timeUpdated = 1))
        progress.accept(dated, CaptureResult())
        assertTrue(progress.label().contains("本次核对 1 屏"))
        assertTrue(progress.label().contains("补时间 1 条"))
        assertTrue(progress.label().contains("待核实 0 屏"))
    }
}
