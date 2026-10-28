package com.jev.probe.core

import java.time.LocalDateTime
import java.time.LocalTime
import java.time.Duration
import java.time.format.DateTimeFormatter

/** Page labels describe a viewport, never a delivery timestamp or a person's present activity. */
data class ConversationTiming(val current: LocalDateTime, val label: String?, val ageDays: Long?,
                              val approximateHours: Long?, val closed: Boolean) {
    val restart: Boolean get() = closed || (ageDays ?: 0) >= 1 || (approximateHours ?: 0) >= 3
    val daypart: String get() = when (current.hour) {
        in 0..5 -> "凌晨"; in 6..11 -> "上午"; in 12..17 -> "下午"; else -> "晚上"
    }
    fun prompt(): String = buildString {
        append("设备当前本地时间：${current.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))}（$daypart）。")
        append("最近可见页面时间：${label ?: "未知"}；页面标签不代表逐条精确发送时刻，采集时间不是发送时间。")
        if (approximateHours != null) append("按页面标签粗略相隔约 $approximateHours 小时，不可断言准确间隔。")
        else if (ageDays != null) append("页面日期约为 $ageDays 天前，具体间隔未知。")
        else append("日期和间隔未知，不能把仅有的钟点认定为今天。")
        if (restart) append("本轮宜自然重启或轻轻回扣旧话题；过时的行程、吃饭、正在忙等状态不能直接延续到现在。")
        if (closed) append("末尾出现收尾表达，不要机械接回已经结束的那一刻。")
        append("分析回复时仍应先回应最新可见消息里的明确问题，不因重启建议跳过对方的问题。")
        append("时间只用于调整聊天节奏，不据此编造双方现在的地点、活动、天气、心情或迟回理由。")
    }
    companion object {
        fun from(snapshot: ChatSnapshot, now: LocalDateTime = LocalDateTime.now()): ConversationTiming {
            val evidence = pageTimeEvidence(snapshot.messages, now.toLocalDate())
            // Do not borrow an earlier date for a later time-only message across a page boundary.
            val label = snapshot.messages.lastOrNull()?.visibleTime ?: evidence.label
            val age = pageDateAgeDays(label, now.toLocalDate())
            val clock = label?.let { Regex("(\\d{1,2}):(\\d{2})").find(it) }
            val time = clock?.let {
                var hour = it.groupValues[1].toInt()
                if (label.contains(Regex("下午|晚上|傍晚|中午")) && hour in 1..11) hour += 12
                if (label.contains(Regex("凌晨|上午")) && hour == 12) hour = 0
                runCatching { LocalTime.of(hour, it.groupValues[2].toInt()) }.getOrNull()
            }
            val hours = if (age != null && time != null) {
                val then = now.toLocalDate().minusDays(age).atTime(time)
                Duration.between(then, now).takeUnless { it.isNegative }?.toHours()
            } else null
            fun isClosing(message: Msg?) = message?.kind == MessageKind.TEXT &&
                Regex("^(?:好[的呀啊]?\\s*)?(?:晚安|先忙[吧啦了]?|先这样[吧啦]?|下次聊|回头聊|明天聊|我去睡[了啦]?)[呀啊啦哈哦～~!！。 ]*$")
                    .matches(message.text.trim())
            val last = snapshot.messages.lastOrNull()
            val closed = isClosing(last) || (last?.kind == MessageKind.TEXT &&
                Regex("^(?:嗯[嗯呐]?|好[的呀啊]?|OK|ok)[～~!！。 ]*$").matches(last.text.trim()) &&
                isClosing(snapshot.messages.dropLast(1).lastOrNull()))
            return ConversationTiming(now, label, age, hours, closed)
        }
    }
}
