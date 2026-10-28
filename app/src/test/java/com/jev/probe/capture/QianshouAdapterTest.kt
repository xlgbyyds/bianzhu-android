package com.jev.probe.capture

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h800dp-xhdpi")
class QianshouAdapterTest {
    private val resources get() = RuntimeEnvironment.getApplication().resources
    private val width get() = resources.displayMetrics.widthPixels

    @Test fun currentToolbarNameTextIdIsAcceptedForAChatPage() {
        val snapshot = QianshouAdapter().extract(page("name_text"), resources)
        assertNotNull(snapshot)
        assertEquals("测试联系人", snapshot!!.title)
        assertEquals(1, snapshot.messages.size)
        assertEquals("other", snapshot.messages.single().side)
    }

    @Test fun olderToolbarNameIdRemainsSupported() {
        val snapshot = QianshouAdapter().extract(page("name"), resources)
        assertNotNull(snapshot)
        assertEquals("测试联系人", snapshot!!.title)
    }

    private fun page(titleId: String): AccessibilityNodeInfo = node(null, null, 0, 1600).apply {
        shadowOf(this).addChild(node("messagesBarGreyEdt", null, 1400, 1500))
        shadowOf(this).addChild(node(titleId, "测试联系人", 80, 130))
        val row = node("content_wrapper", null, 240, 340, 0.0, 1.0)
        shadowOf(row).addChild(node("msg_content_body", null, 240, 340, 0.12, 0.62))
        shadowOf(row).addChild(node("profile_image", null, 240, 340, 0.02, 0.12))
        shadowOf(row).addChild(node("msg_item_text", "你好，测试消息", 250, 330, 0.15, 0.58))
        shadowOf(this).addChild(row)
        org.robolectric.util.ReflectionHelpers.callInstanceMethod<Void>(this, "setSealed",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, true))
    }

    private fun node(id: String?, text: String?, top: Int, bottom: Int,
                     left: Double = 0.0, right: Double = 1.0) = AccessibilityNodeInfo.obtain().apply {
        packageName = "com.tantan.x"
        viewIdResourceName = id?.let { "com.tantan.x:id/$it" }
        this.text = text
        isVisibleToUser = true
        setBoundsInScreen(Rect((width * left).toInt(), top, (width * right).toInt(), bottom))
    }
}
