@file:OptIn(ExperimentalTime::class)

package com.healthguard.detail

import com.healthguard.shared.data.StoredDoseLog
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
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

/** How far a log may sit from a slot and still count as answering it. */
private val SLOT_MATCH_WINDOW = 90.minutes

/**
 * Interleaves [logs] with derived [HistoryEntry.NotRecorded] rows, newest
 * first: every expected slot with no log within ±90 minutes of it becomes a
 * visible gap instead of silently vanishing from history.
 *
 * Matching is by the log's plannedAt (takes recorded against a slot carry
 * that slot as plannedAt; ad-hoc takes carry their wall time). Each slot is
 * answered by its nearest in-window log, and a log can answer only one slot
 * — two slots can never share a single take. Slots are processed ascending,
 * so when a log sits equidistant between two slots the earlier slot claims it.
 */
fun historyWithGaps(
    logs: List<StoredDoseLog>,
    expectedSlots: List<Instant>,
): List<HistoryEntry> {
    val unmatched = logs.toMutableList()
    val gaps = mutableListOf<HistoryEntry.NotRecorded>()
    expectedSlots.sorted().forEach { slot ->
        val nearest = unmatched
            .filter { (it.plannedAt - slot).absoluteValue <= SLOT_MATCH_WINDOW }
            .minByOrNull { (it.plannedAt - slot).absoluteValue }
        if (nearest != null) {
            unmatched.remove(nearest)
        } else {
            gaps.add(HistoryEntry.NotRecorded(slot))
        }
    }
    return (logs.map { HistoryEntry.Logged(it) } + gaps).sortedByDescending { it.at }
}
