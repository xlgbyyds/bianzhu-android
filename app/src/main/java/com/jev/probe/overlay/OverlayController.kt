package com.jev.probe.overlay

import android.content.Context
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.app.KeyguardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Rect
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.jev.probe.core.Analysis
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Msg
import com.jev.probe.core.Prefs
import com.jev.probe.core.RankedReply
import com.jev.probe.core.ReplySuggestion
import com.jev.probe.core.RuntimeState
import com.jev.probe.R
import com.jev.probe.core.pageTimeEvidence
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Floating overlay: a small draggable bubble that expands into a translucent
 * panel showing Jev's read of the chat plus 3 ranked candidate replies. All
 * actions are copy / fill — never send.
 *
 * Design goals: let the chat show through (adjustable opacity), keep the signal
 * scannable (danger badge + intent headline + reply cards), and stay out of the
 * way (draggable bubble that snaps to the edge and remembers its position).
 */
class OverlayController(private val ctx: Context) {

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val prefs = Prefs(ctx)
    private var root: FrameLayout? = null
    private var bubble: TextView? = null
    private var dangerDot: View? = null
    private var panel: LinearLayout? = null
    private var contentBox: LinearLayout? = null
    private var snapshotUpdateBanner: LinearLayout? = null
    private var contentIsIdle = false
    private var expanded = false
    private var backfillActive = false
    private var backfillPages = 0
    private var backfillLines = 0
    private var backfillLastAdded = false
    private var autoBackfillPaused = false
    private var lp: WindowManager.LayoutParams? = null
    private var menuView: View? = null
    private var menuX = 0
    private var menuY = 0
    private val appearanceHandler = Handler(Looper.getMainLooper())
    private var idleDimmed = false
    private var pointerDown = false
    private var hiddenForShot = false
    private var screenOff = false
    private var screenReceiverRegistered = false
    private val idleTask = Runnable {
        if (!expanded && menuView == null && !pointerDown && !backfillActive && prefs.idleFade) {
            idleDimmed = true
            updateBubbleAppearance()
        }
    }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenOff = true
                    if (prefs.hideWhenLocked) collapseForNavigation()
                }
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> screenOff = false
            }
            updateVisibility()
        }
    }

    private fun lockedForAppearance(): Boolean = prefs.hideWhenLocked &&
        (screenOff || (ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked)

    private fun updateVisibility() {
        root?.visibility = if (hiddenForShot || lockedForAppearance())
            View.INVISIBLE else View.VISIBLE
    }

    private fun registerScreenReceiver() {
        if (screenReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else ctx.registerReceiver(screenReceiver, filter)
        screenReceiverRegistered = true
    }

    private fun scheduleIdleFade() {
        if (!idleDimmed && !appearanceHandler.hasCallbacks(idleTask)) appearanceHandler.postDelayed(idleTask, 3000L)
    }

    private fun wakeBubble() {
        appearanceHandler.removeCallbacks(idleTask)
        idleDimmed = false
        updateBubbleAppearance()
    }

    private fun updateBubbleAppearance() {
        bubble?.apply {
            alpha = if (!expanded && menuView == null && !backfillActive && idleDimmed && prefs.idleFade)
                prefs.bubbleOpacity / 100f else 1f
            // Clip the visual inside a safe touch area; do not place the window
            // in HyperOS's extreme-edge back-gesture strip.
            translationX = if (!expanded && menuView == null && !pointerDown && prefs.edgePeek && !backfillActive) {
                if ((lp?.x ?: 0) < screenW / 2) -dp(18).toFloat() else dp(18).toFloat()
            } else 0f
        }
    }

    private fun dockBubble() {
        // A 316dp result panel cannot use the 52dp bubble's edge position.
        if (expanded || menuView != null) return
        val params = lp ?: return
        if (prefs.edgePeek) params.x = if (params.x < screenW / 2) dp(8) else (screenW - dp(60)).coerceAtLeast(dp(8))
        updateBubbleAppearance()
        root?.let { runCatching { wm.updateViewLayout(it, params) } }
    }

    fun refreshAppearance() {
        panel?.background = card(18, panelBg(), stroke = true)
        if (!expanded && menuView == null) dockBubble()
        wakeBubble(); scheduleIdleFade(); updateVisibility()
    }

    fun collapseForNavigation() {
        dismissMenu()
        if (expanded) toggle()
    }

    var onChooseContact: (() -> Unit)? = null
    var onApproveReplyVoice: ((String) -> Unit)? = null
    var onEditReplyVoice: ((String) -> Unit)? = null
    private var contactInfo: TextView? = null
    private var contactInfoText = "联系人自动匹配 · 点此选择"
    fun setContactInfo(name: String?, temporary: Boolean) {
        contactInfoText = if (temporary) "仅本次：${name.orEmpty()} · 记忆采集暂停" else
            "${name?.let { "联系人：$it" } ?: "联系人自动匹配"} · 点此选择"
        contactInfo?.text = contactInfoText
    }

    var onManualAnalyze: (() -> Unit)? = null
    var onDismissAnalysis: (() -> Unit)? = null
    var onRefreshAnalysis: (() -> Unit)? = null

    /** Bubble menu → file the open conversation as a knowledge-base contact. */
    var onSaveContact: (() -> Unit)? = null

    /** Bubble menu → one manual screenshot + OCR of whatever app is open. */
    var onOcrCapture: (() -> Unit)? = null

    /** Guided backfill reads only what is visible and never asks the model. */
    var onToggleHistoryCapture: (() -> Unit)? = null
    var onHistoryInteraction: (() -> Unit)? = null

    /** Explicit opener request for a quiet or brand-new conversation. */
    var onOpenConversation: (() -> Unit)? = null
    var onInviteConversation: (() -> Unit)? = null
    var onHideAssistant: (() -> Unit)? = null

    /** Review the exact transcript supplied to the current analysis request. */
    var onReviewSnapshot: (() -> Unit)? = null

    /** How much knowledge context the last analysis actually used. */
    private var ctxNotes = 0
    private var ctxHistory = 0
    private var ctxContactMatched = false
    private var ctxMemoryOn = false

    /** A caveat about how the current snapshot was captured (OCR mode). */
    private var noteText: String? = null
    private var snapshotAudit: String? = null
    private var snapshotSummary: String? = null

    /** Whether the overlay window is currently on screen. */
    fun isShowing(): Boolean = root != null

    /** Screen-space obstruction used to keep automatic scroll gestures off the panel. */
    fun visibleBoundsOnScreen(): Rect? = root?.takeIf { it.visibility == View.VISIBLE }?.let { view ->
        val location = IntArray(2).also(view::getLocationOnScreen)
        Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
    }

    fun collapseForAutomaticBackfill() { if (expanded) toggle() }

    private var lastJudgment: Analysis? = null
    private var lastFill: ((String) -> Unit)? = null

    /** Set when [showReplies] was handed a draftAndRank failure, so the panel
     *  can say so instead of silently showing "（未生成候选回复）". */
    private var replyError: String? = null
    private var draftReplies: List<ReplySuggestion> = emptyList()
    private var draftIssue: String? = null

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).roundToInt()

    private fun canOverlay(): Boolean = Settings.canDrawOverlays(ctx)

    private val screenW get() = ctx.resources.displayMetrics.widthPixels
    private val screenH get() = ctx.resources.displayMetrics.heightPixels

    /** Panel background: white with the user's opacity so the chat shows through. */
    private fun panelBg(): Int {
        val a = (prefs.overlayOpacity / 100f * 255).roundToInt().coerceIn(150, 255)
        return Color.argb(a, 247, 245, 241)
    }

    private fun card(radius: Int, color: Int, stroke: Boolean = false) = GradientDrawable().apply {
        cornerRadius = dp(radius).toFloat()
        setColor(color)
        if (stroke) setStroke(dp(1), Color.parseColor("#24344F6D"))
    }

    // ---------------------------------------------------------------- window

    private fun ensureRoot() {
        if (prefs.overlayHidden) { hide(); return }
        if (root != null) return
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (prefs.bubbleX in 0..(screenW - dp(52))) prefs.bubbleX else dp(8)
            y = if (prefs.bubbleY >= 0) prefs.bubbleY else dp(150)
        }
        lp = params

        val r = object : FrameLayout(ctx) {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                val diagnostic = event.actionMasked == MotionEvent.ACTION_DOWN ||
                    event.actionMasked == MotionEvent.ACTION_UP ||
                    event.actionMasked == MotionEvent.ACTION_CANCEL
                val handled = super.dispatchTouchEvent(event)
                if (diagnostic) android.util.Log.i("JEVASSIST",
                    "overlay touch action=${event.actionMasked} x=${event.x.toInt()} " +
                        "y=${event.y.toInt()} handled=$handled")
                return handled
            }
        }
        r.clipChildren = true
        val p = buildPanel()
        val bubbleWrap = buildBubble(params)
        r.addView(p)
        r.addView(bubbleWrap)
        try {
            // This controller belongs to the connected AccessibilityService.
            // Its overlay type does not depend on Xiaomi's separate background
            // popup permission. Keep the app-overlay route as a compatibility fallback.
            wm.addView(r, params)
            root = r
            RuntimeState.overlayIssue = null
        } catch (primary: Exception) {
            val fallback = if (canOverlay()) runCatching {
                params.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                wm.addView(r, params)
            } else Result.failure(IllegalStateException("系统悬浮窗备用权限未开启"))
            if (fallback.isSuccess) {
                root = r
                RuntimeState.overlayIssue = null
            } else {
                val reason = fallback.exceptionOrNull()?.javaClass?.simpleName ?: "Unknown"
                RuntimeState.overlayIssue = "悬浮窗创建失败：${primary.javaClass.simpleName} / $reason"
                android.util.Log.e("JEVASSIST", RuntimeState.overlayIssue, primary)
                bubble = null; dangerDot = null; panel = null; contentBox = null; lp = null
            }
        }
        if (root != null) {
            registerScreenReceiver()
            dockBubble()
            scheduleIdleFade()
            updateVisibility()
        }
    }

    private fun buildBubble(params: WindowManager.LayoutParams): View {
        val wrap = FrameLayout(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(dp(52), dp(52))
        }
        val b = TextView(ctx).apply {
            text = ""
            contentDescription = "旁注悬浮按钮 点击展开 长按菜单"
            setCompoundDrawablesWithIntrinsicBounds(0, R.drawable.ic_bianzhu_bookmark, 0, 0)
            compoundDrawablePadding = 0
            setPadding(dp(12), dp(13), dp(12), dp(13))
            gravity = Gravity.CENTER
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor("#F1EEE7"))
                setStroke(dp(1), Color.parseColor("#D5DDE3"))
            }
            layoutParams = FrameLayout.LayoutParams(dp(52), dp(52))
        }
        val dot = View(ctx).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.TRANSPARENT) }
            layoutParams = FrameLayout.LayoutParams(dp(12), dp(12)).apply {
                gravity = Gravity.TOP or Gravity.END
            }
        }
        wrap.addView(b)
        wrap.addView(dot)
        attachBubbleTouch(wrap, params)
        bubble = b; dangerDot = dot
        return wrap
    }

    private fun buildPanel(): LinearLayout {
        val p = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = card(18, panelBg(), stroke = true)
            elevation = dp(8).toFloat()
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = FrameLayout.LayoutParams(dp(316), FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(56) // sit just below the bubble
            }
        }
        // Header
        val header = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(ctx).apply {
            text = "旁注"; setTextColor(Color.parseColor("#263442")); textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(iconBtn("⚙", close = false) { openSettings() })
        header.addView(iconBtn("×", close = true) {
            toggle()
            onDismissAnalysis?.invoke()
        })
        p.addView(header)
        contactInfo = TextView(ctx).apply {
            text = contactInfoText; textSize = 12f; setTextColor(Color.parseColor("#607C92"))
            setPadding(dp(2), dp(8), dp(2), dp(8))
            setOnClickListener { onChooseContact?.invoke() }
        }
        p.addView(contactInfo)

        // Kept outside the result scroll view: incoming messages must not
        // recreate cards, reset scroll, or swallow a copy/fill tap.
        snapshotUpdateBanner = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = card(10, Color.parseColor("#FFF4DF"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            }
            addView(line("聊天画面已变化，原建议已保留", "#805A24", 12f))
            addView(TextView(ctx).apply {
                text = "按最新画面重新生成"
                setTextColor(Color.parseColor("#607C92")); textSize = 13f
                setPadding(0, dp(7), 0, dp(3))
                setOnClickListener { onRefreshAnalysis?.invoke() }
            })
        }

        val scroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            // Cap the height so the panel stays in the upper area and does not
            // cover the WeChat input box / keyboard. Scroll inside if taller.
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (screenH * 0.40f).roundToInt()).apply { topMargin = dp(6) }
        }
        val content = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)
        p.addView(scroll)
        snapshotUpdateBanner?.let { p.addView(it) }
        contentBox = content
        panel = p
        return p
    }

    private fun iconBtn(glyph: String, close: Boolean, onClick: () -> Unit) = TextView(ctx).apply {
        text = glyph
        contentDescription = if (close) "关闭分析面板" else "打开设置"
        gravity = Gravity.CENTER
        setTextColor(Color.parseColor(if (close) "#B54752" else "#53647A"))
        textSize = if (close) 23f else 18f
        setTypeface(typeface, if (close) Typeface.NORMAL else Typeface.BOLD)
        minWidth = dp(42); minHeight = dp(42)
        isFocusable = true
        background = RippleDrawable(
            ColorStateList.valueOf(Color.parseColor(if (close) "#35B54752" else "#30344F6D")),
            card(14, Color.parseColor(if (close) "#FFF0F1" else "#E9EDF0")),
            card(14, Color.WHITE))
        layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginStart = dp(6) }
        setOnClickListener {
            performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
            onClick()
        }
    }

    // --------------------------------------------------------------- gestures

    private fun attachBubbleTouch(v: View, params: WindowManager.LayoutParams) {
        var startX = 0; var startY = 0; var touchX = 0f; var touchY = 0f
        var moved = false; var longFired = false
        val longPress = Runnable {
            if (!moved) { longFired = true; showBubbleMenu() }
        }
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    pointerDown = true
                    wakeBubble()
                    if (backfillActive) onHistoryInteraction?.invoke()
                    startX = params.x; startY = params.y; touchX = e.rawX; touchY = e.rawY
                    moved = false; longFired = false
                    v.postDelayed(longPress, 500); true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (longFired) return@setOnTouchListener true
                    val dx = (e.rawX - touchX).toInt(); val dy = (e.rawY - touchY).toInt()
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
                    // Keep a margin from both side edges: the extreme edge is MIUI's
                    // back-gesture zone, which steals touches and makes the bubble
                    // "stuck". Docking keeps this inset even when the visual peeks.
                    val maxX = (screenW - dp(if (expanded) 324 else 60)).coerceAtLeast(dp(8))
                    val maxY = if (expanded) (screenH - (root?.height ?: 0) - dp(24)).coerceAtLeast(dp(24))
                        else (screenH - dp(120)).coerceAtLeast(dp(24))
                    params.x = (startX + dx).coerceIn(dp(8), maxX)
                    params.y = (startY + dy).coerceIn(dp(24), maxY)
                    root?.let { runCatching { wm.updateViewLayout(it, params) } }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.removeCallbacks(longPress)
                    pointerDown = false
                    if (longFired) { true }
                    else if (moved) {
                        if (!expanded) {
                            dockBubble()
                            prefs.bubbleX = params.x; prefs.bubbleY = params.y
                        }
                        updateBubbleAppearance(); scheduleIdleFade(); true
                    } else { toggle(); true }
                }
                MotionEvent.ACTION_CANCEL -> {
                    pointerDown = false; v.removeCallbacks(longPress); updateBubbleAppearance(); scheduleIdleFade(); true
                }
                else -> false
            }
        }
    }

    private fun showBubbleMenu() {
        if (menuView != null) { dismissMenu(); return }
        if (expanded) toggle()
        wakeBubble()
        val params = lp ?: return
        menuX = params.x; menuY = params.y
        params.x = if (params.x < screenW / 2) dp(8) else (screenW - dp(204)).coerceAtLeast(dp(8))
        params.y = params.y.coerceAtMost((screenH - dp(540)).coerceAtLeast(dp(24)))
        val menu = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = card(12, panelBg(), stroke = true)
            elevation = dp(8).toFloat()
            setPadding(dp(4), dp(4), dp(4), dp(4))
            layoutParams = FrameLayout.LayoutParams(dp(196), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(56) }
        }
        fun item(label: String, action: () -> Unit) {
            menu.addView(menuItem(label) { dismissMenu(); action() })
        }
        item("生成建议") { onManualAnalyze?.invoke() }
        item("截屏识别") { onOcrCapture?.invoke() }
        item("开场建议") { onOpenConversation?.invoke() }
        item("邀约建议") { onInviteConversation?.invoke() }
        item("存入联系人") { onSaveContact?.invoke() }
        item("选择联系人") { onChooseContact?.invoke() }
        item(if (backfillActive) "结束补录" else "补录记录") { onToggleHistoryCapture?.invoke() }
        item("设置") { openSettings() }
        item("隐藏旁注") { hideForUser() }
        item("取消") { }
        menuView = menu
        root?.addView(menu)
        root?.let { runCatching { wm.updateViewLayout(it, params) } }
        updateBubbleAppearance()
    }

    private fun dismissMenu() {
        val menu = menuView ?: return
        root?.removeView(menu)
        menuView = null
        lp?.let { params ->
            params.x = menuX; params.y = menuY
            root?.let { runCatching { wm.updateViewLayout(it, params) } }
        }
        updateBubbleAppearance(); scheduleIdleFade()
    }

    private fun menuItem(label: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = label; setTextColor(Color.parseColor("#26364A")); textSize = 14f
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = RippleDrawable(ColorStateList.valueOf(Color.parseColor("#24344F6D")),
            card(10, Color.TRANSPARENT), card(10, Color.WHITE))
        setOnClickListener {
            performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
            onClick()
        }
    }

    private fun openSettings() {
        runCatching {
            ctx.startActivity(Intent().setClassName(ctx, "com.jev.probe.SettingsActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        if (expanded) toggle()
    }

    private var collapsedX = dp(6)
    private var collapsedY = dp(150)

    private fun toggle() {
        if (prefs.overlayHidden || root == null) return
        // A result arriving after screen-off must not reopen private cards on unlock.
        if (!expanded && lockedForAppearance()) return
        dismissMenu()
        wakeBubble()
        expanded = !expanded
        val params = lp ?: return
        if (expanded) {
            // Open the panel from the left, fully on-screen and up high (clear of the
            // input box), regardless of which edge the bubble was snapped to.
            collapsedX = params.x; collapsedY = params.y
            params.x = dp(6)
            val maxTop = (screenH * 0.14f).roundToInt()
            if (params.y > maxTop) params.y = maxTop
            panel?.visibility = View.VISIBLE
        } else {
            panel?.visibility = View.GONE
            params.x = collapsedX; params.y = collapsedY  // bubble returns to where it was
        }
        android.util.Log.d("JEVASSIST", "overlay: toggle expanded=$expanded x=${params.x} y=${params.y} saved=($collapsedX,$collapsedY)")
        root?.let { runCatching { wm.updateViewLayout(it, params) } }
        if (!expanded) { dockBubble(); scheduleIdleFade() }
        updateBubbleAppearance()
    }

    // ------------------------------------------------------------ public API

    fun showIdle(title: String?) {
        ensureRoot()
        if (backfillActive) {
            bubble?.alpha = 1f
            if (contentBox?.childCount == 0)
                showBackfillProgress(backfillPages, backfillLines, backfillLastAdded)
            return
        }
        updateBubbleAppearance()
        scheduleIdleFade()
        // Passive accessibility events must not replace the View being pressed,
        // or erase loading/error feedback merely because no judgment exists yet.
        // Real conversation changes explicitly clear data-bearing content below.
        if (contentBox?.childCount == 0) {
            setContent(listOf(bigButton("生成建议") { onManualAnalyze?.invoke() }), idle = true)
        }
    }

    /**
     * Drop whatever judgment/candidates/note belonged to the previous
     * conversation. Call this before showing anything for a different chat
     * window (a different app, or new content in the same one) — otherwise a
     * leftover [lastJudgment] from a prior conversation can keep [showIdle]
     * from putting the "分析当前对话" button back, and a leftover [lastFill]
     * could fill the wrong chat's input box.
     */
    fun resetForNewConversation() {
        setContactInfo(null, false)
        clearSnapshotUpdate()
        lastJudgment = null
        lastFill = null
        noteText = null
        snapshotAudit = null
        snapshotSummary = null
        replyError = null
        draftReplies = emptyList()
        draftIssue = null
        ctxNotes = 0; ctxHistory = 0; ctxContactMatched = false; ctxMemoryOn = false
        // The idle button contains no contact data and reads the live page when
        // clicked. Retain it even if a page refresh lands between DOWN and UP.
        if (!contentIsIdle) contentBox?.removeAllViews()
    }

    private fun bigButton(label: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = label; textSize = 14f; gravity = Gravity.CENTER
        setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD)
        background = RippleDrawable(ColorStateList.valueOf(Color.parseColor("#55FFFFFF")),
            card(12, Color.parseColor("#607C92")), card(12, Color.WHITE))
        setPadding(dp(12), dp(11), dp(12), dp(11))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        if (label == "分析当前对话") setOnTouchListener { _, event ->
            // Observe Android's normal touch sequence; do not bypass CANCEL or
            // force a click during scrolling. Stable views preserve real taps.
            if (event.actionMasked == MotionEvent.ACTION_DOWN ||
                event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL)
                android.util.Log.i("JEVASSIST",
                    "analyze button touch action=${event.actionMasked}")
            false
        }
        setOnClickListener {
            if (label == "分析当前对话")
                android.util.Log.i("JEVASSIST", "overlay analyze button tapped callback=${onManualAnalyze != null}")
            onClick()
        }
    }

    fun showLoading(message: String = "分析中…") {
        ensureRoot(); bubble?.alpha = 1f
        // An explicit new request replaces the old result. Keep the new audit
        // and note, but never mix a prior judgment/draft into its callbacks.
        lastJudgment = null
        lastFill = null
        draftReplies = emptyList()
        draftIssue = null
        ctxNotes = 0; ctxHistory = 0; ctxContactMatched = false; ctxMemoryOn = false
        replyError = null              // this round has not failed (yet)
        setContent(listOf(hint(message)))
        if (!expanded) toggle()
    }

    private fun backfillButton(label: String, finish: Boolean = false, onClick: () -> Unit) =
        bigButton(label, onClick).apply {
            minimumHeight = dp(48)
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(if (finish) 12 else 8)
            val fill = if (finish) "#FFF3E5" else "#607C92"
            setTextColor(Color.parseColor(if (finish) "#8A4B19" else "#FFFFFF"))
            val shape = card(12, Color.parseColor(fill)).apply {
                if (finish) setStroke(dp(1), Color.parseColor("#EBC59E"))
            }
            background = RippleDrawable(
                ColorStateList.valueOf(Color.parseColor(if (finish) "#269A4B08" else "#33FFFFFF")),
                shape, card(12, Color.WHITE))
        }

    /** Later network progress must preserve context counts and any draft cards. */
    fun showProgress(message: String) {
        if (draftReplies.isNotEmpty()) return
        ensureRoot(); bubble?.alpha = 1f
        setContent(listOf(hint(message)))
        if (!expanded) toggle()
    }

    /** How many knowledge notes / history lines went into the pending analysis. */
    fun setContextInfo(notes: Int, history: Int, contactMatched: Boolean, memoryOn: Boolean) {
        ctxNotes = notes; ctxHistory = history
        ctxContactMatched = contactMatched; ctxMemoryOn = memoryOn
    }

    /** A caveat line for the panel (OCR mode); null clears it. */
    fun setNote(note: String?) {
        noteText = note
    }

    /** Notify without replacing, opening, or scrolling the original result. */
    fun showSnapshotUpdate() {
        snapshotUpdateBanner?.visibility = View.VISIBLE
    }

    fun clearSnapshotUpdate() {
        snapshotUpdateBanner?.visibility = View.GONE
    }

    /** A compact, auditable summary of the snapshot and its visible time evidence. */
    fun setSnapshotAudit(snapshot: ChatSnapshot) {
        val latest = snapshot.messages.lastOrNull()
        val speaker = when (latest?.side) {
            "me" -> "我"
            "other" -> "对方"
            else -> "未确定"
        }
        val evidence = pageTimeEvidence(snapshot.messages)
        val visibleTime = evidence.label ?: "未知"
        val age = evidence.ageDays
        val delay = when {
            age == null -> "日期未知"
            age == 0L -> "今天"
            else -> "约 ${age} 天前"
        }
        snapshotAudit = "当前页：${snapshot.title ?: "联系人未知"}；识别到 ${snapshot.messages.size} 条可见消息；末条发言：$speaker；可见时间：$visibleTime（$delay，不能代表每条消息的精确发送时间）"
        snapshotSummary = "末条：$speaker · 页面日期：$delay"
    }

    /**
     * Take the overlay out of the picture for one screenshot. INVISIBLE, not
     * removed: the window (and everything on it) must survive the round trip.
     */
    fun setHiddenForShot(hidden: Boolean) {
        hiddenForShot = hidden
        updateVisibility()
    }

    fun showError(msg: String, retryLabel: String = "重新分析当前对话", onRetry: (() -> Unit)? = null) {
        ensureRoot(); bubble?.alpha = 1f
        setContent(listOf(
            line("出错了", "#DC2626", 14f, true),
            hint(msg),
            bigButton(retryLabel) { if (onRetry != null) onRetry() else onManualAnalyze?.invoke() }))
        if (!expanded && root != null) toggle()
    }

    /** Ask once when a chat bubble has readable text but no reliable sender. */
    fun showSpeakerChoice(message: Msg, onChoice: (String) -> Unit) {
        ensureRoot(); bubble?.alpha = 1f
        setContent(listOf(
            line("这条消息是谁发的？", "#263442", 15f, true),
            hint(message.modelText().take(180)),
            hint("发送方无法从当前页面确认，选择后才会继续分析。"),
            bigButton("对方发的") { onChoice("other") },
            bigButton("我发的") { onChoice("me") }
        ))
        if (!expanded && root != null) toggle()
    }

    /**
     * A neutral one-time notice (used when the foreground is WeChat, which is
     * fully disabled). Not framed as an error: shows the bubble, drops any stale
     * judgment from the previous chat, puts the message in the panel and opens it
     * once so the user actually reads it. Never auto-dismisses (unlike a toast)
     * and never takes input focus (the overlay window is FLAG_NOT_FOCUSABLE).
     */
    fun showNotice(msg: String) {
        ensureRoot(); bubble?.alpha = 1f
        resetForNewConversation()
        setContent(listOf(
            line("提示", "#607C92", 14f, true),
            hint(msg)))
        if (!expanded) toggle()
    }

    fun showJudgment(a: Analysis) {
        lastJudgment = a
        render(a, generating = true)
    }

    fun showDraft(drafts: List<ReplySuggestion>, issue: String? = null, onFill: (String) -> Unit) {
        draftReplies = drafts
        draftIssue = issue
        lastFill = onFill
        render(lastJudgment ?: Analysis(null, null, null, null, null, null, null,
            emptyList(), 0L), generating = issue == null)
    }

    fun showOpeners(openers: List<ReplySuggestion>, onFill: (String) -> Unit) {
        ensureRoot(); bubble?.alpha = 1f
        lastFill = onFill
        val views = ArrayList<View>()
        views.add(line("开场建议", "#263442", 15f, true))
        snapshotSummary?.let { views.add(hint(it)) }
        val details = snapshotDetails().apply {
            add(hint("仅供参考；请确认符合你们的实际关系后再发送。"))
            add(hint("每条建议的“调整”中可记住表达偏好，下一组生效。"))
        }
        views.add(disclosure("识别详情", details))
        openers.forEachIndexed { i, opener ->
            views.add(replyCard(i + 1, opener.text, null, onFill, tag = "开场", style = opener.style))
        }
        views.add(bigButton("换一组开场建议") { onOpenConversation?.invoke() })
        setContent(views)
        if (!expanded) toggle()
    }

    fun showInvitations(plan: com.jev.probe.jev.InvitationPlan, replies: List<ReplySuggestion>,
                        onFill: (String) -> Unit) {
        ensureRoot(); bubble?.alpha = 1f
        lastFill = onFill
        val views = ArrayList<View>()
        views.add(line("邀约建议 · ${plan.action.label}", "#263442", 14f, true))
        val evidence = snapshotDetails()
        evidence.add(hint("本轮参考 ${plan.evidence.size} 条可见及相关历史文字 不是全量聊天"))
        evidence.add(hint("参考原话（本地核对 仍需结合上下文）：" +
            (plan.basis?.display() ?: "未选到可靠对方原话 不推定已经愿意见面")))
        evidence.add(hint("主要条件：${plan.obstacle}"))
        evidence.add(hint(com.jev.probe.jev.InvitationPlaybook.SOURCE))
        views.add(disclosure("为什么这样建议 · 核实本次识别", evidence))
        replies.forEachIndexed { i, reply ->
            views.add(replyCard(i + 1, reply.text, null, onFill, tag = "邀约", style = reply.style))
        }
        views.add(disclosure("她回复后怎么接", com.jev.probe.jev.InvitationPolicy.followUps(plan.action)
            .map { hint(it) }))
        views.add(bigButton("换一组邀约建议") { onInviteConversation?.invoke() })
        setContent(views)
        if (!expanded) toggle()
    }

    /** Compact, persistent feedback while the user scrolls old chat pages. */
    var onRetryHistoryCapture: (() -> Unit)? = null
    var onToggleAutoBackfill: (() -> Unit)? = null
    private var backfillCountView: TextView? = null
    private var backfillStatusView: TextView? = null
    private var autoBackfillButton: TextView? = null
    private var backfillRunLabel: String? = null
    private var backfillResultLabel: String? = null

    fun showBackfillProgress(pages: Int, lines: Int, added: Boolean,
                             status: String? = null, runLabel: String? = null) {
        if (status != null) backfillResultLabel = status
        if (runLabel != null) backfillRunLabel = runLabel
        backfillActive = true
        backfillPages = pages
        backfillLines = lines
        backfillLastAdded = added
        ensureRoot()
        updateBubbleAppearance()
        ensureBackfillContent()
        backfillCountView?.text = listOfNotNull(backfillRunLabel, "联系人累计 $lines 条已存消息").joinToString("\n")
        backfillStatusView?.text = backfillResultLabel ?: if (added) "这一屏已保存，可以继续上翻。"
            else "这一屏已核对，无新增消息；可以继续上翻。"
    }

    fun showBackfillWaiting(message: String) {
        ensureRoot()
        backfillActive = true
        ensureBackfillContent()
        updateBubbleAppearance()
        backfillStatusView?.apply { if (text.toString() != message) text = message }
    }

    fun showAutoBackfillState(label: String, message: String, paused: Boolean = false) {
        ensureRoot()
        ensureBackfillContent()
        autoBackfillPaused = paused
        updateBubbleAppearance()
        autoBackfillButton?.text = label
        backfillStatusView?.text = message
    }

    private fun ensureBackfillContent() {
        if (contentBox != null && backfillCountView?.parent === contentBox) return
        backfillCountView = hint("累计 $backfillPages 屏有新增 · $backfillLines 条已存消息")
        backfillStatusView = hint("正在核对当前屏…")
        // Update these labels in place. Replacing the buttons during a progress
        // update could cancel an in-progress tap on Stop or Retry.
        autoBackfillButton = backfillButton("\u5f00\u59cb\u81ea\u52a8\u4e0a\u6ed1") { onToggleAutoBackfill?.invoke() }
        setContent(listOf(line("补录中", "#607C92", 14f, true),
            backfillCountView!!, backfillStatusView!!,
            autoBackfillButton!!,
            hint("可先点聊天里的时间标签展开完整日期 再自动上翻 自动翻页时收起面板 点击悬浮球可暂停查看 补录期间不生成回复"),
            backfillButton("重试当前屏") { onRetryHistoryCapture?.invoke() },
            backfillButton("结束补录", finish = true) { onToggleHistoryCapture?.invoke() }))
    }

    fun stopBackfill() {
        backfillActive = false
        autoBackfillPaused = false
        backfillPages = 0
        backfillLines = 0
        backfillRunLabel = null
        backfillResultLabel = null
        updateBubbleAppearance(); scheduleIdleFade()
    }

    fun showReplies(ranked: List<RankedReply>, error: String? = null, onFill: (String) -> Unit) {
        lastFill = onFill
        replyError = error
        draftReplies = emptyList()
        draftIssue = null
        val a = lastJudgment?.copy(rankedReplies = ranked) ?: return
        lastJudgment = a
        render(a, generating = false)
    }

    fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

    fun hide() {
        appearanceHandler.removeCallbacks(idleTask)
        if (screenReceiverRegistered) {
            runCatching { ctx.unregisterReceiver(screenReceiver) }
            screenReceiverRegistered = false
        }
        menuView = null; idleDimmed = false; hiddenForShot = false; screenOff = false; pointerDown = false
        val r = root ?: return
        runCatching { wm.removeView(r) }
        root = null; bubble = null; panel = null; contentBox = null; dangerDot = null; expanded = false
        snapshotUpdateBanner = null
        contentIsIdle = false
    }

    // --------------------------------------------------------------- rendering

    private fun setContent(views: List<View>, idle: Boolean = false) {
        val c = contentBox ?: return
        contentIsIdle = idle
        c.removeAllViews(); views.forEach { c.addView(it) }
    }

    private fun render(a: Analysis, generating: Boolean) {
        ensureRoot(); bubble?.alpha = 1f
        panel?.background = card(18, panelBg(), stroke = true) // re-apply in case opacity changed
        val views = ArrayList<View>()

        snapshotSummary?.let { views.add(hint(it)) }
        val details = snapshotDetails()

        // What context this read was based on (knowledge base / remembered history).
        details.add(hint(when {
            ctxMemoryOn -> "联系人已关联 · 记忆已开启 · 本轮引用旧消息 $ctxHistory 条 · 笔记 $ctxNotes 条"
            ctxContactMatched -> "联系人已关联 · 记忆未开启 · 笔记 $ctxNotes 条"
            ctxNotes > 0 -> "未关联联系人记忆 · 笔记 $ctxNotes 条"
            else -> "未关联联系人记忆"
        }))

        // How this snapshot was captured, when it changes how to read it.
        // Keep elevated risk visible; routine explanations are opt-in.
        a.dangerLevel?.let {
            val lvl = it.score.roundToInt()
            if (lvl >= 3) views.add(dangerBadge(lvl, it.maxLevel))
            else details.add(dangerBadge(lvl, it.maxLevel))
            tintBubbleDanger(it.score)
        }
        // Intent headline.
        a.trueIntent?.let {
            details.add(line("意图推测：${INTENT[it.choice] ?: it.choice}", "#263442", 14f, true))
            details.add(hint("模型把握 ${(it.confidence * 100).roundToInt()}%"))
        }
        // Compact secondary line: needs · action · reply-now.
        val bits = ArrayList<String>()
        a.sheNeeds?.let { bits.add("要${(NEEDS[it.choice] ?: it.choice)}") }
        a.bestAction?.let { bits.add(ACTION[it.choice] ?: it.choice) }
        a.shouldReplyNow?.let { bits.add(if (it >= 0.5) "可给实质" else "先别给实质") }
        if (bits.isNotEmpty()) details.add(line(bits.joinToString("  ·  "), "#374151", 13f))
        a.tensionResolved?.let { if (it >= 0.7) details.add(line("✓ 紧张已缓解", "#16A34A", 12f)) }

        views.add(disclosure("识别与判断详情", details))
        a.invitationOpportunity?.takeIf { it.choice in setOf("offer", "confirm") && it.confidence >= 0.6 }?.let {
            views.add(pill(if (it.choice == "confirm") "可以落实见面安排" else "可以顺势邀约", false) {
                onInviteConversation?.invoke()
            })
        }
        val showingDrafts = draftReplies.isNotEmpty() && a.rankedReplies.isEmpty()
        views.add(line(when {
            showingDrafts && generating -> "初稿（未排序，判断可能仍在进行）"
            showingDrafts -> "候选回复（尚未排序）"
            a.rankedReplies.any { it.prob < 0.0 } -> "候选回复（已过滤不合适建议）"
            else -> "候选回复（已排序）"
        }, "#68788C", 12f))
        if (showingDrafts) {
            draftIssue?.let { views.add(hint(it)) }
            val fill = lastFill ?: {}
            draftReplies.forEachIndexed { i, candidate ->
                views.add(replyCard(i + 1, candidate.text, null, fill,
                    style = candidate.style, reason = candidate.reason))
            }
            if (generating) views.add(hint("初稿可先参考，排序结果稍后补全。"))
        } else if (generating) {
            views.add(hint("生成中…"))
        } else {
            val fill = lastFill ?: {}
            a.rankedReplies.forEachIndexed { i, r ->
                views.add(replyCard(i + 1, r.text,
                    if (r.prob < 0.0) null else (r.prob * 100).roundToInt(), fill,
                    if (r.prob < 0.0) "已筛选" else null, r.style, r.reason))
            }
            if (a.rankedReplies.isEmpty()) {
                val msg = replyError?.let { "回复接口出错：$it" } ?: "（未生成候选回复）"
                views.add(hint(msg))
            }
        }
        views.add(reAnalyzeBtn())

        setContent(views)
        if (!expanded) toggle()
    }

    private fun dangerBadge(lvl: Int, max: Int): View {
        val color = dangerColor(lvl)
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(6))
        }
        row.addView(TextView(ctx).apply {
            text = "危险 $lvl/$max"
            setTextColor(Color.WHITE); textSize = 13f; setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = card(20, color)
        })
        row.addView(TextView(ctx).apply {
            text = "  " + dangerWord(lvl); setTextColor(color); textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
        })
        return row
    }

    private fun replyCard(rank: Int, text: String, pct: Int?, onFill: (String) -> Unit,
                          tag: String? = null, style: String = "", reason: String = ""): View {
        val top = rank == 1
        val cardBg = if (top) Color.parseColor("#EAF1FB") else Color.parseColor("#F1F4F8")
        val c = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = card(12, cardBg)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        }
        c.addView(TextView(ctx).apply {
            this.text = "#$rank · ${tag ?: if (pct == null) "初稿" else "${pct}%"}"
            setTextColor(Color.parseColor("#607C92")); textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
        })
        c.addView(TextView(ctx).apply {
            this.text = text; setTextColor(Color.parseColor("#26364A")); textSize = 14f
            setPadding(0, dp(3), 0, dp(7)); setLineSpacing(dp(2).toFloat(), 1f)
        })
        val detailViews = ArrayList<View>()
        if (style.isNotBlank()) {
            val styleName = when (style) {
                "fresh_opening" -> "自然新开场"
                "light_callback" -> "轻轻回扣旧话题"
                "natural" -> "自然"
                "follow_detail" -> "接具体细节"
                "simple" -> "平实回应"
                "playful" -> "轻松"
                "gentle_flirt" -> "轻微撩拨"
                "restart" -> "轻松重启"
                "resume_topic" -> "接回旧话题"
                "share_now" -> "自然分享"
                "invite_direct" -> "自然直说"
                "invite_context" -> "结合聊天切口"
                "invite_playful" -> "轻松说法"
                else -> style
            }
            detailViews.add(hint("风格：$styleName"))
        }
        if (reason.isNotBlank()) detailViews.add(hint("依据：$reason"))
        val adjustments = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            detailViews.forEach { addView(it) }
        }
        val btns = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val actions = ArrayList<View>()
        actions.add(pill("复制", false) { copy(text) })
        // Fill, then collapse so the input box + keyboard are visible to review/send.
        actions.add(pill("填入", true) { android.util.Log.d("JEVASSIST", "overlay: fill tapped"); onFill(text); if (expanded) toggle() })
        actions.add(TextView(ctx).apply {
            this.text = "调整 ▾"; textSize = 13f; gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#607C92"))
            setOnClickListener {
                val open = adjustments.visibility != View.VISIBLE
                adjustments.visibility = if (open) View.VISIBLE else View.GONE
                this.text = if (open) "收起 ▴" else "调整 ▾"
                performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
            }
        })
        actions.forEachIndexed { index, action ->
            action.minimumHeight = dp(40)
            action.setPadding(dp(4), dp(4), dp(4), dp(4))
            action.layoutParams = LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (index < actions.lastIndex) marginEnd = dp(6)
            }
            btns.addView(action)
        }
        c.addView(btns)
        run {
            val feedback = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            val choices = listOf(pill("像我", false) {
                onApproveReplyVoice?.invoke(text)
            }, pill("改写学习", false) { onEditReplyVoice?.invoke(text) }, pill("少点AI", false) { rememberFeedback("less_ai") },
                pill("少主动", false) { rememberFeedback("less_forward") },
                pill("少问句", false) { rememberFeedback("less_questions") })
            choices.chunked(2).forEach { buttons ->
                feedback.addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    buttons.forEachIndexed { index, button ->
                        button.layoutParams = LinearLayout.LayoutParams(0,
                            LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                            if (index == 0) rightMargin = dp(8)
                            topMargin = dp(5)
                        }
                        addView(button)
                    }
                })
            }
            adjustments.addView(feedback)
        }
        c.addView(adjustments)
        return c
    }

    private fun snapshotDetails() = ArrayList<View>().apply {
        snapshotAudit?.let { add(hint(it)) }
        add(bigButton("核对本次识别内容") { onReviewSnapshot?.invoke() })
        noteText?.takeIf { it.isNotBlank() }?.let { add(hint(it)) }
    }

    /** Expand locally without rebuilding cards or making another model request. */
    private fun disclosure(label: String, details: List<View>): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            details.forEach { addView(it) }
        }
        addView(TextView(ctx).apply {
            text = "$label ▾"; textSize = 12f
            setTextColor(Color.parseColor("#607C92"))
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(36)
            setOnClickListener {
                val open = body.visibility != View.VISIBLE
                body.visibility = if (open) View.VISIBLE else View.GONE
                text = "$label ${if (open) "▴" else "▾"}"
                performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
            }
        })
        addView(body)
    }

    fun hideForUser() {
        prefs.overlayHidden = true
        onHideAssistant?.invoke()
        hide()
        toast("旁注已隐藏 可从通知栏恢复 或到 App 首页点恢复旁注")
    }

    private fun rememberFeedback(code: String) {
        prefs.rememberReplyFeedback(code)
        toast("已记住这条反馈，下一组建议生效")
    }

    private fun pill(label: String, primary: Boolean, onClick: () -> Unit) = TextView(ctx).apply {
        text = label; textSize = 13f; gravity = Gravity.CENTER
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(if (primary) Color.WHITE else Color.parseColor("#607C92"))
        background = RippleDrawable(ColorStateList.valueOf(Color.parseColor(if (primary) "#55FFFFFF" else "#24344F6D")),
            card(18, if (primary) Color.parseColor("#607C92") else Color.parseColor("#FFFFFF"), stroke = !primary),
            card(18, Color.WHITE))
        setPadding(dp(18), dp(6), dp(18), dp(6))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { rightMargin = dp(8) }
        setOnClickListener { onClick() }
    }

    private fun reAnalyzeBtn() = TextView(ctx).apply {
        text = "重新分析"; textSize = 13f; gravity = Gravity.CENTER
        setTextColor(Color.parseColor("#53647A"))
        setPadding(dp(10), dp(10), dp(10), dp(4))
        setOnClickListener { onManualAnalyze?.invoke() }
    }

    private fun tintBubbleDanger(score: Double) {
        val color = dangerColor(score.roundToInt())
        dangerDot?.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL; setColor(color); setStroke(dp(2), Color.WHITE)
        }
    }

    // --------------------------------------------------------------- helpers

    private fun line(text: String, color: String, size: Float, bold: Boolean = false) =
        TextView(ctx).apply {
            this.text = text; setTextColor(Color.parseColor(color)); textSize = size
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(2), 0, dp(2))
        }

    private fun hint(text: String) = line(text, "#68788C", 12f)

    private fun divider() = View(ctx).apply {
        setBackgroundColor(Color.parseColor("#1F000000"))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(8); bottomMargin = dp(4)
        }
    }

    private fun copy(text: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("jev_reply", text))
        toast("已复制")
    }

    private fun dangerColor(lvl: Int): Int = when {
        lvl >= 6 -> Color.parseColor("#DC2626")
        lvl >= 3 -> Color.parseColor("#D97706")
        else -> Color.parseColor("#16A34A")
    }

    private fun dangerWord(lvl: Int): String = when {
        lvl >= 8 -> "很危险"
        lvl >= 6 -> "偏危险"
        lvl >= 3 -> "留神"
        else -> "安全"
    }

    companion object {
        private val INTENT = mapOf(
            "confirm_you_care" to "确认你在不在乎", "vent_anger" to "在发泄情绪",
            "request_action" to "要你办事", "seek_explanation" to "要个解释",
            "casual_chat" to "随便聊聊", "close_topic" to "事情过去了")
        private val NEEDS = mapOf(
            "apology" to "道歉", "action" to "具体行动", "explanation" to "解释",
            "care" to "你的在乎", "nothing" to "（不用做什么）")
        private val ACTION = mapOf(
            "check_history" to "翻聊天记录", "apologize" to "先道歉", "give_commitment" to "给承诺",
            "explain" to "解释清楚", "acknowledge" to "接住情绪", "say_less" to "少说两句",
            "make_plan" to "定个安排")
    }
}
