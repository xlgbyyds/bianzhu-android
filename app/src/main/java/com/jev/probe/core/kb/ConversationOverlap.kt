package com.jev.probe.core.kb

import com.jev.probe.core.Msg
import java.security.MessageDigest

/** Identifies positions in overlapping visible pages, not globally equal phrases. */
internal object ConversationOverlap {
    data class Line(val id: Long, val side: String, val kind: String, val body: String,
                    val time: String?, val confidence: String, val observedAt: Long = 0)

    fun contentKey(side: String, kind: String, body: String): String {
        val encoded = buildString {
            for (part in listOf(side, kind, body)) append(part.length).append(':').append(part)
        }
        return MessageDigest.getInstance("SHA-256").digest(encoded.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    fun readable(side: String, kind: String, body: String) =
        side in setOf("me", "other") && kind in setOf("TEXT", "VOICE_TRANSCRIPT") && body.isNotBlank()

    private fun same(m: Msg, old: Line?) = old != null &&
        m.side in setOf("me", "other", "unknown") &&
        m.kind.name in setOf("TEXT", "VOICE_TRANSCRIPT") && m.text.isNotBlank() &&
        m.side == old.side && m.kind.name == old.kind && m.text == old.body

    fun distinctive(text: String): Boolean {
        val letters = text.filter { it.isLetterOrDigit() }
        return letters.length >= 6 && letters.toSet().size >= 4
    }

    // A longer conversational sequence can be informative even when every
    // individual bubble is short. A few filler words alone still cannot anchor.
    fun structuredShortSequence(messages: List<Msg>): Boolean =
        messages.size >= 6 && messages.map { Triple(it.side, it.kind, it.text) }.distinct().size >= 4 &&
        messages.sumOf { it.text.length } >= 16 &&
        messages.zipWithNext().count { (a, b) -> a.side != b.side } >= 2

    private fun datedSequence(messages: List<Msg>, old: List<Line?>, referenceAt: Long): Boolean {
        if (messages.size < 3 || messages.map { it.text }.distinct().size < 2) return false
        val times = messages.map { MessageTimeEvidence.parts(it.visibleTime, referenceAt) }
        return times.all { it.day != null && it.minute != null } &&
            old.indices.all { i -> old[i]?.let {
                val was = MessageTimeEvidence.parts(it.time, it.observedAt)
                was.day != null && was.minute != null && was == times[i]
            } == true }
    }

    /** A fully seen sub-window needs no new rows even when its old row IDs are
     * ambiguous. Do not choose an occurrence or enrich its time in that case. */
    fun coveredBySinglePage(page: List<Msg>, previous: List<List<Line?>>, referenceAt: Long = 0): Boolean {
        if (page.size < 2 || page.map { it.text }.distinct().size < 2) return false
        val rich = page.any { distinctive(it.text) } &&
            (page.size >= 3 || (page.sumOf { it.text.length } >= 12 && page.any { it.text.length >= 8 }))
        val times = page.map { MessageTimeEvidence.parts(it.visibleTime, referenceAt) }
        val dated = times.all { it.day != null && it.minute != null } && times.distinct().size >= 2
        if (!rich && !dated) return false
        return previous.any { old ->
            (0..(old.size - page.size)).any { start ->
                page.indices.all { index ->
                    val was = old[start + index]
                    val now = page[index]
                    same(now, was) && (!MessageTimeEvidence.conflicts(now.visibleTime, was!!.time, referenceAt, was.observedAt)) &&
                        (rich || MessageTimeEvidence.parts(was.time, was.observedAt) == times[index])
                }
            }
        }
    }

    private fun matchingRuns(page: List<Msg>, previous: List<List<Line?>>,
                             minimumMessages: Int, minimumAnchors: Int, referenceAt: Long): List<List<Pair<Int, Line>>> {
        val runs = ArrayList<List<Pair<Int, Line>>>()
        for (old in previous) for (i in page.indices) for (j in old.indices) {
            if (!same(page[i], old[j])) continue
            // Inspect the whole equal-content run, so a conflicting date at its
            // end cannot be bypassed by matching just its earlier messages.
            if (i > 0 && j > 0 && same(page[i - 1], old[j - 1])) continue
            var n = 0
            while (i + n < page.size && j + n < old.size && same(page[i + n], old[j + n])) n++
            if (n < minimumMessages) continue
            val messages = page.subList(i, i + n)
            val useful = (messages.count { distinctive(it.text) } >= minimumAnchors &&
                messages.map { it.text }.distinct().size >= 2 &&
                (n >= 3 || (messages.sumOf { it.text.length } >= 12 &&
                    messages.any { it.text.length >= 8 }))) ||
                (minimumAnchors == 1 && (datedSequence(messages, old.subList(j, j + n), referenceAt) ||
                    structuredShortSequence(messages)))
            if (!useful) continue
            if ((0 until n).any { k ->
                    val a = page[i + k].visibleTime?.takeIf { it.isNotBlank() }
                    val b = old[j + k]!!.time?.takeIf { it.isNotBlank() }
                    MessageTimeEvidence.conflicts(a, b, referenceAt, old[j + k]!!.observedAt)
                }) continue
            runs.add((0 until n).map { k -> i + k to old[j + k]!! })
        }
        return runs
    }

    data class Coverage(val positions: Set<Int>, val conflictingPositions: Set<Int>)

    /** Existing duplicate copies need no additional body rows. Keep their IDs
     * unresolved; never borrow one occurrence's timestamp for another. */
    fun representedPositions(page: List<Msg>, previous: List<List<Line?>>, referenceAt: Long = 0,
                             confirmed: Map<Int, Line> = emptyMap()): Coverage {
        val runs = matchingRuns(page, previous, 2, 1, referenceAt)
        val byPosition = runs.flatten().groupBy { it.first }
        val byId = runs.flatten().groupBy { it.second.id }
        val repeatedIds = byId.filterValues { entries -> entries.map { it.first }.distinct().size > 1 }.keys
        val conflicts = byPosition.filterValues { entries ->
            val alternatives = entries.map { it.second }.distinctBy { it.id }
            alternatives.indices.any { i -> alternatives.drop(i + 1).any { b ->
                val a = alternatives[i]
                MessageTimeEvidence.conflicts(a.time, b.time, a.observedAt, b.observedAt)
            } }
        }.keys
        // Reject the complete segment if any of its alternatives has conflicting
        // dates. A date disappearing from the viewport is not evidence of a day.
        val uncertain = runs.filter { run -> run.any { it.first in conflicts } }
            .flatten().map { it.first }.toSet()
        val covered = runs.filter { run -> run.none { it.first in uncertain || it.second.id in repeatedIds } }
            .flatten().map { it.first }.toSet()
        val represented = covered.toMutableSet()
        // Several stored copies of an adjacent fully dated message can agree
        // on its occurrence even though no canonical ID can be selected.
        if (confirmed.size >= 3 && confirmed.keys.map { MessageTimeEvidence.parts(page[it].visibleTime, referenceAt) }
                .filter { it.day != null && it.minute != null }.distinct().size >= 2) {
            for (position in page.indices.filterNot { it in represented || it in uncertain || it in confirmed }) {
                if (position - 1 !in confirmed && position + 1 !in confirmed) continue
                val now = page[position]
                val time = MessageTimeEvidence.parts(now.visibleTime, referenceAt)
                if (time.day == null || time.minute == null) continue
                if (page.count { it.side == now.side && it.kind == now.kind && it.text == now.text &&
                        MessageTimeEvidence.parts(it.visibleTime, referenceAt) == time } != 1) continue
                val instant = time.day.toEpochDay() * 1440 + time.minute
                val before = (position - 1).takeIf { it in confirmed }?.let { MessageTimeEvidence.parts(page[it].visibleTime, referenceAt) }
                val after = (position + 1).takeIf { it in confirmed }?.let { MessageTimeEvidence.parts(page[it].visibleTime, referenceAt) }
                if (before?.day != null && before.minute != null && before.day.toEpochDay() * 1440 + before.minute > instant) continue
                if (after?.day != null && after.minute != null && after.day.toEpochDay() * 1440 + after.minute < instant) continue
                val alternatives = previous.flatten().filterNotNull().filter { was ->
                    same(now, was) && MessageTimeEvidence.parts(was.time, was.observedAt) == time
                }.distinctBy { it.id }
                if (alternatives.isNotEmpty() && alternatives.none { was -> confirmed.values.any { it.id == was.id } }) represented.add(position)
            }
        }
        return Coverage(represented, uncertain)
    }

    /** Enough context to recognize an exact previously observed raw window. */
    fun informativeWindow(page: List<Msg>, referenceAt: Long): Boolean {
        if (page.size < 2 || page.map { it.text }.distinct().size < 2) return false
        if (structuredShortSequence(page)) return true
        if (page.any { distinctive(it.text) } &&
            (page.size >= 3 || (page.sumOf { it.text.length } >= 12 && page.any { it.text.length >= 8 }))) return true
        val times = page.map { MessageTimeEvidence.parts(it.visibleTime, referenceAt) }
        return times.all { it.day != null && it.minute != null } && times.distinct().size >= 2
    }

    /** Returns canonical line ids only for an unambiguous, informative sequence. */
    fun match(page: List<Msg>, previous: List<List<Line?>>,
              minimumMessages: Int = 2, minimumAnchors: Int = 1, referenceAt: Long = 0): Map<Int, Line> {
        val runs = matchingRuns(page, previous, minimumMessages, minimumAnchors, referenceAt)
        // A short repeated phrase elsewhere must not veto a longer, distinctive
        // alignment. Equal-strength occurrences remain ambiguous; never pick the
        // first or newest row merely because it appeared first in the query.
        fun strength(run: List<Pair<Int, Line>>) = run.size to run.sumOf { it.second.body.length }
        val best = HashMap<Int, Pair<Int, Int>>()
        for (run in runs) for ((position, _) in run) {
            val score = strength(run)
            val current = best[position]
            if (current == null || score.first > current.first ||
                (score.first == current.first && score.second > current.second)) best[position] = score
        }
        val strongest = runs.map { run -> run.filter { best[it.first] == strength(run) } }
        val byPosition = HashMap<Int, MutableSet<Long>>()
        val byId = HashMap<Long, MutableSet<Int>>()
        for (run in strongest) for ((position, line) in run) {
            byPosition.getOrPut(position) { HashSet() }.add(line.id)
            byId.getOrPut(line.id) { HashSet() }.add(position)
        }
        val matched = HashMap<Int, Line>()
        for (run in strongest) {
            // Repeated text within a page, or two plausible old occurrences,
            // cannot consume the same stored message twice.
            if (run.any { (position, line) -> byPosition[position]!!.size != 1 || byId[line.id]!!.size != 1 }) continue
            run.forEach { (position, line) -> matched[position] = line }
        }
        // A dated three-message alignment can anchor its adjacent dated message
        // from another stored page. A lone word/time never supplies this proof.
        if (matched.size >= 3 && matched.keys.map { MessageTimeEvidence.parts(page[it].visibleTime, referenceAt) }
                .filter { it.day != null && it.minute != null }.distinct().size >= 2) {
            val anchored = matched.keys.toSet()
            for (position in page.indices.filterNot(matched::containsKey)) {
                if (position - 1 !in anchored && position + 1 !in anchored) continue
                val now = page[position]
                val time = MessageTimeEvidence.parts(now.visibleTime, referenceAt)
                if (time.day == null || time.minute == null) continue
                val instant = time.day.toEpochDay() * 1440 + time.minute
                val before = (position - 1).takeIf { it in anchored }?.let {
                    MessageTimeEvidence.parts(page[it].visibleTime, referenceAt)
                }
                val after = (position + 1).takeIf { it in anchored }?.let {
                    MessageTimeEvidence.parts(page[it].visibleTime, referenceAt)
                }
                if (before?.day != null && before.minute != null &&
                    before.day.toEpochDay() * 1440 + before.minute > instant) continue
                if (after?.day != null && after.minute != null &&
                    after.day.toEpochDay() * 1440 + after.minute < instant) continue
                if (page.count { it.side == now.side && it.kind == now.kind && it.text == now.text &&
                        MessageTimeEvidence.parts(it.visibleTime, referenceAt) == time } != 1) continue
                val unique = previous.flatten().filterNotNull().filter { was ->
                    same(now, was) && MessageTimeEvidence.parts(was.time, was.observedAt) == time
                }.distinctBy { it.id }.singleOrNull() ?: continue
                if (matched.values.none { it.id == unique.id }) matched[position] = unique
            }
        }
        return matched
    }
}
