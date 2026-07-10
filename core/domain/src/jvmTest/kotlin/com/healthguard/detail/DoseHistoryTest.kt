@file:OptIn(ExperimentalTime::class)

package com.healthguard.detail

import com.healthguard.domain.model.DoseStatus
import com.healthguard.domain.model.StoredDoseLog
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class DoseHistoryTest {

    private var doseCounter = 0

    private fun log(
        plannedAt: String,
        takenAt: String? = plannedAt,
        status: DoseStatus = DoseStatus.TAKEN,
    ): StoredDoseLog = StoredDoseLog(
        id = "d-${doseCounter++}",
        scheduleId = "sch-1",
        plannedAt = Instant.parse(plannedAt),
        takenAt = takenAt?.let(Instant::parse),
        status = status,
    )

    @Test
    fun `slots without any nearby log become not-recorded rows`() {
        val entries = historyWithGaps(
            logs = listOf(log("2026-07-06T09:00:00Z")),
            expectedSlots = listOf(
                Instant.parse("2026-07-06T09:00:00Z"),
                Instant.parse("2026-07-06T21:00:00Z"),
            ),
        )
        assertEquals(
            listOf(
                HistoryEntry.NotRecorded(Instant.parse("2026-07-06T21:00:00Z")),
                HistoryEntry.Logged(log = entries.filterIsInstance<HistoryEntry.Logged>().single().log),
            ),
            entries,
        )
    }

    @Test
    fun `entries interleave newest first by their effective time`() {
        val morningTake = log("2026-07-06T09:00:00Z", takenAt = "2026-07-06T09:10:00Z")
        val oldSkip = log("2026-07-04T21:00:00Z", takenAt = null, status = DoseStatus.SKIPPED)
        val entries = historyWithGaps(
            logs = listOf(morningTake, oldSkip),
            expectedSlots = listOf(
                Instant.parse("2026-07-04T21:00:00Z"),
                Instant.parse("2026-07-05T09:00:00Z"),
                Instant.parse("2026-07-06T09:00:00Z"),
            ),
        )
        assertEquals(
            listOf(
                HistoryEntry.Logged(morningTake),
                HistoryEntry.NotRecorded(Instant.parse("2026-07-05T09:00:00Z")),
                HistoryEntry.Logged(oldSkip),
            ),
            entries,
        )
    }

    @Test
    fun `a log within 90 minutes of the slot answers it`() {
        // Taken 80 minutes late, recorded ad hoc (plannedAt = takenAt).
        val lateTake = log("2026-07-06T10:20:00Z")
        val entries = historyWithGaps(
            logs = listOf(lateTake),
            expectedSlots = listOf(Instant.parse("2026-07-06T09:00:00Z")),
        )
        assertEquals(listOf<HistoryEntry>(HistoryEntry.Logged(lateTake)), entries)
    }

    @Test
    fun `a log more than 90 minutes from every slot leaves the slot unrecorded`() {
        val extraTake = log("2026-07-06T13:00:00Z")
        val entries = historyWithGaps(
            logs = listOf(extraTake),
            expectedSlots = listOf(Instant.parse("2026-07-06T09:00:00Z")),
        )
        assertEquals(
            listOf(
                HistoryEntry.Logged(extraTake),
                HistoryEntry.NotRecorded(Instant.parse("2026-07-06T09:00:00Z")),
            ),
            entries,
        )
    }

    @Test
    fun `a slot is matched by its nearest log`() {
        // Both logs are within 90 minutes of the slot; the nearer one
        // answers it and the other stands alone.
        val near = log("2026-07-06T09:20:00Z")
        val far = log("2026-07-06T10:10:00Z")
        val entries = historyWithGaps(
            logs = listOf(near, far),
            expectedSlots = listOf(Instant.parse("2026-07-06T09:00:00Z")),
        )
        assertEquals(
            listOf<HistoryEntry>(HistoryEntry.Logged(far), HistoryEntry.Logged(near)),
            entries,
        )
    }

    @Test
    fun `one log can answer only one slot`() {
        // A single take between two slots answers the nearer (earlier when
        // equidistant); the other becomes not recorded.
        val take = log("2026-07-06T10:30:00Z")
        val entries = historyWithGaps(
            logs = listOf(take),
            expectedSlots = listOf(
                Instant.parse("2026-07-06T09:00:00Z"),
                Instant.parse("2026-07-06T12:00:00Z"),
            ),
        )
        assertEquals(
            listOf(
                HistoryEntry.NotRecorded(Instant.parse("2026-07-06T12:00:00Z")),
                HistoryEntry.Logged(take),
            ),
            entries,
        )
    }

    @Test
    fun `no slots means logs pass through untouched`() {
        val take = log("2026-07-06T09:00:00Z")
        assertEquals(
            listOf<HistoryEntry>(HistoryEntry.Logged(take)),
            historyWithGaps(listOf(take), expectedSlots = emptyList()),
        )
    }
}
