package com.healthguard.home

import com.healthguard.activity.DoseDayStatus
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class WeekCaptionTest {

    // --- weekCaption ---

    private fun week(vararg states: DoseDayStatus): List<WeekDay> =
        states.mapIndexed { index, state -> WeekDay(LocalDate(2026, 7, 2 + index), state) }

    @Test
    fun `on-pace today with pending slots is excluded and announced`() {
        val days = week(
            DoseDayStatus.MET, DoseDayStatus.MET, DoseDayStatus.NOT_TAKEN,
            DoseDayStatus.MET, DoseDayStatus.PARTIAL, DoseDayStatus.MET,
            DoseDayStatus.MET,
        )
        assertEquals(
            "4 of 6 days on track. Today still to come.",
            weekCaption(days, todayPending = true),
        )
    }

    @Test
    fun `an empty today with pending slots is excluded and announced`() {
        // Early morning: no slot has passed yet.
        val days = week(
            DoseDayStatus.MET, DoseDayStatus.MET, DoseDayStatus.MET,
            DoseDayStatus.MET, DoseDayStatus.MET, DoseDayStatus.MET,
            DoseDayStatus.OUT_OF_TREATMENT,
        )
        assertEquals(
            "6 of 6 days on track. Today still to come.",
            weekCaption(days, todayPending = true),
        )
    }

    @Test
    fun `a behind-schedule today counts off-track immediately`() {
        val days = week(
            DoseDayStatus.MET, DoseDayStatus.MET, DoseDayStatus.MET,
            DoseDayStatus.MET, DoseDayStatus.MET, DoseDayStatus.MET,
            DoseDayStatus.PARTIAL,
        )
        assertEquals("6 of 7 days on track.", weekCaption(days, todayPending = true))
    }

    @Test
    fun `a finished today is counted without the suffix`() {
        val days = week(
            DoseDayStatus.MET, DoseDayStatus.MET, DoseDayStatus.NOT_TAKEN,
            DoseDayStatus.MET, DoseDayStatus.MET, DoseDayStatus.MET,
            DoseDayStatus.MET,
        )
        assertEquals("6 of 7 days on track.", weekCaption(days, todayPending = false))
    }

    @Test
    fun `days without expectations leave the tally`() {
        // Schedule started mid-week: the first two days never owed anything.
        val days = week(
            DoseDayStatus.OUT_OF_TREATMENT, DoseDayStatus.OUT_OF_TREATMENT, DoseDayStatus.MET,
            DoseDayStatus.MET, DoseDayStatus.NOT_TAKEN, DoseDayStatus.MET,
            DoseDayStatus.MET,
        )
        assertEquals("4 of 5 days on track.", weekCaption(days, todayPending = false))
    }

    @Test
    fun `fully skipped days leave the tally like out-of-treatment ones`() {
        // A deliberate day off is not a lapse, so it never dents the score.
        val days = week(
            DoseDayStatus.MET, DoseDayStatus.SKIPPED, DoseDayStatus.MET,
            DoseDayStatus.MET, DoseDayStatus.NOT_TAKEN, DoseDayStatus.MET,
            DoseDayStatus.MET,
        )
        assertEquals("5 of 6 days on track.", weekCaption(days, todayPending = false))
    }

    @Test
    fun `a fully skipped today with pending slots still reads on pace`() {
        val days = week(
            DoseDayStatus.MET, DoseDayStatus.MET, DoseDayStatus.MET,
            DoseDayStatus.MET, DoseDayStatus.MET, DoseDayStatus.MET,
            DoseDayStatus.SKIPPED,
        )
        assertEquals(
            "6 of 6 days on track. Today still to come.",
            weekCaption(days, todayPending = true),
        )
    }

    @Test
    fun `a perfect fully counted week gets the celebration line`() {
        val days = week(
            DoseDayStatus.MET, DoseDayStatus.MET, DoseDayStatus.MET,
            DoseDayStatus.MET, DoseDayStatus.MET, DoseDayStatus.MET,
            DoseDayStatus.MET,
        )
        assertEquals("7 of 7 days on track — nice work.", weekCaption(days, todayPending = false))
    }

    @Test
    fun `a week with nothing scheduled says so`() {
        val days = week(
            DoseDayStatus.OUT_OF_TREATMENT, DoseDayStatus.OUT_OF_TREATMENT, DoseDayStatus.OUT_OF_TREATMENT,
            DoseDayStatus.OUT_OF_TREATMENT, DoseDayStatus.OUT_OF_TREATMENT, DoseDayStatus.OUT_OF_TREATMENT,
            DoseDayStatus.OUT_OF_TREATMENT,
        )
        assertEquals("No scheduled doses this week.", weekCaption(days, todayPending = false))
    }
}
