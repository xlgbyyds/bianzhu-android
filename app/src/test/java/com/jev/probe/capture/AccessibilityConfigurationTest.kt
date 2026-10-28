package com.jev.probe.capture

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AccessibilityConfigurationTest {
    @Test fun installedServiceMetadataEnablesGestureCaptureAndWindowReadingTogether() {
        val context = RuntimeEnvironment.getApplication()
        val component = ComponentName(context.packageName,
            "com.google.android.accessibility.selecttospeak.SelectToSpeakService")
        val resolved = ResolveInfo().apply {
            serviceInfo = context.packageManager.getServiceInfo(component, PackageManager.GET_META_DATA)
        }
        // The framework uses this constructor to parse service metadata; it is
        // hidden from SDK stubs but available in Robolectric's real framework.
        val constructor = AccessibilityServiceInfo::class.java.getDeclaredConstructor(
            ResolveInfo::class.java, Context::class.java).apply { isAccessible = true }
        val info = constructor.newInstance(resolved, context)
        assertTrue(info.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES != 0)
        assertTrue(info.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT != 0)
        assertTrue(info.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT != 0)
    }
}
