package com.jev.probe

import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.SeekBar
import com.jev.probe.core.Prefs
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsModelPersistenceTest {
    private val model = "deepseek/deepseek-v4.1-flash"
    private fun descendants(v: View): List<View> = listOf(v) +
        if (v is ViewGroup) (0 until v.childCount).flatMap { descendants(v.getChildAt(it)) } else emptyList()

    @Test fun appearanceSavePersistsSeparatelyFromPanelOpacityAndModelConfiguration() {
        val prefs = Prefs(RuntimeEnvironment.getApplication()).apply {
            replyModel = model
            replyBaseUrl = Prefs.DEFAULT_REPLY_BASE
            overlayOpacity = 92
        }
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val all = descendants(controller.get().window.decorView)
        all.filterIsInstance<TextView>().single { it.text == "贴边收起" }.let { label ->
            ((label.parent as ViewGroup).getChildAt(1) as TextView).performClick()
        }
        all.filterIsInstance<SeekBar>().single { it.max == 80 }.progress = 23
        all.filterIsInstance<TextView>().single { it.text == "保存全部设置" }.performClick()
        assertFalse(prefs.edgePeek)
        assertEquals(43, prefs.bubbleOpacity)
        assertEquals(92, prefs.overlayOpacity)
        assertEquals(model, prefs.replyModel)
        controller.pause().stop().destroy()
        val reopened = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        assertTrue(descendants(reopened.get().window.decorView).filterIsInstance<TextView>()
            .any { it.text == "闲置按钮不透明度：43%" })
        reopened.pause().stop().destroy()
    }

    @Test fun reselectAndSwitchBackDoNotOverwriteReplyModelAndSaveSurvivesReopen() {
        val prefs = Prefs(RuntimeEnvironment.getApplication())
        prefs.replyBaseUrl = Prefs.DEFAULT_REPLY_BASE
        prefs.replyModel = model
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val activity = controller.get()
        val all = descendants(activity.window.decorView)
        val input = all.filterIsInstance<EditText>().single { it.text.toString() == model }
        val replyCard = input.parent as ViewGroup
        fun pill(name: String) = descendants(replyCard).filterIsInstance<TextView>()
            .single { it.text.toString() == name }
        repeat(3) { pill("OpenRouter").performClick() }
        assertEquals(model, input.text.toString())
        pill("DeepSeek 官方").performClick()
        assertEquals(Prefs.DEEPSEEK_MODEL, input.text.toString())
        input.setText("deepseek-v4-pro")
        pill("OpenRouter").performClick()
        assertEquals(model, input.text.toString())
        pill("DeepSeek 官方").performClick()
        assertEquals("deepseek-v4-pro", input.text.toString())
        pill("OpenRouter").performClick()
        all.filterIsInstance<TextView>().single { it.text.toString() == "保存全部设置" }.performClick()
        assertEquals(model, Prefs(RuntimeEnvironment.getApplication()).replyModel)
        controller.pause().stop().destroy()
        val reopened = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        assertTrue(descendants(reopened.get().window.decorView).filterIsInstance<EditText>()
            .any { it.text.toString() == model })
        reopened.pause().stop().destroy()
    }

    @Test fun editingWithoutSaveDoesNotOverwritePersistedConfiguration() {
        val prefs = Prefs(RuntimeEnvironment.getApplication())
        prefs.replyBaseUrl = Prefs.DEFAULT_REPLY_BASE
        prefs.replyModel = model
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val input = descendants(controller.get().window.decorView).filterIsInstance<EditText>()
            .single { it.text.toString() == model }
        input.setText("another/model")
        controller.pause().stop().destroy()
        assertEquals(model, Prefs(RuntimeEnvironment.getApplication()).replyModel)
    }
}
