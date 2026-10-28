package com.jev.probe.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PrefsReplyFeedbackTest {
    @Test fun preferencesStoreOnlyApprovedStyleCodesAndCanBeCleared() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(Prefs.PREFS_MAIN, 0).edit().clear().commit()
        val prefs = Prefs(context)

        prefs.rememberReplyFeedback("style:playful")
        prefs.rememberReplyFeedback("less_questions")
        prefs.rememberReplyFeedback("raw chat text must never be accepted")

        val guidance = prefs.replyFeedbackGuidance()
        assertTrue(guidance.contains("轻松") || guidance.contains("杞绘澗"))
        assertFalse(guidance.contains("raw chat text"))
        prefs.clearReplyFeedback()
        assertFalse(prefs.replyFeedbackGuidance().isNotBlank())
    }
}
