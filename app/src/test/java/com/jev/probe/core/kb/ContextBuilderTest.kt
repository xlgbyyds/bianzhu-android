package com.jev.probe.core.kb

import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Msg
import org.junit.Assert.assertEquals
import org.junit.Test

class ContextBuilderTest {
    @Test fun retainsEarlierRepeatedLineWhileRemovingCurrentScreen() {
        val history = listOf(
            LogEntry("other", "好", 1L, "app"),
            LogEntry("me", "我明天有空", 2L, "app"),
            LogEntry("other", "好", 3L, "app")
        )
        val visible = ChatSnapshot("同一人", listOf(Msg("me", "我明天有空"), Msg("other", "好")))
        assertEquals(listOf(history.first()), ContextBuilder.trimVisibleTail(history, visible))
    }

    @Test fun leavesHistoryUntouchedWhenVisibleScreenIsDifferent() {
        val history = listOf(LogEntry("other", "昨天见", 1L, "app"))
        val visible = ChatSnapshot("同一人", listOf(Msg("other", "今天见")))
        assertEquals(history, ContextBuilder.trimVisibleTail(history, visible))
    }
}
