package com.jev.probe

import android.content.Intent
import android.content.ComponentName
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.jev.probe.core.Prefs
import com.jev.probe.core.RuntimeState
import android.app.NotificationManager
import android.Manifest
import android.os.Build
import kotlin.math.roundToInt

/**
 * Home / setup screen. Card-based layout with a live readiness summary, a
 * guided permission checklist (each row reflects its real granted state), a
 * prominent on/off switch, and a link to settings.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var container: LinearLayout
    private val a11yComponent by lazy {
        ComponentName(packageName, "com.google.android.accessibility.selecttospeak.SelectToSpeakService")
    }

    private val accent = Color.parseColor("#607C92")
    private val green = Color.parseColor("#16A34A")
    private val red = Color.parseColor("#DC2626")
    private val ink = Color.parseColor("#263442")
    private val sub = Color.parseColor("#6B7280")

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).roundToInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        window.decorView.setBackgroundColor(Color.parseColor("#F7F5F1"))

        val scroll = ScrollView(this)
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(28))
        }
        container.padForSystemBars()   // edge-to-edge: keep the title off the status bar
        scroll.addView(container)
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        build()
    }

    private fun build() {
        container.removeAllViews()

        container.addView(text("旁注", 24f, ink, bold = true))
        container.addView(text("整理当前页面的可见文字 保存记录和生成参考建议 所有内容由你确认使用",
            13f, sub).apply { setPadding(0, dp(6), 0, dp(16)) })

        val a11y = isA11yEnabled()
        val overlay = Settings.canDrawOverlays(this)
        val batteryExempt = (getSystemService(POWER_SERVICE) as PowerManager)
            .isIgnoringBatteryOptimizations(packageName)
        val key = prefs.hasKey()   // judge route key: the one analysis cannot run without
        val ready = a11y && RuntimeState.serviceConnected && key && prefs.enabled

        // Readiness card
        container.addView(statusCard(ready, a11y, overlay, key))
        container.addView(privacyHint())
        if (prefs.overlayHidden) {
            container.addView(actionRow("恢复旁注", "当前已隐藏；恢复后返回聊天页即可使用，无需关闭再开启总开关") {
                prefs.overlayHidden = false
                runCatching { com.jev.probe.capture.KeepAliveService.start(this) }
                build()
            })
        }

        // Permission checklist
        container.addView(sectionLabel("权限设置"))
        val notifications = (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).areNotificationsEnabled()
        container.addView(permCard("通知栏恢复入口", "隐藏后可从通知栏一键恢复；未开启时仍可从 App 首页恢复", notifications) {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 65)
            } else startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        })
        container.addView(permCard("无障碍权限", "读取当前聊天窗口的消息文字", a11y) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        container.addView(permCard("系统悬浮窗权限（备用）", "无障碍悬浮窗失败时用于兼容", overlay) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        container.addView(permCard("省电豁免", "检测 Android 系统省电白名单；小米省电策略也可在应用信息中查看", batteryExempt) {
            runCatching {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        })
        container.addView(permCard("小米自启动", "小米未提供通用的状态查询接口，请在应用信息中确认", null) {
            runCatching {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        })

        // Actions
        container.addView(sectionLabel("其他"))
        container.addView(actionRow("设置", "密钥 · 模型 · 关系 · 透明度 · 会话白名单") {
            startActivity(Intent(this, SettingsActivity::class.java))
        })
        container.addView(actionRow("刷新运行状态", "查看服务、最近页面和悬浮窗错误") { build() })

        // Master toggle
        val toggle = bigToggle(prefs.enabled)
        toggle.setOnClickListener {
            prefs.enabled = !prefs.enabled
            build()
        }
        container.addView(toggle)
    }

    // ---------------------------------------------------------------- cards

    private fun statusCard(ready: Boolean, a11y: Boolean, overlay: Boolean, key: Boolean): View {
        val c = cardBox()
        val hidden = prefs.enabled && prefs.overlayHidden
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(dot(if (hidden) Color.parseColor("#B7791F") else if (ready) green else red).apply {
            (layoutParams as LinearLayout.LayoutParams).rightMargin = dp(10)
        })
        head.addView(text(if (hidden) "旁注已隐藏 请点击下方恢复" else if (ready) "已就绪，可以用了" else "尚未就绪",
            16f, if (hidden) Color.parseColor("#B7791F") else if (ready) green else ink, bold = true))
        c.addView(head)
        c.addView(checkLine("无障碍", a11y))
        c.addView(checkLine("后台服务", RuntimeState.serviceConnected))
        c.addView(checkLine("密钥", key, okWord = "已设", noWord = "未设"))
        c.addView(text("系统悬浮窗备用权限：${if (overlay) "已开启" else "未开启"}", 12f, sub).apply {
            setPadding(0, dp(6), 0, 0)
        })
        if (a11y && !RuntimeState.serviceConnected) {
            c.addView(text("无障碍已授权，但服务没有连接。请在系统设置里关闭再重新开启本应用的无障碍服务。", 12f, red).apply {
                setPadding(0, dp(6), 0, 0)
            })
        }
        RuntimeState.lastEventPackage?.let { pkg ->
            c.addView(text("最近收到页面事件：$pkg", 12f, sub).apply { setPadding(0, dp(6), 0, 0) })
        }
        RuntimeState.overlayIssue?.let { issue ->
            c.addView(text(issue, 12f, red).apply { setPadding(0, dp(6), 0, 0) })
        }
        c.addView(text("最近处理：${RuntimeState.lastAction(this)}", 12f, sub).apply {
            setPadding(0, dp(6), 0, 0)
        })
        // History recording is opt-in (off by default). Mention it here, never block on it.
        if (!prefs.contextEnabled) {
            c.addView(text("关联上下文未开启，可在设置里开启", 12f, sub).apply {
                setPadding(0, dp(8), 0, 0)
            })
        }
        return c
    }

    /** One tappable line under the readiness card, opening the privacy policy page. */
    private fun privacyHint(): View = text("读取的聊天内容只发往你自己配置的接口 · 隐私政策", 11f, sub).apply {
        setPadding(dp(2), dp(8), 0, 0)
        setOnClickListener { openUrl(PRIVACY_URL) }
    }

    /** Opens an external link; swallows the failure with a toast rather than crashing. */
    private fun openUrl(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure {
            Toast.makeText(this, "打不开浏览器", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkLine(label: String, ok: Boolean, okWord: String = "已开", noWord: String = "未开"): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(5), 0, 0)
        }
        row.addView(text(if (ok) "✓" else "✗", 14f, if (ok) green else red, bold = true).apply {
            (this as TextView).width = dp(22)
        })
        row.addView(text(label + (if (ok) okWord else noWord), 13f, sub))
        return row
    }

    private fun permCard(title: String, desc: String, granted: Boolean?, onClick: () -> Unit): View {
        val c = cardBox()
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        left.addView(text(title, 15f, ink, bold = true))
        left.addView(text(desc, 12f, sub).apply { setPadding(0, dp(3), 0, 0) })
        if (granted == true) left.addView(text("✓ 已开启", 12f, green, bold = true).apply { setPadding(0, dp(4), 0, 0) })
        if (granted == null) left.addView(text("状态需在系统确认", 12f, sub).apply { setPadding(0, dp(4), 0, 0) })
        row.addView(left)
        row.addView(btn(when (granted) {
            true -> "已开启"
            false -> "去开启"
            null -> "查看设置"
        }, granted != true, onClick))
        c.addView(row)
        return c
    }

    private fun actionRow(title: String, desc: String, onClick: () -> Unit): View {
        val c = cardBox()
        c.setOnClickListener { onClick() }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        left.addView(text(title, 15f, ink, bold = true))
        left.addView(text(desc, 12f, sub).apply { setPadding(0, dp(3), 0, 0) })
        row.addView(left)
        row.addView(text("›", 22f, sub))
        c.addView(row)
        return c
    }

    private fun bigToggle(on: Boolean): View {
        return TextView(this).apply {
            text = if (on) "旁注已开启 · 点击关闭" else "旁注已关闭 · 点击开启"
            textSize = 15f; gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (on) Color.WHITE else accent)
            background = roundBg(dp(14), if (on) accent else Color.WHITE, stroke = !on)
            setPadding(dp(16), dp(15), dp(16), dp(15))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(18) }
        }
    }

    // ---------------------------------------------------------------- atoms

    private fun cardBox(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = roundBg(dp(14), Color.WHITE)
        setPadding(dp(14), dp(13), dp(14), dp(13))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) }
    }

    private fun sectionLabel(t: String) = text(t, 12f, sub, bold = true).apply {
        setPadding(dp(2), dp(18), 0, dp(2))
    }

    private fun text(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun dot(color: Int) = View(this).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
        layoutParams = LinearLayout.LayoutParams(dp(10), dp(10))
    }

    private fun btn(label: String, enabled: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 13f; gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD)
        setTextColor(if (enabled) Color.WHITE else sub)
        background = roundBg(dp(10), if (enabled) accent else Color.parseColor("#E5E7EB"))
        setPadding(dp(16), dp(8), dp(16), dp(8))
        if (enabled) setOnClickListener { onClick() }
    }

    private fun roundBg(radius: Int, color: Int, stroke: Boolean = false) = GradientDrawable().apply {
        cornerRadius = radius.toFloat(); setColor(color)
        if (stroke) setStroke(dp(1), accent)
    }

    private fun isA11yEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.split(':').any {
            ComponentName.unflattenFromString(it)?.let { component -> component == a11yComponent } == true
        }
    }

    companion object {
        private const val PRIVACY_URL = "https://chatjevs.com/privacy.html"
    }
}
