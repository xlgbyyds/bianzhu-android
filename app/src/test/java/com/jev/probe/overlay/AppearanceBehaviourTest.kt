package com.jev.probe.overlay

import android.app.Activity
import android.content.Intent
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.jev.probe.core.Prefs
import com.jev.probe.core.ReplySuggestion
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-420dpi")
@LooperMode(LooperMode.Mode.PAUSED)
class AppearanceBehaviourTest {
    private lateinit var activity: Activity
    private lateinit var overlay: OverlayController
    private lateinit var prefs: Prefs
    @Before fun setup() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        prefs = Prefs(activity).apply { overlayHidden = false }
        overlay = OverlayController(activity)
        overlay.showIdle(null)
    }
    @After fun cleanup() { overlay.hide() }

    @Test fun passiveCaptureDoesNotPreventIdleFadeAndTapWakesTheButton() {
        repeat(4) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            overlay.showIdle(null)
        }
        val bubble = field<TextView>("bubble")
        assertEquals(.35f, bubble.alpha, .001f)
        touchBubble()
        assertEquals(1f, bubble.alpha, .001f)
        assertEquals(View.VISIBLE, field<View>("panel").visibility)
        assertEquals(52, (bubble.width / activity.resources.displayMetrics.density).toInt())
    }

    @Test fun rightEdgeMenuFitsAndActionOnlyRunsOnce() {
        overlay.hide()
        val dm = activity.resources.displayMetrics
        prefs.bubbleX = dm.widthPixels - (60 * dm.density).toInt()
        overlay.showIdle(null)
        var invitations = 0
        overlay.onInviteConversation = { invitations++ }
        showMenu()
        val menu = field<View>("menuView")
        val params = field<WindowManager.LayoutParams>("lp")
        assertTrue(params.x >= 0)
        assertTrue(params.x + menu.layoutParams.width <= dm.widthPixels)
        descendants(menu).filterIsInstance<TextView>().single { it.text == "邀约建议" }.performClick()
        assertEquals(1, invitations)
        assertNull(field<View?>("menuView"))
        assertTrue(field<TextView>("bubble").translationX > 0)
    }

    @Test fun draggingExpandedPanelNeverDocksItBeyondTheRightScreenEdge() {
        overlay.showOpeners(listOf(ReplySuggestion("拖动后仍能看到", "natural", ""))) {}
        touchBubble() // collapse, then open through an actual touch to measure
        touchBubble()
        val handle = field<TextView>("bubble").parent as View
        listOf(MotionEvent.ACTION_DOWN to 26f, MotionEvent.ACTION_MOVE to 1000f,
            MotionEvent.ACTION_UP to 1000f).forEach { (action, x) ->
            val event = MotionEvent.obtain(0, 0, action, x, 26f, 0)
            handle.dispatchTouchEvent(event); event.recycle()
        }
        val params = field<WindowManager.LayoutParams>("lp")
        val panel = field<View>("panel")
        assertEquals(View.VISIBLE, panel.visibility)
        assertTrue(params.x + panel.layoutParams.width <= activity.resources.displayMetrics.widthPixels)
        assertEquals(-1, prefs.bubbleX) // moving a panel must not overwrite the saved bubble edge
    }

    @Test fun screenOffAndScreenshotRestoreDoNotRevealLateResultsOnUnlock() {
        overlay.showOpeners(listOf(ReplySuggestion("原建议", "natural", ""))) {}
        activity.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        shadowOf(Looper.getMainLooper()).idle()
        overlay.setHiddenForShot(true)
        overlay.showOpeners(listOf(ReplySuggestion("锁屏后返回的结果", "natural", ""))) {}
        overlay.setHiddenForShot(false)
        assertEquals(View.INVISIBLE, field<View>("root").visibility)
        assertEquals(View.GONE, field<View>("panel").visibility)
        activity.sendBroadcast(Intent(Intent.ACTION_USER_PRESENT))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(View.VISIBLE, field<View>("root").visibility)
        assertEquals(View.GONE, field<View>("panel").visibility)
        touchBubble()
        assertTrue(descendants(field("root")).filterIsInstance<TextView>().any { it.text == "锁屏后返回的结果" })
    }

    @Test fun navigationCollapsesButRetainsCardsWithoutCallingModels() {
        var requests = 0
        overlay.onOpenConversation = { requests++ }
        overlay.onManualAnalyze = { requests++ }
        overlay.showOpeners(listOf(ReplySuggestion("保留的建议", "natural", ""))) {}
        val card = descendants(field("root")).filterIsInstance<TextView>().single { it.text == "保留的建议" }
        overlay.collapseForNavigation()
        assertEquals(View.GONE, field<View>("panel").visibility)
        touchBubble()
        assertSame(card, descendants(field("root")).filterIsInstance<TextView>().single { it.text == "保留的建议" })
        assertEquals(0, requests)
    }

    @Test fun hideAndRecreateClearsTemporaryScreenshotInvisibility() {
        overlay.setHiddenForShot(true)
        overlay.hide()
        overlay.showIdle(null)
        assertEquals(View.VISIBLE, field<View>("root").visibility)
    }

    @Test fun activeBackfillRemainsVisibleAndBookmarkSurvivesStopping() {
        overlay.showBackfillProgress(1, 22, true)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4))
        overlay.showBackfillWaiting("正在读取")
        overlay.showAutoBackfillState("暂停", "继续上翻", false)
        val bubble = field<TextView>("bubble")
        assertEquals(1f, bubble.alpha, .001f)
        assertEquals("", bubble.text.toString())
        overlay.stopBackfill()
        assertEquals("", bubble.text.toString())
        assertNotNull(bubble.compoundDrawables[1])
    }

    private fun showMenu() {
        OverlayController::class.java.getDeclaredMethod("showBubbleMenu").apply { isAccessible = true }.invoke(overlay)
    }
    private fun touchBubble() {
        val root = field<FrameLayout>("root")
        root.measure(View.MeasureSpec.makeMeasureSpec(1220, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(2712, View.MeasureSpec.AT_MOST))
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        val touch = field<TextView>("bubble").parent as View
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
            val event = MotionEvent.obtain(0, 0, action, 26f, 26f, 0)
            touch.dispatchTouchEvent(event)
            event.recycle()
        }
    }
    private fun descendants(v: View): List<View> = listOf(v) + if (v is ViewGroup)
        (0 until v.childCount).flatMap { descendants(v.getChildAt(it)) } else emptyList()
    @Suppress("UNCHECKED_CAST") private fun <T> field(name: String): T = OverlayController::class.java
        .getDeclaredField(name).apply { isAccessible = true }.get(overlay) as T
}
