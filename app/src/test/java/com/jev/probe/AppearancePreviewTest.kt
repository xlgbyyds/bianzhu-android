package com.jev.probe

import android.app.Activity
import android.view.ViewGroup
import android.view.WindowManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppearancePreviewTest {
    @Test fun backgroundPreviewIsCoveredButReturningAllowsNormalScreenshots() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val decor = activity.window.decorView as ViewGroup
        val normal = decor.childCount
        assertEquals(0, activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
        controller.pause()
        assertEquals(normal + 1, decor.childCount)
        assertNotEquals(0, activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
        controller.resume()
        assertEquals(normal, decor.childCount)
        assertEquals(0, activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
        controller.pause().stop().destroy()
    }
}
