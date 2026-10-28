package com.jev.probe

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.jev.probe.core.Prefs
import com.jev.probe.core.kb.Contact
import com.jev.probe.core.kb.ContactListOrder
import com.jev.probe.core.kb.KbStore
import com.jev.probe.core.kb.Note
import com.jev.probe.core.kb.ProfileClaim
import com.jev.probe.core.kb.RelationshipProfile
import com.jev.probe.jev.RelationshipProfileGenerator
import com.jev.probe.core.kb.profileUserContext
import com.jev.probe.jev.RelationshipProfileJobs
import com.jev.probe.jev.ReplyClient
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Knowledge base manager: the notes the assistant may quote, and the contacts
 * that tie a conversation title (across apps) to a person.
 *
 * Everything shown here lives in the app-private `filesDir/kb` directory and
 * nowhere else. Plain code-built views, same card/pill vocabulary as
 * [SettingsActivity].
 */
class KnowledgeActivity : AppCompatActivity() {

    private companion object {
        val PROFILE_APPS = setOf("com.tencent.mm", "com.tantan.x", "cn.soulapp.android", "com.wepie.ivy")
    }

    private lateinit var store: KbStore
    private lateinit var container: LinearLayout
    private val profileExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val contactListExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var contactListGeneration = 0
    private lateinit var contactOrder: ContactListOrder

    /** 0 = notes, 1 = contacts. */
    private var tab = 0

