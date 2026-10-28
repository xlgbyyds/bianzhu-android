package com.jev.probe.capture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.jev.probe.capture.ocr.MlKitOcr
import com.jev.probe.capture.ocr.OcrLine
import com.jev.probe.capture.ocr.ScreenCapture
import com.jev.probe.core.BubbleRect
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.MessageKind
import com.jev.probe.core.Msg
import com.jev.probe.core.Prefs
import com.jev.probe.core.RankedReply
import com.jev.probe.core.ReplySuggestion
import com.jev.probe.core.RuntimeState
import com.jev.probe.core.kb.ContextBuilder
import com.jev.probe.core.kb.ConversationLedger
import com.jev.probe.core.kb.KbStore
import com.jev.probe.jev.ConversationPurpose
import com.jev.probe.jev.InvitationPlan
import com.jev.probe.jev.JevClient
import com.jev.probe.jev.RelationshipProfileGenerator
import com.jev.probe.core.kb.profileUserContext
import com.jev.probe.jev.RelationshipProfileJobs
import com.jev.probe.jev.ReplyClient
import com.jev.probe.overlay.OverlayController
import com.jev.probe.SnapshotReviewActivity
import com.jev.probe.SnapshotReviewCodec
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.UUID

/**
 * The live capture service (registered under a disguised class name so WeChat
 * exposes its node tree — see the disguised subclass). It reads whichever
 * adapted chat app is in the foreground, detects a new incoming message from the
 * other person, runs Jev analysis off the main thread, and drives the floating
 * overlay.
 *
 * Per-app node rules live in [ChatAppAdapter] implementations; everything here
 * is app-agnostic.
 *
 * It never sends a message. The only write action is ACTION_SET_TEXT (or a
 * clipboard PASTE fallback) to fill the chat input box when the user taps
 * "填入"; the user still presses send.
 */
