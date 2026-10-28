package com.jev.probe

import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import com.jev.probe.core.kb.Contact
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ContactOrderDialogTest {
    private fun activity(): AppCompatActivity {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        controller.get().setTheme(androidx.appcompat.R.style.Theme_AppCompat_Light)
        return controller.setup().get()
    }
    private val contacts = listOf(Contact("first", "同名"), Contact("second", "同名"), Contact("third", "末位"))
    @Test fun cancelAfterMoveDoesNotSaveOrChangeContactObjects() {
        var saves = 0
        val dialog = ContactOrderDialog.show(activity(), contacts, emptyMap()) { saves++; true }
        ShadowLooper.idleMainLooper()
        val adapter = descendants(dialog.window!!.decorView).filterIsInstance<RecyclerView>().single()
            .adapter as ContactOrderDialog.OrderAdapter
        assertTrue(adapter.move(0, 2))
        dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).performClick()
        ShadowLooper.idleMainLooper()
        assertEquals(0, saves)
        assertEquals(listOf("first", "second", "third"), contacts.map { it.id })
        assertFalse(dialog.isShowing)
    }
    @Test fun saveUsesMovedIdsAndKeepsDialogOpenOnPersistenceFailure() {
        var saved: List<String>? = null
        var successful = false
        val dialog = ContactOrderDialog.show(activity(), contacts, mapOf("first" to 4000)) {
            saved = it; successful
        }
        ShadowLooper.idleMainLooper()
        val adapter = descendants(dialog.window!!.decorView).filterIsInstance<RecyclerView>().single()
            .adapter as ContactOrderDialog.OrderAdapter
        assertFalse(adapter.move(-1, 0))
        assertTrue(adapter.move(2, 0))
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
        assertEquals(listOf("third", "first", "second"), saved)
        assertTrue(dialog.isShowing)
        successful = true
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
        assertFalse(dialog.isShowing)
    }
    private fun descendants(v: View): List<View> = listOf(v) +
        if (v is ViewGroup) (0 until v.childCount).flatMap { descendants(v.getChildAt(it)) } else emptyList()
}
