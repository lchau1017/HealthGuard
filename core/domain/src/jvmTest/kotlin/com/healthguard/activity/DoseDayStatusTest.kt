@file:OptIn(ExperimentalTime::class)

package com.healthguard.activity

import com.healthguard.shared.data.DoseStatus
import com.healthguard.shared.data.StoredDoseLog
import com.healthguard.shared.data.StoredSchedule
import com.healthguard.shared.extraction.Frequency
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

class DoseDayStatusTest {

    private val zone = TimeZone.UTC

    private fun schedule(
        frequency: Frequency? = Frequency.TimesPerDay(2),
        startedAt: Instant? = Instant.parse("2026-06-01T00:00:00Z"),
    ) = StoredSchedule(
        id = "sch-1",
        medicationId = "med-1",
        frequency = frequency,
        withFood = null,
        startedAt = startedAt,
        stoppedAt = null,
    )

    private var doseCounter = 0

    private fun log(at: String, status: DoseStatus = DoseStatus.TAKEN): StoredDoseLog {
        val instant = Instant.parse(at)
        return StoredDoseLog(
            id = "d-${doseCounter++}",
            scheduleId = "sch-1",
            plannedAt = instant,
            takenAt = if (status == DoseStatus.TAKEN) instant else null,
            status = status,
        )
    }

    // --- doseDayStatus ---

    @Test
    fun `a day with all adjusted expected doses taken is met`() {
        assertEquals(DoseDayStatus.MET, doseDayStatus(expected = 2, taken = 2, skipped = 0))
        assertEquals(DoseDayStatus.MET, doseDayStatus(expected = 2, taken = 1, skipped = 1))
        assertEquals(DoseDayStatus.MET, doseDayStatus(expected = 2, taken = 3, skipped = 0))
        // Takes beyond the skip-adjusted expectation still count as met.
        assertEquals(DoseDayStatus.MET, doseDayStatus(expected = 2, taken = 1, skipped = 2))
    }

    @Test
    fun `a day with some but not all doses taken is partial`() {
        assertEquals(DoseDayStatus.PARTIAL, doseDayStatus(expected = 2, taken = 1, skipped = 0))
        assertEquals(DoseDayStatus.PARTIAL, doseDayStatus(expected = 3, taken = 2, skipped = 0))
        // Taken measured against expected − skipped: 1 of (3−1)=2 is partial.
        assertEquals(DoseDayStatus.PARTIAL, doseDayStatus(expected = 3, taken = 1, skipped = 1))
    }

    @Test
    fun `a day with expectations and no takes is not-taken whether missed or unrecorded`() {
        assertEquals(DoseDayStatus.NOT_TAKEN, doseDayStatus(expected = 2, taken = 0, skipped = 0))
        // Partly skipped, rest unanswered: still a lapse, not a choice.
        assertEquals(DoseDayStatus.NOT_TAKEN, doseDayStatus(expected = 2, taken = 0, skipped = 1))
    }

    @Test
    fun `a fully skipped day is skipped not a lapse`() {
        assertEquals(DoseDayStatus.SKIPPED, doseDayStatus(expected = 2, taken = 0, skipped = 2))
        assertEquals(DoseDayStatus.SKIPPED, doseDayStatus(expected = 1, taken = 0, skipped = 1))
        // Over-logged skips still read as a deliberate day off.
        assertEquals(DoseDayStatus.SKIPPED, doseDayStatus(expected = 1, taken = 0, skipped = 2))
    }

    @Test
    fun `a day without expectations is out of treatment`() {
        assertEquals(
            DoseDayStatus.OUT_OF_TREATMENT,
            doseDayStatus(expected = 0, taken = 0, skipped = 0),
        )
        // An as-needed extra take does not create an expectation.
        assertEquals(
            DoseDayStatus.OUT_OF_TREATMENT,
            doseDayStatus(expected = 0, taken = 1, skipped = 0),
        )
    }

    // --- doseDayStatusByDay ---

    @Test
    fun `buckets status per local day and omits days without expectations`() {
        val schedule = schedule() // 2x/day since 2026-06-01
        val expected = listOf(
            Instant.parse("2026-06-02T09:00:00Z"),
            Instant.parse("2026-06-02T21:00:00Z"),
            Instant.parse("2026-06-03T09:00:00Z"),
            Instant.parse("2026-06-03T21:00:00Z"),
            Instant.parse("2026-06-04T09:00:00Z"),
            Instant.parse("2026-06-04T21:00:00Z"),
            Instant.parse("2026-06-05T09:00:00Z"),
            Instant.parse("2026-06-05T21:00:00Z"),
        )
        val logs = listOf(
            // 2nd: both taken -> MET.
            log("2026-06-02T09:00:00Z"),
            log("2026-06-02T21:10:00Z"),
            // 3rd: one taken -> PARTIAL.
            log("2026-06-03T09:00:00Z"),
            // 4th: nothing -> NOT_TAKEN.
            // 5th: both skipped -> SKIPPED (a visible choice, not absent).
            log("2026-06-05T09:00:00Z", status = DoseStatus.SKIPPED),
            log("2026-06-05T21:00:00Z", status = DoseStatus.SKIPPED),
        )
        assertEquals(
            mapOf(
                LocalDate(2026, 6, 2) to DoseDayStatus.MET,
                LocalDate(2026, 6, 3) to DoseDayStatus.PARTIAL,
                LocalDate(2026, 6, 4) to DoseDayStatus.NOT_TAKEN,
                LocalDate(2026, 6, 5) to DoseDayStatus.SKIPPED,
            ),
            doseDayStatusByDay(expected, logs, zone),
        )
        // `schedule` documents where the expected instants come from in prod.
        assertEquals(
            expected,
            com.healthguard.shared.domain.expectedDoseTimes(
                schedule,
                Instant.parse("2026-06-02T00:00:00Z"),
                Instant.parse("2026-06-06T00:00:00Z"),
                zone,
            ),
        )
    }

    @Test
    fun `takes bucket by their taken day when it differs from planned`() {
        // Planned late on the 2nd, taken past midnight on the 3rd: the take
        // belongs to the 3rd, leaving the 2nd's slot unanswered.
        val expected = listOf(
            Instant.parse("2026-06-02T21:00:00Z"),
        )
        val lateTake = StoredDoseLog(
            id = "late",
            scheduleId = "sch-1",
            plannedAt = Instant.parse("2026-06-02T21:00:00Z"),
            takenAt = Instant.parse("2026-06-03T00:30:00Z"),
            status = DoseStatus.TAKEN,
        )
        assertEquals(
            mapOf(LocalDate(2026, 6, 2) to DoseDayStatus.NOT_TAKEN),
            doseDayStatusByDay(expected, listOf(lateTake), zone),
        )
    }
}