open class ChatCaptureService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newFixedThreadPool(2)
    // Passive disk writes never occupy the two network/analysis workers. A fast
    // history scroll is bounded to avoid a stale queue growing for minutes.
    private val memoryWorker = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(16), ThreadPoolExecutor.DiscardOldestPolicy())
    /** One low-priority, one-request-at-a-time first-use profile build. */
    private val profileWorker = Executors.newSingleThreadExecutor()

    /** The personal build also tries the retained WeChat node adapter. */
    private val adapters = listOf(WeChatAdapter(), SoulAdapter(), QianshouAdapter(), QingtengAdapter(), UkiAdapter(),
        QQAdapter(), XAdapter(), FeishuAdapter())
        .associateBy { it.pkg }

    /** Submit to the worker, ignoring rejection after the service is torn down
     *  (a stale overlay callback must never crash the process). */
    private fun submit(task: () -> Unit) {
        try { worker.execute(task) } catch (_: RejectedExecutionException) { }
    }
    private lateinit var prefs: Prefs
    private var overlay: OverlayController? = null

    private var lastSignature: String = ""
    private var activePkg: String? = null
    private val backfill = BackfillSession()
    private val historyCaptureMode get() = backfill.active != null
    private var backfillStarting = false
    private var backfillFinishedNotice: Pair<ConversationSession.Target, Long>? = null
    private var lastBackfillPage: String? = null
    private var backfillRetryCount = 0
    private var backfillTreeRetryCount = 0
    private val backfillScan = Runnable { scanWechatBackfill() }
    private var autoBackfillRunning = false
    private var autoBackfillAwaitingCapture = false
    private var autoBackfillExpectedPage: String? = null
    private var autoBackfillStalls = 0
    private val autoScrollRetryPlan = AutoScrollRetryPlan()
    private val autoBackfillDuplicateStreak = AutoBackfillDuplicateStreak()
    private var autoBackfillLastMethod = AutoScrollMethod.ACCESSIBILITY
    private var ignoreTouchPauseUntil = 0L
    private val autoBackfillTick = Runnable { performAutoBackfillStep() }
    private var autoIdentityRetries = 0
    private var backfillSavedSignature: String? = null
    private val autoBackfillTimeout = Runnable {
        if (autoBackfillRunning && autoBackfillAwaitingCapture) onAutoBackfillTimeout()
    }
    private val manualTouchPause = Runnable {
        if (autoBackfillRunning)
            pauseAutoBackfill("\u68c0\u6d4b\u5230\u4f60\u5728\u64cd\u4f5c\u5c4f\u5e55\uff0c\u81ea\u52a8\u4e0a\u6ed1\u5df2\u6682\u505c\uff1b\u8865\u5f55\u4f1a\u8bdd\u4fdd\u6301\u5f00\u542f")
    }
    private var lastBackfillPages = -1
    private var lastBackfillLines = 0
    private var lastBackfillFeedbackAt = 0L
    private var analyzing = false
    private val session = ConversationSession()
    private val contactBinding = ConversationContactBinding()
    private var toolsRequestId: String? = null
    private var toolsTarget: ConversationSession.Target? = null
    private var toolsActivityActive = false
    private data class VoiceScope(val target: ConversationSession.Target, val key: String, val name: String,
                                  val contactId: String?)
    private var voiceScope: VoiceScope? = null
    private val toolsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            if (toolsRequestId == null || intent?.getStringExtra("requestId") != toolsRequestId) return
            val target = toolsTarget ?: return
            val contactId = intent?.getStringExtra("contactId")
            val permanent = intent?.getBooleanExtra("permanent", false) == true
            val canceled = intent?.getBooleanExtra("canceled", false) == true
            toolsRequestId = null
            finishConversationTools(target, contactId, permanent, canceled)
        }
    }

    private fun finishConversationTools(target: ConversationSession.Target, contactId: String?,
                                        permanent: Boolean, canceled: Boolean, attempt: Int = 0) {
        // Ignore an old delayed result after a newer picker/editor has opened.
        if (!toolsActivityActive || toolsTarget != target || toolsRequestId != null) return
        val root = rootInActiveWindow
        val live = root?.let { targetFor(it) }
        Log.i(TAG, "contact tools return attempt=$attempt sameSession=${session.target == target} " +
            "pkg=${root?.packageName} window=${root?.windowId} expectedWindow=${target.windowId} " +
            "titleKnown=${live?.title != null} sameTarget=${live == target}")
        if (session.target != target || live != target) {
            if (session.target == target && attempt < 12) {
                val foreground = root?.packageName?.toString()
                val retry = { finishConversationTools(target, contactId, permanent, canceled, attempt + 1) }
                if (root == null || foreground == packageName) {
                    main.postDelayed(retry, 250L)
                    return
                }
                // WeChat's toolbar text can disappear from its accessibility tree
                // on returning from our Activity. Read the *current* toolbar;
                // never infer identity from the app alone or reuse the old name.
                if (foreground == target.pkg && root.windowId == target.windowId &&
                    live?.title == null && target.pkg == PKG_WECHAT) {
                    if (ocrBusy || analyzing || wechatTitleRegion(root) == null) main.postDelayed(retry, 250L)
                    else resolveWechatTitle(root, quiet = true,
                        onProblem = { _, _ -> main.postDelayed(retry, 250L) }, onReady = retry)
                    return
                }
            }
            toolsActivityActive = false; toolsTarget = null
            overlay?.setHiddenForShot(false)
            overlay?.toast("未回到原聊天页，未改变联系人关联")
            return
        }
        toolsActivityActive = false; toolsTarget = null
        overlay?.setHiddenForShot(false)
        val store = KbStore.get(this)
        var feedback = "已返回原会话"
        if (!canceled) {
            val contact = contactId?.let { store.contact(it) }?.takeIf { target.pkg in it.apps }
            when {
                contactId == null -> { contactBinding.clear(); feedback = "已恢复按页面自动匹配" }
                contact == null -> feedback = "此联系人已删除或不属于当前 App，未关联"
                permanent -> {
                    if (store.confirmAlias(contact.id, target.title.orEmpty(), target.pkg)) {
                        contactBinding.clear(); feedback = "备注关联已确认，以后在此 App 自动匹配"
                    } else feedback = "备注与其他联系人冲突，未修改关联；请先核对身份"
                }
                else -> { contactBinding.select(target, contact.id); feedback = "仅本次使用此联系人；记忆采集已暂停" }
            }
        }
        cancelAnalysis()
        val contact = contactBinding.contactId(target)?.let { store.contact(it) }
            ?: store.findContact(target.title.orEmpty(), target.pkg)
        overlay?.setContactInfo(contact?.name, contactBinding.contactId(target) != null)
        overlay?.showIdle(target.title)
        overlay?.toast(feedback)
    }

    private fun openConversationTools(mode: String, text: String? = null) {
        if (historyCaptureMode || backfillStarting) { overlay?.toast("请先结束补录，再选择联系人或编辑表达"); return }
        val root = rootInActiveWindow ?: return
        val target = targetFor(root) ?: run { overlay?.toast("请回到聊天页再操作"); return }
        if (target.title.isNullOrBlank()) {
            if (target.pkg == PKG_WECHAT && wechatTitleRegion(root) != null) {
                resolveWechatTitle(root) { openConversationTools(mode, text) }
            } else overlay?.toast("页面联系人名称未知，请先核对标题")
            return
        }
        observeTarget(target)
        val store = KbStore.get(this)
        val contact = contactBinding.contactId(target)?.let { store.contact(it) }
            ?: store.findContact(target.title.orEmpty(), target.pkg)
        if (mode == "edit" && voiceScope?.target != target) { overlay?.toast("建议所属会话已变化，请重新分析"); return }
        val confirmedVoice = if (mode == "edit") resolveVoiceScope() ?: return else null
        analysisTasks.forEach { it.cancel(true) }; analysisTasks.clear()
        analyzing = false; main.removeCallbacks(debounce); session.invalidate()
        pinnedAnalysisTarget.pin(target)
        val nonce = UUID.randomUUID().toString()
        toolsRequestId = nonce; toolsTarget = target; toolsActivityActive = true
        overlay?.setHiddenForShot(true)
        try {
            startActivity(Intent(this, com.jev.probe.ConversationToolsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("mode", mode)
                .putExtra("requestId", nonce).putExtra("app", target.pkg).putExtra("title", target.title)
                .putExtra("selected", contact?.id)
                .putExtra("scope", confirmedVoice?.key).putExtra("name", confirmedVoice?.name).putExtra("text", text))
        } catch (e: Exception) {
            toolsActivityActive = false; toolsRequestId = null; toolsTarget = null
            overlay?.setHiddenForShot(false); overlay?.toast("无法打开编辑页，请重试")
        }
    }

    private fun setVoiceScope(target: ConversationSession.Target, ctx: com.jev.probe.core.kb.ChatContext?) {
        voiceScope = VoiceScope(target, com.jev.probe.core.ReplyVoiceStore.scope(target.pkg, ctx?.contact?.id, target.title),
            ctx?.contact?.name ?: target.title.orEmpty(), ctx?.contact?.id)
        overlay?.setContactInfo(ctx?.contact?.name, contactBinding.contactId(target) != null)
    }

    private fun resolveVoiceScope(): VoiceScope? {
        val scope = voiceScope
        if (scope == null || session.target != scope.target) {
            overlay?.toast("所属会话已变化，请重新分析"); return null
        }
        val store = KbStore.get(this)
        val contact = if (scope.contactId != null) store.contact(scope.contactId)
            else store.findContact(scope.target.title, scope.target.pkg)
        if (contact == null || scope.target.pkg !in contact.apps) {
            overlay?.toast("请先把当前会话存为联系人，再保存表达样本，避免同名的人共用记录")
            return null
        }
        return VoiceScope(scope.target, com.jev.probe.core.ReplyVoiceStore.scope(scope.target.pkg, contact.id, null),
            contact.name, contact.id)
    }
    private val scopeReadRetry = ScopeReadRetry()
    private val pendingScopeRetries = HashSet<String>()
    private val pinnedAnalysisTarget = PinnedAnalysisTarget()
    private var pinnedContextReady = false
    private data class DeferredPinnedCapture(val snapshot: ChatSnapshot, val pkg: String)
    private var deferredPinnedCapture: DeferredPinnedCapture? = null
    private var reviewRequestId: String? = null
    private var reviewTarget: ConversationSession.Target? = null
    private var reviewActivityActive = false
    private var reviewPurpose = ConversationPurpose.REPLY
    private var lastRequestPurpose = ConversationPurpose.REPLY
    private var reviewedSnapshotOverride: Pair<ConversationSession.Target, ChatSnapshot>? = null
    private var reviewReceiverRegistered = false
    private val snapshotReviewReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            if (reviewRequestId == null ||
                intent?.getStringExtra(SnapshotReviewActivity.EXTRA_REQUEST_ID) != reviewRequestId) return
            val target = reviewTarget
            if (intent?.getBooleanExtra(SnapshotReviewActivity.EXTRA_CANCELED, false) == true) {
                reviewRequestId = null
                if (target != null && session.target == target)
                    main.postDelayed({ resumeReviewedAnalysis(target) }, 250L)
                return
            }
            val corrected = SnapshotReviewCodec.decode(intent?.getStringExtra(SnapshotReviewActivity.EXTRA_SNAPSHOT))
            reviewRequestId = null
            if (target == null || session.target != target || corrected == null || corrected.messages.isEmpty()) {
                overlay?.toast("会话已变化或识别内容无效，请重新分析当前页面")
                if (target != null && session.target == target)
                    main.postDelayed({ resumeReviewedAnalysis(target) }, 250L)
                return
            }
            if (target.title != null && corrected.title != target.title) {
                overlay?.toast("联系人标题不匹配，未应用这次修改")
                main.postDelayed({ resumeReviewedAnalysis(target) }, 250L)
                return
            }
            analysisTasks.forEach { it.cancel(true) }
            analysisTasks.clear()
            analyzing = false
            main.removeCallbacks(debounce)
            pinnedAnalysisTarget.pin(target)
            pinnedContextReady = false
            deferredPinnedCapture = null
            session.invalidate()
            pendingSnapshot = corrected
            currentSnapshot = corrected
            lastSignature = corrected.signature()
            if (reviewPurpose != ConversationPurpose.REPLY) reviewedSnapshotOverride = target to corrected
            overlay?.setSnapshotAudit(corrected)
            overlay?.setNote(corrected.note)
            main.postDelayed({ resumeReviewedAnalysis(target) }, 250L)
        }
    }
    private val wechatTitleGuard = WechatTitleGuard()
    private val wechatIdentity = WechatIdentityContinuity()
    private val analysisTasks = ArrayList<Future<*>>()
    private var activeDelivery: AnalysisDelivery? = null
    private var destroyed = false
    private val captureRefresh = Runnable { if (!destroyed && prefs.enabled) maybeCapture() }

    private fun scheduleCaptureRefresh(delayMs: Long = 160L) {
        if (!main.hasCallbacks(captureRefresh)) main.postDelayed(captureRefresh, delayMs)
    }
    private val preferencesListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key?.startsWith("appearance_") == true || key == "overlay_opacity") {
            main.post { overlay?.refreshAppearance() }
        }
        if (key == "enabled" || key == "whitelist" || key == "overlay_hidden") {
            main.post {
                leaveConversation()
                if (prefs.overlayHidden) {
                    resetBackfill("assistant hidden")
                    main.removeCallbacks(captureRefresh)
                }
                overlay?.hide()
                if (key == "overlay_hidden") {
                    runCatching { KeepAliveService.start(this) }
                    if (!prefs.overlayHidden && prefs.enabled) scheduleCaptureRefresh(900L)
                }
            }
        }
    }

    /** Invalidate callbacks before cancelling workers; interruption alone is not a guard. */
    private fun cancelAnalysis() {
        afterVoiceOcr = null
        activeDelivery = null
        pinnedAnalysisTarget.clear()
        pinnedContextReady = false
        deferredPinnedCapture = null
        session.invalidate()
        main.removeCallbacks(debounce)
        pendingSnapshot = null
        analyzing = false
        analysisTasks.forEach { it.cancel(true) }
        analysisTasks.clear()
        // A new visible page is expected during backfill. Clearing the panel
        // here erased its progress text, sometimes after the last throttled UI
        // update, leaving an empty expanded panel while the bubble kept counting.
        if (!historyCaptureMode) {
            overlay?.resetForNewConversation()
            contactBinding.contactId(session.target)?.let { id ->
                overlay?.setContactInfo(KbStore.get(this).contact(id)?.name, true)
            }
        }
    }

    private fun observeTarget(target: ConversationSession.Target?) {
        if (session.observe(target)) {
            contactBinding.observe(target)
            voiceScope = null
            wechatTranscripts.clear()
            if (reviewedSnapshotOverride?.first != target) reviewedSnapshotOverride = null
            cancelAnalysis()
            resetBackfill()
            overlay?.resetForNewConversation()
            currentSnapshot = null
            activePkg = null
            lastSignature = ""
            lastOcrSignature = ""
        }
    }

    private fun leaveConversation() {
        if (session.target != null) {
            // observeTarget already cancels and clears once on this transition.
            observeTarget(null)
        } else if (analyzing || pendingSnapshot != null || currentSnapshot != null) {
            cancelAnalysis()
        }
        currentSnapshot = null
    }

    /** Read the live target, never the previous chat's cached/stabilized title. */
    private fun targetFor(root: AccessibilityNodeInfo, snapshot: ChatSnapshot? = snapshotFor(root)): ConversationSession.Target? {
        val pkg = root.packageName?.toString() ?: return null
        if (pkg == packageName || pkg == "com.android.systemui" ||
            pkg.contains("launcher", true) || pkg == "com.miui.home") return null
        val adapter = adapters[pkg]
        val resolvedTitle = if (adapter != null) {
            if (snapshot == null && pkg != PKG_WECHAT) return null
            // A loading/unknown title cannot prove which conversation is open.
            snapshot?.title ?: if (pkg == PKG_WECHAT) {
                wechatManualTitle(root)
            } else null
        } else {
            findTitleInActionBar(root, Int.MAX_VALUE, resources.displayMetrics.widthPixels,
                resources, 0.15, 0.85)
        }
        val title = resolvedTitle?.takeUnless { isTransientTitle(it) }
        val messagesSignature = if (title == null) {
            // Some WeChat remarks render as an image/single custom glyph: the
            // tree still proves this is a chat and exposes its message rows,
            // but neither tree text nor local title OCR can read that label.
            // Bind one-shot work to the visible rows instead of guessing which
            // contact it is. No contact memory can match a null title.
            if (pkg != PKG_WECHAT || snapshot?.messages.isNullOrEmpty() ||
                wechatTitleRegion(root) == null) return null
            snapshot!!.signature()
        } else null
        // Visible messages change while a request is in flight. They are data,
        // not the identity of a conversation; including their signature here
        // silently discarded otherwise valid network/screenshot callbacks.
        return ConversationSession.Target(pkg, root.windowId, title, messagesSignature)
    }

    private fun isCurrent(token: ConversationSession.Token): Boolean {
        if (destroyed || !prefs.enabled || prefs.overlayHidden || !session.accepts(token)) return false
        val liveRoot = rootInActiveWindow
        val live = liveRoot?.let { targetFor(it) }
        // The token also owns the displayed result after the workers finish.
        val pinnedRequest = pinnedAnalysisTarget.isBoundTo(token.target)
        val transientPinnedRead = pinnedRequest && liveRoot != null &&
            pinnedAnalysisTarget.isSameWindow(
                liveRoot.packageName?.toString().orEmpty(), liveRoot.windowId
            ) && (live == null || live.title.isNullOrBlank()) &&
            (analyzing || ocrBusy || live != null)
        if ((live != token.target && !transientPinnedRead) ||
            !prefs.isAllowed(currentSnapshot?.title ?: live?.title)) {
            leaveConversation()
            overlay?.hide()
            return false
        }
        return true
    }

    /** Only called on the main thread, including the context-completion callback. */
    private fun submitAnalysis(task: () -> Unit) {
        try { analysisTasks.add(worker.submit(task)) } catch (_: RejectedExecutionException) { }
    }

    @Suppress("DEPRECATION")
    private fun registerSnapshotReviewReceiver() {
        if (reviewReceiverRegistered) return
        val filter = IntentFilter(SnapshotReviewActivity.ACTION_RESULT)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(snapshotReviewReceiver, filter, RECEIVER_NOT_EXPORTED)
        else registerReceiver(snapshotReviewReceiver, filter)
        val toolsFilter = IntentFilter(com.jev.probe.ConversationToolsActivity.ACTION_RESULT)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(toolsReceiver, toolsFilter, RECEIVER_NOT_EXPORTED)
        else registerReceiver(toolsReceiver, toolsFilter)
        reviewReceiverRegistered = true
    }

    private fun openSnapshotReview() {
        val snapshot = pendingSnapshot ?: run { overlay?.toast("当前没有可核对的识别内容"); return }
        val target = session.target ?: run { overlay?.toast("当前会话已变化，请重新分析"); return }
        if (target.title.isNullOrBlank()) {
            overlay?.toast("当前联系人标题无法确认，暂不能修改识别内容")
            return
        }
        // The review screen can stay open longer than the reply request. Stop
        // that request first so its callback cannot mistake our own Activity
        // for leaving the chat and tear down the pinned conversation.
        analysisTasks.forEach { it.cancel(true) }
        analysisTasks.clear()
        analyzing = false
        main.removeCallbacks(debounce)
        pinnedAnalysisTarget.pin(target)
        pinnedContextReady = false
        deferredPinnedCapture = null
        session.invalidate()
        val requestId = UUID.randomUUID().toString()
        reviewRequestId = requestId
        reviewTarget = target
        reviewPurpose = lastRequestPurpose
        reviewActivityActive = true
        overlay?.setHiddenForShot(true)
        try {
            startActivity(Intent(this, SnapshotReviewActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(SnapshotReviewActivity.EXTRA_REQUEST_ID, requestId)
                .putExtra(SnapshotReviewActivity.EXTRA_SNAPSHOT, SnapshotReviewCodec.encode(snapshot)))
        } catch (e: Exception) {
            reviewActivityActive = false
            overlay?.setHiddenForShot(false)
            overlay?.toast("无法打开识别内容核对页：${e.javaClass.simpleName}")
        }
    }

    private fun resumeReviewedAnalysis(target: ConversationSession.Target, attempts: Int = 0) {
        val root = rootInActiveWindow
        val live = root?.let { targetFor(it) }
        if (attempts < 12 && root?.packageName?.toString() == PKG_WECHAT && root.windowId == target.windowId &&
            live?.title.isNullOrBlank() && wechatTitleRegion(root) != null && !ocrBusy) {
            resolveWechatTitle(root, quiet = true, onProblem = { message, retryable ->
                // Returning from an Activity can redraw the same chat after the
                // screenshot ticket was created. Re-read a few settled frames;
                // the name still has to equal the original target before reuse.
                val returning = rootInActiveWindow
                if (retryable && attempts < 3 && returning?.packageName?.toString() == target.pkg &&
                    returning.windowId == target.windowId && session.target == target) {
                    main.postDelayed({ resumeReviewedAnalysis(target, attempts + 1) }, 450L)
                    return@resolveWechatTitle
                }
                reviewActivityActive = false
                overlay?.setHiddenForShot(false)
                overlay?.showError("返回会话未能核实：$message", "重试${reviewPurpose.label}建议") {
                    val purpose = reviewPurpose
                    reviewPurpose = ConversationPurpose.REPLY
                    if (purpose == ConversationPurpose.REPLY) overlay?.onManualAnalyze?.invoke()
                    else requestConversationSuggestions(purpose)
                }
            }) { resumeReviewedAnalysis(target, attempts + 1) }
            return
        }
        val sameChat = root?.packageName?.toString() == target.pkg && root.windowId == target.windowId &&
            live?.pkg == target.pkg && live.title == target.title
        if (!sameChat) {
            if (attempts < 12 && session.target == target) {
                main.postDelayed({ resumeReviewedAnalysis(target, attempts + 1) }, 250L)
            } else {
                reviewActivityActive = false
                overlay?.setHiddenForShot(false)
                overlay?.toast("没有回到原聊天页面，已保留修改但未重新分析")
            }
            return
        }
        reviewActivityActive = false
        overlay?.setHiddenForShot(false)
        val purpose = reviewPurpose
        reviewPurpose = ConversationPurpose.REPLY
        if (purpose != ConversationPurpose.REPLY) requestConversationSuggestions(purpose)
        else runAnalysis()
    }

    private fun retrySupportedScopeRead(
        action: String,
        pkg: String?,
        windowId: Int?,
        retry: () -> Unit
    ): Boolean {
        if (pkg == null || !adapters.containsKey(pkg) || windowId == null) return false
        if (!pendingScopeRetries.add(action)) return true
        val attempt = scopeReadRetry.next(action, pkg, windowId)
        if (attempt == null) {
            pendingScopeRetries.remove(action)
            return false
        }
        Log.i(TAG, "$action chat page read incomplete; retry=$attempt pkg=$pkg window=$windowId")
        overlay?.showProgress("聊天页面正在刷新，重新确认中（$attempt/2）…")
        main.postDelayed({
            pendingScopeRetries.remove(action)
            val live = rootInActiveWindow
            if (live?.packageName?.toString() == pkg && live.windowId == windowId) {
                retry()
            } else {
                scopeReadRetry.reset(action)
                overlay?.showNotice("当前页面已变化，本次识别已取消；请回到聊天页后重试。")
            }
        }, 220L)
        return true
    }

    private fun supportedScopeReadFailure(pkg: String?): String = when (pkg) {
        PKG_WECHAT -> "微信"
        "com.tantan.x" -> "牵手"
        "cn.soulapp.android" -> "Soul"
        "cn.neoclub.uki" -> "Uki"
        QingtengAdapter.PACKAGE -> "青藤之恋"
        else -> "当前 App"
    } + "已识别为支持的 App，但聊天页结构暂时没读全。这不代表该 App 不支持；请保持聊天页停稳后重试。"

    private val debounce = Runnable { runAnalysis() }
    private var pendingSnapshot: ChatSnapshot? = null
    @Volatile private var currentSnapshot: ChatSnapshot? = null
    private var foregroundPkg: String? = null
    private var lastScrollAt = 0L
    private var soulScrollSettlesAt = 0L

    // ---- OCR path (B stage). Everything here runs on the main thread: the
    // screenshot callback and the ML Kit callback are both posted back to it.
    private val screenCapture by lazy {
        ScreenCapture(this,
            hideOverlay = { overlay?.setHiddenForShot(true) },
            restoreOverlay = { overlay?.setHiddenForShot(false) })
    }
    private val ocr = MlKitOcr()
    private var ocrBusy = false
    private val wechatTranscripts = WechatTranscriptCache()
    private var voiceOcrActive = false
    private var afterVoiceOcr: Pair<ConversationSession.Token, () -> Unit>? = null

    /** What the screen looked like the last time we fired an automatic shot.
     *  See [ocrSignature]: this is the brake on the OCR path. */
    private var lastOcrSignature: String = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        // HyperOS may reconnect this service without killing its process. An
        // older overlay can remain above the new one and absorb taps while its
        // callbacks belong to a detached controller.
        if (overlay != null) {
            Log.i(TAG, "capture service reconnect: removing previous overlay")
            overlay?.hide()
        }
        RuntimeState.serviceConnected = true
        RuntimeState.lastEventPackage = null
        RuntimeState.overlayIssue = null
        prefs = Prefs(this)
        val mainPrefs = getSharedPreferences(Prefs.PREFS_MAIN, MODE_PRIVATE)
        if (!mainPrefs.getBoolean("personal_manual_only_v8_done", false)) {
            // The user chose a quiet bubble, with analysis only after a tap.
            prefs.autoAnalyze = false
            prefs.ocrAutoAnalyze = false
            mainPrefs.edit().putBoolean("personal_manual_only_v8_done", true).apply()
        }
        getSharedPreferences(Prefs.PREFS_MAIN, MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(preferencesListener)
        overlay = OverlayController(this)
        overlay?.onHideAssistant = {
            resetBackfill("assistant hidden")
            leaveConversation()
            main.removeCallbacks(captureRefresh)
        }
        registerSnapshotReviewReceiver()
        overlay?.onChooseContact = { openConversationTools("contacts") }
        overlay?.onEditReplyVoice = { text -> openConversationTools("edit", text) }
        overlay?.onApproveReplyVoice = { text ->
            resolveVoiceScope()?.let { scope ->
                val saved = com.jev.probe.core.ReplyVoiceStore(this).approve(scope.key, scope.name, scope.target.pkg, text)
                overlay?.toast(if (saved) "已为此联系人记住这句表达，下一组建议生效" else "保存失败，请重试")
            }
        }
        overlay?.onDismissAnalysis = {
            cancelAnalysis()
            overlay?.showIdle(currentSnapshot?.title)
        }
        overlay?.onRefreshAnalysis = {
            val purpose = lastRequestPurpose
            cancelAnalysis()
            if (purpose != ConversationPurpose.REPLY) requestConversationSuggestions(purpose)
            else overlay?.onManualAnalyze?.invoke()
        }
        overlay?.onManualAnalyze = {
            Log.i(TAG, "manual analyze callback entered")
            RuntimeState.recordAction(this, "已点击分析当前对话")
            if (historyCaptureMode) {
                overlay?.showNotice("正在补录旧消息：只保存当前可见内容，不生成回复。结束补录后再点分析。")
            } else if (analyzing || ocrBusy) {
                if (voiceOcrActive) session.token()?.let {
                    afterVoiceOcr = it to { overlay?.onManualAnalyze?.invoke(); Unit }
                }
                overlay?.showProgress(if (ocrBusy) "正在截屏识别，请稍候…" else "上一轮分析仍在进行…")
            } else {
                // A tap must read the *current* tree. A voice transcription may
                // appear without a content-changed event, leaving the cached
                // snapshot at my older message.
                val root = rootInActiveWindow
                val pkg = root?.packageName?.toString()
                val fresh = root?.let { snapshotFor(it) }
                val target = root?.let { targetFor(it, fresh) }
                // A one-sided conversation is valid. Re-running flat OCR cannot
                // establish the owner of an ambiguous wide text node: let the
                // existing speaker-confirmation flow handle that uncertainty.
                val wechatTreeUsable = pkg != PKG_WECHAT || fresh?.messages?.isNotEmpty() == true
                Log.i(TAG, "manual read pkg=$pkg messages=${fresh?.messages?.size ?: 0} " +
                    "target=${target != null} titleMatch=${target != null && fresh != null && target.matchesSnapshot(fresh)} " +
                    "treeUsable=$wechatTreeUsable latestSide=${fresh?.latestFrom} " +
                    "latestTimePresent=${fresh?.messages?.lastOrNull()?.visibleTime != null}")
                if (pkg == PKG_WECHAT && target?.title.isNullOrBlank() && wechatTitleRegion(root) != null) {
                    resolveWechatTitle(root) { overlay?.onManualAnalyze?.invoke() }
                } else if (pkg == PKG_WECHAT && fresh != null && target != null &&
                    fresh.voiceTranscriptRects.isNotEmpty()) {
                    resolveWechatTranscripts(root, fresh, target) { overlay?.onManualAnalyze?.invoke() }
                } else if (fresh != null && fresh.messages.isNotEmpty() && target != null &&
                    target.matchesSnapshot(fresh) && wechatTreeUsable) {
                    scopeReadRetry.reset("analyze")
                    observeTarget(target)
                    currentSnapshot = fresh
                    activePkg = pkg
                    lastSignature = fresh.signature()
                    overlay?.setNote(if (target.title == null)
                        "微信备注未能识别；本次仅使用当前可见消息，不关联联系人记忆。"
                    else fresh.note)
                    queueMemoryCapture(fresh, pkg,
                        historical = android.os.SystemClock.elapsedRealtime() - lastScrollAt < 3000L)
                    pendingSnapshot = fresh
                    runAnalysis()
                } else if (pkg == PKG_WECHAT && fresh != null && target != null &&
                    fresh.messages.isEmpty()) {
                    scopeReadRetry.reset("analyze")
                    ocrCaptureManual()
                } else if (pkg == PKG_WECHAT || pkg == "com.tantan.x" || pkg == "cn.soulapp.android" ||
                    pkg == "cn.neoclub.uki" || pkg == QingtengAdapter.PACKAGE) {
                    if (!retrySupportedScopeRead("analyze", pkg, root.windowId) {
                            overlay?.onManualAnalyze?.invoke()
                        }) {
                        scopeReadRetry.reset("analyze")
                        overlay?.showNotice(supportedScopeReadFailure(pkg))
                    }
                } else {
                    scopeReadRetry.reset("analyze")
                    // Use a screenshot only when the tree has no readable body.
                    ocrCaptureManual()
                }
            }
        }
        // Bubble menu: file the open conversation as a knowledge-base contact.
        // Contacts are never created automatically — this is the one-tap way in.
        overlay?.onSaveContact = saveContact@{
            if (contactBinding.contactId(session.target) != null) {
                overlay?.toast("请在选择联系人中确认备注关联，或先恢复自动匹配")
                return@saveContact
            }
            val root = rootInActiveWindow
            val pkg = root?.packageName?.toString().orEmpty()
            val target = root?.let { targetFor(it) }
            val chat = root?.let { snapshotFor(it) }
            // WeChat may hide every message node for an accessibility event.
            // Its visible title plus bottom editor still prove a chat page, so
            // do not require the message adapter to return a snapshot here.
            val wechatTitle = if (pkg == PKG_WECHAT) root?.let { wechatManualTitle(it) } else null
            val title = target?.title ?: wechatTitle
            Log.i(TAG, "save contact pkg=$pkg target=${target != null} " +
                "chat=${chat != null} wechatTitle=${wechatTitle != null} title=${title != null}")
            when {
                pkg == PKG_WECHAT && title == null && root != null && wechatTitleRegion(root) != null ->
                    resolveWechatTitle(root) { overlay?.onSaveContact?.invoke() }
                pkg.isBlank() || (pkg == PKG_WECHAT && wechatTitle == null && chat == null) ||
                    (pkg != PKG_WECHAT && (target == null || chat == null)) ->
                    overlay?.toast("请先打开受支持 App 的聊天页")
                title.isNullOrBlank() -> overlay?.toast("当前会话没有标题，存不了")
                isTransientTitle(title) -> overlay?.toast("当前会话标题还没加载出来，稍后再试")
                pkg == QingtengAdapter.PACKAGE && !QingtengAdapter.canRemember(title) ->
                    overlay?.showNotice("当前昵称只有标点，无法可靠关联记忆；请先在青藤之恋设置可区分的备注，再保存联系人。分析和开场仍可使用。")
                else -> submit {
                    val msg = try {
                        KbStore.get(this).saveOrMergeContact(title, pkg)
                    } catch (e: Exception) {
                        Log.w(TAG, "save contact failed: ${e.javaClass.simpleName}")
                        "\u4fdd\u5b58\u5931\u8d25\uff1a${e.javaClass.simpleName}"
                    }
                    val failed = msg.contains("\u5931\u8d25")
                    Log.i(TAG, "save contact completed pkg=$pkg success=${!failed}")
                    main.post { overlay?.toast(msg) }
                }
            }
        }
        overlay?.onOpenConversation = { requestConversationSuggestions(ConversationPurpose.OPENING) }
        overlay?.onInviteConversation = { requestConversationSuggestions(ConversationPurpose.INVITATION) }
        // Bubble menu: one manual screenshot + OCR, for any app at all.
        overlay?.onOcrCapture = {
            if (historyCaptureMode) overlay?.showBackfillWaiting("正在补录；如需生成回复，请先结束补录。")
            else if (rootInActiveWindow?.packageName?.toString() == "cn.neoclub.uki")
                overlay?.showNotice("Uki 当前页包含内置话术卡；为避免误当成对方消息，暂不做整屏 OCR 分析。")
            else ocrCaptureManual()
        }
        overlay?.onToggleHistoryCapture = { toggleHistoryCapture() }
        overlay?.onHistoryInteraction = {
            if (autoBackfillRunning) pauseAutoBackfill("已暂停自动翻页 可查看进度或手动上翻")
        }
        overlay?.onToggleAutoBackfill = { toggleAutoBackfill() }
        overlay?.onReviewSnapshot = { openSnapshotReview() }
        overlay?.onRetryHistoryCapture = {
            val run = backfill.active
            if (run?.target?.pkg == PKG_WECHAT) {
                backfillRetryCount = 0
                lastBackfillPage = null
                scheduleWechatBackfill(0)
            } else if (run != null) {
                val root = rootInActiveWindow
                val live = root?.let { targetFor(it) }
                val snapshot = root?.let { snapshotFor(it) }
                if (live != null && backfill.accepts(run, live) && snapshot != null && snapshot.title == live.title) {
                    lastBackfillFeedbackAt = 0L
                    overlay?.showBackfillWaiting("正在重新核对并保存当前屏…")
                    if (snapshot.messages.isEmpty()) overlay?.showBackfillWaiting("当前屏没有读到可保存的消息，请停稳后再试。")
                    else queueMemoryCapture(snapshot, live.pkg, historical = true)
                } else {
                    resetBackfill("retry target changed")
                    leaveConversation()
                    overlay?.showNotice("当前会话已变化或无法确认，补录已停止；请核对联系人后重新开始。")
                }
            }
        }
        // Keep the process at foreground importance so MIUI does not freeze us.
        runCatching { KeepAliveService.start(this) }
        // Load the bundled OCR model now, off the main thread: the first
        // recognize() otherwise pays for it inside the screenshot callback.
        submit { MlKitOcr.warmUp() }
        // HyperOS may kill and restart us. On (re)connect, proactively re-show the
        // bubble for whatever chat is already open, so it comes back on its own
        // instead of waiting for the user to scroll.
        main.postDelayed({ if (prefs.enabled) runCatching { maybeCapture() } }, 900)
        // Migrate local memory on the disk worker, before queued screen captures.
        // Opening the database here also makes migration independent of a tap.
        memoryWorker.execute {
            runCatching { ConversationLedger.get(this).writableDatabase }
                .onFailure { Log.e(TAG, "conversation memory migration failed", it) }
        }
        Log.i(TAG, "capture service connected")
    }

    private fun resetBackfill(reason: String = "conversation invalidated"): BackfillSession.Run? {
        backfillSavedSignature = null
        if (historyCaptureMode) Log.i(TAG, "backfill reset: $reason")
        stopAutoBackfill()
        main.removeCallbacks(backfillScan)
        lastBackfillPage = null
        backfillRetryCount = 0
        backfillTreeRetryCount = 0
        overlay?.stopBackfill()
        return backfill.stop()
    }

    private fun toggleHistoryCapture() {
        if (contactBinding.contactId(session.target) != null) {
            overlay?.toast("临时选择只用于分析；请先确认备注关联或恢复自动匹配，再补录")
            return
        }
        // Stopping must work even after a page/title disappeared. Never require
        // a valid cached snapshot to stop an active recording session.
        if (historyCaptureMode) {
            val ended = resetBackfill("user stop") ?: return
            backfillFinishedNotice = ended.target to (android.os.SystemClock.elapsedRealtime() + 5000L)
            overlay?.showNotice("补录已停止，正在汇总已保存的消息…")
            memoryWorker.execute {
                val stats = KbStore.get(this).captureStats(ended.contactId)
                main.post {
                    if (!destroyed && !historyCaptureMode) overlay?.showNotice(
                        "补录已结束 ${ended.progress.label()}。联系人累计 ${stats.lines} 条。只代表核对过的可见页面，不代表全部历史已补齐。")
                }
            }
            Log.i(TAG, "backfill stopped by user")
            return
        }
        val root = rootInActiveWindow
        val pkg = root?.packageName?.toString()
        if (root == null || pkg !in setOf(PKG_WECHAT, "com.tantan.x", QingtengAdapter.PACKAGE, SoulAdapter.PACKAGE)) {
            overlay?.showNotice("请先打开微信、牵手、Soul 或青藤之恋的聊天页，再开始补录。")
            return
        }
        if (ocrBusy) { overlay?.showProgress("正在核对聊天标题，请稍候…"); return }
        cancelAnalysis()
        val target = targetFor(root)
        if ((target == null || target.title.isNullOrBlank()) &&
            pkg == PKG_WECHAT && wechatTitleRegion(root) != null) {
            backfillStarting = true
            resolveWechatTitle(root, onProblem = { _, _ -> backfillStarting = false }) {
                backfillStarting = false
                toggleHistoryCapture()
            }
            return
        }
        val snapshot = snapshotFor(root)
        if (target == null || target.title.isNullOrBlank() || snapshot == null ||
            snapshot.title.isNullOrBlank() || snapshot.title != target.title) {
            overlay?.showNotice("\u8fd8\u4e0d\u80fd\u786e\u8ba4\u8054\u7cfb\u4eba\u8eab\u4efd\uff1b\u672c\u6b21\u672a\u5f00\u59cb\u8865\u5f55\u3002\u8bf7\u5148\u8bc6\u522b\u5e76\u4fdd\u5b58\u5f53\u524d\u5907\u6ce8\u5bf9\u5e94\u7684\u8054\u7cfb\u4eba\u3002")
            return
        }
        if (pkg == QingtengAdapter.PACKAGE && !QingtengAdapter.canRemember(target.title)) {
            overlay?.showNotice("当前昵称只有标点，补录尚未开始；请先在青藤之恋设置可区分的备注，再保存联系人并开启记忆。")
            return
        }
        val contact = KbStore.get(this).findContact(snapshot.title, target.pkg)
        if (contact == null || !contact.apps.contains(target.pkg)) {
            overlay?.showNotice("\u5f53\u524d\u5fae\u4fe1\u5907\u6ce8\u672a\u5339\u914d\u5230\u8054\u7cfb\u4eba\uff0c\u8bf7\u5148\u70b9\u201c\u628a\u5f53\u524d\u4f1a\u8bdd\u5b58\u4e3a\u8054\u7cfb\u4eba\u201d\uff1b\u672c\u6b21\u672a\u5f00\u59cb\u8865\u5f55\u3002")
            return
        }
        if (!contact.memoryEnabled) {
            overlay?.showNotice("联系人「${contact.name}」的聊天记忆仍是关闭的，请在联系人页开启并保存。")
            return
        }
        observeTarget(target)
        currentSnapshot = snapshot
        activePkg = pkg
        lastSignature = snapshot.signature()
        val run = backfill.start(target, contact.id)
        if (run == null) {
            overlay?.showNotice("\u8054\u7cfb\u4eba\u8eab\u4efd\u672a\u786e\u8ba4\uff0c\u8865\u5f55\u672a\u5f00\u59cb\u3002")
            return
        }
        backfillFinishedNotice = null
        lastBackfillPage = null
        lastBackfillPages = -1
        lastBackfillFeedbackAt = 0L
        val stats = KbStore.get(this).captureStats(contact.id)
        overlay?.showNotice("已开始为当前联系人补录。")
        overlay?.showBackfillProgress(stats.pages, stats.lines, false,
            status = "正在核对当前屏 尚未保存", runLabel = run.progress.label())
        overlay?.showBackfillWaiting("正在保存当前屏；停稳后再继续上翻。")
        Log.i(TAG, "backfill started pkg=$pkg")
        if (pkg == PKG_WECHAT) scanWechatBackfill()
        else queueMemoryCapture(snapshot, pkg, true)
    }

    private fun toggleAutoBackfill() {
        autoIdentityRetries = 0
        ignoreTouchPauseUntil = android.os.SystemClock.elapsedRealtime() + 500L
        // The interaction-start event for this very button schedules a pause;
        // cancel that deferred pause before applying the explicit button action.
        main.removeCallbacks(manualTouchPause)
        if (autoBackfillRunning) {
            pauseAutoBackfill("\u5df2\u6682\u505c\u81ea\u52a8\u4e0a\u6ed1\uff1b\u8865\u5f55\u4f1a\u8bdd\u4ecd\u4f1a\u7ee7\u7eed\uff0c\u4f60\u53ef\u624b\u52a8\u7ffb\u9875")
            return
        }
        val run = backfill.active
        if (run == null) {
            overlay?.showNotice("\u8bf7\u5148\u5f00\u59cb\u8865\u5f55\u5e76\u4fdd\u6301\u5728\u804a\u5929\u9875")
            return
        }
        autoBackfillRunning = true
        autoBackfillAwaitingCapture = false
        autoBackfillExpectedPage = null
        autoBackfillStalls = 0
        autoScrollRetryPlan.reset()
        autoBackfillDuplicateStreak.reset()
        autoBackfillLastMethod = AutoScrollMethod.ACCESSIBILITY
        overlay?.showAutoBackfillState("\u6682\u505c\u81ea\u52a8\u4e0a\u6ed1", "\u81ea\u52a8\u7ffb\u9875\u5df2\u5f00\u542f\uff1b\u4f60\u4e00\u64cd\u4f5c\u5c4f\u5e55\u5c31\u4f1a\u6682\u505c")
        main.removeCallbacks(autoBackfillTick)
        overlay?.collapseForAutomaticBackfill()
        main.postDelayed(autoBackfillTick, 1200L)
    }

    private fun pauseAutoBackfill(message: String) {
        Log.i(TAG, "automatic backfill paused: $message")
        stopAutoBackfill()
        overlay?.showAutoBackfillState("\u7ee7\u7eed\u81ea\u52a8\u4e0a\u6ed1", message, paused = true)
    }

    private fun stopAutoBackfill() {
        autoBackfillRunning = false
        autoBackfillAwaitingCapture = false
        autoBackfillExpectedPage = null
        autoBackfillStalls = 0
        autoScrollRetryPlan.reset()
        autoBackfillDuplicateStreak.reset()
        autoBackfillLastMethod = AutoScrollMethod.ACCESSIBILITY
        main.removeCallbacks(autoBackfillTick)
        main.removeCallbacks(autoBackfillTimeout)
        main.removeCallbacks(manualTouchPause)
    }

    private fun performAutoBackfillStep() {
        val run = backfill.active ?: run { pauseAutoBackfill("\u8865\u5f55\u4f1a\u8bdd\u5df2\u7ed3\u675f"); return }
        if (!autoBackfillRunning || autoBackfillAwaitingCapture) return
        val root = rootInActiveWindow
        val windowState = backfill.windowState(run, root?.packageName?.toString(), root?.windowId)
        if (windowState == BackfillSession.WindowState.WAIT || ocrBusy) {
            waitForBackfillVerification(run); return
        }
        if (windowState == BackfillSession.WindowState.LEFT) {
            resetBackfill("automatic scroll left target window")
            overlay?.showNotice("\u804a\u5929\u7a97\u53e3\u5df2\u53d8\u5316\uff0c\u81ea\u52a8\u8865\u5f55\u5df2\u505c\u6b62")
            return
        }
        if (root == null) { waitForBackfillVerification(run); return }
        val snapshot = snapshotFor(root)
        val liveTarget = targetFor(root, snapshot)
        if (snapshot == null || liveTarget == null || snapshot.title.isNullOrBlank()) {
            waitForBackfillVerification(run)
            return
        }
        if (!backfill.accepts(run, liveTarget) ||
            snapshot.title != run.target.title) {
            pauseAutoBackfill("\u65e0\u6cd5\u518d\u6b21\u786e\u8ba4\u5f53\u524d\u8054\u7cfb\u4eba\uff0c\u81ea\u52a8\u4e0a\u6ed1\u5df2\u6682\u505c\uff0c\u672c\u9875\u672a\u91c7\u96c6")
            return
        }
        val list = findScrollableChatList(root)
        if (snapshot.signature() != backfillSavedSignature) {
            waitForBackfillVerification(run)
            return
        }
        autoIdentityRetries = 0
        if (list == null) {
            pauseAutoBackfill("\u672a\u627e\u5230\u53ef\u786e\u8ba4\u7684\u804a\u5929\u5217\u8868\uff0c\u4e3a\u907f\u514d\u8bef\u6ed1\u5df2\u6682\u505c")
            return
        }
        val pageKey = if (run.target.pkg == PKG_WECHAT) wechatPageKey(root, snapshot) else snapshot.signature()
        val listBounds = Rect().also(list::getBoundsInScreen)
        val preferredMethod = autoScrollRetryPlan.methodForNextAttempt()
        ignoreTouchPauseUntil = android.os.SystemClock.elapsedRealtime() + 1400L
        var methodUsed = preferredMethod
        var scrollAccepted = if (preferredMethod == AutoScrollMethod.ACCESSIBILITY)
            list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) else false
        if (!scrollAccepted) {
            methodUsed = AutoScrollMethod.GESTURE
            scrollAccepted = dispatchAutoBackfillGesture(listBounds)
            if (!scrollAccepted && preferredMethod == AutoScrollMethod.GESTURE) {
                methodUsed = AutoScrollMethod.ACCESSIBILITY
                scrollAccepted = list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            }
        }
        autoBackfillLastMethod = methodUsed
        Log.i(TAG, "automatic backfill target id=${list.viewIdResourceName ?: "none"} " +
            "bounds=${listBounds.left},${listBounds.top},${listBounds.right},${listBounds.bottom} " +
            "preferred=$preferredMethod used=$methodUsed accepted=$scrollAccepted " +
            "pageChars=${pageKey?.length ?: 0}")
        if (!scrollAccepted) {
            handleAutoBackfillNoProgress("滚动动作未被聊天列表接受")
            return
        }
        autoBackfillAwaitingCapture = true
        autoBackfillExpectedPage = pageKey
        main.removeCallbacks(autoBackfillTimeout)
        main.postDelayed(autoBackfillTimeout, 4500L)
        Log.i(TAG, "automatic backfill scroll requested pkg=${run.target.pkg}")
    }

    private fun waitForBackfillVerification(run: BackfillSession.Run) {
        if (!autoBackfillRunning || !backfill.isCurrent(run)) return
        if (++autoIdentityRetries > 8) {
            pauseAutoBackfill("本屏身份或保存尚未确认 自动翻页已暂停 请点重试当前屏")
            return
        }
        overlay?.showBackfillWaiting("正在核实联系人并保存当前屏 $autoIdentityRetries/8 确认后继续自动翻页")
        if (run.target.pkg == PKG_WECHAT) scheduleWechatBackfill(0)
        main.removeCallbacks(autoBackfillTick)
        main.postDelayed(autoBackfillTick, 900L)
    }

    /** Fallback for chat lists that claim scrollability but reject accessibility scroll actions. */
    private fun dispatchAutoBackfillGesture(bounds: Rect): Boolean {
        if ((serviceInfo.capabilities and android.accessibilityservice.AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES) == 0) {
            Log.w(TAG, "automatic backfill gesture capability unavailable; use list action")
            return false
        }
        val swipe = AutoScrollTargetPolicy.safeUpwardSwipe(
            AutoScrollTargetPolicy.Bounds(bounds.left, bounds.top, bounds.right, bounds.bottom),
            resources.displayMetrics.heightPixels,
            overlay?.visibleBoundsOnScreen()?.let { AutoScrollTargetPolicy.Bounds(it.left, it.top, it.right, it.bottom) }
        ) ?: return false
        val path = Path().apply {
            moveTo(swipe.x, swipe.startY)
            lineTo(swipe.x, swipe.endY)
        }
        // Keep the same pointer down briefly at the end before lifting it.
        // Releasing a fast drag directly makes RecyclerView fling past the
        // planned overlap, despite an apparently correct gesture distance.
        val moving = GestureDescription.StrokeDescription(path, 0L, 620L, true)
        val holding = moving.continueStroke(Path().apply { moveTo(swipe.x, swipe.endY) }, 0L, 180L, false)
        val gesture = GestureDescription.Builder().addStroke(moving).build()
        val finish = GestureDescription.Builder().addStroke(holding).build()
        val run = backfill.active
        fun interrupted(message: String) {
            if (autoBackfillRunning && run != null && backfill.isCurrent(run)) pauseAutoBackfill(message)
        }
        // Accessibility reports our injected gesture as a touch on some ROMs.
        // Do not mistake that for the user's pause gesture.
        ignoreTouchPauseUntil = android.os.SystemClock.elapsedRealtime() + 1400L
        return dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                // Always release the held pointer; this does not initiate a new
                // scroll, and never resumes a stopped recording run.
                val accepted = dispatchGesture(finish, object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        Log.i(TAG, "automatic backfill gesture completed without fling")
                    }
                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        interrupted("滑动被打断 自动翻页已暂停 请核对当前屏")
                    }
                }, main)
                if (!accepted) interrupted("系统未完成滑动收尾 自动翻页已暂停")
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.i(TAG, "automatic backfill fallback gesture cancelled")
                interrupted("滑动被打断 自动翻页已暂停 请核对当前屏")
            }
        }, main)
    }

    private fun handleAutoBackfillNoProgress(reason: String) {
        autoBackfillAwaitingCapture = false
        autoBackfillExpectedPage = null
        val retry = autoScrollRetryPlan.noProgress(autoBackfillLastMethod)
        autoBackfillStalls = autoScrollRetryPlan.unchangedPages
        Log.i(TAG, "automatic backfill no page change attempt=$autoBackfillStalls method=$autoBackfillLastMethod reason=$reason")
        if (!retry) {
            pauseAutoBackfill("连续尝试不同滚动方式仍没有新页面；自动上滑已暂停，补录仍开启。请手动上滑确认是否已到历史顶部。")
        } else {
            overlay?.showAutoBackfillState("暂停自动上滑", "当前页未变化；将切换滚动方式重试（$autoBackfillStalls/4）")
            main.postDelayed(autoBackfillTick, 700L)
        }
    }

    /** Pick the largest scrollable node contained in the chat message viewport. */
    private fun findScrollableChatList(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val screen = resources.displayMetrics
        // The WeChat message RecyclerView ends immediately above its composer;
        // use that live boundary because MIUI's display height differs from the
        // screen-coordinate space returned by accessibility nodes.
        val editorTop = findEditable(root)?.let { Rect().also(it::getBoundsInScreen).top }
        val viewportBottom = editorTop?.takeIf { it > 0 }
            ?: (screen.heightPixels * 0.95f).toInt()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        for (i in 0 until root.childCount) root.getChild(i)?.let(stack::addLast)
        var best: AccessibilityNodeInfo? = null
        var bestArea = 0L
        var visited = 0
        while (stack.isNotEmpty() && visited++ < 2500) {
            val node = stack.removeLast()
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            val area = AutoScrollTargetPolicy.areaIfSafe(
                visible = node.isVisibleToUser,
                enabled = node.isEnabled,
                scrollable = node.isScrollable,
                editable = node.isEditable,
                left = bounds.left,
                top = bounds.top,
                right = bounds.right,
                bottom = bounds.bottom,
                screenWidth = screen.widthPixels,
                screenHeight = screen.heightPixels,
                viewportTop = (screen.heightPixels * 0.08f).toInt(),
                viewportBottom = viewportBottom
            )
            if (area != null && area > bestArea) { best = node; bestArea = area }
            for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let(stack::addLast)
        }
        return best
    }

    private fun scheduleWechatBackfill(delayMs: Long = 550L) {
        // Do not let repeated animation/content events postpone the scan forever.
        if (backfill.active?.target?.pkg == PKG_WECHAT && !main.hasCallbacks(backfillScan))
            main.postDelayed(backfillScan, delayMs)
    }

    private fun scanWechatBackfill() {
        val run = backfill.active ?: return
        if (run.target.pkg != PKG_WECHAT) return
        val root = rootInActiveWindow
        val windowState = backfill.windowState(run, root?.packageName?.toString(), root?.windowId)
        if (windowState == BackfillSession.WindowState.WAIT) {
            // Xiaomi's transient system overlay can become the active window
            // during a scroll. Pause reads; never capture that window as chat.
            wechatTitleGuard.invalidate()
            overlay?.showBackfillWaiting("系统窗口暂时遮挡聊天，这一屏尚未保存；返回聊天后继续。")
            if (backfillTreeRetryCount++ < 3) scheduleWechatBackfill(900L)
            Log.i(TAG, "backfill paused for system window")
            return
        }
        if (root != null && windowState == BackfillSession.WindowState.LEFT) {
            resetBackfill("left window pkg=${root.packageName} window=${root.windowId}")
            leaveConversation()
            overlay?.hide()
            return
        }
        if (root == null || wechatTitleRegion(root) == null) {
            // A recycled/incomplete tree while scrolling is not proof of leaving.
            // Retain consent but write nothing until the live name is verified.
            overlay?.showBackfillWaiting("页面暂未读完整，这一屏尚未保存；停稳后重试。")
            if (backfillTreeRetryCount++ < 3) scheduleWechatBackfill(900L)
            Log.i(TAG, "backfill waiting: incomplete tree attempt=$backfillTreeRetryCount")
            return
        }
        if (backfillTreeRetryCount > 0) {
            backfillTreeRetryCount = 0
            backfillRetryCount = 0
        }
        if (android.os.SystemClock.elapsedRealtime() - lastScrollAt < 450L) {
            scheduleWechatBackfill(450L)
            return
        }
        val page = wechatPageKey(root) ?: return
        // The page key alone cannot establish identity after a navigation event.
        if (page == lastBackfillPage && targetFor(root) == run.target) {
            if (lastBackfillPages >= 0)
                overlay?.showBackfillProgress(lastBackfillPages, lastBackfillLines, false)
            Log.i(TAG, "backfill page unchanged chars=${page.length} autoAwaiting=$autoBackfillAwaitingCapture")
            return
        }
        if (ocrBusy) { scheduleWechatBackfill(700L); return }
        val target = targetFor(root)
        if (target == null || target.title.isNullOrBlank()) {
            overlay?.showBackfillWaiting("\u5f53\u524d\u5907\u6ce8\u6682\u65f6\u65e0\u6cd5\u786e\u8ba4\uff1b\u672c\u5c4f\u672a\u4fdd\u5b58\uff0c\u6b63\u5728\u91cd\u8bd5\u8bc6\u522b\u3002")
            resolveWechatTitle(root, backgroundRun = run, onProblem = { message, retry ->
                if (backfill.isCurrent(run)) {
                    overlay?.showBackfillWaiting("\u672c\u5c4f\u672a\u4fdd\u5b58\uff1a$message")
                    if (retry && backfillRetryCount++ < 3) scheduleWechatBackfill(1200L)
                }
            }) {
                if (backfill.isCurrent(run)) scanWechatBackfill()
            }
            return
        }
        if (!backfill.accepts(run, target)) {
            resetBackfill("verified a different contact")
            leaveConversation()
            overlay?.showNotice("检测到会话身份变化，补录已停止；新页面未写入原联系人的记忆。请核对联系人后重新开始。")
            Log.i(TAG, "backfill stopped: conversation changed")
            return
        }
        val snapshot = snapshotFor(root) ?: return
        if (snapshot.title.isNullOrBlank() || snapshot.title != target.title) {
            overlay?.showBackfillWaiting("\u672c\u5c4f\u8054\u7cfb\u4eba\u8eab\u4efd\u672a\u786e\u8ba4\uff0c\u5c1a\u672a\u4fdd\u5b58\uff1b\u8bf7\u505c\u7a33\u540e\u91cd\u8bd5\u3002")
            scheduleWechatBackfill(1200L)
            return
        }
        if (snapshot.messages.isEmpty()) {
            overlay?.showBackfillWaiting("\u8fd9\u4e00\u5c4f\u6ca1\u6709\u8bfb\u5230\u53ef\u4fdd\u5b58\u7684\u6587\u5b57\uff0c\u505c\u7a33\u540e\u91cd\u8bd5\u6216\u7ee7\u7eed\u7ffb\u9875\u3002")
            return
        }
        if (snapshot.voiceTranscriptRects.isNotEmpty()) {
            resolveWechatTranscripts(root, snapshot, target, run) {
                if (backfill.isCurrent(run)) scanWechatBackfill()
            }
            return
        }
        // Screen contents may move; identity is carried only across strong page
        // overlap. Missing or conflicting evidence never reaches persistent storage.
        currentSnapshot = snapshot
        lastBackfillPage = page
        backfillRetryCount = 0
        queueMemoryCapture(snapshot, PKG_WECHAT, historical = true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val eventPkg = event.packageName?.toString()
        val type = event.eventType
        // Scroll events can arrive after our injected gesture has finished.
        // They do not prove human interaction; the bubble's explicit touch and
        // genuine touch-start events pause automation. Every moved page still
        // requires fresh identity verification before another gesture or save.
        if (type == AccessibilityEvent.TYPE_TOUCH_INTERACTION_START && autoBackfillRunning &&
            android.os.SystemClock.elapsedRealtime() >= ignoreTouchPauseUntil) {
            main.removeCallbacks(manualTouchPause)
            main.postDelayed(manualTouchPause, 140L)
        }
        // Rendering our panel emits content/scroll events too. Feeding those
        // back into capture can erase the very button the user is pressing.
        // Keep window-state events so opening our real settings still hides it.
        if (eventPkg == packageName && type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        RuntimeState.lastEventPackage = eventPkg
        if (!prefs.enabled || prefs.overlayHidden) { leaveConversation(); overlay?.hide(); return }
        // Review owns this session until its result has re-verified the returning
        // chat. Window refreshes during our Activity/return animation must not
        // clear the target before the broadcast or turn invitations into replies.
        if (reviewActivityActive) {
            val fg = rootInActiveWindow?.packageName?.toString()
            if (fg == null || fg == packageName || fg == "com.android.systemui" || fg == reviewTarget?.pkg) {
                if (fg == packageName) overlay?.setHiddenForShot(true)
                return
            }
            reviewActivityActive = false
            reviewRequestId = null
            reviewTarget = null
        }
        // A clock/status notification or keyboard redraw is not a chat update.
        // Actual foreground transitions still arrive through window events below.
        if (type in setOf(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                AccessibilityEvent.TYPE_VIEW_SCROLLED) && eventPkg !in adapters) return

        // Backfill gets a throttled scan instead of repeatedly walking WeChat's
        // large accessibility tree on every animation frame. Null/partial trees
        // are handled by the scanner; they must not clear the recording session.
        if (backfill.active?.target?.pkg == PKG_WECHAT) {
            if (eventPkg == packageName && rootInActiveWindow?.packageName?.toString() != packageName) return
            if (type == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
                lastScrollAt = android.os.SystemClock.elapsedRealtime()
                backfillRetryCount = 0
            }
            if (type in setOf(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                    AccessibilityEvent.TYPE_VIEW_SCROLLED)) {
                if (eventPkg == PKG_WECHAT)
                    wechatTitleGuard.invalidate()
                if (eventPkg == PKG_WECHAT)
                    overlay?.showBackfillWaiting("当前屏尚未保存；停稳后自动核对联系人并保存。")
                scheduleWechatBackfill()
            }
            return
        }

        // WeChat can draw its title without exposing text. A screenshot-derived
        // name must never survive navigation, scrolling, or a content change.
        // Our own overlay events were excluded above and do not expire it.
        if ((eventPkg == PKG_WECHAT && type in setOf(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                AccessibilityEvent.TYPE_VIEW_SCROLLED)) ||
            (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && eventPkg != packageName &&
                rootInActiveWindow?.packageName?.toString() != PKG_WECHAT)) {
            wechatTitleGuard.invalidate()
        }

        // The result handler verifies the returning page before binding anything.
        // Do not let a partial return frame discard its original session or record
        // visible messages under an unconfirmed contact in the meantime.
        if (toolsActivityActive && rootInActiveWindow?.packageName?.toString() in
            setOf(packageName, toolsTarget?.pkg)) return

        if (type == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            lastScrollAt = android.os.SystemClock.elapsedRealtime()
            if (eventPkg == SoulAdapter.PACKAGE) {
                // Do not record intermediate RecyclerView frames as extra pages.
                // Only scrolling extends this wait; clocks/typing do not delay it.
                soulScrollSettlesAt = lastScrollAt + 450L
                main.removeCallbacks(captureRefresh)
                scheduleCaptureRefresh(450L)
                if (historyCaptureMode) overlay?.showBackfillWaiting("当前屏尚未保存；停稳后自动保存可见消息。")
                return
            }
        }
        // Decide "did we leave the chat app" from the REAL active window, not the
        // event's package. The event package can be an IME (e.g. com.tencent.wetype)
        // or the status bar while the chat app is still foreground — keying off it
        // made the bubble flicker (hide → re-show → hide…). rootInActiveWindow stays
        // on the chat app while the keyboard is up, so this is stable.
        //
        // An app with no adapter is NOT a reason to take the bubble away: the only
        // way into DingTalk / Telegram / anything else is the bubble menu's
        // "截屏识别一次", and a bubble that is gone cannot be tapped. So we park
        // the idle bubble there instead — still no automatic capture, no analysis.
        // The bubble does come off for places where it would only be in the way:
        // our own settings screens, the launcher, and the system UI.
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val fg = rootInActiveWindow?.packageName?.toString()
            if (prefs.collapseOnAppSwitch && fg != null && fg != "com.android.systemui" && fg != session.target?.pkg)
                overlay?.collapseForNavigation()
            if (toolsActivityActive && fg == packageName) {
                overlay?.setHiddenForShot(true)
                return
            }
            if (reviewActivityActive && fg == packageName) {
                overlay?.setHiddenForShot(true)
                return
            }
            if (reviewActivityActive && fg != packageName) {
                reviewActivityActive = false
                if (fg == reviewTarget?.pkg) overlay?.setHiddenForShot(false)
                else {
                    reviewRequestId = null
                    reviewTarget = null
                }
            }
            if (fg != null && fg !in adapters) {
                val target = rootInActiveWindow?.let { targetFor(it) }
                if (session.target != target) leaveConversation()
                foregroundPkg = fg
                val drop = fg == packageName ||
                    fg.contains("launcher", ignoreCase = true) ||
                    fg == "com.miui.home" ||
                    fg == "com.android.systemui"
                if (drop) overlay?.hide() else overlay?.showIdle(null)
                return
            }
        }

        // An own window-state event with another app still active came from
        // the overlay rather than an activity switch.
        if (eventPkg == packageName) return

        when (type) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                main.removeCallbacks(captureRefresh)
                maybeCapture()
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> scheduleCaptureRefresh()
        }
    }

    private fun maybeCapture() {
        if (destroyed || !prefs.enabled || prefs.overlayHidden) { overlay?.hide(); return }
        if (reviewActivityActive) return
        if (voiceOcrActive) return
        if (backfill.active?.target?.pkg == PKG_WECHAT) {
            scheduleWechatBackfill()
            return
        }
        val root = rootInActiveWindow ?: run { leaveConversation(); overlay?.hide(); return }
        val pkg = root.packageName?.toString()
        if (toolsActivityActive && pkg in setOf(packageName, toolsTarget?.pkg)) return
        val scrollWait = soulScrollSettlesAt - android.os.SystemClock.elapsedRealtime()
        if (pkg == SoulAdapter.PACKAGE && scrollWait > 0) {
            scheduleCaptureRefresh(scrollWait)
            return
        }
        // Keep the completion receipt readable despite late same-window events.
        // A navigation away still takes the normal hide/invalidation path.
        backfillFinishedNotice?.let { (target, until) ->
            if (android.os.SystemClock.elapsedRealtime() < until &&
                pkg == target.pkg && root.windowId == target.windowId) return
            backfillFinishedNotice = null
        }
        // Between title OCR and its start callback, do not accidentally record
        // the first historical screen as ordinary passive/new conversation data.
        if (backfillStarting) return
        // Apps with no adapter are never handled automatically (v1.3 revision):
        // the only way in for them is the bubble menu's "截屏识别一次".
        if (adapters[pkg] == null) {
            if (session.target != null && session.target != targetFor(root)) leaveConversation()
            val drop = pkg == null || pkg == packageName ||
                pkg.contains("launcher", ignoreCase = true) ||
                pkg == "com.miui.home" || pkg == "com.android.systemui"
            if (drop) overlay?.hide() else {
                foregroundPkg = pkg
                overlay?.showIdle(null)
            }
            return
        }
        // Outside a chat window (the conversation list, a profile, settings…) the
        // adapter returns null. That is NOT a reason to show nothing: an adapted
        // app must behave at least as well as an unadapted one, which parks an idle
        // bubble so the menu stays reachable. Without this, opening QQ / Feishu on
        // their list screen produced no bubble at all.
        val readStart = android.os.SystemClock.elapsedRealtime()
        val rawSnapshot = snapshotFor(root)
        if (rawSnapshot == null) {
            if (preservePinnedDuringUnreadableCapture(root)) return
            leaveConversation(); overlay?.showIdle(null); return
        }
        val target = targetFor(root, rawSnapshot)
        if (target == null) {
            if (preservePinnedDuringUnreadableCapture(root)) return
            leaveConversation(); overlay?.showIdle(null); return
        }
        if (pinnedAnalysisTarget.isTransientRead(target)) {
            overlay?.showSnapshotUpdate()
            return
        }
        observeTarget(target)
        // Use only this window's title; never inherit another conversation's title.
        val snapshot = rawSnapshot
        if (!prefs.isAllowed(snapshot.title)) { leaveConversation(); overlay?.hide(); return }
        // In a chat window but the tree holds no text (Feishu draws its bodies,
        // WeChat hides them when the disguise fails) → screenshot + OCR, subject
        // to ScreenCapture's own >=1s throttle and failure backoff.
        if (snapshot.messages.isEmpty()) {
            // In a chat window, but the tree carries no text (Feishu draws its
            // message bodies). Park the bubble BEFORE attempting OCR, so the user
            // still has something to tap when OCR is off, deduped, or comes back
            // empty — previously all three cases left the screen with no bubble.
            if (overlay?.isShowing() != true) overlay?.showIdle(snapshot.title)
            // Wait for device validation before unattended WeChat screenshots.
            // The bubble's explicit "截屏识别一次" remains available.
            if (prefs.ocrFallback && pkg != PKG_WECHAT &&
                pkg != "cn.soulapp.android" && pkg != "com.tantan.x" &&
                pkg != "cn.neoclub.uki" && pkg != QingtengAdapter.PACKAGE) {
                // Gate BEFORE the shot, not after the OCR. Feishu's tree is empty
                // on every content-changed event, and a successful shot resets the
                // failure backoff — so without this the caret blinking or an
                // "online" badge flipping keeps a screenshot going out every
                // second forever. The picture can only differ if the bubbles moved
                // or the conversation changed, and that is exactly what the
                // signature measures.
                val sig = ocrSignature(pkg ?: "", snapshot.title, snapshot.bubbleRects)
                if (sig == lastOcrSignature && overlay?.isShowing() == true) return
                if (ocrBusy) return
                lastOcrSignature = sig
                ocrCapture(snapshot.title, snapshot.bubbleRects, pkg ?: "", manual = false)
            }
            return
        }

        // Switching to another adapted app resets the dedupe signature, so two apps
        // whose last few messages happen to match cannot swallow each other.
        if (pkg != activePkg) { activePkg = pkg; lastSignature = "" }

        currentSnapshot = snapshot
        val sig = snapshot.signature()
        val showing = overlay?.isShowing() == true
        // Same content and the bubble is already up → nothing to do.
        if (sig == lastSignature && showing) return
        // Same content but the bubble is gone (killed by MIUI, or we left and came
        // back) → just put the bubble back, do NOT re-analyze (saves tokens/time).
        if (sig == lastSignature && !showing) { overlay?.showIdle(snapshot.title); return }
        // New visible rows are data changes, not a new contact. Retain both
        // in-flight work and completed suggestions until an explicit action.
        if (pinnedAnalysisTarget.protects(target)) {
            lastSignature = sig
            captureWhilePinned(snapshot, pkg ?: return)
            overlay?.showSnapshotUpdate()
            return
        }
        cancelAnalysis()
        lastSignature = sig
        Log.d(TAG, "snapshot[$pkg] n=${snapshot.messages.size} " +
            snapshot.messages.takeLast(6).joinToString(" | ") { "${it.side}:${it.kind}:${it.text.length}" }) // no message content
        Log.i(TAG, "capture read pkg=$pkg duration=${android.os.SystemClock.elapsedRealtime() - readStart}ms")

        // Trigger only when the newest message is from the other person, and only
        // if auto-analyze is on. Otherwise show the idle bubble (tap to analyze).
        // Scrolling exposes old messages as if they had just arrived. Never
        // auto-analyze a newly visible old row as a fresh reply request.
        val justScrolled = android.os.SystemClock.elapsedRealtime() - lastScrollAt < 3000L
        queueMemoryCapture(snapshot, pkg, historical = historyCaptureMode || justScrolled)
        if (snapshot.latestFrom != "other" || !prefs.autoAnalyze || justScrolled || historyCaptureMode) {
            overlay?.showIdle(snapshot.title); return
        }

        pendingSnapshot = snapshot
        main.removeCallbacks(debounce)
        main.postDelayed(debounce, 800) // debounce bursts of content-changed events
    }

    private fun onBackfillCaptureCompleted(run: BackfillSession.Run, captured: ChatSnapshot,
                                           result: com.jev.probe.core.kb.CaptureResult) {
        if (!autoBackfillRunning || !backfill.isCurrent(run) || !autoBackfillAwaitingCapture) return
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != run.target.pkg || root.windowId != run.target.windowId) return
        val live = snapshotFor(root) ?: return
        if (live.title.isNullOrBlank() || live.signature() != captured.signature()) {
            if (run.target.pkg == PKG_WECHAT) scheduleWechatBackfill(0)
            return
        }
        val livePage = if (run.target.pkg == PKG_WECHAT) wechatPageKey(root, live) else live.signature()
        when (classifyBackfillPageTransition(
            targetMatches = live.title == run.target.title,
            expectedPage = autoBackfillExpectedPage,
            observedPage = livePage
        )) {
            BackfillPageTransition.TARGET_CHANGED -> {
                pauseAutoBackfill("\u6eda\u52a8\u540e\u65b0\u9875\u672a\u80fd\u786e\u8ba4\uff0c\u81ea\u52a8\u4e0a\u6ed1\u5df2\u6682\u505c")
                return
            }
            // Accessibility may report a scroll action before the list lays out
            // its next page. Keep the original timeout armed instead of treating
            // this transient observation as the end of history.
            BackfillPageTransition.WAITING -> {
                Log.i(TAG, "automatic backfill capture arrived before page transition; keep waiting")
                return
            }
            BackfillPageTransition.ADVANCED -> Unit
        }
        autoBackfillAwaitingCapture = false
        autoBackfillExpectedPage = null
        autoBackfillStalls = 0
        autoIdentityRetries = 0
        autoScrollRetryPlan.progress()
        main.removeCallbacks(autoBackfillTimeout)
        if (autoBackfillDuplicateStreak.accept(result.added, result.timeUpdated)) {
            val count = autoBackfillDuplicateStreak.consecutivePages
            pauseAutoBackfill("连续 $count 屏都是已存消息，自动上滑已暂停；不代表历史补齐。要找更早遗漏请点继续，结束补录由你决定。")
            return
        }
        overlay?.showAutoBackfillState("\u6682\u505c\u81ea\u52a8\u4e0a\u6ed1", "\u672c\u5c4f\u5df2\u6838\u5bf9\uff0c\u7a0d\u540e\u7ee7\u7eed\u4e0a\u7ffb\uff1b\u89e6\u6478\u5c4f\u5e55\u53ef\u968f\u65f6\u6682\u505c")
        main.postDelayed(autoBackfillTick, 1300L)
    }

    private fun pinAnalysis(target: ConversationSession.Target) {
        pinnedAnalysisTarget.pin(target)
        pinnedContextReady = false
        deferredPinnedCapture = null
        overlay?.clearSnapshotUpdate()
    }

    /** Do not let newly arrived lines race into the context for this request. */
    private fun captureWhilePinned(snapshot: ChatSnapshot, pkg: String) {
        if (pinnedContextReady) queueMemoryCapture(snapshot, pkg,
            historical = android.os.SystemClock.elapsedRealtime() - lastScrollAt < 3000L)
        else deferredPinnedCapture = DeferredPinnedCapture(snapshot, pkg)
        Log.i(TAG, "live page advanced; requested snapshot and suggestions retained")
    }

    private fun preservePinnedDuringUnreadableCapture(root: AccessibilityNodeInfo): Boolean {
        val pkg = root.packageName?.toString() ?: return false
        if (!pinnedAnalysisTarget.isSameWindow(pkg, root.windowId)) return false
        // After completion, an unreadable WeChat body is tolerable only when
        // its toolbar and bottom editor still prove this is a chat page.
        if (!analyzing && !ocrBusy &&
            (pkg != PKG_WECHAT || wechatTitleRegion(root) == null)) return false
        overlay?.showSnapshotUpdate()
        return true
    }

    private fun finishPinnedContext(target: ConversationSession.Target) {
        if (!pinnedAnalysisTarget.protects(target)) return
        pinnedContextReady = true
        deferredPinnedCapture?.let { queueMemoryCapture(it.snapshot, it.pkg, historical = false) }
        deferredPinnedCapture = null
    }

    /** A missing scroll event or unchanged accessibility snapshot gets bounded retries. */
    private fun onAutoBackfillTimeout() {
        val run = backfill.active ?: run { pauseAutoBackfill("补录会话已结束"); return }
        val root = rootInActiveWindow
        val windowState = backfill.windowState(run, root?.packageName?.toString(), root?.windowId)
        if (windowState == BackfillSession.WindowState.WAIT) {
            if (++autoIdentityRetries > 8) {
                pauseAutoBackfill("页面暂时无法核实 自动上滑已暂停 补录仍开启"); return
            }
            overlay?.showBackfillWaiting("暂时等待聊天窗口恢复 $autoIdentityRetries/8 本屏尚未保存")
            if (run.target.pkg == PKG_WECHAT) scheduleWechatBackfill(0)
            main.postDelayed(autoBackfillTimeout, 900L)
            return
        }
        if (windowState == BackfillSession.WindowState.LEFT || root == null) {
            pauseAutoBackfill("聊天窗口已变化，自动上滑已暂停")
            return
        }
        val live = snapshotFor(root)
        if (ocrBusy || live?.title.isNullOrBlank()) {
            if (++autoIdentityRetries > 8) {
                pauseAutoBackfill("滚动后本屏仍未核实 自动翻页已暂停 请重试当前屏")
                return
            }
            overlay?.showBackfillWaiting("滚动后正在核实本屏 $autoIdentityRetries/8 尚未继续翻页")
            if (run.target.pkg == PKG_WECHAT) scheduleWechatBackfill(0)
            main.postDelayed(autoBackfillTimeout, 900L)
            return
        }
        val page = if (run.target.pkg == PKG_WECHAT) wechatPageKey(root, live) else live?.signature()
        if (live?.title == run.target.title && page != null && page != autoBackfillExpectedPage) {
            Log.i(TAG, "automatic backfill timeout saw changed page chars=${page.length}; rescanning")
            if (run.target.pkg == PKG_WECHAT) main.post(backfillScan)
            else live?.let { queueMemoryCapture(it, run.target.pkg, historical = true) }
            main.postDelayed(autoBackfillTimeout, 3000L)
            return
        }
        handleAutoBackfillNoProgress("等待滚动后的新页面超时")
    }

    /** Passive, opt-in storage. Never blocks a bubble tap or sends a model request. */
    private fun queueMemoryCapture(snapshot: ChatSnapshot, pkg: String?, historical: Boolean) {
        if (contactBinding.contactId(session.target) != null) return
        if (pkg != PKG_WECHAT && pkg != "com.tantan.x" && pkg != QingtengAdapter.PACKAGE && pkg != SoulAdapter.PACKAGE) return
        if ((pkg == SoulAdapter.PACKAGE || pkg == PKG_WECHAT) &&
            snapshot.messages.any { it.side != "me" && it.side != "other" }) {
            if (historyCaptureMode) {
                val message = "本屏有发言者未确认的消息 请先核实我和对方 本屏未保存"
                backfill.active?.progress?.accept(snapshot, com.jev.probe.core.kb.CaptureResult(reason = message))
                if (autoBackfillRunning) pauseAutoBackfill(message)
                else overlay?.showBackfillWaiting(message)
            }
            return
        }
        if (pkg == QingtengAdapter.PACKAGE && !QingtengAdapter.canRemember(snapshot.title)) return
        if (snapshot.title.isNullOrBlank()) return
        if (snapshot.messages.isEmpty()) return
        if (pkg == PKG_WECHAT && snapshot.voiceTranscriptRects.isNotEmpty()) {
            val contact = KbStore.get(this).findContact(snapshot.title, pkg)
            if (contact?.memoryEnabled != true || pkg !in contact.apps || ocrBusy || analyzing) return
            val root = rootInActiveWindow ?: return
            val target = targetFor(root, snapshot) ?: return
            resolveWechatTranscripts(root, snapshot, target, backfill.active, silent = backfill.active == null) {
                rootInActiveWindow?.let(::snapshotFor)?.let { queueMemoryCapture(it, pkg, historical) }
            }
            return
        }
        val captured = snapshot.copy(messages = snapshot.messages.toList())
        val observedAt = System.currentTimeMillis()
        val queuedAtElapsed = android.os.SystemClock.elapsedRealtime()
        val recordingRun = backfill.active
        try { memoryWorker.execute {
            try {
                val store = KbStore.get(this)
                val result = store.captureVisibleDetailed(recordingRun?.contactId, captured, pkg,
                    historical, observedAt, queuedAtElapsed)
                if (recordingRun != null) {
                    if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
                        Log.i(TAG, "backfill audit saved=${result.saved} dates=" +
                            captured.messages.mapNotNull { it.visibleTime }.distinct().joinToString(";") +
                            " keys=" + captured.messages.joinToString(",") {
                                com.jev.probe.core.kb.ConversationOverlap.contentKey(it.side, it.kind.name, it.text)
                            })
                    }
                    val stats = store.contact(recordingRun.contactId)?.takeIf { pkg in it.apps }
                        ?.let { store.captureStats(it.id) }
                    main.post {
                        if (backfill.isCurrent(recordingRun) && session.target?.pkg == pkg &&
                            session.target?.title == captured.title && stats != null) {
                            recordingRun.progress.accept(captured, result)
                            if (result.saved) backfillSavedSignature = captured.signature()
                            else lastBackfillPage = null
                            val now = android.os.SystemClock.elapsedRealtime()
                            if (pkg == PKG_WECHAT || stats.pages != lastBackfillPages || now - lastBackfillFeedbackAt >= 1500L) {
                                lastBackfillPages = stats.pages
                                lastBackfillLines = stats.lines
                                lastBackfillFeedbackAt = now
                                overlay?.showBackfillProgress(stats.pages, stats.lines, result.added > 0,
                                    result.status(), recordingRun.progress.label())
                                Log.i(TAG, "backfill saved pages=${stats.pages} lines=${stats.lines} " +
                                    "added=${result.added} timeUpdated=${result.timeUpdated} saved=${result.saved}")
                            }
                            // Show the capture result first. A pause reason must remain visible
                            // instead of being immediately replaced by this progress update.
                            if (result.saved) onBackfillCaptureCompleted(recordingRun, captured, result)
                            else if (autoBackfillRunning) pauseAutoBackfill(result.status())
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "memory capture failed: ${e.javaClass.simpleName}")
                if (recordingRun != null) main.post {
                    if (backfill.isCurrent(recordingRun)) {
                        lastBackfillPage = null
                        if (autoBackfillRunning) pauseAutoBackfill("\u5f53\u524d\u5c4f\u4fdd\u5b58\u5931\u8d25\uff0c\u81ea\u52a8\u4e0a\u6ed1\u5df2\u6682\u505c\uff0c\u8bf7\u91cd\u8bd5")
                        else overlay?.showBackfillWaiting("这一屏保存失败，请点重试当前屏。")
                    }
                }
            }
        } } catch (_: RejectedExecutionException) { }
    }

    /** A placeholder title an app shows only for a moment (e.g. X's "连接中…"
     *  right after opening a DM thread) — never a real conversation title.
     *  Blank/null counts too, so a caller can always fall back the same way. */
    private fun isTransientTitle(t: String?): Boolean {
        val trimmed = t?.trim()?.removeSuffix("…")?.removeSuffix("...")?.trim()
        if (trimmed.isNullOrEmpty()) return true
        val lower = trimmed.lowercase()
        return TRANSIENT_TITLE_WORDS.any { lower.contains(it.lowercase()) }
    }

    /** Starts a cached draft on first analysis without adding latency to replies. */
    private fun maybeGenerateRelationshipProfile(contact: com.jev.probe.core.kb.Contact?, appName: String): Boolean {
        contact ?: return false
        if (!contact.memoryEnabled || appName !in contact.apps ||
            appName !in setOf(PKG_WECHAT, "com.tantan.x", "cn.soulapp.android", QingtengAdapter.PACKAGE))
            return false
        if (contact.relationshipProfiles.containsKey(appName) || contact.relationshipProfileDrafts.containsKey(appName))
            return false
        if (prefs.effectiveReplyKey().isBlank()) return false
        if (!RelationshipProfileJobs.begin(contact.id, appName)) return false
        return try {
            profileWorker.execute {
                try {
                    val store = KbStore.get(this)
                    val source = store.relationshipSource(contact.id, appName)
                    if (source.lines.isEmpty()) return@execute
                    val client = ReplyClient(prefs)
                    val profile = RelationshipProfileGenerator({ system, user, temperature ->
                        client.completeProfileRoundTrip(system, user, temperature)
                    }, parallelism = 2, checkpoints = com.jev.probe.jev.FileProfileCheckpoints(
                        this, contact.id, appName, client.profileCacheRoute
                    ), userContext = (store.contact(contact.id) ?: contact).profileUserContext(appName)).generate(source) { progress ->
                        RelationshipProfileJobs.update(contact.id, appName, progress)
                    }
                    if (Thread.currentThread().isInterrupted) throw InterruptedException("档案生成已取消")
                    if (store.saveGeneratedRelationshipProfile(contact.id, appName, profile))
                        RuntimeState.recordAction(this, "联系人关系档案已生成并关联到此联系人")
                } catch (e: Exception) {
                    Log.w(TAG, "relationship profile generation failed: ${e.javaClass.simpleName}")
                    if (!destroyed) RuntimeState.recordAction(this, "联系人关系档案未生成，可在联系人页重试")
                } finally {
                    RelationshipProfileJobs.finish(contact.id, appName)
                }
            }
            true
        } catch (_: RejectedExecutionException) {
            RelationshipProfileJobs.finish(contact.id, appName)
            false
        }
    }

    /** Shared capture, title and retry path; purpose survives every continuation. */
    private fun requestConversationSuggestions(purpose: ConversationPurpose) {
        val root = rootInActiveWindow
        val pkg = root?.packageName?.toString()
        val observedSnapshot = if (root != null && pkg in setOf(PKG_WECHAT, "com.tantan.x",
                "cn.soulapp.android", "cn.neoclub.uki", QingtengAdapter.PACKAGE))
            snapshotFor(root) else null
        val target = root?.let { targetFor(it, observedSnapshot) }
        val reviewed = target?.let { candidate ->
            reviewedSnapshotOverride?.takeIf { it.first == candidate }
        }
        val snapshot = reviewed?.second ?: observedSnapshot
        if (reviewed != null) reviewedSnapshotOverride = null
        Log.i(TAG, "open conversation pkg=${pkg.orEmpty()} snapshot=${snapshot != null} " +
            "titlePresent=${!snapshot?.title.isNullOrBlank()} target=${target != null} " +
            "titleMatch=${target != null && snapshot != null && target.matchesSnapshot(snapshot)} " +
            "messages=${snapshot?.messages?.size ?: 0}")
        when {
            historyCaptureMode -> overlay?.showNotice("正在补录旧消息；请先结束补录，再生成${purpose.label}建议。")
            analyzing || ocrBusy -> {
                if (voiceOcrActive) session.token()?.let {
                    afterVoiceOcr = it to { requestConversationSuggestions(purpose); Unit }
                }
                overlay?.showProgress("上一轮请求仍在进行，请稍候…")
            }
            pkg == PKG_WECHAT && target?.title.isNullOrBlank() && wechatTitleRegion(root) != null ->
                resolveWechatOpeningTitle(root, purpose)
            snapshot == null || target == null || !target.matchesSnapshot(snapshot) ->
                if (!(pkg != null && pkg in OPENING_CHAT_PACKAGES &&
                    retrySupportedScopeRead(purpose.key, pkg, root.windowId) {
                        requestConversationSuggestions(purpose)
                    })) {
                    scopeReadRetry.reset(purpose.key)
                    overlay?.showNotice(if (pkg != null && pkg in OPENING_CHAT_PACKAGES)
                        supportedScopeReadFailure(pkg)
                    else "请打开微信、牵手、青藤之恋、Soul 或 Uki 的聊天页，再点“帮我${purpose.label}”；资料卡读取尚未接入。")
                }
            target.title == null && !prefs.isAllowed(null) ->
                overlay?.showNotice("微信备注暂时无法识别；联系人白名单无法确认此人，本次未生成${purpose.label}建议。请先让备注可识别，或关闭白名单限制。")
            pkg == PKG_WECHAT && snapshot.voiceTranscriptRects.isNotEmpty() ->
                resolveWechatTranscripts(root, snapshot, target) { requestConversationSuggestions(purpose) }
            purpose == ConversationPurpose.INVITATION && snapshot.messages.any { it.side == "unknown" } -> {
                val index = snapshot.messages.indexOfLast { it.side == "unknown" }
                overlay?.showSpeakerChoice(snapshot.messages[index]) { side ->
                    if (rootInActiveWindow?.let { targetFor(it) } != target) {
                        overlay?.showNotice("会话已变化 请重新点击帮我邀约")
                    } else {
                        val corrected = snapshot.messages.toMutableList()
                        corrected[index] = corrected[index].copy(side = side)
                        reviewedSnapshotOverride = target to snapshot.copy(messages = corrected)
                        requestConversationSuggestions(purpose)
                    }
                }
            }
            purpose == ConversationPurpose.INVITATION && !prefs.hasKey() ->
                overlay?.showError("未设置判断接口密钥 请先到设置页配置")
            prefs.effectiveReplyKey().isBlank() ->
                overlay?.showError("未设置回复接口密钥，请到设置页检查")
            else -> {
                scopeReadRetry.reset(purpose.key)
                observeTarget(target)
                lastRequestPurpose = purpose
                currentSnapshot = snapshot
                pendingSnapshot = snapshot
                activePkg = target.pkg
                lastSignature = snapshot.signature()
                overlay?.setSnapshotAudit(snapshot)
                overlay?.setNote(if (target.title == null)
                    "微信备注未能识别；本次仅使用当前可见消息，不关联联系人记忆。"
                else snapshot.note)
                val token = session.begin()
                if (token == null) overlay?.showError("无法确认当前会话，请重试")
                else {
                    pinAnalysis(token.target)
                    analyzing = true
                    RuntimeState.recordAction(this, "正在生成${purpose.label}建议")
                    overlay?.showLoading("已锁定点击时的聊天画面，正在准备${purpose.label}建议…")
                    main.postDelayed({
                        if (analyzing && isCurrent(token)) {
                            val tasks = analysisTasks.toList()
                            session.invalidate()
                            completeAnalysis(token.target)
                            tasks.forEach { it.cancel(true) }
                            overlay?.showError("本轮已等待 60 秒，已停止等待；请检查网络后重试。", "重试${purpose.label}建议") {
                                requestConversationSuggestions(purpose)
                            }
                        }
                    }, 60_000L)
                    main.postDelayed({
                        if (analyzing && isCurrent(token))
                            overlay?.showProgress("${purpose.label}建议仍在生成；网络慢时请稍候…")
                    }, 10_000L)
                    main.postDelayed({
                        if (analyzing && isCurrent(token))
                            overlay?.showProgress("已等待 20 秒，仍在请求模型；请保持当前会话打开。")
                    }, 20_000L)
                    val selectedContactId = contactBinding.contactId(target)
                    submitAnalysis {
                        val contextStart = android.os.SystemClock.elapsedRealtime()
                        val ctx = try {
                                if (purpose == ConversationPurpose.INVITATION)
                                    ContextBuilder.buildInvitation(this, snapshot, target.pkg, prefs, selectedContactId)
                                else ContextBuilder.build(this, snapshot, target.pkg, prefs, selectedContactId)
                            }
                            catch (e: Exception) {
                                Log.w(TAG, "opener context failed: ${e.javaClass.simpleName}"); null
                            }
                        if (ctx == null || (selectedContactId != null && ctx.contact == null)) {
                            main.post {
                                if (isCurrent(token)) {
                                    completeAnalysis(token.target)
                                    overlay?.showError("联系人上下文未能读取，未套用默认关系；请核对联系人后重试。", "重试${purpose.label}建议") {
                                        requestConversationSuggestions(purpose)
                                    }
                                }
                            }
                            return@submitAnalysis
                        }
                        val profileStarted = purpose != ConversationPurpose.INVITATION &&
                                maybeGenerateRelationshipProfile(ctx?.contact, target.pkg)
                        val contextMs = android.os.SystemClock.elapsedRealtime() - contextStart
                        val relation = relationshipForChannel(
                            ctx?.effectiveRelationship(prefs.relationship) ?: prefs.relationship, target.pkg)
                        val generationStart = android.os.SystemClock.elapsedRealtime()
                        var invitationPlan: InvitationPlan? = null
                            val result = try {
                                val client = JevClient(prefs)
                                val replies = if (purpose == ConversationPurpose.INVITATION) {
                                    main.post { if (isCurrent(token)) {
                                        setVoiceScope(token.target, ctx)
                                        finishPinnedContext(token.target)
                                        overlay?.showProgress("1/2 正在判断邀约切口和上次邀约状态…")
                                    } }
                                    client.invitations(snapshot, relation, ctx) { plan ->
                                        invitationPlan = plan
                                        main.post { if (isCurrent(token))
                                            overlay?.showProgress("2/2 判断已完成 正在生成${plan.action.label}的说法…") }
                                    }.replies
                                } else client.openers(snapshot, relation, ctx)
                                replies to null
                            }
                            catch (e: Exception) { emptyList<ReplySuggestion>() to (e.message ?: e.javaClass.simpleName) }
                        Log.i(TAG, "timing opener context=${contextMs}ms generate=" +
                            "${android.os.SystemClock.elapsedRealtime() - generationStart}ms " +
                            "success=${result.second == null && result.first.isNotEmpty()}")
                        main.post {
                            if (!isCurrent(token)) return@post
                            setVoiceScope(token.target, ctx)
                            finishPinnedContext(token.target)
                            completeAnalysis(token.target)
                            if (profileStarted) overlay?.setNote(listOfNotNull(snapshot.note,
                                "首次使用已开始后台生成该联系人档案；完成后会自动用于此联系人的后续建议，可到联系人页查看和编辑。")
                                .joinToString("；"))
                            if (result.second != null || result.first.isEmpty())
                                overlay?.showError("${purpose.label}建议生成失败：${result.second ?: "未返回内容"}", "重试${purpose.label}建议") {
                                    requestConversationSuggestions(purpose)
                                }
                            else if (purpose == ConversationPurpose.INVITATION && invitationPlan != null)
                                    overlay?.showInvitations(invitationPlan!!, result.first) { text -> fillInput(token, text) }
                                else overlay?.showOpeners(result.first) { text -> fillInput(token, text) }
                        }
                    }
                }
            }
        }
    }

    private fun runAnalysis() {
        lastRequestPurpose = ConversationPurpose.REPLY
        Log.i(TAG, "analysis route history=$historyCaptureMode pending=${pendingSnapshot != null} " +
            "latest=${pendingSnapshot?.latestFrom ?: "none"} kind=${pendingSnapshot?.messages?.lastOrNull()?.kind ?: "none"}")
        if (historyCaptureMode) {
            overlay?.showNotice("正在补录旧消息，不生成回复；请先从悬浮球菜单结束补录。")
            return
        }
        val snapshot = pendingSnapshot ?: run { overlay?.showError("当前没有读到对话，请重试或点截屏识别一次"); return }
        if (snapshot.latestFrom == "unknown") {
            RuntimeState.recordAction(this, "末条发言者不确定，等待确认")
            val token = session.token() ?: run { overlay?.showError("无法确认当前会话，请重试"); return }
            overlay?.showSpeakerChoice(snapshot.messages.last()) { side ->
                if (!isCurrent(token)) { overlay?.showError("会话已变化，请重新点击分析"); return@showSpeakerChoice }
                pendingSnapshot = snapshot.copy(messages = snapshot.messages.dropLast(1) +
                    snapshot.messages.last().copy(side = side))
                runAnalysis()
            }
            return
        }
        if (snapshot.latestFrom == "me") {
            RuntimeState.recordAction(this, "末条识别为我方，未生成回复")
            overlay?.showNotice("本次识别到的末条消息是你发的，暂不生成接话建议。若要主动续聊，请点“帮我开场”；图片等未识别内容请核对。")
            return
        }
        if (snapshot.messages.last().kind == MessageKind.VOICE_UNKNOWN) {
            overlay?.showNotice("对方末条语音的正文尚未读到。请先转成文字，并让整段转写完整显示在屏幕内后再分析。")
            return
        }
        if (snapshot.messages.last().kind == MessageKind.MEDIA_UNKNOWN) {
            overlay?.showNotice("对方最后发的是图片或贴纸，内容未知；这次不根据它猜回复。")
            return
        }
        if (analyzing) { overlay?.showProgress("上一轮分析仍在进行…"); return }
        if (destroyed || !prefs.enabled || prefs.overlayHidden) { overlay?.showError("助手未运行，请返回首页检查开关"); return }
        val previous = session.token() ?: run { overlay?.showError("无法确认当前会话，请重试"); return }
        if (!isCurrent(previous)) { overlay?.showError("会话已变化，请重新点击分析"); return }
        if (!prefs.hasKey()) {
            RuntimeState.recordAction(this, "缺少判断接口密钥")
            overlay?.showError("未设置判断接口密钥，去设置里填")
            return
        }
        val token = session.begin() ?: return
        pinAnalysis(token.target)
        analyzing = true
        RuntimeState.recordAction(this, "正在请求模型")
        overlay?.showLoading("已锁定点击时的聊天画面，正在读取上下文并生成建议…")
        overlay?.setSnapshotAudit(snapshot)
        overlay?.setNote(snapshot.note)
        val client = JevClient(prefs)
        val rel = prefs.relationship
        var judgmentFinished = false
        var draftFinished = false
        main.postDelayed({
            if (analyzing && !draftFinished && isCurrent(token))
                overlay?.showProgress(if (judgmentFinished)
                    "判断已返回，回复草稿仍在生成；网络慢时请稍候…"
                else "判断和回复草稿仍在生成；网络慢时请稍候…")
        }, 10_000L)
        main.postDelayed({
            if (analyzing && !draftFinished && isCurrent(token))
                overlay?.showProgress("已等待 20 秒，回复草稿仍在生成；请保持当前会话打开。")
        }, 20_000L)
        main.postDelayed({
            if (analyzing && isCurrent(token)) {
                val tasks = analysisTasks.toList()
                activeDelivery?.timeout() ?: run {
                    completeAnalysis(token.target)
                    session.invalidate()
                    overlay?.showError("读取上下文已等待 60 秒，请重试。")
                }
                tasks.forEach { it.cancel(true) }
            }
        }, 60_000L)
        val selectedContactId = contactBinding.contactId(token.target)
        submitAnalysis {
            val contextStart = android.os.SystemClock.elapsedRealtime()
            val ctx = try {
                ContextBuilder.build(this, snapshot, token.target.pkg, prefs, selectedContactId)
            } catch (e: Exception) {
                Log.w(TAG, "context build failed: ${e.javaClass.simpleName}"); null
            }
            if (ctx == null || (selectedContactId != null && ctx.contact == null)) {
                main.post {
                    if (isCurrent(token)) {
                        completeAnalysis(token.target)
                        overlay?.showError("联系人上下文未能读取，未套用默认关系；请核对联系人后重试。")
                    }
                }
                return@submitAnalysis
            }
            val profileStarted = maybeGenerateRelationshipProfile(ctx?.contact, token.target.pkg)
            Log.i(TAG, "timing context=${android.os.SystemClock.elapsedRealtime() - contextStart}ms")
            val unlinkedMemory = ctx?.contact == null && KbStore.get(this).contacts()
                .any { it.memoryEnabled && it.apps.contains(token.target.pkg) }
            main.post {
                if (!isCurrent(token)) return@post
                setVoiceScope(token.target, ctx)
                finishPinnedContext(token.target)
                if (unlinkedMemory) overlay?.setNote(listOfNotNull(snapshot.note,
                    "本会话未关联旧记忆；若是已认识的人，请到联系人页确认身份后再使用旧记录。")
                    .joinToString("；"))
                else if (profileStarted) overlay?.setNote(listOfNotNull(snapshot.note,
                    "首次使用已开始后台生成该联系人档案；完成后会自动用于此联系人的后续建议，可到联系人页查看和编辑。")
                    .joinToString("；"))
                overlay?.setContextInfo(ctx?.notes?.size ?: 0, ctx?.history?.size ?: 0,
                    ctx?.contact != null, ctx?.contact?.memoryEnabled == true)
                overlay?.showProgress("上下文已就绪，正在生成初稿并判断对话…")
                val effectiveRel = relationshipForChannel(
                    ctx?.effectiveRelationship(rel) ?: rel, token.target.pkg)
                lateinit var delivery: AnalysisDelivery
                delivery = AnalysisDelivery(
                    showJudgment = { overlay?.showJudgment(it) },
                    showDraft = { drafts, issue -> overlay?.showDraft(drafts, issue) { fillInput(token, it) } },
                    showReplies = { ranked -> overlay?.showReplies(ranked) { fillInput(token, it) } },
                    showProgress = { overlay?.showProgress(it) },
                    showError = { overlay?.showError(it) },
                    rank = { drafts ->
                        submitAnalysis {
                            val started = android.os.SystemClock.elapsedRealtime()
                            val result = try {
                                val metadata = drafts.associateBy { it.text }
                                client.rank(snapshot, effectiveRel, drafts.map { it.text }, ctx).map {
                                    val source = metadata[it.text]
                                    it.copy(style = source?.style.orEmpty(), reason = source?.reason.orEmpty())
                                } to null
                            } catch (e: Exception) { emptyList<RankedReply>() to "建议排序未完成：${e.message}" }
                            Log.i(TAG, "timing rank=${android.os.SystemClock.elapsedRealtime() - started}ms")
                            main.post { if (isCurrent(token)) delivery.rankingReceived(result.first, result.second) }
                        }
                    },
                    finish = { completeAnalysis(token.target) }
                )
                activeDelivery = delivery
                submitAnalysis {
                    val started = android.os.SystemClock.elapsedRealtime()
                    val judgment = client.judge(snapshot, effectiveRel, ctx)
                    Log.i(TAG, "timing judge=${android.os.SystemClock.elapsedRealtime() - started}ms success=${judgment.error == null}")
                    main.post {
                        if (isCurrent(token)) {
                            judgmentFinished = true
                            RuntimeState.recordAction(this, if (judgment.error == null) "判断已返回" else "判断暂不可用，继续等待回复初稿")
                            delivery.judgmentReceived(judgment)
                        }
                    }
                }
                submitAnalysis {
                    var error: String? = null
                    val started = android.os.SystemClock.elapsedRealtime()
                    val drafts = try { client.draft(snapshot, effectiveRel, ctx) } catch (e: Exception) {
                        error = e.message ?: e.javaClass.simpleName
                        emptyList()
                    }
                    Log.i(TAG, "timing draft=${android.os.SystemClock.elapsedRealtime() - started}ms count=${drafts.size}")
                    main.post {
                        if (isCurrent(token)) {
                            draftFinished = true
                            RuntimeState.recordAction(this, if (error == null) "回复初稿已返回 ${drafts.size} 条" else "回复初稿未生成")
                            delivery.draftReceived(drafts, error)
                        }
                    }
                }
            }
        }
    }

    /** Worker completion must not release the snapshot backing visible cards. */
    private fun completeAnalysis(target: ConversationSession.Target) {
        if (!pinnedAnalysisTarget.isBoundTo(target)) return
        finishPinnedContext(target)
        analyzing = false
        activeDelivery = null
        analysisTasks.clear()
    }

    // ------------------------------------------------------------------ OCR

    /**
     * Bubble menu → "截屏识别一次". Works on ANY app, adapted or not: one whole
     * screen shot, every line OCR'd, lines grouped into pseudo-bubbles by line
     * spacing. Clear left/right alignment gets a tentative speaker; ambiguous
     * rows stay unknown and cannot trigger a reply.
     */
    private fun ocrCaptureManual() {
        val root = rootInActiveWindow
        val pkg = root?.packageName?.toString() ?: foregroundPkg ?: activePkg ?: ""
        // Top bar text, if this app has one we can read; else the first OCR line.
        val title = root?.let {
            if (pkg == PKG_WECHAT)
                wechatManualTitle(it)
            else
                findTitleInActionBar(it, Int.MAX_VALUE, resources.displayMetrics.widthPixels, resources, 0.15, 0.85)
        }
        val target = root?.let { targetFor(it) } ?: run {
            if (pkg == PKG_WECHAT && root != null && wechatTitleRegion(root) != null) {
                resolveWechatTitle(root) { ocrCaptureManual() }
                return
            }
            RuntimeState.recordAction(this, "无法确认当前会话")
            overlay?.showError("无法确认当前会话，请等待标题加载后重试")
            return
        }
        if (target.title == null && !prefs.isAllowed(null)) {
            overlay?.showNotice("WeChat title is unreadable; the contact whitelist cannot verify this person, so OCR analysis was skipped.")
            return
        }
        observeTarget(target)
        RuntimeState.recordAction(this, "正在截屏识别")
        overlay?.showLoading("截屏识别中…")
        // When the keyboard is open, its first row can start well above the
        // old fixed 84% crop. Stop at the chat editor instead.
        val editorTop = root.let { findEditable(it) }?.let { editor ->
            Rect().also { editor.getBoundsInScreen(it) }.top
        }
        ocrCapture(title, emptyList(), pkg, manual = true, bottomScreenY = editorTop)
    }

    /**
     * What the screen would look like to a camera, as far as the tree can tell.
     *
     * Feishu: the conversation title plus every bubble rectangle and its side —
     * the bubbles move whenever the list scrolls or a message arrives, and stay
     * put when only chrome (caret, presence dot, timestamp) redraws. Apps that
     * give us no rectangles fall back to package + title, which at least stops a
     * burst of events on one screen from becoming a burst of screenshots.
     */
    private fun ocrSignature(pkg: String, title: String?, rects: List<BubbleRect>): String {
        if (rects.isEmpty()) return pkg + "|" + (title ?: "")
        return (title ?: "") + "|" + rects.joinToString(";") { br ->
            val r = br.rect
            "${r.left},${r.top},${r.right},${r.bottom},${br.side}"
        }
    }

    /**
     * Screenshot, then either OCR each known bubble rect (Feishu: the tree knows
     * where the bubbles are and who sent them, just not what they say) or OCR
     * the whole screen (everything else).
     */
    private fun ocrCapture(treeTitle: String?, rects: List<BubbleRect>, pkg: String, manual: Boolean,
                           bottomScreenY: Int? = null) {
        if (ocrBusy) {
            if (manual) RuntimeState.recordAction(this, "上一轮识别仍在进行")
            if (manual) overlay?.toast("正在识别，请稍候")
            return
        }
        if (destroyed || !prefs.enabled || prefs.overlayHidden) {
            if (manual) overlay?.showError("助手未运行，请返回首页检查开关")
            return
        }
        val token = session.token() ?: run {
            if (manual) overlay?.showError("无法确认当前会话，请重试")
            return
        }
        if (!isCurrent(token)) {
            if (manual) overlay?.showError("会话已变化，请重新点击分析")
            return
        }
        if (manual) pinAnalysis(token.target)
        ocrBusy = true
        screenCapture.capture(shouldCapture = { isCurrent(token) }) { res ->
            if (!isCurrent(token)) {
                if (res is ScreenCapture.Result.Ok) res.bitmap.recycle()
                ocrBusy = false
                return@capture
            }
            when (res) {
                is ScreenCapture.Result.Failed -> {
                    ocrBusy = false
                    if (manual) pinnedAnalysisTarget.clear()
                    RuntimeState.recordAction(this, "截屏失败（码 ${res.code}）")
                    Log.i(TAG, "ocr: screenshot failed code=${res.code}")
                    // Nothing was read, so the signature must not claim this
                    // screen is done — the next event may retry, still held
                    // back by ScreenCapture's own throttle and failure backoff.
                    if (!manual) lastOcrSignature = ""
                    // Throttle/interval codes are transient timing, not
                    // something the user can act on — nagging would be constant.
                    val transient = res.code == ScreenCapture.CODE_THROTTLED || res.code == 3
                    if (manual || !transient) overlay?.showError(res.humanMessage)
                }
                is ScreenCapture.Result.Ok -> {
                    RuntimeState.recordAction(this, "截屏完成，正在识别文字")
                    ocr.scaleX = res.scaleX; ocr.scaleY = res.scaleY
                    ocr.originX = res.originX; ocr.originY = res.originY
                    if (rects.isNotEmpty() && !manual) {
                        // Re-measure inside the callback. The rects handed in were
                        // read before the 120ms overlay-hide wait and the shot
                        // itself; one scroll tick in between and we would crop the
                        // rows next to the ones in the picture. Fall back to the
                        // old rects only if the tree gives us nothing now.
                        val fresh = rootInActiveWindow?.let { collectFeishuBubbleRects(it, resources) }
                        ocrByRects(res.bitmap, if (fresh.isNullOrEmpty()) rects else fresh, treeTitle, pkg, token)
                    } else ocrWholeScreen(res.bitmap, treeTitle, pkg, manual, token, bottomScreenY)
                }
            }
        }
    }

    /** One OCR pass per bubble rectangle; each rect becomes exactly one message. */
    private fun ocrByRects(bmp: Bitmap, rects: List<BubbleRect>, title: String?, pkg: String, token: ConversationSession.Token) {
        val sx = ocr.scaleX; val sy = ocr.scaleY
        // Screen -> bitmap: drop the window origin first. A window shot does not
        // start at (0,0) in split screen or when it excludes the status bar.
        val ox = ocr.originX; val oy = ocr.originY
        val out = arrayOfNulls<Msg>(rects.size)
        var remaining = rects.size
        rects.forEachIndexed { i, br ->
            val region = Rect(
                ((br.rect.left - ox) * sx).toInt(), ((br.rect.top - oy) * sy).toInt(),
                ((br.rect.right - ox) * sx).toInt(), ((br.rect.bottom - oy) * sy).toInt())
            ocr.recognize(bmp, region) { lines ->
                val text = cleanBubbleText(lines.joinToString(" ") { it.text })
                if (text.isNotEmpty()) out[i] = Msg(br.side, text)
                remaining--
                if (remaining == 0) {
                    runCatching { bmp.recycle() }
                    finishOcrSnapshot(ChatSnapshot(title, out.filterNotNull()), pkg, manual = false, token = token)
                }
            }
        }
    }

    /** Whole screen minus the top bar and the input area, grouped by line gaps. */
    private fun ocrWholeScreen(bmp: Bitmap, treeTitle: String?, pkg: String, manual: Boolean,
                               token: ConversationSession.Token, bottomScreenY: Int?) {
        val top = (bmp.height * TOP_CROP).toInt()
        val editorBottom = bottomScreenY?.let { ((it - ocr.originY) * ocr.scaleY).toInt() }
        val bottom = minOf((bmp.height * BOTTOM_CROP).toInt(), editorBottom ?: bmp.height)
            .coerceAtLeast(top + 8)
        val region = Rect(0, top, bmp.width, bottom)
        ocr.recognize(bmp, region) { lines ->
            runCatching { bmp.recycle() }
            val msgs = groupOcrLines(lines)
            val title = treeTitle?.takeIf { it.isNotBlank() }
                ?: if (pkg == PKG_WECHAT) null else lines.firstOrNull()?.text?.trim()?.take(24)
            finishOcrSnapshot(ChatSnapshot(title, msgs, note = OCR_NOTE), pkg, manual, token)
        }
    }

    /** OCR has no bubble ownership; never upgrade ambiguous lines to certainty. */
    private fun groupOcrLines(lines: List<OcrLine>): List<Msg> =
        OcrMessageParser.parse(lines, resources.displayMetrics.widthPixels)

    /** Strip the read receipt and the timestamp Feishu glues onto a bubble. */
    private fun cleanBubbleText(raw: String): String {
        var t = raw.trim()
        var changed = true
        while (changed && t.isNotEmpty()) {
            changed = false
            for (tail in arrayOf("已读", "未读")) {
                if (t.endsWith(tail)) { t = t.removeSuffix(tail).trim(); changed = true }
            }
            TAIL_TIME.find(t)?.let { t = t.substring(0, it.range.first).trim(); changed = true }
        }
        return t
    }

    /** Shared tail of both OCR paths: dedupe, then analyze or park the bubble. */
    private fun finishOcrSnapshot(snapshot: ChatSnapshot, pkg: String, manual: Boolean, token: ConversationSession.Token) {
        ocrBusy = false
        if (!isCurrent(token)) return
        // Counts only — OCR'd chat text never goes to logcat.
        Log.i(TAG, "ocr[$pkg] msgs=${snapshot.messages.size} manual=$manual")
        RuntimeState.recordAction(this, "文字识别完成（${snapshot.messages.size} 条）")
        if (snapshot.messages.isEmpty()) {
            if (manual) pinnedAnalysisTarget.clear()
            if (manual) overlay?.showError("这一屏没认出文字")
            return
        }
        if (!prefs.isAllowed(snapshot.title)) { leaveConversation(); overlay?.hide(); return }

        if (pkg.isNotEmpty() && pkg != activePkg) { activePkg = pkg; lastSignature = "" }
        currentSnapshot = snapshot
        val sig = snapshot.signature()
        // Manual taps always re-run; the automatic path dedupes like the tree path.
        if (!manual && sig == lastSignature) {
            if (overlay?.isShowing() != true) overlay?.showIdle(snapshot.title)
            return
        }
        // Same rule as the tree path: past this point the conversation is either
        // new or being force-refreshed, so drop whatever was shown before.
        cancelAnalysis()
        lastSignature = sig

        // OCR can also fill opted-in WeChat memory, but only when the chat title
        // still matches the live window and every captured bubble has a reliable
        // side. Whole-screen OCR with uncertain rows must not poison memory.
        val reliableWechatOcr = pkg == PKG_WECHAT && token.target.title != null &&
            snapshot.title == token.target.title &&
            snapshot.messages.size >= 2 && snapshot.messages.all {
                it.side == "me" || it.side == "other"
            }
        if (reliableWechatOcr) queueMemoryCapture(snapshot, pkg,
            historical = historyCaptureMode ||
                android.os.SystemClock.elapsedRealtime() - lastScrollAt < 3000L)
        if (historyCaptureMode) {
            overlay?.showNotice(if (reliableWechatOcr)
                "这一屏可识别消息已加入补录；未生成回复。"
            else "这一屏的会话或发言者不够确定，未写入记忆；请换一屏再试。")
            return
        }

        val auto = prefs.ocrAutoAnalyze && prefs.autoAnalyze && snapshot.latestFrom == "other"
        if (manual || auto) {
            pendingSnapshot = snapshot
            main.removeCallbacks(debounce)
            runAnalysis()
        } else {
            overlay?.setNote(snapshot.note)
            overlay?.showIdle(snapshot.title)
        }
    }

    /** Resolve only the originating chat's input, never an arbitrary foreground editor. */
    private fun inputFor(token: ConversationSession.Token): AccessibilityNodeInfo? {
        if (!isCurrent(token)) return null
        val root = rootInActiveWindow ?: return null
        if (targetFor(root) != token.target) return null
        val input = when (token.target.pkg) {
            PKG_WECHAT -> findEditable(root)
            "com.tencent.mobileqq" -> root.findAccessibilityNodeInfosByViewId("com.tencent.mobileqq:id/input").firstOrNull()
            "com.ss.android.lark" -> root.findAccessibilityNodeInfosByViewId("com.ss.android.lark:id/kb_rich_text_content").firstOrNull()
            "com.twitter.android" -> findEditable(root)
            else -> null // Unknown apps support explicit clipboard copy, not unverified writes.
        }
        // Re-read the node after SET_TEXT: the accessibility cache may still
        // contain the previous draft even though the write already succeeded.
        input ?: return null
        if (!input.refresh()) return null
        return input.takeIf { it.isVisibleToUser && it.isEnabled && !it.isPassword }
    }

    /** Fill without blocking the main thread; all retries re-resolve the original target. */
    private fun fillInput(token: ConversationSession.Token, text: String) {
        fun finish(ok: Boolean) {
            if (!isCurrent(token)) return
            if (ok) overlay?.toast("已填入，确认后自己发送")
            else { copyToClipboard(text); overlay?.toast("已复制，长按输入框粘贴") }
        }
        if (!isCurrent(token)) { overlay?.toast("会话已变化，请重新分析后填入"); return }
        if (inputFor(token) == null) { finish(false); return }
        GuardedInputWriter(
            resolve = {
                inputFor(token)?.let { node ->
                    object : GuardedInputWriter.Input {
                        override val text: String? get() = node.text?.toString()
                        override fun setText(text: String) = setTextRaw(node, text)
                        override fun focus() { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
                        override fun paste() { node.performAction(AccessibilityNodeInfo.ACTION_PASTE) }
                    }
                }
            },
            later = { delay, action -> main.postDelayed({ action() }, delay) },
            copy = { copyToClipboard(it) },
            complete = { finish(it) }
        ).fill(text)
    }

    private fun setTextRaw(edit: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun findEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var guard = 0
        var found: AccessibilityNodeInfo? = null
        while (stack.isNotEmpty() && guard < 5000) {
            guard++
            val node = stack.removeLast()
            if (node.isEditable && node.isVisibleToUser && node.isEnabled && !node.isPassword) {
                if (found != null) return null // Ambiguous editor: clipboard only.
                found = node
            }
            for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.addLast(it) }
        }
        return if (stack.isEmpty()) found else null
    }

    private fun snapshotFor(root: AccessibilityNodeInfo): ChatSnapshot? {
        val pkg = root.packageName?.toString()
        if (pkg != PKG_WECHAT) wechatIdentity.clear()
        val raw = adapters[pkg]?.extract(root, resources)
            ?: if (wechatTitleRegion(root) != null) ChatSnapshot(null, emptyList()) else return null
        if (pkg != PKG_WECHAT) return raw
        val treeTitle = raw.title?.takeIf { it.isNotBlank() }
        val screenshotTitle = if (treeTitle == null) wechatTitleGuard.title(wechatPageKey(root, raw)) else null
        val title = wechatIdentity.observe(root.windowId, raw.messages, treeTitle ?: screenshotTitle)
            ?: return raw
        val source = if (treeTitle == null) "\uff08OCR\u6807\u9898\uff0c\u8bf7\u6838\u5bf9\uff09" else ""
        val time = raw.messages.lastOrNull()?.visibleTime
        val named = raw.copy(title = title, note = listOfNotNull(raw.note,
            "会话：$title$source\n" +
                if (time == null) "末条发送时间未知" else "末条页面时间：$time（非逐条精确时间）").joinToString("；"))
        return if (named.voiceTranscriptRects.isEmpty()) named
            else wechatTranscripts.get(wechatTranscripts.key(root.windowId, named)) ?: named
    }

    /** One screenshot, only custom-drawn voice transcript crops; never infer a sender from OCR. */
    private fun resolveWechatTranscripts(root: AccessibilityNodeInfo, snapshot: ChatSnapshot,
                                          target: ConversationSession.Target,
                                          run: BackfillSession.Run? = null, silent: Boolean = false,
                                          onReady: () -> Unit) {
        if (ocrBusy || snapshot.voiceTranscriptRects.isEmpty()) return
        observeTarget(target)
        val token = session.token() ?: return
        val key = wechatTranscripts.key(root.windowId, snapshot)
        val started = android.os.SystemClock.elapsedRealtime()
        fun stillSamePage(): Boolean {
            if (!isCurrent(token) || (run != null && !backfill.isCurrent(run))) return false
            val live = rootInActiveWindow ?: return false
            if (live.packageName?.toString() != PKG_WECHAT || live.windowId != target.windowId) return false
            val raw = adapters[PKG_WECHAT]?.extract(live, resources) ?: return false
            val liveTitle = raw.title ?: wechatIdentity.observe(live.windowId, raw.messages,
                wechatTitleGuard.title(wechatPageKey(live, raw)))
            return liveTitle == snapshot.title &&
                wechatTranscripts.key(live.windowId, raw.copy(title = liveTitle)) == key
        }
        fun failed(message: String, retry: Boolean = false) {
            ocrBusy = false
            voiceOcrActive = false
            val waiting = afterVoiceOcr?.takeIf { it.first == token }
            if (waiting != null) afterVoiceOcr = null
            if (destroyed || !prefs.enabled || prefs.overlayHidden || !isCurrent(token) ||
                (silent && waiting == null)) return
            if (run != null) {
                if (backfill.isCurrent(run)) {
                    overlay?.showBackfillWaiting(message)
                    if (retry) scheduleWechatBackfill(1100L)
                }
            } else overlay?.showError(message)
        }
        ocrBusy = true
        voiceOcrActive = true
        if (run == null && !silent) overlay?.showLoading("正在读取已展开的语音转写…")
        else if (run != null) overlay?.showBackfillWaiting("正在读取本屏语音转写；识别完再继续上翻…")
        val capture = Runnable {
          if (!stillSamePage()) {
              failed("语音转写区域已移动，请停稳后重试", true)
              return@Runnable
          }
          screenCapture.capture(shouldCapture = { stillSamePage() }) { result ->
            if (!stillSamePage()) {
                if (result is ScreenCapture.Result.Ok) result.bitmap.recycle()
                failed("语音转写区域已移动，请停稳后重试", true)
                return@capture
            }
            if (result is ScreenCapture.Result.Failed) {
                failed(result.humanMessage, result.code == ScreenCapture.CODE_THROTTLED || result.code == 3)
                return@capture
            }
            result as ScreenCapture.Result.Ok
            ocr.scaleX = result.scaleX; ocr.scaleY = result.scaleY
            ocr.originX = result.originX; ocr.originY = result.originY
            val texts = LinkedHashMap<Int, String>()
            var remaining = snapshot.voiceTranscriptRects.size
            snapshot.voiceTranscriptRects.forEach { region ->
                val rect = region.rect
                val crop = Rect(((rect.left - result.originX) * result.scaleX).toInt(),
                    ((rect.top - result.originY) * result.scaleY).toInt(),
                    ((rect.right - result.originX) * result.scaleX).toInt(),
                    ((rect.bottom - result.originY) * result.scaleY).toInt())
                ocr.recognize(result.bitmap, crop) { lines ->
                    val text = lines.sortedBy { it.bounds.top }.joinToString(" ") { it.text.trim() }
                    texts[region.messageIndex] = listOfNotNull(texts[region.messageIndex], text)
                        .filter(String::isNotBlank).joinToString(" ")
                    if (--remaining == 0) {
                        result.bitmap.recycle()
                        if (!stillSamePage()) failed("语音转写区域已移动，请停稳后重试", true)
                        else {
                            wechatTranscripts.remember(key, snapshot, texts)
                            ocrBusy = false
                            voiceOcrActive = false
                            Log.i(TAG, "wechat voice crops=${snapshot.voiceTranscriptRects.size} " +
                                "recognized=${texts.values.count(String::isNotBlank)} duration=" +
                                "${android.os.SystemClock.elapsedRealtime() - started}ms")
                            val waiting = afterVoiceOcr?.takeIf { it.first == token }
                            if (waiting != null) afterVoiceOcr = null
                            onReady()
                            if (waiting != null && isCurrent(waiting.first)) waiting.second.invoke()
                        }
                    }
                }
            }
          }
        }
        val cooldown = screenCapture.cooldownRemainingMs()
        if (cooldown > 0L) main.postDelayed(capture, cooldown + 25L) else capture.run()
    }

    private fun relationshipForChannel(relationship: String, pkg: String): String =
        relationship + if (pkg == PKG_WECHAT) "\n当前渠道事实：双方已经在微信内聊天；" +
            "不要再建议加微信、要微信号或加绿泡泡，也不要编造我最近做过的事情。" else ""

    private fun titlePageChanged() {
        // A late screenshot callback must not resurrect the panel on the home
        // screen or another app after navigation already hid it.
        val live = rootInActiveWindow
        if (!destroyed && prefs.enabled && !prefs.overlayHidden && live != null && wechatTitleRegion(live) != null)
            overlay?.showError("聊天页面已变化，请重新分析")
        else overlay?.hide()
    }

    /** These nodes identify the toolbar, not message text or a profile card. */
    private fun wechatTitleRegion(root: AccessibilityNodeInfo): Rect? {
        if (root.packageName?.toString() != PKG_WECHAT) return null
        val editor = findEditable(root) ?: return null
        val editorBounds = Rect().also { editor.getBoundsInScreen(it) }
        if (editorBounds.top < resources.displayMetrics.heightPixels / 2) return null
        return root.findAccessibilityNodeInfosByViewId("com.tencent.mm:id/gp")
            .asSequence().filter { it.isVisibleToUser }
            .map { Rect().also(it::getBoundsInScreen) }
            .firstOrNull { it.width() > 8 && it.height() > 8 && it.top >= 0 &&
                it.bottom < resources.displayMetrics.heightPixels * 0.14 }
    }

    private fun wechatPageKey(root: AccessibilityNodeInfo,
                              snapshot: ChatSnapshot? = adapters[PKG_WECHAT]?.extract(root, resources)): String? {
        val region = wechatTitleRegion(root) ?: return null
        return "${root.windowId}|$region|${snapshot?.messages.orEmpty()}"
    }

    /** Resolve locally before sending anything to a model or associating memory. */
    private fun resolveWechatOpeningTitle(root: AccessibilityNodeInfo, purpose: ConversationPurpose) {
        val originalPage = wechatPageKey(root) ?: return
        val originalWindow = root.windowId
        fun samePage(): Boolean = rootInActiveWindow?.let {
            it.packageName?.toString() == PKG_WECHAT && it.windowId == originalWindow &&
                wechatPageKey(it) == originalPage
        } == true
        overlay?.showLoading("正在核对微信标题，准备${purpose.label}建议…")
        resolveWechatTitle(root, quiet = true, onProblem = { message, retryable ->
            val live = rootInActiveWindow
            if (live?.packageName?.toString() == PKG_WECHAT && live.windowId == originalWindow) {
                // Harmless redraws can expire a screenshot ticket while the actual
                // page is unchanged. Retry only that exact page, at most twice.
                val queued = retryable && samePage() &&
                    retrySupportedScopeRead("${purpose.key}-title", PKG_WECHAT, originalWindow) {
                        if (samePage()) requestConversationSuggestions(purpose)
                        else overlay?.showError("聊天页面已变化，请重新生成${purpose.label}建议", "重试${purpose.label}建议") {
                            requestConversationSuggestions(purpose)
                        }
                    }
                if (!queued) {
                    scopeReadRetry.reset("${purpose.key}-title")
                    overlay?.showError(message, "重试${purpose.label}建议") { requestConversationSuggestions(purpose) }
                }
            }
        }) {
            scopeReadRetry.reset("${purpose.key}-title")
            requestConversationSuggestions(purpose)
        }
    }

    private fun resolveWechatTitle(root: AccessibilityNodeInfo,
                                   backgroundRun: BackfillSession.Run? = null,
                                   onProblem: ((String, Boolean) -> Unit)? = null,
                                   quiet: Boolean = false,
                                   onReady: () -> Unit) {
        if (ocrBusy || analyzing) { overlay?.showProgress("上一轮识别仍在进行，请稍候…"); return }
        val region = wechatTitleRegion(root) ?: return
        val page = wechatPageKey(root) ?: return
        val ticket = wechatTitleGuard.begin(page)
        val start = android.os.SystemClock.elapsedRealtime()
        fun stillCurrent() = !destroyed && prefs.enabled && !prefs.overlayHidden &&
            (backgroundRun == null || backfill.isCurrent(backgroundRun)) &&
            wechatTitleGuard.accepts(ticket, rootInActiveWindow?.let { wechatPageKey(it) })
        fun changed() {
            if (backgroundRun == null) {
                onProblem?.invoke("聊天页面已变化", true)
                if (!quiet) titlePageChanged()
            }
            else if (backfill.isCurrent(backgroundRun)) onProblem?.invoke("页面还在变化，停稳后继续。", true)
        }
        fun failed(message: String, retry: Boolean = false) {
            if (backgroundRun == null) {
                onProblem?.invoke(message, retry)
                if (!quiet) overlay?.showError(message)
            }
            else if (backfill.isCurrent(backgroundRun)) onProblem?.invoke(message, retry)
        }
        ocrBusy = true
        RuntimeState.recordAction(this, "正在识别微信聊天标题")
        if (backgroundRun == null && !quiet) overlay?.showLoading("正在识别微信聊天标题…")
        screenCapture.capture(shouldCapture = { stillCurrent() }) { result ->
            if (!stillCurrent()) {
                if (result is ScreenCapture.Result.Ok) result.bitmap.recycle()
                ocrBusy = false
                changed()
                return@capture
            }
            if (result is ScreenCapture.Result.Failed) {
                ocrBusy = false
                Log.i(TAG, "wechat title screenshot failed code=${result.code}")
                failed(result.humanMessage, result.code == ScreenCapture.CODE_THROTTLED || result.code == 3)
                return@capture
            }
            result as ScreenCapture.Result.Ok
            ocr.scaleX = result.scaleX; ocr.scaleY = result.scaleY
            ocr.originX = result.originX; ocr.originY = result.originY
        val nameRegion = wechatNameOcrRegion(root, region)
        val crop = Rect(((nameRegion.left - result.originX) * result.scaleX).toInt(),
                ((nameRegion.top - result.originY) * result.scaleY).toInt(),
                ((nameRegion.right - result.originX) * result.scaleX).toInt(),
                ((nameRegion.bottom - result.originY) * result.scaleY).toInt())
            fun finishTitleRecognition(lines: List<OcrLine>, pass: String) {
                ocrBusy = false
                val title = wechatTitleCandidate(lines.map { it.text },
                    WECHAT_NON_CHAT_TITLES, ::isTransientTitle)
                Log.i(TAG, "wechat title OCR pass=$pass lines=${lines.size} " +
                    "lengths=${lines.map { it.text.length }} accepted=${title != null}")
                if (!stillCurrent()) {
                    changed()
                } else if (title == null) {
                    failed("未能看清聊天标题，请保持聊天页完整显示后重试")
                } else if (wechatTitleGuard.remember(ticket,
                        rootInActiveWindow?.let { wechatPageKey(it) }, title)) {
                    rootInActiveWindow?.let { live ->
                        adapters[PKG_WECHAT]?.extract(live, resources)?.let { visible ->
                            wechatIdentity.observe(live.windowId, visible.messages, title)
                        }
                    }
                    Log.i(TAG, "wechat title resolved locally duration=${android.os.SystemClock.elapsedRealtime() - start}ms")
                    // A following full-message OCR must respect Android's shot interval.
                    val delay = if (backgroundRun != null) 0L else
                        (1100L - (android.os.SystemClock.elapsedRealtime() - start)).coerceAtLeast(0)
                    main.postDelayed({
                        if (stillCurrent()) onReady()
                        else changed()
                    }, delay)
                }
            }
            ocr.recognize(result.bitmap, crop) { lines ->
                if (wechatTitleCandidate(lines.map { it.text }, WECHAT_NON_CHAT_TITLES,
                        ::isTransientTitle) != null) {
                    result.bitmap.recycle()
                    finishTitleRecognition(lines, "native")
                } else {
                    // A newly edited short nickname can occupy only one small
                    // glyph in WeChat's toolbar. Re-run local OCR on a 3x copy
                    // of that same title-only crop before showing a hard error.
                    val retryBitmap = runCatching {
                        val safeCrop = Rect(crop).apply {
                            if (!intersect(0, 0, result.bitmap.width, result.bitmap.height))
                                throw IllegalArgumentException("empty title crop")
                        }
                        val clipped = Bitmap.createBitmap(result.bitmap, safeCrop.left, safeCrop.top,
                            safeCrop.width(), safeCrop.height())
                        val enlarged = Bitmap.createScaledBitmap(clipped,
                            (clipped.width * 3).coerceAtMost(2048),
                            (clipped.height * 3).coerceAtMost(1024), true)
                        if (enlarged !== clipped) clipped.recycle()
                        enlarged
                    }.getOrNull()
                    result.bitmap.recycle()
                    if (retryBitmap == null) {
                        finishTitleRecognition(emptyList(), "upscale-unavailable")
                    } else {
                        ocr.recognize(retryBitmap, null) { retryLines ->
                            retryBitmap.recycle()
                            finishTitleRecognition(retryLines, "upscaled")
                        }
                    }
                }
            }
        }
    }

    /** A bottom editor plus a real title distinguishes a chat from the WeChat list/search page. */
    private fun wechatManualTitle(root: AccessibilityNodeInfo): String? {
        val title = (findWeChatTitle(root, Int.MAX_VALUE, resources.displayMetrics.widthPixels, resources)
            ?: wechatTitleGuard.title(wechatPageKey(root)))
            ?.takeUnless { it in WECHAT_NON_CHAT_TITLES } ?: return null
        val editor = findEditable(root) ?: return null
        val bounds = Rect()
        editor.getBoundsInScreen(bounds)
        return title.takeIf { bounds.top >= resources.displayMetrics.heightPixels / 2 }
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("jev_reply", text))
    }

    override fun onInterrupt() {
        leaveConversation()
        overlay?.hide()
    }

    override fun onDestroy() {
        destroyed = true
        RuntimeState.serviceConnected = false
        RuntimeState.lastEventPackage = null
        getSharedPreferences(Prefs.PREFS_MAIN, MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(preferencesListener)
        if (reviewReceiverRegistered) {
            runCatching { unregisterReceiver(snapshotReviewReceiver) }
            runCatching { unregisterReceiver(toolsReceiver) }
        }
        reviewReceiverRegistered = false
        reviewRequestId = null
        reviewTarget = null
        reviewActivityActive = false
        leaveConversation()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
        // Tear the overlay down and cut its callback so a stale button tap can
        // never call back into this dead instance.
        overlay?.onManualAnalyze = null
        overlay?.onDismissAnalysis = null
        overlay?.onRefreshAnalysis = null
        overlay?.onSaveContact = null
        overlay?.onOcrCapture = null
        overlay?.onToggleHistoryCapture = null
        overlay?.onHistoryInteraction = null
        overlay?.onToggleAutoBackfill = null
        overlay?.onRetryHistoryCapture = null
        overlay?.onOpenConversation = null
        overlay?.onHideAssistant = null
        overlay?.onReviewSnapshot = null
        overlay?.onChooseContact = null
        overlay?.onApproveReplyVoice = null
        overlay?.onEditReplyVoice = null
        toolsRequestId = null
        toolsTarget = null
        toolsActivityActive = false
        overlay?.hide()
        overlay = null
        worker.shutdownNow()
        memoryWorker.shutdown()
        profileWorker.shutdownNow()
    }

    companion object {
        private const val TAG = "JEVASSIST"

        private const val PKG_WECHAT = "com.tencent.mm"
        private val OPENING_CHAT_PACKAGES = setOf(
            PKG_WECHAT, "com.tantan.x", "cn.soulapp.android", "cn.neoclub.uki", QingtengAdapter.PACKAGE
        )
        private val WECHAT_NON_CHAT_TITLES = setOf("微信", "通讯录", "发现", "我", "搜索")

        /** Whole-screen OCR keeps the middle: no action bar, no input area. */
        private const val TOP_CROP = 0.12f
        private const val BOTTOM_CROP = 0.84f

        /** Said on the panel whenever a snapshot came from flat-screen OCR. */
        private const val OCR_NOTE = "OCR 按位置粗分双方；不确定的发言者需核对。只保留可见的页面时间，缺失标未知，不代表刚收到。"
        private val TAIL_TIME = Regex("""\d{1,2}[:：]\d{2}$""")

        /** Transient placeholder titles apps show while a chat page is still
         *  connecting/loading — see [isTransientTitle]. Matched as a substring,
         *  case-insensitive, after trimming a trailing ellipsis. */
        private val TRANSIENT_TITLE_WORDS = listOf(
            "连接中", "正在连接", "未连接", "Connecting",
            "加载中", "Loading", "同步中", "Syncing"
        )
    }
}
