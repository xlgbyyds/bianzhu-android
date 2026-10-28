package com.jev.probe.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.jev.probe.SettingsActivity
import com.jev.probe.R
import com.jev.probe.core.Prefs

/**
 * A minimal foreground service whose only job is to keep the app process at
 * foreground importance so MIUI/HyperOS "Greezer" does not freeze the
 * accessibility service (which otherwise dies within seconds — see P1 report).
 * Not a full fix on its own: the user must also grant autostart / no battery
 * restriction, but this holds the process while the app is set up and running.
 */
class KeepAliveService : Service() {

    override fun onCreate() {
        super.onCreate()
        startForeground(1, buildNotification())
    }

    private fun buildNotification(): Notification {
        val channelId = "jev_keepalive"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(channelId, "旁注运行状态", NotificationManager.IMPORTANCE_MIN)
            ch.setShowBadge(false)
            nm.createNotificationChannel(ch)
        }
        val hidden = Prefs(this).overlayHidden
        val restore = PendingIntent.getService(this, 1,
            Intent(this, KeepAliveService::class.java).setAction(ACTION_RESTORE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val home = PendingIntent.getActivity(this, 2,
            Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = Notification.Builder(this, channelId)
            .setContentTitle(if (hidden) "旁注已隐藏" else "旁注运行中")
            .setContentText(if (hidden) "点击恢复悬浮按钮" else "点击打开设置")
            .setSmallIcon(R.drawable.ic_bianzhu_notification)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(if (hidden) restore else home)
        if (hidden) builder.addAction(Notification.Action.Builder(null, "恢复旁注", restore).build())
        return builder.build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_RESTORE) Prefs(this).overlayHidden = false
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(1, buildNotification())
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_RESTORE = "com.jev.personal.chatassistant.RESTORE_OVERLAY"
        fun start(ctx: Context) {
            val i = Intent(ctx, KeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }
    }
}
