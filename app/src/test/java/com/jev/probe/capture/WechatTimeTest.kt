package com.jev.probe.capture

import org.junit.Assert.*
import org.junit.Test

class WechatTimeTest {
    @Test fun acceptsVisibleWechatDateAndTimeSeparators() {
        listOf("9月27日 下午2:28", "2026年9月27日 14:28", "昨天 18:04", "星期一 上午10:00", "16:32",
            "周四 晚上9:08", "10月8日 周四 晚上9:33", "2026年10月8日 星期四 晚上9:08")
            .forEach { assertTrue(it, isWechatTimeLabel(it)) }
    }

    @Test fun rejectsMessagesMerelyMentioningDatesOrTimes() {
        listOf("", "明天10:00见", "9月27日我发给你", "今天开心吗", "下午2:28再联系",
            "周四晚上9:08再说", "这周四忙吗", "10月8日 周四 晚上9:33吃饭")
            .forEach { assertFalse(it, isWechatTimeLabel(it)) }
    }

    @Test fun doesNotApplyAFutureSeparatorToEarlierRows() {
        val markers = listOf(1200 to "9月27日 下午2:01", 1950 to "9月27日 下午2:28")
        assertNull(qianshouTimeForRow(900, markers))
        assertEquals("9月27日 下午2:01", qianshouTimeForRow(1300, markers))
        assertEquals("9月27日 下午2:28", qianshouTimeForRow(2200, markers))
    }
}
