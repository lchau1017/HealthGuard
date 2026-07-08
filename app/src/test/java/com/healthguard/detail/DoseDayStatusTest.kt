@file:OptIn(ExperimentalTime::class)

package com.healthguard.detail

import com.healthguard.shared.data.DoseStatus
import com.healthguard.shared.data.StoredDoseLog
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DoseDayStatusTest {

    private val zone = TimeZone.UTC

    private var counter = 0

    private fun log(
        dayIso: String,
        status: DoseStatus,
        hour: Int = 9,
        takenAt: Instant? = null,
    ): StoredDoseLog {
        val planned = Instant.parse("${dayIso}T${hour.toString().padStart(2, '0')}:00:00Z")
        return StoredDoseLog(
            id = "d-${counter++}",
            scheduleId = "sch-1",
            plannedAt = planned,
            takenAt = takenAt ?: planned.takeIf { status == DoseStatus.TAKEN },
            status = status,
        )
    }

    @Test
    fun `all takes on a day is ALL`() {
        val statuses = doseDayStatuses(
            listOf(log("2026-07-06", DoseStatus.TAKEN), log("2026-07-06", DoseStatus.TAKEN, hour = 21)),
            zone,
        )
        assertEquals(DayDoseStatus.ALL, statuses[LocalDate(2026, 7, 6)])
    }

    @Test
    fun `a take mixed with a miss or skip is SOME`() {
        val statuses = doseDayStatuses(
            listOf(
                log("2026-07-06", DoseStatus.TAKEN),
                log("2026-07-06", DoseStatus.MISSED, hour = 21),
                log("2026-07-05", DoseStatus.TAKEN),
                log("2026-07-05", DoseStatus.SKIPPED, hour = 21),
            ),
            zone,
        )
        assertEquals(DayDoseStatus.SOME, statuses[LocalDate(2026, 7, 6)])
        assertEquals(DayDoseStatus.SOME, statuses[LocalDate(2026, 7, 5)])
    }

    @Test
    fun `only misses or skips is MISSED`() {
        val statuses = doseDayStatuses(
            listOf(
                log("2026-07-06", DoseStatus.MISSED),
                log("2026-07-05", DoseStatus.SKIPPED),
            ),
            zone,
        )
        assertEquals(DayDoseStatus.MISSED, statuses[LocalDate(2026, 7, 6)])
        assertEquals(DayDoseStatus.MISSED, statuses[LocalDate(2026, 7, 5)])
    }

    @Test
    fun `days without logs are absent`() {
        val statuses = doseDayStatuses(listOf(log("2026-07-06", DoseStatus.TAKEN)), zone)
        assertNull(statuses[LocalDate(2026, 7, 7)])
        assertEquals(1, statuses.size)
    }

    @Test
    fun `taken doses bucket by takenAt not plannedAt`() {
        // Planned late on the 5th, actually taken after midnight on the 6th.
        val statuses = doseDayStatuses(
            listOf(
                log(
                    "2026-07-05",
                    DoseStatus.TAKEN,
                    hour = 21,
                    takenAt = Instant.parse("2026-07-06T00:30:00Z"),
                ),
            ),
            zone,
        )
        assertEquals(DayDoseStatus.ALL, statuses[LocalDate(2026, 7, 6)])
        assertNull(statuses[LocalDate(2026, 7, 5)])
    }
}
