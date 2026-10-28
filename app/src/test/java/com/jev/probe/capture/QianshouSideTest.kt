package com.jev.probe.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QianshouSideTest {
    @Test fun avatarSideDoesNotDependOnTranscriptionWidth() {
        assertEquals("other", qianshouSideForAvatar(87, 1220))
        assertEquals("me", qianshouSideForAvatar(1133, 1220))
        assertEquals("unknown", qianshouSideForAvatar(610, 1220))
    }

    @Test fun clippedOrDuplicatedAvatarNodesKeepAConsistentSide() {
        assertEquals("other", qianshouSideForAvatars(listOf(87, 91), 1220))
        assertEquals("me", qianshouSideForAvatars(listOf(1133), 1220))
        assertEquals("unknown", qianshouSideForAvatars(listOf(87, 1133), 1220))
    }

    @Test fun onlyClearlyOneSidedBubbleCanReplaceMissingAvatar() {
        assertEquals("other", qianshouSideForShortBubble(180, 740, 1220))
        assertEquals("me", qianshouSideForShortBubble(480, 1040, 1220))
        assertEquals("unknown", qianshouSideForShortBubble(180, 1040, 1220))
    }

    @Test fun pageDateSeparatorsApplyOnlyToFollowingVisibleRows() {
        val markers = listOf(772 to "9月15日 22:47", 1416 to "9月15日 23:52")
        assertNull(qianshouTimeForRow(532, markers))
        assertEquals("9月15日 22:47", qianshouTimeForRow(832, markers))
        assertEquals("9月15日 22:47", qianshouTimeForRow(1188, markers))
        assertEquals("9月15日 23:52", qianshouTimeForRow(1476, markers))
        assertEquals("9月15日 23:52", qianshouTimeForRow(2212, markers))
    }
}
