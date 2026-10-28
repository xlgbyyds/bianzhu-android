package com.jev.probe

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.MessageKind
import com.jev.probe.core.Msg
import org.json.JSONArray
import org.json.JSONObject

/** Per-request transcript correction. Nothing here changes saved contact memory. */
class SnapshotReviewActivity : Activity() {
    private data class MessageRow(
        val side: Spinner,
        val kind: Spinner,
        val body: EditText,
        val time: EditText,
        val confidence: String,
        val originalTime: String?
    )

    private lateinit var snapshot: ChatSnapshot
    private val rows = ArrayList<MessageRow>()
    private var submitted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        snapshot = SnapshotReviewCodec.decode(intent.getStringExtra(EXTRA_SNAPSHOT)) ?: run {
            finish()
            return
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
            setBackgroundColor(0xFFF7F9FC.toInt())
        }
        root.addView(TextView(this).apply {
            text = "核对识别内容"
            textSize = 20f
            setTextColor(0xFF1F2D40.toInt())
        })
        root.addView(TextView(this).apply {
            text = "${snapshot.title ?: "当前会话"} · 本次识别到 ${snapshot.messages.size} 条。修改只用于本次分析，不会写入联系人聊天记忆。"
            textSize = 13f
            setTextColor(0xFF68788C.toInt())
            setPadding(0, dp(6), 0, dp(10))
        })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        snapshot.messages.forEachIndexed { index, message -> list.addView(messageEditor(index, message)) }
        val scroll = ScrollView(this).apply { addView(list) }
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(Button(this).apply {
            text = "保存并重新分析"
            setOnClickListener { submitCorrections() }
        })
        root.addView(Button(this).apply {
            text = "返回，不修改"
            setOnClickListener { cancelReview() }
        })
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(dp(16) + bars.left, dp(12) + bars.top,
                dp(16) + bars.right, dp(16) + bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
        root.isFocusableInTouchMode = true
        root.requestFocus()
    }

    private fun messageEditor(index: Int, message: Msg): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        box.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(8)
        }
        box.addView(TextView(this).apply {
            text = "第 ${index + 1} 条"
            textSize = 14f
            setTextColor(0xFF355FA8.toInt())
        })
        val side = spinner(SIDES, when (message.side) {
            "me" -> 0
            "other" -> 1
            else -> 2
        })
        box.addView(label("发送方")); box.addView(side)
        val kind = spinner(KINDS, MessageKind.values().indexOf(message.kind).coerceAtLeast(0))
        box.addView(label("消息类型")); box.addView(kind)
        val body = EditText(this).apply {
            setText(message.text)
            hint = "识别到的文字；语音内容未知时不要猜写"
            minLines = 1
            maxLines = 5
            gravity = Gravity.TOP
            textSize = 14f
        }
        box.addView(label("消息内容")); box.addView(body)
        val time = EditText(this).apply {
            setText(message.visibleTime.orEmpty())
            hint = "可见的日期/时间；没有就留空"
            setSingleLine(true)
            textSize = 14f
        }
        box.addView(label("页面可见时间（不确定可留空）")); box.addView(time)
        rows.add(MessageRow(side, kind, body, time, message.timeConfidence, message.visibleTime))
        return box
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(0xFF68788C.toInt())
        setPadding(0, dp(4), 0, 0)
    }

    private fun spinner(values: List<String>, selected: Int) = Spinner(this).apply {
        adapter = ArrayAdapter(this@SnapshotReviewActivity,
            android.R.layout.simple_spinner_dropdown_item, values)
        setSelection(selected)
    }

    private fun submitCorrections() {
        val corrected = snapshot.copy(messages = rows.map { row ->
            val side = when (row.side.selectedItemPosition) {
                0 -> "me"
                1 -> "other"
                else -> "unknown"
            }
            val kind = MessageKind.values().getOrElse(row.kind.selectedItemPosition) { MessageKind.TEXT }
            val time = row.time.text.toString().trim().takeIf { it.isNotBlank() }
            Msg(side, row.body.text.toString().trim(), kind, time,
                when {
                    time == null -> "unknown"
                    time != row.originalTime -> "manual"
                    else -> row.confidence.takeIf { it != "unknown" } ?: "partial"
                })
        })
        sendBroadcast(android.content.Intent(ACTION_RESULT)
            .setPackage(packageName)
            .putExtra(EXTRA_REQUEST_ID, intent.getStringExtra(EXTRA_REQUEST_ID))
            .putExtra(EXTRA_SNAPSHOT, SnapshotReviewCodec.encode(corrected)))
        submitted = true
        finish()
    }

    @Deprecated("Use system back navigation")
    override fun onBackPressed() {
        cancelReview()
    }

    private fun cancelReview() {
        if (!submitted) sendBroadcast(android.content.Intent(ACTION_RESULT)
            .setPackage(packageName)
            .putExtra(EXTRA_REQUEST_ID, intent.getStringExtra(EXTRA_REQUEST_ID))
            .putExtra(EXTRA_CANCELED, true))
        finish()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val ACTION_RESULT = "com.jev.probe.action.SNAPSHOT_REVIEW_RESULT"
        const val EXTRA_REQUEST_ID = "review_request_id"
        const val EXTRA_SNAPSHOT = "review_snapshot"
        const val EXTRA_CANCELED = "review_canceled"
        private val SIDES = listOf("我", "对方", "不确定")
        private val KINDS = listOf("文字", "语音转写", "语音内容未知", "图片/其他未知")
    }

}

internal object SnapshotReviewCodec {
    fun encode(snapshot: ChatSnapshot): String = JSONObject()
        .put("title", snapshot.title)
        .put("note", snapshot.note)
        .put("messages", JSONArray().apply {
            snapshot.messages.forEach { message ->
                put(JSONObject()
                    .put("side", message.side)
                    .put("text", message.text)
                    .put("kind", message.kind.name)
                    .put("time", message.visibleTime)
                    .put("confidence", message.timeConfidence))
            }
        }).toString()

    fun decode(encoded: String?): ChatSnapshot? = runCatching {
        if (encoded.isNullOrBlank()) return null
        val json = JSONObject(encoded)
        val messages = json.optJSONArray("messages") ?: JSONArray()
        ChatSnapshot(
            json.optString("title").takeIf { it.isNotBlank() },
            (0 until messages.length()).mapNotNull { index ->
                val row = messages.optJSONObject(index) ?: return@mapNotNull null
                val kind = runCatching { MessageKind.valueOf(row.optString("kind")) }
                    .getOrDefault(MessageKind.TEXT)
                Msg(row.optString("side", "unknown"), row.optString("text"), kind,
                    row.optString("time").takeIf { it.isNotBlank() },
                    row.optString("confidence", "unknown"))
            },
            note = json.optString("note").takeIf { it.isNotBlank() }
        )
    }.getOrNull()
}
