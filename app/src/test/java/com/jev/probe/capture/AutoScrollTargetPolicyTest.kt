package com.jev.probe.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoScrollTargetPolicyTest {
    @Test fun acceptsLargeScrollableMessageViewport() {
        assertEquals(800L * 600L, AutoScrollTargetPolicy.areaIfSafe(
            visible = true, enabled = true, scrollable = true, editable = false,
            left = 0, top = 180, right = 800, bottom = 780,
            screenWidth = 800, screenHeight = 1000
        ))
    }

    @Test fun acceptsWechatViewportThatEndsAtTheComposerBoundary() {
        assertEquals(1220L * 2104L, AutoScrollTargetPolicy.areaIfSafe(
            visible = true, enabled = true, scrollable = true, editable = false,
            left = 0, top = 284, right = 1220, bottom = 2388,
            screenWidth = 1224, screenHeight = 2544,
            viewportTop = 203, viewportBottom = 2407
        ))
    }

    @Test fun rejectsNodesThatCouldScrollTheWrongPartOfTheScreen() {
        fun area(top: Int, bottom: Int, left: Int = 0, right: Int = 800) =
            AutoScrollTargetPolicy.areaIfSafe(
                visible = true, enabled = true, scrollable = true, editable = false,
                left = left, top = top, right = right, bottom = bottom,
                screenWidth = 800, screenHeight = 1000
            )
        assertNull(area(0, 700))       // includes the title bar
        assertNull(area(180, 951))     // extends into the composer / bottom controls
        assertNull(area(180, 500, right = 300)) // too narrow to be the message list
        assertEquals(600L * 500L, AutoScrollTargetPolicy.areaIfSafe(
            visible = true, enabled = true, scrollable = true, editable = false,
            left = 50, top = 200, right = 650, bottom = 700,
            screenWidth = 800, screenHeight = 1000
        ))
    }

    @Test fun rejectsNonScrollableHiddenDisabledAndEditableNodes() {
        assertNull(AutoScrollTargetPolicy.areaIfSafe(false, true, true, false,
            0, 180, 800, 780, 800, 1000))
        assertNull(AutoScrollTargetPolicy.areaIfSafe(true, false, true, false,
            0, 180, 800, 780, 800, 1000))
        assertNull(AutoScrollTargetPolicy.areaIfSafe(true, true, false, false,
            0, 180, 800, 780, 800, 1000))
        assertNull(AutoScrollTargetPolicy.areaIfSafe(true, true, true, true,
            0, 180, 800, 780, 800, 1000))
    }

    @Test fun fallbackSwipeStaysInsideMessageViewportAndAvoidsOverlay() {
        val list = AutoScrollTargetPolicy.Bounds(0, 180, 800, 780)
        val swipe = AutoScrollTargetPolicy.safeUpwardSwipe(list, 1000,
            AutoScrollTargetPolicy.Bounds(300, 300, 500, 700))!!
        assertTrue(swipe.x < 300f || swipe.x > 500f)
        assertTrue(swipe.startY < list.bottom)
        assertTrue(swipe.endY > list.top)
        assertTrue(swipe.startY < swipe.endY)
        assertTrue(swipe.endY - swipe.startY < (list.bottom - list.top) * 0.60f)
        assertNull(AutoScrollTargetPolicy.safeUpwardSwipe(list, 1000,
            AutoScrollTargetPolicy.Bounds(0, 0, 800, 1000)))
    }

    @Test fun repeatedUnchangedPagesAlternateMechanismsThenResetAfterProgress() {
        val plan = AutoScrollRetryPlan(maxUnchangedPages = 4)
        assertEquals(AutoScrollMethod.GESTURE, plan.methodForNextAttempt())
        assertTrue(plan.noProgress(AutoScrollMethod.ACCESSIBILITY))
        assertEquals(AutoScrollMethod.GESTURE, plan.methodForNextAttempt())
        assertTrue(plan.noProgress(AutoScrollMethod.GESTURE))
        assertEquals(AutoScrollMethod.ACCESSIBILITY, plan.methodForNextAttempt())
        assertTrue(plan.noProgress(AutoScrollMethod.ACCESSIBILITY))
        assertFalse(plan.noProgress(AutoScrollMethod.GESTURE))
        plan.progress()
        assertEquals(0, plan.unchangedPages)
        assertEquals(AutoScrollMethod.GESTURE, plan.methodForNextAttempt())
    }

    @Test fun unchangedPageDuringLayoutWaitsInsteadOfStoppingAutoBackfill() {
        assertEquals(BackfillPageTransition.WAITING,
            classifyBackfillPageTransition(true, "page-a", "page-a"))
        assertEquals(BackfillPageTransition.WAITING,
            classifyBackfillPageTransition(true, "page-a", null))
        assertEquals(BackfillPageTransition.ADVANCED,
            classifyBackfillPageTransition(true, "page-a", "page-b"))
        assertEquals(BackfillPageTransition.TARGET_CHANGED,
            classifyBackfillPageTransition(false, "page-a", "page-b"))
    }

    @Test fun distinctPagesAlreadyStoredPauseAutomaticBackfillWithoutClaimingCompletion() {
        val streak = AutoBackfillDuplicateStreak(maxConsecutivePages = 3)
        assertFalse(streak.accept(0, 0))
        assertFalse(streak.accept(0, 0))
        assertEquals(2, streak.consecutivePages)
        assertTrue(streak.accept(0, 0))
        streak.reset() // user explicitly resumes automatic scrolling
        assertFalse(streak.accept(0, 0))
    }

    @Test fun aNewMessageOrRecoveredTimeBreaksTheDuplicateStreak() {
        val streak = AutoBackfillDuplicateStreak(maxConsecutivePages = 3)
        assertFalse(streak.accept(0, 0))
        assertFalse(streak.accept(1, 0))
        assertEquals(0, streak.consecutivePages)
        assertFalse(streak.accept(0, 0))
        assertFalse(streak.accept(0, 1))
        assertEquals(0, streak.consecutivePages)
    }

    @Test fun expandedXiaomiPanelStillLeavesASafeRightLaneForOlderHistory() {
        val list = AutoScrollTargetPolicy.Bounds(0, 284, 1220, 2388)
        val swipe = AutoScrollTargetPolicy.safeUpwardSwipe(list, 2712,
            AutoScrollTargetPolicy.Bounds(20, 400, 1050, 2200))!!
        assertTrue(swipe.x > 1050 && swipe.x < 1220)
        assertTrue(swipe.startY < swipe.endY)
    }
}