    private val accent = Color.parseColor("#3A7AFE")
    private val ink = Color.parseColor("#111827")
    private val sub = Color.parseColor("#6B7280")
    private val pillOff = Color.parseColor("#EEF1F5")
    private val red = Color.parseColor("#DC2626")

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).roundToInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = KbStore.get(this)
        contactOrder = ContactListOrder(this)
        tab = savedInstanceState?.getInt("kbTab", 0) ?: 0
        window.decorView.setBackgroundColor(Color.parseColor("#F2F3F5"))

        val scroll = ScrollView(this)
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(28))
        }
        container.padForSystemBars()   // edge-to-edge: keep the title off the status bar
        scroll.addView(container)
        setContentView(scroll)
        render()
    }

    override fun onResume() {
        super.onResume()
        if (::container.isInitialized && tab == 1) render()
    }

    override fun onDestroy() {
        contactListExecutor.shutdownNow()
        profileExecutor.shutdownNow()
        super.onDestroy()
    }

    // --------------------------------------------------------------- screens

    private fun render() {
        contactListGeneration++
        container.removeAllViews()
        container.addView(text("知识库与联系人", 24f, ink, bold = true))
        container.addView(text("聊天记忆默认只存在本机。首次分析联系人时会用设置中的回复模型分批读取该 App 的全部已保存记录，生成后自动关联到此联系人并用于后续建议；可随时查看和编辑。",
            12f, sub).apply { setPadding(0, dp(6), 0, dp(4)) })
        container.addView(tabs())
        if (tab == 0) renderNotes() else renderContacts()
    }

    private fun tabs(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
        }
        listOf("笔记", "联系人").forEachIndexed { i, name ->
            val pill = TextView(this).apply {
                text = name; textSize = 13f; gravity = Gravity.CENTER
                setPadding(dp(18), dp(8), dp(18), dp(8))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8) }
                setTextColor(if (i == tab) Color.WHITE else sub)
                setTypeface(typeface, if (i == tab) Typeface.BOLD else Typeface.NORMAL)
                background = round(dp(9), if (i == tab) accent else pillOff)
                setOnClickListener { tab = i; render() }
            }
            row.addView(pill)
        }
        return row
    }

    // ----------------------------------------------------------------- notes

    private fun renderNotes() {
        val notes = store.notes().sortedByDescending { it.updatedAt }
        container.addView(twoButtons("新建笔记", { editNoteDialog(null) },
            "从文本导入", { importNotesDialog() }))
        if (notes.isEmpty()) {
            container.addView(emptyCard("还没有笔记。写点该记住的事实：习惯、忌口、项目代号、约定过的时间。"))
            return
        }
        notes.forEach { container.addView(noteRow(it)) }
        container.addView(text("点条目编辑，长按删除。命中规则：任一标签或标题出现在会话标题或最近 6 条消息里。",
            11f, sub).apply { setPadding(dp(2), dp(12), 0, 0) })
    }

    private fun noteRow(n: Note): View {
        val c = card()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val head = n.title.ifBlank { "（无标题）" } + if (n.alwaysOn) "  · 常驻" else ""
        left.addView(text(head, 15f, ink, bold = true))
        left.addView(text(
            if (n.tags.isEmpty()) "无标签" else "标签：" + n.tags.joinToString("、"),
            12f, sub).apply { setPadding(0, dp(3), 0, 0) })
        left.addView(text(n.content.replace("\n", " ").take(46), 12f, sub)
            .apply { setPadding(0, dp(3), 0, 0) })
        row.addView(left)
        row.addView(smallToggle(n.enabled) {
            store.saveNote(n.copy(enabled = !n.enabled)); render()
        })
        c.addView(row)
        c.setOnClickListener { editNoteDialog(n) }
        c.setOnLongClickListener {
            confirm("删除笔记", "删除「${n.title}」？不可恢复。") {
                store.deleteNote(n.id); render()
            }
            true
        }
        return c
    }

    private fun editNoteDialog(existing: Note?) {
        val box = dialogBox()
        val titleEdit = edit(existing?.title ?: "", "标题，例如：口味忌口")
        val contentEdit = edit(existing?.content ?: "", "正文，写清楚事实本身").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 4; gravity = Gravity.TOP
        }
        val tagsEdit = edit(existing?.tags?.joinToString("，") ?: "", "逗号分隔，例如：吃饭，周末")
        val alwaysRow = toggleRow("常驻（每次分析都带上）", existing?.alwaysOn ?: false)
        val enabledRow = toggleRow("启用", existing?.enabled ?: true)
        box.addView(label("标题")); box.addView(titleEdit)
        box.addView(label("正文")); box.addView(contentEdit)
        box.addView(label("标签")); box.addView(tagsEdit)
        box.addView(alwaysRow); box.addView(enabledRow)

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "新建笔记" else "编辑笔记")
            .setView(wrapScroll(box))
            .setPositiveButton("保存") { _, _ ->
                val title = titleEdit.text.toString().trim()
                val content = contentEdit.text.toString().trim()
                if (title.isBlank() && content.isBlank()) {
                    toast("标题和正文不能都空着"); return@setPositiveButton
                }
                store.saveNote(Note(
                    id = existing?.id ?: KbStore.newId(),
                    title = title,
                    content = content,
                    tags = splitTags(tagsEdit.text.toString()),
                    alwaysOn = (alwaysRow.tag as? Boolean) ?: false,
                    enabled = (enabledRow.tag as? Boolean) ?: true
                ))
                render()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun importNotesDialog() {
        val box = dialogBox()
        box.addView(text("按空行分段，每段第一行当标题，其余当正文。", 12f, sub))
        val input = edit("", "口味忌口\n不吃香菜，海鲜过敏\n\n项目代号\n内部叫小蓝").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 8; gravity = Gravity.TOP
        }
        box.addView(input)
        AlertDialog.Builder(this)
            .setTitle("从文本导入")
            .setView(wrapScroll(box))
            .setPositiveButton("导入") { _, _ ->
                val chunks = input.text.toString().split(Regex("\\r?\\n[ \\t]*\\r?\\n"))
                var n = 0
                chunks.forEach { chunk ->
                    val lines = chunk.trim().split("\n").map { it.trim() }.filter { it.isNotEmpty() }
                    if (lines.isNotEmpty()) {
                        store.saveNote(Note(
                            id = KbStore.newId(),
                            title = lines.first(),
                            content = lines.drop(1).joinToString("\n")
                        ))
                        n++
                    }
                }
                toast(if (n == 0) "没解析出内容" else "已导入 $n 条")
                render()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun splitTags(raw: String): List<String> =
        raw.split(",", "，", "、").map { it.trim() }.filter { it.isNotEmpty() }

    // -------------------------------------------------------------- contacts

    private fun renderContacts() {
        val contacts = store.contacts()
        container.addView(twoButtons("新建联系人", { editContactDialog(null) }, null, null))
        if (contacts.isEmpty()) {
            container.addView(emptyCard(
                "还没有联系人。也可以在聊天里长按悬浮球，选「把当前会话存为联系人」。"))
            return
        }
        val section = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        section.addView(text("正在读取聊天记忆数量…", 12f, sub).apply { setPadding(0, dp(12), 0, 0) })
        container.addView(section)
        val generation = contactListGeneration
        contactListExecutor.execute {
            val result = runCatching { store.contactMemoryCounts() }
            runOnUiThread {
                if (isFinishing || isDestroyed || tab != 1 || generation != contactListGeneration) return@runOnUiThread
                section.removeAllViews()
                val counts = result.getOrNull()
                if (counts == null) {
                    section.addView(twoButtons("数量读取失败，点此重试", { render() }, null, null))
                    return@runOnUiThread
                }
                val ordered = contactOrder.arrange(store.contacts(), counts)
                section.addView(twoButtons(
                    if (contactOrder.manual) "按记忆降序" else "记忆降序 ✓", {
                        if (contactOrder.useMemoryOrder()) render() else toast("排序保存失败，请重试")
                    }, "手动拖动排序", {
                        ContactOrderDialog.show(this, ordered, counts) { ids ->
                            val saved = contactOrder.saveManual(ids)
                            if (saved) { toast("联系人顺序已保存"); render() }
                            saved
                        }
                    }))
                section.addView(text(if (contactOrder.manual)
                    "当前：手动顺序 · 新联系人追加在后面"
                else "当前：聊天记忆从多到少 · 按已保存记录条数排序", 12f, sub)
                    .apply { setPadding(dp(2), dp(8), 0, dp(2)) })
                ordered.forEach { section.addView(contactRow(it, counts[it.id] ?: 0)) }
                section.addView(text("点条目编辑，长按删除。手动排序请使用上方排序按钮。", 11f, sub)
                    .apply { setPadding(dp(2), dp(12), 0, 0) })
            }
        }
    }

    private fun contactRow(c0: Contact, logN: Int): View {
        val c = card()
        c.addView(text(c0.name.ifBlank { "（无名）" }, 15f, ink, bold = true))
        if (c0.aliases.isNotEmpty())
            c.addView(text("别名：" + c0.aliases.joinToString("、"), 12f, sub)
                .apply { setPadding(0, dp(3), 0, 0) })
        if (c0.apps.isNotEmpty())
            c.addView(text("来源：" + c0.apps.joinToString("、") { appLabel(it) }, 12f, sub)
                .apply { setPadding(0, dp(3), 0, 0) })
        if (c0.relationship.isNotBlank())
            c.addView(text("关系：" + c0.relationship.replace("\n", " ").take(40), 12f, sub)
                .apply { setPadding(0, dp(3), 0, 0) })
        c.addView(text(if (c0.memoryEnabled) "聊天记忆：已开启" else "聊天记忆：未开启",
            12f, sub).apply { setPadding(0, dp(3), 0, 0) })
        if (c0.notes.isNotBlank())
            c.addView(text("备注：" + c0.notes.replace("\n", " ").take(40), 12f, sub)
                .apply { setPadding(0, dp(3), 0, 0) })

        val profileApps = c0.apps.filter { it in PROFILE_APPS }
        if (profileApps.isNotEmpty()) {
            c.addView(label("按 App 隔离的关系档案"))
            profileApps.forEach { appName ->
                val accepted = c0.relationshipProfiles[appName]
                val draft = c0.relationshipProfileDrafts[appName]
                val stale = accepted != null && store.relationshipProfileNeedsUpdate(c0, appName)
                val generating = RelationshipProfileJobs.isActive(c0.id, appName)
                val state = when {
                    generating -> "后台生成中，完成后自动关联"
                    draft != null -> "旧版待审核草稿"
                    accepted == null -> "尚未生成"
                    stale -> "有新记录，建议手动更新"
                    else -> "已关联并用于此 App"
                }
                c.addView(text("${appLabel(appName)}：$state", 12f, if (draft != null) accent else sub)
                    .apply { setPadding(0, dp(2), 0, dp(4)) })
                if (generating) {
                    val progress = RelationshipProfileJobs.current(c0.id, appName)
                    c.addView(text("${progress?.percent ?: 0}% · ${progress?.phase ?: "后台正在分批分析"}；完成后会自动用于此联系人此 App 的建议。", 11f, sub)
                        .apply { setPadding(0, dp(4), 0, dp(6)) })
                    c.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                        max = 100
                        this.progress = progress?.percent ?: 0
                        progressTintList = android.content.res.ColorStateList.valueOf(accent)
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(7))
                            .apply { bottomMargin = dp(8) }
                    })
                } else {
                    c.addView(twoButtons(
                        if (accepted == null) "生成关系档案" else "更新关系档案",
                        { generateRelationshipProfile(c0, appName) },
                        if (accepted != null || draft != null) "查看 / 编辑" else null,
                        if (accepted != null || draft != null) ({ showRelationshipProfile(c0.id, appName) }) else null
                    ))
                }
            }
        }

        c.addView(TextView(this).apply {
            text = "查看 / 编辑聊天记忆（$logN 条采集行） ›"
            textSize = 13f; setTextColor(accent); setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(12), 0, dp(7))
            setOnClickListener {
                startActivity(Intent(this@KnowledgeActivity, HistoryActivity::class.java)
                    .putExtra(HistoryActivity.EXTRA_CONTACT_ID, c0.id))
            }
        })
        val clear = TextView(this).apply {
            text = "清空此人全部记忆"
            textSize = 11.5f; setTextColor(red)
            setPadding(0, dp(3), 0, dp(2))
            setOnClickListener {
                if (logN == 0) { toast("本来就没有历史"); return@setOnClickListener }
                confirm("清空历史", "删掉「${c0.name}」的 $logN 条聊天历史？联系人档案保留。") {
                    store.clearLog(c0.id); render()
                }
            }
        }
        c.addView(clear)
        c.setOnClickListener { editContactDialog(c0) }
        c.setOnLongClickListener {
            confirm("删除联系人", "删除「${c0.name}」及其全部历史？不可恢复。") {
                store.deleteContact(c0.id); render()
            }
            true
        }
        return c
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("kbTab", tab)
        super.onSaveInstanceState(outState)
    }

    private fun generateRelationshipProfile(contact: Contact, appName: String) {
        val prefs = Prefs(this)
        if (prefs.effectiveReplyKey().isBlank()) {
            toast("先到设置里配置回复模型和密钥")
            return
        }
        confirm("生成${appLabel(appName)}关系档案？",
            "将核对「${contact.name}」在${appLabel(appName)}的全部本地记录，复用此前完成的分段摘要，仅将新增或修改的分段及受影响的汇总发送到当前回复模型。首次使用或更换模型需要重新建立摘要；失败或取消后再次点击可续做。最终档案仍综合全部历史证据，保存前保留原档案。摘要仅存本机，清空聊天记忆时一起删除；不会向联系人发送消息。") {
            if (!RelationshipProfileJobs.begin(contact.id, appName)) {
                toast("该联系人档案正在后台生成，请稍后查看")
                return@confirm
            }
            val progressText = text("0% · 正在读取本地记录…", 14f, ink)
                .apply { setPadding(0, dp(4), 0, dp(10)) }
            val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = 0
                progressTintList = android.content.res.ColorStateList.valueOf(accent)
            }
            val progressView = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(16), dp(20), dp(14))
                addView(progressText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(progressBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10)))
            }
            var finished = false
            var latestProgress = com.jev.probe.jev.ProfileGenerationProgress(
                "正在读取本地记录", 0, 0, 0
            )
            var job: java.util.concurrent.Future<*>? = null
            val dialog = AlertDialog.Builder(this)
                .setTitle("生成关系档案")
                .setView(progressView)
                .setNegativeButton("取消", null)
                .create()
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                    if (!finished) {
                        job?.cancel(true)
                        progressText.text = "已请求取消，完成的摘要已保存在本机，下次可续做。正在处理的网络请求可能需要等返回。"
                        finished = true
                        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).text = "关闭"
                    } else dialog.dismiss()
                }
            }
            dialog.show()
            try { job = profileExecutor.submit {
                try {
                    val source = store.relationshipSource(contact.id, appName)
                    if (source.lines.isEmpty()) throw IllegalStateException("没有读到此 App 下可用的聊天记录")
                    val client = ReplyClient(prefs)
                    val profile = RelationshipProfileGenerator(complete = { system, user, temperature ->
                        client.completeProfileRoundTrip(system, user, temperature)
                    }, checkpoints = com.jev.probe.jev.FileProfileCheckpoints(
                        this, contact.id, appName, client.profileCacheRoute
                    ), userContext = (store.contact(contact.id) ?: contact).profileUserContext(appName)).generate(source) { event ->
                        latestProgress = event
                        RelationshipProfileJobs.update(contact.id, appName, event)
                        runOnUiThread {
                        if (!isFinishing && dialog.isShowing) {
                            progressBar.progress = event.percent
                            progressText.text = "${event.percent}% · ${event.phase}\n\n${source.lines.size} 条已保存记录；发送时间不明的记录不会被当作同一时间。"
                        }
                    } }
                    if (Thread.currentThread().isInterrupted) throw InterruptedException("档案生成已取消")
                    if (!store.saveGeneratedRelationshipProfile(contact.id, appName, profile))
                        throw IllegalStateException("档案保存失败；原档案未更改")
                    runOnUiThread {
                        if (isFinishing || !dialog.isShowing) return@runOnUiThread
                        finished = true
                        progressBar.progress = 100
                        progressText.text = "关系档案已保存并自动关联到此联系人与此 App。${profile.coverageLabel()}\n\n${latestProgress.phase}\n\n后续匹配到此联系人时会自动带入建议；新增聊天后再次更新会复用未变化的摘要。"
                        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).text = "查看档案"
                        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                            dialog.dismiss()
                            render()
                            showRelationshipProfile(contact.id, appName)
                        }
                        render()
                    }
                } catch (e: Exception) {
                    val message = e.message?.take(220) ?: e.javaClass.simpleName
                    runOnUiThread {
                        if (isFinishing || !dialog.isShowing) return@runOnUiThread
                        finished = true
                        progressBar.progress = latestProgress.percent
                        progressText.text = "${latestProgress.percent}% · ${latestProgress.phase}\n\n生成失败：$message\n\n已完成摘要保存在本机，再次更新可续做。已有档案和聊天记录保持不变。"
                        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).text = "关闭"
                    }
                } finally {
                    RelationshipProfileJobs.finish(contact.id, appName)
                }
            } } catch (e: Exception) {
                RelationshipProfileJobs.finish(contact.id, appName)
                finished = true
                progressText.text = "无法启动后台任务：${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    private fun showRelationshipProfile(contactId: String, appName: String) {
        val contact = store.contact(contactId) ?: return
        val draft = contact.relationshipProfileDrafts[appName]
        val profile = draft ?: contact.relationshipProfiles[appName]
        if (profile == null) { toast("此 App 还没有关系档案"); return }
        val refs = buildSet {
            addAll(profile.relationshipStage.sourceRefs)
            addAll(profile.attitudeAssessment.sourceRefs)
            addAll(profile.conversationDirection.sourceRefs)
            profile.knownFacts.forEach { addAll(it.sourceRefs) }
            profile.interactionPatterns.forEach { addAll(it.sourceRefs) }
        }
        val evidence = store.profileEvidence(contactId, refs).associateBy { it.ref }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(10))
        }
        body.addView(text(if (draft != null) "旧版待审核草稿；接受后会仅用于「${contact.name}」在${appLabel(appName)}的建议。"
            else "档案已关联到「${contact.name}」；各条分别标明聊天依据或人工补充，态度推测不代表对方的确认。", 12f,
            if (draft != null) accent else sub))
        body.addView(text(profile.coverageLabel(), 12f, sub).apply { setPadding(0, dp(8), 0, dp(8)) })
        body.addView(text("关系阶段", 14f, ink, bold = true))
        body.addView(text(claimDescription(profile.relationshipStage), 13f, ink))
        addEvidence(body, profile.relationshipStage, evidence)
        body.addView(text("她对你的态度（推测）", 14f, ink, bold = true).apply { setPadding(0, dp(10), 0, 0) })
        body.addView(text(claimDescription(profile.attitudeAssessment), 13f, ink))
        addEvidence(body, profile.attitudeAssessment, evidence)
        addClaimSection(body, "已知事实与用户补充", profile.knownFacts, evidence)
        addClaimSection(body, "互动模式（推测）", profile.interactionPatterns, evidence)
        body.addView(text("后续聊天方向（参考）", 14f, ink, bold = true).apply { setPadding(0, dp(10), 0, 0) })
        body.addView(text(claimDescription(profile.conversationDirection), 13f, ink))
        addEvidence(body, profile.conversationDirection, evidence)
        body.addView(text("建议节奏", 14f, ink, bold = true).apply { setPadding(0, dp(10), 0, 0) })
        body.addView(text(profile.pacingAdvice, 13f, ink))
        if (profile.unknowns.isNotEmpty()) {
            body.addView(text("仍不能确定", 14f, ink, bold = true).apply { setPadding(0, dp(10), 0, 0) })
            body.addView(text(profile.unknowns.joinToString("\n• ", prefix = "• "), 13f, sub))
        }
        val scroll = wrapScroll(body).apply { isFillViewport = false }
        val builder = AlertDialog.Builder(this)
            .setTitle("${contact.name} · ${appLabel(appName)}关系档案")
            .setView(scroll)
            .setNegativeButton("编辑", null)
            .setNeutralButton(if (draft != null) "接受并用于建议" else "完成", null)
            .setPositiveButton("关闭", null)
        val dialog = builder.show()
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            dialog.dismiss()
            editRelationshipProfile(contactId, appName, profile)
        }
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            if (draft != null) {
                if (store.acceptRelationshipProfile(contactId, appName)) {
                    dialog.dismiss()
                    render()
                    toast("已接受；后续仅此 App 的分析会使用该档案")
                } else toast("接受失败，请重试")
            } else dialog.dismiss()
        }
    }

    private fun addClaimSection(
        body: LinearLayout,
        title: String,
        claims: List<ProfileClaim>,
        evidence: Map<String, com.jev.probe.core.kb.ProfileLine>
    ) {
        if (claims.isEmpty()) return
        body.addView(text(title, 14f, ink, bold = true).apply { setPadding(0, dp(10), 0, 0) })
        claims.forEach { claim ->
            body.addView(text("• ${claim.text}${if (claim.userEdited) "（你编辑）" else ""}", 13f, ink)
                .apply { setPadding(0, dp(3), 0, 0) })
            addEvidence(body, claim, evidence)
        }
    }

    private fun addEvidence(
        body: LinearLayout,
        claim: ProfileClaim,
        evidence: Map<String, com.jev.probe.core.kb.ProfileLine>
    ) {
        if (claim.sourceRefs.isEmpty()) {
            body.addView(text(if (claim.userEdited) "依据：由你手动维护" else "依据：未找到可核验记录", 11f, sub))
            return
        }
        val rows = claim.sourceRefs.mapNotNull(evidence::get)
        if (rows.isEmpty()) {
            body.addView(text("依据记录已不可用", 11f, sub))
            return
        }
        rows.forEach { line ->
            val side = if (line.side == "me") "我" else if (line.side == "other") "对方" else "说话人未知"
            val time = line.visibleTime?.let { " · 页面时间 $it" }.orEmpty()
            body.addView(text("依据 ${line.ref.removePrefix("db:")}$time · $side：${line.text.take(180)}",
                11f, sub).apply { setPadding(dp(8), dp(2), 0, dp(2)) })
        }
    }

    private fun claimDescription(claim: ProfileClaim): String = buildString {
        append(claim.text)
        if (!claim.userEdited) append("\n模型证据支持度：").append((claim.confidence * 100).toInt()).append("%")
    }

    private fun editRelationshipProfile(contactId: String, appName: String, original: RelationshipProfile) {
        val box = dialogBox()
        val stage = edit(original.relationshipStage.text, "关系阶段").multiline(3)
        val attitude = edit(original.attitudeAssessment.text, "对方态度判断").multiline(4)
        val facts = edit(original.knownFacts.joinToString("\n") { it.text }, "每行一条").multiline(5)
        val patterns = edit(original.interactionPatterns.joinToString("\n") { it.text }, "每行一条").multiline(4)
        val direction = edit(original.conversationDirection.text, "共同话题与自然延续方向").multiline(3)
        val pacing = edit(original.pacingAdvice, "建议节奏").multiline(3)
        val unknowns = edit(original.unknowns.joinToString("\n"), "每行一条").multiline(3)
        box.addView(label("关系阶段")); box.addView(stage)
        box.addView(label("对方态度（只作推测）")); box.addView(attitude)
        box.addView(label("已知事实")); box.addView(facts)
        box.addView(label("互动模式")); box.addView(patterns)
        box.addView(label("后续聊天方向（根据双方记录）")); box.addView(direction)
        box.addView(label("建议节奏")); box.addView(pacing)
        box.addView(label("尚不能确定")); box.addView(unknowns)
        box.addView(text("人工维护的关系阶段、态度、事实、互动模式和聊天方向会在模型更新时保留。可继续在这里修改；节奏与待确认事项会结合补充资料重新生成。保存后用于此联系人和此 App 的后续建议。",
            11f, sub).apply { setPadding(0, dp(8), 0, 0) })
        val dialog = AlertDialog.Builder(this)
            .setTitle("编辑关系档案")
            .setView(wrapScroll(box))
            .setPositiveButton("保存并用于后续建议", null)
            .setNegativeButton("取消", null)
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            fun editedClaim(old: ProfileClaim, newText: String): ProfileClaim =
                if (newText.trim() == old.text.trim()) old else ProfileClaim(newText.trim(), emptyList(), 0.0, true)
            fun editedLines(field: EditText, old: List<ProfileClaim>): List<ProfileClaim> =
                field.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }.map { value ->
                    old.firstOrNull { it.text.trim() == value } ?: ProfileClaim(value, emptyList(), 0.0, true)
                }
            val changed = original.copy(
                relationshipStage = editedClaim(original.relationshipStage, stage.text.toString()),
                attitudeAssessment = editedClaim(original.attitudeAssessment, attitude.text.toString()),
                knownFacts = editedLines(facts, original.knownFacts),
                interactionPatterns = editedLines(patterns, original.interactionPatterns),
                conversationDirection = editedClaim(original.conversationDirection, direction.text.toString()),
                pacingAdvice = pacing.text.toString().trim(),
                unknowns = unknowns.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }
            )
            if (!store.saveRelationshipProfile(contactId, appName, changed)) {
                toast("保存失败；原档案没有改变")
                return@setOnClickListener
            }
            dialog.dismiss()
            render()
            toast("已保存；该档案将用于此联系人此 App 的后续建议")
        }
    }

    private fun EditText.multiline(lines: Int) = apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        minLines = lines
        gravity = Gravity.TOP
    }

    private fun editContactDialog(existing: Contact?) {
        val box = dialogBox()
        val nameEdit = edit(existing?.name ?: "", "名字，一般就是会话标题")
        val aliasEdit = edit(existing?.aliases?.joinToString("\n") ?: "", "每行一个，仅填同一 App 内曾用名").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3; gravity = Gravity.TOP
        }
        val relEdit = edit(existing?.relationship ?: "", "例如：同事，带我做项目的组长")
        val memoryRow = toggleRow("为此人记录可见聊天（只存本机）", existing?.memoryEnabled ?: false)
        val notesEdit = edit(existing?.notes ?: "", "关于这个人要记住的事").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3; gravity = Gravity.TOP
        }
        box.addView(label("名字")); box.addView(nameEdit)
        box.addView(label("别名（同一 App 内，每行一个）")); box.addView(aliasEdit)
        box.addView(label("关系")); box.addView(relEdit)
        val appAliasEdits = existing?.appAliases.orEmpty().mapValues { (_, names) ->
            edit(names.joinToString("\n"), "每行一个已确认备注，删空即可解除关联").multiline(2)
        }
        appAliasEdits.forEach { (app, input) ->
            box.addView(label("${appLabel(app)} 已确认备注（仅用于此 App）")); box.addView(input)
        }
        box.addView(memoryRow)
        box.addView(text("从支持的聊天页保存联系人时会默认开启；只记录此后聊天页可见消息，时间不明会标为未知。可随时在此关闭。", 11f, sub))
        box.addView(label("备注")); box.addView(notesEdit)

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "新建联系人" else "编辑联系人")
            .setView(wrapScroll(box))
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = nameEdit.text.toString().trim()
                if (name.isBlank()) { toast("名字不能空"); return@setOnClickListener }
                val requestedMemory = (memoryRow.tag as? Boolean) ?: false
                val boundToApp = existing?.apps?.any {
                    it == "com.tencent.mm" || it == "com.tantan.x" || it == "com.wepie.ivy" || it == "cn.soulapp.android"
                } == true
                val saved = store.saveContact(Contact(
                    id = existing?.id ?: KbStore.newId(),
                    name = name,
                    aliases = aliasEdit.text.toString().split("\n")
                        .map { it.trim() }.filter { it.isNotEmpty() },
                    apps = existing?.apps ?: emptyList(),
                    memoryEnabled = requestedMemory && boundToApp,
                    relationship = relEdit.text.toString().trim(),
                    notes = notesEdit.text.toString().trim(),
                    autoSummary = existing?.autoSummary ?: "",
                    relationshipProfiles = existing?.relationshipProfiles ?: emptyMap(),
                    relationshipProfileDrafts = existing?.relationshipProfileDrafts ?: emptyMap(),
                    appAliases = appAliasEdits.mapValues { (_, input) -> input.text.toString().lines()
                        .map(String::trim).filter(String::isNotEmpty).distinct() }
                ))
                if (!saved) { toast("保存失败，请重试"); return@setOnClickListener }
                toast(when {
                    requestedMemory && !boundToApp -> "档案已保存；记忆仅支持从微信、牵手、Soul 或青藤之恋聊天页存档后开启"
                    requestedMemory -> "档案已保存；聊天记忆已开启"
                    else -> "档案已保存；聊天记忆未开启"
                })
                render()
                dialog.dismiss()
            }
    }

    private fun appLabel(pkg: String): String = when (pkg) {
        "com.tencent.mm" -> "微信"
        "com.tantan.x" -> "牵手"
        "com.wepie.ivy" -> "青藤之恋"
        "cn.soulapp.android" -> "Soul"
        "cn.neoclub.uki" -> "Uki"
        "com.tencent.mobileqq" -> "QQ"
        "com.ss.android.lark" -> "飞书"
        "com.twitter.android" -> "X"
        else -> pkg
    }

    // ----------------------------------------------------------------- atoms

    private fun confirm(title: String, msg: String, onYes: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title).setMessage(msg)
            .setPositiveButton("确定") { _, _ -> onYes() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    private fun dialogBox() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(8), dp(20), dp(4))
    }

    private fun wrapScroll(v: View) = ScrollView(this).apply { addView(v) }

    private fun twoButtons(
        a: String, onA: () -> Unit,
        b: String?, onB: (() -> Unit)?
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
        }
        row.addView(wideBtn(a, true, onA))
        if (b != null && onB != null) row.addView(wideBtn(b, false, onB))
        return row
    }

    private fun wideBtn(labelText: String, primary: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = labelText; textSize = 14f; gravity = Gravity.CENTER
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(if (primary) Color.WHITE else accent)
        background = round(dp(11), if (primary) accent else Color.WHITE, stroke = !primary)
        setPadding(dp(12), dp(11), dp(12), dp(11))
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            .apply { rightMargin = dp(8) }
        setOnClickListener { onClick() }
    }

    private fun smallToggle(on: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = if (on) "开" else "关"; textSize = 13f; gravity = Gravity.CENTER
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(if (on) Color.WHITE else sub)
        background = round(dp(10), if (on) accent else Color.parseColor("#E5E7EB"))
        setPadding(dp(16), dp(6), dp(16), dp(6))
        setOnClickListener { onClick() }
    }

    private fun toggleRow(labelText: String, initial: Boolean): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(2)); tag = initial
        }
        val lab = text(labelText, 14f, ink).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val sw = TextView(this).apply {
            text = if (initial) "开" else "关"; textSize = 13f; gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (initial) Color.WHITE else sub)
            background = round(dp(10), if (initial) accent else Color.parseColor("#E5E7EB"))
            setPadding(dp(18), dp(6), dp(18), dp(6))
        }
        val toggle = View.OnClickListener {
            val now = !((row.tag as? Boolean) ?: true); row.tag = now
            sw.text = if (now) "开" else "关"
            sw.setTextColor(if (now) Color.WHITE else sub)
            sw.background = round(dp(10), if (now) accent else Color.parseColor("#E5E7EB"))
        }
        row.setOnClickListener(toggle)
        sw.setOnClickListener(toggle)
        row.addView(lab); row.addView(sw)
        return row
    }

    private fun emptyCard(msg: String): View = card().apply { addView(text(msg, 13f, sub)) }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = round(dp(14), Color.WHITE)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(10) }
    }

    private fun label(t: String) = text(t, 13f, ink, bold = true).apply { setPadding(0, dp(12), 0, dp(4)) }

    private fun edit(value: String, hintText: String) = EditText(this).apply {
        setText(value); hint = hintText; textSize = 14f; setTextColor(ink)
        setHintTextColor(Color.parseColor("#9CA3AF"))
        background = round(dp(8), Color.parseColor("#F3F4F6"))
        setPadding(dp(10), dp(10), dp(10), dp(10))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(2) }
    }

    private fun text(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun round(radius: Int, color: Int, stroke: Boolean = false) = GradientDrawable().apply {
        cornerRadius = radius.toFloat(); setColor(color)
        if (stroke) setStroke(dp(1), accent)
    }
}
