package com.jev.probe.capture

/** Text bounds alone cannot prove bubble ownership. Only use clear alignment. */
internal object OcrSideClassifier {
    fun classify(left: Int, right: Int, width: Int): String {
        if (width <= 0 || left < 0 || right <= left || right > width) return "unknown"
        // A broad text area can belong to either bubble even when its centre
        // leans left/right. Its shorter wrapped lines are not independent proof.
        if (right - left > width * 0.5f) return "unknown"
        return when {
            left > width * 0.38f && right > width * 0.70f -> "me"
            left < width * 0.30f && right < width * 0.78f -> "other"
            else -> "unknown"
        }
    }
}
