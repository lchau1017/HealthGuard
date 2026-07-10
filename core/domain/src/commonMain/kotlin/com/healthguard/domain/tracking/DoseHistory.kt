package com.healthguard.domain.tracking

import com.healthguard.domain.model.StoredDoseLog
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * One row of the detail history list: either a real dose log or a derived
 * "Not recorded" marker for an expected slot nothing was logged against.
 */
sealed interface HistoryEntry {
    /** When the entry happened (or should have), for chronological ordering. */
    val at: Instant

    data class Logged(val log: StoredDoseLog) : HistoryEntry {
        override val at: Instant get() = log.takenAt ?: log.plannedAt
    }

    data class NotRecorded(val slotAt: Instant) : HistoryEntry {
        override val at: Instant get() = slotAt
    }
}

/**
 * How far a log may sit from a slot and still count as answering it — and
 * therefore how long a slot stays open before it can read as "not recorded":
 * a slot is never declared unanswered while a matching take is still likely.
 */
val SLOT_MATCH_WINDOW = 90.minutes

/**
 * The expected slots no log answers — the "Not recorded" derivation shared
 * by the detail history and the day-detail sheet. A slot is answered by its
 * nearest log time within ±90 minutes, and a log can answer only one slot —
 * two slots can never share a single take. Slots are processed ascending,
 * so when a log sits equidistant between two slots the earlier slot claims
 * it. Log times are plannedAt (takes recorded against a slot carry that
 * slot as plannedAt; ad-hoc takes carry their wall time).
 */
fun unansweredSlots(
    expectedSlots: List<Instant>,
    logTimes: List<Instant>,
): List<Instant> {
    val unmatched = logTimes.toMutableList()
    val gaps = mutableListOf<Instant>()
    expectedSlots.sorted().forEach { slot ->
        val nearest = unmatched
            .filter { (it - slot).absoluteValue <= SLOT_MATCH_WINDOW }
            .minByOrNull { (it - slot).absoluteValue }
        if (nearest != null) {
            unmatched.remove(nearest)
        } else {
            gaps.add(slot)
        }
    }
    return gaps
}

/**
 * Interleaves [logs] with derived [HistoryEntry.NotRecorded] rows, newest
 * first: every expected slot [unansweredSlots] leaves open becomes a
 * visible gap instead of silently vanishing from history.
 *
 * Slots are matched against [matchLogs], which defaults to [logs]. Pass a
 * superset when the displayed list is time-clipped: a log up to
 * [SLOT_MATCH_WINDOW] outside the clip can still answer a slot just inside
 * it, and must suppress that slot's gap row even though the log itself is
 * not displayed.
 */
fun historyWithGaps(
    logs: List<StoredDoseLog>,
    expectedSlots: List<Instant>,
    matchLogs: List<StoredDoseLog> = logs,
): List<HistoryEntry> {
    val gaps = unansweredSlots(expectedSlots, matchLogs.map { it.plannedAt })
        .map { HistoryEntry.NotRecorded(it) }
    return (logs.map { HistoryEntry.Logged(it) } + gaps).sortedByDescending { it.at }
}
