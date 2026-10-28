package com.jev.probe.capture

import android.content.Intent
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import com.jev.probe.core.Prefs
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KeepAliveServiceTest {
    @Test fun stickyRestartKeepsHiddenAndNotificationActionRestoresWithoutChangingMasterSwitch() {
        val controller = Robolectric.buildService(KeepAliveService::class.java)
        val prefs = Prefs(controller.get()).apply { overlayHidden = true; enabled = false }
        controller.create()
        val service = controller.get()
        val notifications = shadowOf(service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
        val hiddenNotice = notifications.getNotification(1)
        assertEquals("旁注已隐藏", hiddenNotice.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(Notification.VISIBILITY_SECRET, hiddenNotice.visibility)
        assertEquals("恢复旁注", hiddenNotice.actions.single().title)
        service.onStartCommand(null, 0, 1)
        assertTrue(prefs.overlayHidden)
        service.onStartCommand(Intent().setAction(KeepAliveService.ACTION_RESTORE), 0, 2)
        assertFalse(prefs.overlayHidden)
        assertEquals("旁注运行中", notifications.getNotification(1).extras.getString(Notification.EXTRA_TITLE))
        assertFalse(prefs.enabled)
        prefs.enabled = true
        controller.destroy()
    }
}
