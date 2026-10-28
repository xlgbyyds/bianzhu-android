package com.jev.probe.capture

import android.graphics.Rect
import com.jev.probe.capture.ocr.OcrLine
import com.jev.probe.core.Msg
import kotlin.math.abs

/** Flat OCR remains approximate. A short wrapped line cannot prove ownership. */
internal object OcrMessageParser {
    fun parse(lines: List<OcrLine>, width: Int): List<Msg> {
        val out = ArrayList<Msg>()
        val group = ArrayList<OcrLine>()
        var visibleTime: String? = null
        fun flush() {
            if (group.isEmpty()) return
            val bounds = Rect(group.first().bounds)
            group.drop(1).forEach { bounds.union(it.bounds) }
            val sides = group.map { OcrSideClassifier.classify(it.bounds.left, it.bounds.right, width) }.toSet()
            val side = if (sides.size == 1 && "unknown" !in sides)
                OcrSideClassifier.classify(bounds.left, bounds.right, width) else "unknown"
            out.add(Msg(side, group.joinToString(" ") { it.text.trim() },
                visibleTime = visibleTime, timeConfidence = if (visibleTime == null) "unknown" else "partial"))
            group.clear()
        }
        for (line in lines.filter { it.text.isNotBlank() && !it.bounds.isEmpty }.sortedBy { it.bounds.top }) {
            // An actual time-only reply near an avatar is still speech. Only a
            // narrow, centred separator can introduce the following page time.
            val centredTime = width > 0 && line.bounds.centerX() in (width * .40).toInt()..(width * .60).toInt() &&
                line.bounds.width() < width * .65 && isWechatTimeLabel(line.text)
            if (centredTime) {
                flush()
                visibleTime = line.text.trim()
                continue
            }
            val previous = group.lastOrNull()
            if (previous != null) {
                val gap = line.bounds.top - previous.bounds.bottom
                val height = maxOf(previous.bounds.height(), 1)
                val aligned = abs(line.bounds.left - previous.bounds.left) <= width * .06 ||
                    abs(line.bounds.right - previous.bounds.right) <= width * .06
                val previousSide = OcrSideClassifier.classify(previous.bounds.left, previous.bounds.right, width)
                val lineSide = OcrSideClassifier.classify(line.bounds.left, line.bounds.right, width)
                if (gap > height * 1.2 || gap < -height * .5 || !aligned ||
                    (previousSide != "unknown" && lineSide != "unknown" && previousSide != lineSide)) flush()
            }
            group.add(line)
        }
        flush()
        return out
    }
}
