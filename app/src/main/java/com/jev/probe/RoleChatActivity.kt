package com.jev.probe

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.jev.probe.capture.PupcallAdapter
import com.jev.probe.core.RoleChatPreference
import com.jev.probe.core.RoleChatStore
import com.jev.probe.core.kb.KbStore
import com.jev.probe.jev.RoleChatGuide

/** Local explanation and manual preferences. Opening this screen never requests a model. */
class RoleChatActivity : Activity() {
    private lateinit var body: LinearLayout
    private fun dp(value: Int) = (resources.displayMetrics.density * value).toInt()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#F7F5F1")); addView(body)
        }
        setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(dp(18) + bars.left, dp(12) + bars.top, dp(18) + bars.right, dp(16) + bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(scroll)
        val contact = intent.getStringExtra("contactId")?.let { KbStore.get(this).contact(it) }
            ?.takeIf { PupcallAdapter.PACKAGE in it.apps }
        val snapshot = SnapshotReviewCodec.decode(intent.getStringExtra("snapshot"))
        val store = RoleChatStore(this)
        val current = store.load(contact?.id)
        line("角色沟通", true)
        line(if (contact == null) "呼叫小狗 · 本 App 默认偏好"
            else "${contact.name} · 仅此联系人在呼叫小狗的偏好", true)
        line(if (contact == null) "未保存联系人的会话使用本 App 默认偏好。要为某个人单独设置，请先在聊天页长按旁注，存入联系人。"
            else "偏好按档案 ID 保存；聊天备注变化后仍须核对联系人，不能凭旧名字套用。不影响微信或其他联系人的设置。")
        if (snapshot?.roleChat != null) {
            line("这句话怎么理解", true)
            val latest = snapshot.messages.lastOrNull { it.side == "other" }
            line(latest?.text?.take(500)?.let { "对方说：$it" } ?: "当前没有可解读的对方文字消息")
            RoleChatGuide.observations(snapshot).forEach { line(it) }
            line("命中的用语", true)
            val hits = RoleChatGuide.matched(snapshot)
            if (hits.isEmpty()) line("没有命中已核实的圈内术语，按普通聊天理解；不猜未知缩写。")
            hits.forEach { line("${it.name}：${it.meaning}") }
        }
        val enabled = Switch(this).apply { text = "启用角色沟通辅助"; isChecked = current.enabled; body.addView(this) }
        line("我的相处偏好", true)
        val myRole = choice(RoleChatStore.MY_ROLES, current.myRole)
        line("建议的说话语气", true)
        val tone = choice(RoleChatStore.TONES, current.tone)
        val other = if (contact != null) {
            line("对方明确说过的偏好", true)
            edit(current.otherRole, "未确认就留空 不自动照抄资料标签", 2)
        } else null
        line("表达边界与不喜欢的说法", true)
        val limits = edit(current.boundaries, "例如 不用主人称呼 不要强势命令 先自然认识", 3)
        button("保存偏好") {
            val value = RoleChatPreference(enabled.isChecked, myRole.selectedItem.toString(),
                other?.text?.toString()?.trim()?.ifBlank { "未确认" } ?: "未确认",
                tone.selectedItem.toString(), limits.text.toString())
            if (store.save(value, contact?.id)) {
                Toast.makeText(this, "已保存 下次分析和开场生效", Toast.LENGTH_SHORT).show(); finish()
            } else Toast.makeText(this, "保存失败 请重试", Toast.LENGTH_SHORT).show()
        }
        if (contact != null) button("恢复使用本 App 默认偏好") {
            if (store.reset(contact.id)) { Toast.makeText(this, "已恢复默认偏好", 0).show(); finish() }
        }
        line("术语小词典", true)
        RoleChatGuide.terms.forEach { line("${it.name}：${it.meaning}") }
        line("仅辅助理解成年人的称呼与相处方式；资料标签不是对方对你作出的承诺。未知缩写不编造，模板迹象也不能证明是机器人。")
        button("NCSF 术语资料") { openSource(RoleChatGuide.NCSF) }
        button("角色称呼资料") { openSource(RoleChatGuide.GLOSSARY) }
        button("返回聊天") { finish() }
        body.isFocusableInTouchMode = true; body.requestFocus()
    }
    private fun line(value: String, heading: Boolean = false) {
        body.addView(TextView(this).apply {
            text = value; textSize = if (heading) 17f else 14f
            setTextColor(Color.parseColor(if (heading) "#263442" else "#53647A"))
            setPadding(0, dp(10), 0, dp(7))
        })
    }
    private fun choice(options: List<String>, value: String) = Spinner(this).apply {
        adapter = ArrayAdapter(this@RoleChatActivity, android.R.layout.simple_spinner_dropdown_item, options)
        setSelection(options.indexOf(value).coerceAtLeast(0)); body.addView(this)
    }
    private fun edit(value: String, placeholder: String, lines: Int) = EditText(this).apply {
        setText(value); hint = placeholder; minLines = lines; maxLines = 6
        setTextColor(Color.parseColor("#263442")); body.addView(this)
    }
    private fun button(label: String, action: () -> Unit) {
        body.addView(Button(this).apply { text = label; isAllCaps = false; setOnClickListener { action() } })
    }
    private fun openSource(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { Toast.makeText(this, "没有可用浏览器", 0).show() }
    }
}
