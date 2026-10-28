package com.jev.probe.capture

/** Strict geometry gate for automatic history scrolling. */
internal object AutoScrollTargetPolicy {
    data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int)
    data class Swipe(val x: Float, val startY: Float, val endY: Float)

    fun areaIfSafe(
        visible: Boolean,
        enabled: Boolean,
        scrollable: Boolean,
        editable: Boolean,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        screenWidth: Int,
        screenHeight: Int,
        viewportTop: Int = (screenHeight * 0.10f).toInt(),
        viewportBottom: Int = (screenHeight * 0.95f).toInt()
    ): Long? {
        if (!visible || !enabled || !scrollable || editable || screenWidth <= 0 || screenHeight <= 0)
            return null
        val width = right - left
        val height = bottom - top
        if (top < viewportTop || bottom > viewportBottom ||
            width < screenWidth * 0.55f || height < screenHeight * 0.22f)
            return null
        return width.toLong() * height
    }

    /** Pull the list downward to reveal older messages, keeping 44% screen overlap. */
    fun safeUpwardSwipe(bounds: Bounds, screenHeight: Int, covered: Bounds? = null): Swipe? {
        val width = bounds.right - bounds.left
        val height = bounds.bottom - bounds.top
        if (width <= 0 || screenHeight <= 0 || height < screenHeight * 0.22f) return null
        val startY = bounds.top + height * 0.22f
        val endY = bounds.top + height * 0.78f
        val lanes = listOf(0.50f, 0.32f, 0.68f, 0.20f, 0.80f, 0.92f, 0.08f)
        val x = lanes.asSequence().map { bounds.left + width * it }
            .firstOrNull { candidate ->
                covered == null || candidate < covered.left || candidate > covered.right ||
                    startY > covered.bottom || endY < covered.top
            } ?: return null
        return Swipe(x, startY, endY)
    }
}

internal enum class AutoScrollMethod { ACCESSIBILITY, GESTURE }
internal enum class BackfillPageTransition { WAITING, ADVANCED, TARGET_CHANGED }

internal fun classifyBackfillPageTransition(
    targetMatches: Boolean,
    expectedPage: String?,
    observedPage: String?
): BackfillPageTransition = when {
    !targetMatches -> BackfillPageTransition.TARGET_CHANGED
    observedPage == null || observedPage == expectedPage -> BackfillPageTransition.WAITING
    else -> BackfillPageTransition.ADVANCED
}

/** Alternates scroll methods after a stable page and bounds retries at history end. */
internal class AutoScrollRetryPlan(private val maxUnchangedPages: Int = 4) {
    var preferGesture: Boolean = true
        private set
    var unchangedPages: Int = 0
        private set

    fun methodForNextAttempt(): AutoScrollMethod =
        if (preferGesture) AutoScrollMethod.GESTURE else AutoScrollMethod.ACCESSIBILITY

    /** Returns false once repeated attempts produced no visible movement. */
    fun noProgress(lastMethod: AutoScrollMethod): Boolean {
        unchangedPages++
        preferGesture = lastMethod == AutoScrollMethod.ACCESSIBILITY
        return unchangedPages < maxUnchangedPages
    }

    fun progress() {
        unchangedPages = 0
        preferGesture = true
    }

    fun reset() = progress()
}

/** A moving page can still contain nothing new. Pause after several such pages so
 * automatic backfill cannot run through an already recorded conversation forever. */
internal class AutoBackfillDuplicateStreak(private val maxConsecutivePages: Int = 5) {
    var consecutivePages: Int = 0
        private set

    /** Called only after a different page was verified and saved successfully. */
    fun accept(added: Int, timeUpdated: Int): Boolean {
        consecutivePages = if (added == 0 && timeUpdated == 0) consecutivePages + 1 else 0
        return consecutivePages >= maxConsecutivePages
    }

    fun reset() { consecutivePages = 0 }
}
