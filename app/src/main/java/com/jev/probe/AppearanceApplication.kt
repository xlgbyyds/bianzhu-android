package com.jev.probe

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import java.util.WeakHashMap

/** Presentation only: package identity, credentials and contact storage are unchanged. */
class AppearanceApplication : Application() {
    private data class PrivatePreview(val cover: View, val wasSecure: Boolean)
    private val previews = WeakHashMap<Activity, PrivatePreview>()

    private fun hidePreview(activity: Activity) {
        if (previews.containsKey(activity)) return
        val decor = activity.window.decorView as? ViewGroup ?: return
        val wasSecure = activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val cover = View(activity).apply {
            setBackgroundColor(Color.rgb(247, 245, 241))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        decor.addView(cover, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        previews[activity] = PrivatePreview(cover, wasSecure)
    }

    private fun restorePreview(activity: Activity) {
        val preview = previews.remove(activity) ?: return
        (preview.cover.parent as? ViewGroup)?.removeView(preview.cover)
        if (!preview.wasSecure) activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) {
                activity.title = getString(R.string.app_name)
                @Suppress("DEPRECATION")
                activity.setTaskDescription(ActivityManager.TaskDescription(
                    getString(R.string.app_name), null, Color.rgb(247, 245, 241)))
                if (Build.VERSION.SDK_INT >= 33) activity.setRecentsScreenshotEnabled(false)
            }
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = restorePreview(activity)
            // Some OEM launchers ignore setRecentsScreenshotEnabled. Cover and
            // secure only the paused activity; foreground screenshots still work.
            override fun onActivityPaused(activity: Activity) = hidePreview(activity)
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = restorePreview(activity)
        })
    }
}
