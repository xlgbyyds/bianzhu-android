package com.jev.probe.capture

import org.junit.Assert.*
import org.junit.Test

class ConversationContactBindingTest {
    private val target = ConversationSession.Target("wechat", 12, "新备注")
    @Test fun selectionSurvivesNewMessagesButNeverNavigationRenameOrAppSwitch() {
        for (next in listOf(target.copy(title = "其他人"), target.copy(pkg = "soul"),
            target.copy(windowId = 13), null)) {
            val binding = ConversationContactBinding()
            binding.select(target, "old-contact")
            binding.observe(target)
            assertEquals("old-contact", binding.contactId(target))
            binding.observe(next)
            assertNull(binding.contactId(target))
            binding.observe(target)
            assertNull(binding.contactId(target))
        }
    }
    @Test fun dismissalAndNewAnalysisDoNotChangeBindingUntilExplicitlyCleared() {
        val binding = ConversationContactBinding(); binding.select(target, "old")
        val session = ConversationSession(); session.observe(target); session.begin(); session.invalidate()
        assertEquals("old", binding.contactId(session.target))
        binding.clear(); assertNull(binding.contactId(target))
    }
    @Test(expected = IllegalArgumentException::class) fun unreadableNameCannotAcquireAnIdentity() {
        ConversationContactBinding().select(target.copy(title = null), "old")
    }
}
