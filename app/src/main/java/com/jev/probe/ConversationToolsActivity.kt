package com.jev.probe

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.jev.probe.core.ReplyVoiceStore
import com.jev.probe.core.kb.KbStore

/** Foreground editor avoids making an unfocusable overlay fight the chat app's keyboard. */
class ConversationToolsActivity : Activity() {
    private lateinit var content: LinearLayout
    private val store by lazy { ReplyVoiceStore(this) }
    private var resultSent = false
    private fun dp(v: Int) = (resources.displayMetrics.density * v).toInt()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.setBackgroundColor(Color.parseColor("#F2F5FA"))
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(content) }
        setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(dp(16) + bars.left, dp(12) + bars.top, dp(16) + bars.right, dp(12) + bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(scroll)
        when (intent.getStringExtra("mode")) {
            "contacts" -> contacts()
            "edit" -> edit()
            else -> samples()
        }
    }
    private fun line(text: String, heading: Boolean = false) = TextView(this).apply {
        this.text = text; textSize = if (heading) 19f else 14f
        setTextColor(Color.parseColor(if (heading) "#23354E" else "#53647A"))
        setPadding(dp(4), dp(8), dp(4), dp(8))
        content.addView(this)
    }
    private fun button(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text; isAllCaps = false; setOnClickListener { action() }; content.addView(this)
    }
    private fun appName(app: String): String = when (app) {
        "com.tencent.mm" -> "微信"; "com.tantan.x" -> "牵手"; "cn.soulapp.android" -> "Soul"
        "com.wepie.ivy" -> "青藤之恋"; else -> "其他 App"
    }
    private fun contacts() {
        val app = intent.getStringExtra("app").orEmpty()
        val title = intent.getStringExtra("title").orEmpty()
        val current = intent.getStringExtra("selected")
        line("选择当前会话的联系人", true)
        line("${appName(app)} 页面备注：$title\n默认只用于当前会话分析。临时选择会暂停此会话的记忆采集；离开聊天页后自动解除。")
        button("恢复自动匹配") { result(null, false) }
        val contacts = KbStore.get(this).contacts().filter { app in it.apps }.sortedBy { it.name }
        if (contacts.isEmpty()) line("此 App 尚没有已保存联系人，请先回聊天页存为联系人。")
        contacts.forEach { contact ->
            button(contact.name + if (contact.id == current) "  当前使用" else "") {
                AlertDialog.Builder(this).setTitle(contact.name)
                    .setMessage("仅本次使用会引用此人的记录与关系档案，不修改备注关联，也不向此人补录。")
                    .setPositiveButton("仅本次使用") { _, _ -> result(contact.id, false) }
                    .setNeutralButton("确认备注关联…") { _, _ ->
                        AlertDialog.Builder(this).setTitle("确认这是同一个人")
                            .setMessage("将当前 App 的备注“$title”关联到“${contact.name}”。以后同一 App 出现此备注会自动匹配；不会合并联系人或搬移旧记录。请确认身份。")
                            .setPositiveButton("确认永久关联") { _, _ -> result(contact.id, true) }
                            .setNegativeButton("取消", null).show()
                    }.setNegativeButton("取消", null).show()
            }
        }
        button("返回") { finish() }
    }
    private fun edit(sampleId: String? = null, initial: String? = null) {
        content.removeAllViews()
        val original = sampleId?.let { id -> store.samples().firstOrNull { it.id == id } }
        val scope = original?.scope ?: intent.getStringExtra("scope").orEmpty()
        val name = original?.name ?: intent.getStringExtra("name").orEmpty()
        val app = original?.app ?: intent.getStringExtra("app").orEmpty()
        line("改写并学习你的表达", true)
        line("仅用于：$name\n保留你认可的最终说法。只学习表达节奏，不写进聊天记录、不填入或发送消息。")
        val input = EditText(this).apply {
            setText(original?.text ?: initial ?: intent.getStringExtra("text").orEmpty())
            hint = "写下你最终认可的说法"; minLines = 3; maxLines = 6
            setTextColor(Color.parseColor("#23354E")); content.addView(this)
        }
        button("保存表达样本") {
            if (input.text.isBlank()) { input.error = "请填写你认可的说法"; return@button }
            if (input.text.length > 160) { input.error = "表达样本请保持在 160 字内"; return@button }
            if (!store.approve(scope, name, app, input.text.toString())) {
                input.error = "保存失败，请重试"; return@button
            }
            android.widget.Toast.makeText(this, "已记住最终改写，下次分析生效", 0).show()
            if (original != null && original.text != input.text.toString().trim()) store.delete(original.id)
            if (intent.getStringExtra("mode") == "edit") finish() else samples()
        }
        button("取消") { if (intent.getStringExtra("mode") == "edit") finish() else samples() }
        input.clearFocus(); content.isFocusableInTouchMode = true; content.requestFocus()
    }
    private fun samples() {
        content.removeAllViews()
        line("我的表达样本", true)
        line("“像我”保存真实句子；最终改写优先。每个会话最多保留 24 条明确样本，总计最多 240 条。分析只取少量同联系人样本，另外参考此人的历史 from=me，不跨人套用亲密表达。")
        val samples = store.samples()
        if (samples.isEmpty()) line("还没有明确认可的样本。到回复建议点“像我”或“改写学习”即可。")
        samples.forEach { sample ->
            line("${sample.name} · ${sample.app}\n${sample.text}")
            button("编辑这条") { edit(sample.id) }
            button("删除这条") {
                if (store.delete(sample.id)) samples()
                else android.widget.Toast.makeText(this, "删除失败，请重试", 0).show()
            }
        }
        button("返回") { finish() }
    }
    private fun result(contactId: String?, permanent: Boolean) {
        resultSent = true
        sendBroadcast(Intent(ACTION_RESULT).setPackage(packageName)
            .putExtra("requestId", intent.getStringExtra("requestId"))
            .putExtra("contactId", contactId).putExtra("permanent", permanent))
        finish()
    }
    override fun onDestroy() {
        if (!isChangingConfigurations && !resultSent && intent.hasExtra("requestId")) {
            sendBroadcast(Intent(ACTION_RESULT).setPackage(packageName)
                .putExtra("requestId", intent.getStringExtra("requestId")).putExtra("canceled", true))
        }
        super.onDestroy()
    }
    companion object { const val ACTION_RESULT = "com.jev.probe.CONVERSATION_TOOLS_RESULT" }
}
