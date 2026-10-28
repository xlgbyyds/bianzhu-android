package com.jev.probe.core.kb

import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Msg
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class InvitationMemoryTest {
    @Test fun retrievalKeepsResponsesAndFrozenDatesAndCannotCrossPersonOrApp() {
        val ledger = ConversationLedger(RuntimeEnvironment.getApplication())
        try {
            val observed = LocalDateTime.of(2026, 10, 20, 12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            ledger.record("a", "wechat", ChatSnapshot("A", listOf(
                Msg("me", "周末见面吗", visibleTime = "昨天 16:00"),
                Msg("other", "这周不行 下周看看", visibleTime = "昨天 16:01"))), true, observed)
            ledger.record("b", "wechat", ChatSnapshot("B", listOf(Msg("other", "另一人的邀约秘密"))), true, observed)
            ledger.record("a", "soul", ChatSnapshot("A", listOf(Msg("other", "另一App周末有空"))), true, observed)
            val rows = ledger.invitationHistory("a", "wechat")
            assertEquals(2, rows.size)
            assertEquals(listOf("me", "other"), rows.map { it.side })
            assertTrue(rows.all { it.historical && it.app == "wechat" })
            assertFalse(rows.any { it.visibleTime.orEmpty().contains("昨天") })
            assertTrue(rows.first().visibleTime.orEmpty().contains("10"))
            assertEquals("这周不行 下周看看", rows.last().text)
        } finally { ledger.close() }
    }

    @Test fun unknownDatesRemainUnknownAndCurrentBoundaryIsNotDropped() {
        val ledger = ConversationLedger(RuntimeEnvironment.getApplication())
        try {
            ledger.record("c", "wechat", ChatSnapshot("C", listOf(Msg("other", "想去河边走走"))), true, 1000)
            ledger.record("c", "wechat", ChatSnapshot("C", listOf(Msg("other", "不要再约我了"))), false, 2000)
            val rows = ledger.invitationHistory("c", "wechat")
            assertTrue(rows.last().text.contains("不要再约"))
            assertTrue(rows.all { it.visibleTime == null })
        } finally { ledger.close() }
    }
}
