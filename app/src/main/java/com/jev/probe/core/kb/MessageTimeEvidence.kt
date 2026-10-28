package com.jev.probe.core.kb

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Compare visible labels by evidence, without inventing a date for a clock-only label. */
internal object MessageTimeEvidence {
    private val calendarLabel = Regex("(?:(\\d{4})年)?(\\d{1,2})月(\\d{1,2})日")
    private val clockLabel = Regex("(\\d{1,2})[:：](\\d{2})")
    private val afternoon = Regex("下午|晚上|傍晚|中午")
    private val morning = Regex("凌晨|上午")
    private val relative = Regex("今天|昨天|前天")
    data class Parts(val day: LocalDate?, val minute: Int?)

    fun parts(label: String?, observedAt: Long = 0): Parts {
        val raw = label.orEmpty()
        val reference = if (observedAt > 0) Instant.ofEpochMilli(observedAt)
            .atZone(ZoneId.systemDefault()).toLocalDate() else null
        val date = calendarLabel.find(raw)
        val day = when {
            date != null -> runCatching { LocalDate.of(
                date.groupValues[1].toIntOrNull() ?: reference?.year ?: 2000,
                date.groupValues[2].toInt(), date.groupValues[3].toInt()) }.getOrNull()
            reference == null -> null
            "前天" in raw -> reference.minusDays(2)
            "昨天" in raw -> reference.minusDays(1)
            "今天" in raw -> reference
            else -> null
        }
        val clock = clockLabel.find(raw)
        val minute = clock?.let {
            var hour = it.groupValues[1].toInt()
            val minutes = it.groupValues[2].toInt()
            if (afternoon.containsMatchIn(raw) && hour in 1..11) hour += 12
            if (morning.containsMatchIn(raw) && hour == 12) hour = 0
            (hour * 60 + minutes).takeIf { hour in 0..23 && minutes in 0..59 }
        }
        return Parts(day, minute)
    }

    fun conflicts(a: String?, b: String?, aObservedAt: Long = 0, bObservedAt: Long = 0): Boolean {
        if (a.isNullOrBlank() || b.isNullOrBlank()) return false
        val x = parts(a, aObservedAt); val y = parts(b, bObservedAt)
        if (x.day != null && y.day != null && x.day != y.day) return true
        if (x.minute != null && y.minute != null && x.minute != y.minute) return true
        // Unparsed, non-identical evidence must not silently merge.
        return x.day == null && x.minute == null && y.day == null && y.minute == null && a != b
    }

    fun enriches(old: String?, fresh: String?, referenceAt: Long = 0, freshObservedAt: Long = referenceAt): Boolean {
        if (fresh.isNullOrBlank() || conflicts(old, fresh, referenceAt, freshObservedAt)) return false
        if (old.isNullOrBlank()) return true
        val a = parts(old, referenceAt); val b = parts(fresh, freshObservedAt)
        return (a.day == null && b.day != null) || (a.minute == null && b.minute != null)
    }

    /** Freeze relative labels at observation; a later backfill must not change their day. */
    fun freezeRelative(label: String?, observedAt: Long): String? {
        if (label == null) return null
        if (!relative.containsMatchIn(label)) return label
        val day = parts(label, observedAt).day ?: return label
        return label.replace(relative, "${day.year}年${day.monthValue}月${day.dayOfMonth}日")
    }
}
