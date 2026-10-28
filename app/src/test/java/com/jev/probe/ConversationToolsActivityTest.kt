package com.jev.probe

import android.app.AlertDialog
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import com.jev.probe.core.ReplyVoiceStore
import com.jev.probe.core.kb.Contact
import com.jev.probe.core.kb.KbStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConversationToolsActivityTest {
    private val requestId = java.util.UUID.randomUUID().toString()
    @Before @After fun resetStore() {
        KbStore::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
    }
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun button(activity: ConversationToolsActivity, text: String) =
        views(activity.window.decorView).filterIsInstance<TextView>().first { it.text.toString() == text }
    private fun chooser(): ConversationToolsActivity {
        val context = RuntimeEnvironment.getApplication()
        val kb = KbStore.get(context)
        kb.saveContact(Contact("picker-a", "微信同名", apps = listOf("com.tencent.mm")))
        kb.saveContact(Contact("picker-b", "Soul独有", apps = listOf("cn.soulapp.android")))
        return Robolectric.buildActivity(ConversationToolsActivity::class.java,
            Intent(context, ConversationToolsActivity::class.java).putExtra("mode", "contacts")
                .putExtra("app", "com.tencent.mm").putExtra("title", "当前新备注").putExtra("requestId", requestId))
            .setup().get()
    }
    @Test fun pickerDefaultsToTemporarySelectionAndDoesNotWriteContacts() {
        val activity = chooser()
        assertFalse(views(activity.window.decorView).filterIsInstance<TextView>().any { it.text == "Soul独有" })
        button(activity, "微信同名").performClick()
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        ShadowLooper.idleMainLooper()
        val broadcast = shadowOf(RuntimeEnvironment.getApplication()).broadcastIntents
            .last { it.action == ConversationToolsActivity.ACTION_RESULT }
        assertEquals("picker-a", broadcast.getStringExtra("contactId"))
        assertFalse(broadcast.getBooleanExtra("permanent", true))
        assertNull(KbStore.get(activity).findContact("当前新备注", "com.tencent.mm"))
    }
    @Test fun permanentAssociationRequiresSeparateConfirmation() {
        val activity = chooser()
        button(activity, "微信同名").performClick()
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
        ShadowLooper.idleMainLooper()
        assertEquals("确认永久关联", ShadowAlertDialog.getLatestAlertDialog()
            .getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
        assertFalse(shadowOf(RuntimeEnvironment.getApplication()).broadcastIntents.any {
            it.action == ConversationToolsActivity.ACTION_RESULT && it.getStringExtra("requestId") == requestId })
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        ShadowLooper.idleMainLooper()
        val result = shadowOf(RuntimeEnvironment.getApplication()).broadcastIntents.last {
            it.action == ConversationToolsActivity.ACTION_RESULT }
        assertTrue(result.getBooleanExtra("permanent", false))
        // Only the service may apply the confirmed alias after rechecking the original live chat.
        assertNull(KbStore.get(activity).findContact("当前新备注", "com.tencent.mm"))
    }
    @Test fun finalRewriteSavesRealTextWithoutSendingOrRecordingAMessage() {
        val context = RuntimeEnvironment.getApplication()
        val scope = ReplyVoiceStore.scope("com.tencent.mm", "editor-a", "原聊天")
        val activity = Robolectric.buildActivity(ConversationToolsActivity::class.java,
            Intent(context, ConversationToolsActivity::class.java).putExtra("mode", "edit")
                .putExtra("scope", scope).putExtra("app", "com.tencent.mm").putExtra("name", "原聊天")
                .putExtra("text", "原来的生成句子")).setup().get()
        val input = views(activity.window.decorView).filterIsInstance<EditText>().single()
        input.setText("好家伙 你比猫还能折腾")
        button(activity, "保存表达样本").performClick()
        assertEquals("好家伙 你比猫还能折腾", ReplyVoiceStore(activity).samples(scope).single().text)
        assertTrue(activity.isFinishing)
    }
}
