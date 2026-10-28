package com.jev.probe

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.jev.probe.core.kb.KbStore
import com.jev.probe.core.kb.MemoryItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Inspect and correct only this contact's local memory. No message is sent. */
class HistoryActivity : AppCompatActivity() {
    private lateinit var store: KbStore
    private lateinit var contactId: String
    private lateinit var content: LinearLayout
    private var grouped = true
    private var legacy = false
    private var offset = 0
    private val pageSize = 25
    private val ink = Color.parseColor("#111827")
    private val sub = Color.parseColor("#6B7280")
    private val accent = Color.parseColor("#3A7AFE")
    private val red = Color.parseColor("#DC2626")
    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).roundToInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        contactId = intent.getStringExtra(EXTRA_CONTACT_ID).orEmpty()
        store = KbStore.get(this)
        if (store.contact(contactId) == null) { finish(); return }
        window.decorView.setBackgroundColor(Color.parseColor("#F2F3F5"))
        val scroll = ScrollView(this)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(42), dp(18), dp(28))
        }
        scroll.addView(content)
        setContentView(scroll)
        render()
    }

    private fun render() {
        val contact = store.contact(contactId) ?: run { finish(); return }
        val stats = store.captureStats(contactId)
        val groups = store.historyGroupCount(contactId)
        val oldCount = store.legacyLogSize(contactId)
        val total = if (legacy) oldCount else if (grouped) groups else stats.lines
        offset = offset.coerceIn(0, if (total == 0) 0 else ((total - 1) / pageSize) * pageSize)
        content.removeAllViews()
        content.addView(button("‹ 返回联系人", accent) { finish() })
        content.addView(label(contact.name + " · 聊天记忆", 23f, ink, true))
        content.addView(label("仅存本机；只会在这位联系人匹配成功且记忆开启时供 Jev 参考。", 12f, sub))
        content.addView(label("${stats.pages} 屏有新增 · ${stats.lines} 条已存消息 · $groups 组相同内容" +
            if (oldCount > 0) " · 旧版 $oldCount 条" else "", 13f, ink, true))
        content.addView(label("重叠屏中能确认是同一条的消息只保存一次；无法确认的相同文字仍保留供你核对。" +
            "“本机看到时间”不是发送时间；页面没有日期时显示未知。", 12f, sub))
        content.addView(label("这里按本机保存顺序倒序显示 不是按消息发送时间排序 每页25项 较早保存的消息在下一页", 12f, sub))

        val modes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        modes.addView(modeButton("合并查看", !legacy && grouped) {
            grouped = true; legacy = false; offset = 0; render()
        })
        modes.addView(modeButton("已存消息", !legacy && !grouped) {
            grouped = false; legacy = false; offset = 0; render()
        })
        if (oldCount > 0) modes.addView(modeButton("旧版", legacy) {
            legacy = true; offset = 0; render()
        })
        content.addView(modes)
        content.addView(label(when {
            legacy -> "旧版记录按保存顺序倒序显示，点击可修改或删除。"
            grouped -> "相同内容合并显示；多条也可能确实发送了多次。点开会修改或删除整组，单独处理请切到“已存消息”。"
            else -> "每一行都是实际保存的消息；点击只修改这一行。"
        }, 12f, sub))

        if (total == 0) content.addView(label("还没有可查看的聊天内容。", 15f, sub))
        else if (legacy) {
            store.legacyLogPage(contactId, pageSize, offset).forEach { (index, entry) ->
                val item = MemoryItem(index.toLong(), entry.side, entry.kind, entry.text,
                    entry.visibleTime, entry.timeConfidence, entry.ts, entry.historical, 1)
                content.addView(itemCard(item) { editLegacy(index) })
            }
        } else {
            store.historyItems(contactId, grouped, pageSize, offset).forEach { item ->
                content.addView(itemCard(item) { editItem(item) })
            }
        }

        if (total > pageSize) {
            content.addView(label("第 ${offset + 1}–${minOf(offset + pageSize, total)} 项 / 共 $total 项", 12f, sub))
            val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            if (offset > 0) nav.addView(modeButton("上一页", false) {
                offset = (offset - pageSize).coerceAtLeast(0); render()
            })
            if (offset + pageSize < total) nav.addView(modeButton("下一页", false) {
                offset += pageSize; render()
            })
            content.addView(nav)
        }
    }

    private fun itemCard(item: MemoryItem, onClick: () -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = shape(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(9) }
            addView(label("${sideLabel(item.side)} · ${kindLabel(item.kind)}" +
                if (item.copies > 1) " · 相同内容 ${item.copies} 条" else "", 13f, accent, true))
            addView(label(item.body.ifBlank { "（无文字内容）" }, 15f, ink))
            addView(label("页面时间：${item.visibleTime?.ifBlank { "未知" } ?: "未知"}" +
                "（${confidenceLabel(item.timeConfidence)}）", 12f, sub))
            addView(label("本机看到：${stamp.format(Date(item.observedAt))}" +
                if (item.historical) " · 补录旧消息" else "", 11f, sub))
            setOnClickListener { onClick() }
        }

    private fun editItem(item: MemoryItem) {
        showEditor(item, onSave = { side, kind, body, time, confidence ->
            val n = store.editHistoryItem(contactId, item.id, grouped,
                side, kind, body, time, confidence)
            toast(if (n > 0) "已修改 $n 条采集行" else "保存失败，记录可能已变化")
            if (n > 0) render()
            n > 0
        }, onDelete = {
            confirm("删除记忆", "删除这${if (grouped) "组 ${item.copies}" else "一"}条采集行？") {
                val n = store.deleteHistoryItem(contactId, item.id, grouped)
                toast(if (n > 0) "已删除 $n 条采集行" else "删除失败，记录可能已变化")
                render()
            }
        })
    }

    private fun editLegacy(index: Int) {
        val entry = store.legacyLogPage(contactId, 1, store.legacyLogSize(contactId) - 1 - index)
            .firstOrNull()?.second ?: return
        val item = MemoryItem(index.toLong(), entry.side, entry.kind, entry.text,
            entry.visibleTime, entry.timeConfidence, entry.ts, entry.historical, 1)
        showEditor(item, onSave = { side, kind, body, time, confidence ->
            val ok = store.editLegacyLog(contactId, index, entry.copy(
                side = side, kind = kind, text = body,
                visibleTime = time, timeConfidence = confidence))
            toast(if (ok) "已保存" else "保存失败")
            if (ok) render()
            ok
        }, onDelete = {
            confirm("删除记忆", "删除这条旧版记录？") {
                val ok = store.deleteLegacyLog(contactId, index)
                toast(if (ok) "已删除" else "删除失败")
                render()
            }
        })
    }

    private fun showEditor(item: MemoryItem,
                           onSave: (String, String, String, String?, String) -> Boolean,
                           onDelete: () -> Unit) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }
        val sides = listOf("我", "对方", "不确定")
        val sideValues = listOf("me", "other", "unknown")
        val kinds = listOf("文字", "语音转写", "语音内容未知", "图片/贴纸内容未知")
        val kindValues = listOf("TEXT", "VOICE_TRANSCRIPT", "VOICE_UNKNOWN", "MEDIA_UNKNOWN")
        val side = spinner(sides, sideValues.indexOf(item.side).coerceAtLeast(0))
        val kind = spinner(kinds, kindValues.indexOf(item.kind).coerceAtLeast(0))
        val body = edit(item.body, "聊天文字")
        val time = edit(item.visibleTime.orEmpty(), "例如 9月15日 16:00；看不到就留空")
        box.addView(label("发言方", 13f, ink, true)); box.addView(side)
        box.addView(label("消息类型", 13f, ink, true)); box.addView(kind)
        box.addView(label("内容", 13f, ink, true)); box.addView(body)
        box.addView(label("页面显示的时间（不是本机采集时间）", 13f, ink, true)); box.addView(time)
        box.addView(label("改动页面时间后标为“人工填写”；留空标为“未知”。", 11f, sub))
        val dialog = AlertDialog.Builder(this)
            .setTitle("编辑聊天记忆")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("保存", null)
            .setNeutralButton("删除", null)
            .setNegativeButton("取消", null)
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val newBody = body.text.toString().trim()
            if (newBody.isBlank()) { toast("内容不能留空"); return@setOnClickListener }
            val newTime = time.text.toString().trim().ifBlank { null }
            val confidence = when {
                newTime == null -> "unknown"
                newTime != item.visibleTime -> "manual"
                else -> item.timeConfidence
            }
            if (onSave(sideValues[side.selectedItemPosition], kindValues[kind.selectedItemPosition],
                    newBody, newTime, confidence)) dialog.dismiss()
        }
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            dialog.dismiss(); onDelete()
        }
    }

    private fun sideLabel(side: String) = when (side) {
        "me" -> "我"; "other" -> "对方"; else -> "发言方不确定"
    }
    private fun kindLabel(kind: String) = when (kind) {
        "VOICE_TRANSCRIPT" -> "语音转写"; "VOICE_UNKNOWN" -> "语音内容未知"
        "MEDIA_UNKNOWN" -> "图片/贴纸内容未知"; else -> "文字"
    }
    private fun confidenceLabel(confidence: String) = when (confidence) {
        "manual" -> "人工填写"; "partial" -> "页面标签，非逐条精确时间"
        "exact" -> "精确"; else -> "未知"
    }

    private fun spinner(labels: List<String>, selected: Int) = Spinner(this).apply {
        adapter = ArrayAdapter(this@HistoryActivity, android.R.layout.simple_spinner_dropdown_item, labels)
        setSelection(selected)
    }

    private fun edit(value: String, hintText: String) = EditText(this).apply {
        setText(value); hint = hintText
        textSize = 14f
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        minLines = 2; maxLines = 7
    }

    private fun label(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(5), 0, dp(3))
    }

    private fun button(value: String, color: Int, onClick: () -> Unit) = TextView(this).apply {
        text = value; textSize = 14f; setTextColor(color)
        setPadding(dp(3), dp(8), dp(3), dp(8)); setOnClickListener { onClick() }
    }

    private fun modeButton(value: String, active: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = value; textSize = 12f; gravity = Gravity.CENTER
        setTextColor(if (active) Color.WHITE else accent)
        background = shape(if (active) accent else Color.WHITE)
        setPadding(dp(8), dp(8), dp(8), dp(8))
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            .apply { rightMargin = dp(5); topMargin = dp(12) }
        setOnClickListener { onClick() }
    }

    private fun shape(color: Int) = GradientDrawable().apply {
        cornerRadius = dp(11).toFloat(); setColor(color)
    }

    private fun confirm(title: String, message: String, yes: () -> Unit) {
        AlertDialog.Builder(this).setTitle(title).setMessage(message)
            .setPositiveButton("删除") { _, _ -> yes() }
            .setNegativeButton("取消", null).show()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    companion object { const val EXTRA_CONTACT_ID = "contact_id" }
}
