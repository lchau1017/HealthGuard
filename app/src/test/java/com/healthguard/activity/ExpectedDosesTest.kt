@file:OptIn(ExperimentalTime::class)

package com.healthguard.activity

import com.healthguard.shared.data.DoseStatus
import com.healthguard.shared.data.StoredDoseLog
import com.healthguard.shared.data.StoredSchedule
import com.healthguard.shared.extraction.Frequency
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExpectedDosesTest {

    private val zone = TimeZone.UTC

    // --- AdherenceResult.percent ---

    @Test
    fun `percent is taken over expected`() {
        assertEquals(84, AdherenceResult(taken = 21, expected = 25, skipped = 0).percent)
        assertEquals(50, AdherenceResult(taken = 1, expected = 2, skipped = 0).percent)
        assertEquals(0, AdherenceResult(taken = 0, expected = 3, skipped = 0).percent)
        assertEquals(100, AdherenceResult(taken = 12, expected = 12, skipped = 0).percent)
    }

    @Test
    fun `skips are excluded from the denominator`() {
        // 8 taken of 10 expected with 2 deliberate skips: nothing was lapsed.
        assertEquals(100, AdherenceResult(taken = 8, expected = 10, skipped = 2).percent)
        assertEquals(50, AdherenceResult(taken = 4, expected = 10, skipped = 2).percent)
    }

    @Test
    fun `percent rounds to the nearest whole number`() {
        assertEquals(67, AdherenceResult(taken = 2, expected = 3, skipped = 0).percent)
        assertEquals(33, AdherenceResult(taken = 1, expected = 3, skipped = 0).percent)
    }

    @Test
    fun `nothing expected yields null percent`() {
        assertNull(AdherenceResult(taken = 0, expected = 0, skipped = 0).percent)
        // Extra takes with no expectation still have no meaningful percent.
        assertNull(AdherenceResult(taken = 3, expected = 0, skipped = 0).percent)
    }

    @Test
    fun `fully skipped window floors the denominator instead of dividing by zero`() {
        assertEquals(0, AdherenceResult(taken = 0, expected = 2, skipped = 2).percent)
    }

    @Test
    fun `percent is capped at 0 to 100`() {
        // More takes than expected (extra doses) never exceeds 100.
        assertEquals(100, AdherenceResult(taken = 5, expected = 4, skipped = 0).percent)
        assertEquals(100, AdherenceResult(taken = 1, expected = 2, skipped = 2).percent)
    }

    // --- AdherenceResult.meetsTarget ---

    @Test
    fun `meets the 80 percent target exactly at the boundary`() {
        // 79 / 80 / 81 around the clinical PDC threshold.
        assertEquals(false, AdherenceResult(taken = 79, expected = 100, skipped = 0).meetsTarget)
        assertEquals(true, AdherenceResult(taken = 80, expected = 100, skipped = 0).meetsTarget)
        assertEquals(true, AdherenceResult(taken = 81, expected = 100, skipped = 0).meetsTarget)
    }

    @Test
    fun `no percent means no target verdict`() {
        assertNull(AdherenceResult(taken = 0, expected = 0, skipped = 0).meetsTarget)
        assertNull(AdherenceResult(taken = 3, expected = 0, skipped = 0).meetsTarget)
    }

    // --- adherenceResult over a window ---

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

    @Test
    fun `adherenceResult counts expected from the schedule and takes and skips from logs`() {
        // 2x/day over two full days = 4 expected; 2 taken, 1 skipped, one
        // slot unrecorded -> 2 of (4-1) = 67%.
        val result = adherenceResult(
            schedule = schedule(),
            logs = listOf(
                log("2026-06-02T09:00:00Z"),
                log("2026-06-02T21:00:00Z", status = DoseStatus.SKIPPED),
                log("2026-06-03T09:05:00Z"),
            ),
            from = Instant.parse("2026-06-02T00:00:00Z"),
            to = Instant.parse("2026-06-04T00:00:00Z"),
            zone = zone,
        )
        assertEquals(AdherenceResult(taken = 2, expected = 4, skipped = 1), result)
        assertEquals(67, result.percent)
    }

    @Test
    fun `missed logs do not inflate the denominator`() {
        // The missed slot is already one of the expected four.
        val result = adherenceResult(
            schedule = schedule(),
            logs = listOf(
                log("2026-06-02T09:00:00Z"),
                log("2026-06-02T21:00:00Z", status = DoseStatus.MISSED),
                log("2026-06-03T09:00:00Z"),
                log("2026-06-03T21:00:00Z"),
            ),
            from = Instant.parse("2026-06-02T00:00:00Z"),
            to = Instant.parse("2026-06-04T00:00:00Z"),
            zone = zone,
        )
        assertEquals(AdherenceResult(taken = 3, expected = 4, skipped = 0), result)
        assertEquals(75, result.percent)
    }

    @Test
    fun `days without any log still count as expected`() {
        // Two silent days out of four: honesty about unlogged gaps.
        val result = adherenceResult(
            schedule = schedule(frequency = Frequency.TimesPerDay(1)),
            logs = listOf(
                log("2026-06-02T09:00:00Z"),
                log("2026-06-05T09:00:00Z"),
            ),
            from = Instant.parse("2026-06-02T00:00:00Z"),
            to = Instant.parse("2026-06-06T00:00:00Z"),
            zone = zone,
        )
        assertEquals(AdherenceResult(taken = 2, expected = 4, skipped = 0), result)
        assertEquals(50, result.percent)
    }

    @Test
    fun `interval schedules expect nothing and yield a null percent`() {
        val result = adherenceResult(
            schedule = schedule(frequency = Frequency.EveryHours(6)),
            logs = listOf(log("2026-06-02T09:00:00Z"), log("2026-06-02T15:00:00Z")),
            from = Instant.parse("2026-06-02T00:00:00Z"),
            to = Instant.parse("2026-06-04T00:00:00Z"),
            zone = zone,
        )
        assertEquals(AdherenceResult(taken = 2, expected = 0, skipped = 0), result)
        assertNull(result.percent)
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
