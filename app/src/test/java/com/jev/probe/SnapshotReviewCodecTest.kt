package com.jev.probe

import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.MessageKind
import com.jev.probe.core.Msg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SnapshotReviewCodecTest {
    @Test fun correctionPayloadRoundTripsWithoutLosingSenderKindOrVisibleTime() {
        val snapshot = ChatSnapshot("ExampleContact", listOf(
            Msg("me", "我发的", MessageKind.TEXT, "9月15日 16:00", "partial"),
            Msg("other", "语音转写内容", MessageKind.VOICE_TRANSCRIPT, null, "unknown"),
            Msg("other", "", MessageKind.VOICE_UNKNOWN)
        ), note = "time label may cover multiple lines")

        val decoded = SnapshotReviewCodec.decode(SnapshotReviewCodec.encode(snapshot))

        assertNotNull(decoded)
        assertEquals(snapshot, decoded)
    }
}
