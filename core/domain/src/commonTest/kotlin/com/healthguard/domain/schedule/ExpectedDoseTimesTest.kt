package com.healthguard.domain.schedule

import com.healthguard.domain.model.ScheduleId
import com.healthguard.domain.model.MedicationId
import com.healthguard.domain.model.StoredSchedule
import com.healthguard.domain.extraction.Frequency
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Pins the exact semantics of [expectedDoseTimes]:
 *
 * - Only while active: the window is clipped to
 *   [max(from, startedAt), min(to, stoppedAt ?: to)); dormant -> empty.
 * - TimesPerDay(n): the meal-aligned [doseSlots] anchor instants of every
 *   local day that fall inside the clipped window (half-open, so a slot at
 *   exactly `to` is excluded and one at exactly the clipped start counts).
 * - EveryHours(h): always empty — interval dosing is a maximum frequency
 *   (as-needed), not a mandatory schedule.
 * - Slots are wall-clock anchors: 09:00 stays 09:00 local across DST.
 */
class ExpectedDoseTimesTest {

    private val utc = TimeZone.UTC
    private val london = TimeZone.of("Europe/London")

    private fun schedule(
        frequency: Frequency? = Frequency.TimesPerDay(2),
        startedAt: Instant? = Instant.parse("2026-06-01T06:00:00Z"),
        stoppedAt: Instant? = null,
    ) = StoredSchedule(
        id = ScheduleId("sched-1"),
        medicationId = MedicationId("med-1"),
        frequency = frequency,
        withFood = null,
        startedAt = startedAt,
        stoppedAt = stoppedAt,
    )

    // --- inactive / non-slot schedules ---

    @Test
    fun dormantScheduleExpectsNothing() {
        val result = expectedDoseTimes(
            schedule(startedAt = null),
            from = Instant.parse("2026-06-01T00:00:00Z"),
            to = Instant.parse("2026-06-08T00:00:00Z"),
            zone = utc,
        )
        assertEquals(emptyList(), result)
    }

    @Test
    fun nullFrequencyExpectsNothing() {
        val result = expectedDoseTimes(
            schedule(frequency = null),
            from = Instant.parse("2026-06-01T00:00:00Z"),
            to = Instant.parse("2026-06-08T00:00:00Z"),
            zone = utc,
        )
        assertEquals(emptyList(), result)
    }

    @Test
    fun everyHoursExpectsNothing() {
        // Interval dosing is an as-needed ceiling, never a mandatory plan.
        val result = expectedDoseTimes(
            schedule(frequency = Frequency.EveryHours(6)),
            from = Instant.parse("2026-06-01T00:00:00Z"),
            to = Instant.parse("2026-06-08T00:00:00Z"),
            zone = utc,
        )
        assertEquals(emptyList(), result)
    }

    // --- counts per frequency ---

    @Test
    fun onceADayYieldsOneSlotPerDay() {
        // Three full UTC days: 09:00 on each.
        val result = expectedDoseTimes(
            schedule(frequency = Frequency.TimesPerDay(1), startedAt = Instant.parse("2026-05-01T00:00:00Z")),
            from = Instant.parse("2026-06-01T00:00:00Z"),
            to = Instant.parse("2026-06-04T00:00:00Z"),
            zone = utc,
        )
        assertEquals(
            listOf(
                Instant.parse("2026-06-01T09:00:00Z"),
                Instant.parse("2026-06-02T09:00:00Z"),
                Instant.parse("2026-06-03T09:00:00Z"),
            ),
            result,
        )
    }

    @Test
    fun twiceADayYieldsTwoSlotsPerDay() {
        val result = expectedDoseTimes(
            schedule(frequency = Frequency.TimesPerDay(2), startedAt = Instant.parse("2026-05-01T00:00:00Z")),
            from = Instant.parse("2026-06-01T00:00:00Z"),
            to = Instant.parse("2026-06-03T00:00:00Z"),
            zone = utc,
        )
        assertEquals(
            listOf(
                Instant.parse("2026-06-01T09:00:00Z"),
                Instant.parse("2026-06-01T21:00:00Z"),
                Instant.parse("2026-06-02T09:00:00Z"),
                Instant.parse("2026-06-02T21:00:00Z"),
            ),
            result,
        )
    }

    @Test
    fun threeTimesADayYieldsMealAnchorsPerDay() {
        val result = expectedDoseTimes(
            schedule(frequency = Frequency.TimesPerDay(3), startedAt = Instant.parse("2026-05-01T00:00:00Z")),
            from = Instant.parse("2026-06-01T00:00:00Z"),
            to = Instant.parse("2026-06-05T00:00:00Z"),
            zone = utc,
        )
        assertEquals(12, result.size)
        assertEquals(Instant.parse("2026-06-01T08:00:00Z"), result.first())
        assertEquals(Instant.parse("2026-06-04T21:00:00Z"), result.last())
    }

    // --- window clipping ---

    @Test
    fun startMidDayExcludesEarlierSlotsOfTheFirstDay() {
        // Started at noon: the 09:00 slot that day was never owed.
        val result = expectedDoseTimes(
            schedule(frequency = Frequency.TimesPerDay(2), startedAt = Instant.parse("2026-06-01T12:00:00Z")),
            from = Instant.parse("2026-06-01T00:00:00Z"),
            to = Instant.parse("2026-06-02T00:00:00Z"),
            zone = utc,
        )
        assertEquals(listOf(Instant.parse("2026-06-01T21:00:00Z")), result)
    }

    @Test
    fun startExactlyOnASlotIncludesIt() {
        val result = expectedDoseTimes(
            schedule(frequency = Frequency.TimesPerDay(1), startedAt = Instant.parse("2026-06-01T09:00:00Z")),
            from = Instant.parse("2026-06-01T00:00:00Z"),
            to = Instant.parse("2026-06-02T00:00:00Z"),
            zone = utc,
        )
        assertEquals(listOf(Instant.parse("2026-06-01T09:00:00Z")), result)
    }

    @Test
    fun stoppedMidWindowClipsAtStop() {
        // Stopped on the 3rd at 10:00: that day's 09:00 still counts, the
        // 21:00 does not, and later days expect nothing.
        val result = expectedDoseTimes(
            schedule(
                frequency = Frequency.TimesPerDay(2),
                startedAt = Instant.parse("2026-05-01T00:00:00Z"),
                stoppedAt = Instant.parse("2026-06-03T10:00:00Z"),
            ),
            from = Instant.parse("2026-06-02T00:00:00Z"),
            to = Instant.parse("2026-06-06T00:00:00Z"),
            zone = utc,
        )
        assertEquals(
            listOf(
                Instant.parse("2026-06-02T09:00:00Z"),
                Instant.parse("2026-06-02T21:00:00Z"),
                Instant.parse("2026-06-03T09:00:00Z"),
            ),
            result,
        )
    }

    @Test
    fun toNowCutsTodaysFutureSlots() {
        // Callers pass to = now: today's 21:00 has not happened, so it is
        // never owed yet.
        val result = expectedDoseTimes(
            schedule(frequency = Frequency.TimesPerDay(2), startedAt = Instant.parse("2026-05-01T00:00:00Z")),
            from = Instant.parse("2026-06-02T00:00:00Z"),
            to = Instant.parse("2026-06-02T10:30:00Z"),
            zone = utc,
        )
        assertEquals(listOf(Instant.parse("2026-06-02T09:00:00Z")), result)
    }

    @Test
    fun slotExactlyAtToIsExcluded() {
        val result = expectedDoseTimes(
            schedule(frequency = Frequency.TimesPerDay(1), startedAt = Instant.parse("2026-05-01T00:00:00Z")),
            from = Instant.parse("2026-06-02T00:00:00Z"),
            to = Instant.parse("2026-06-02T09:00:00Z"),
            zone = utc,
        )
        assertEquals(emptyList(), result)
    }

    @Test
    fun emptyOrInvertedWindowExpectsNothing() {
        val result = expectedDoseTimes(
            schedule(frequency = Frequency.TimesPerDay(2), startedAt = Instant.parse("2026-06-05T00:00:00Z")),
            from = Instant.parse("2026-06-01T00:00:00Z"),
            to = Instant.parse("2026-06-03T00:00:00Z"),
            zone = utc,
        )
        assertEquals(emptyList(), result)
    }

    // --- DST ---

    @Test
    fun slotsStayOnLocalWallClockAcrossDst() {
        // London springs forward on 2026-03-29: 09:00 local is 09:00 UTC
        // before the change and 08:00 UTC after it.
        val result = expectedDoseTimes(
            schedule(frequency = Frequency.TimesPerDay(1), startedAt = Instant.parse("2026-03-01T00:00:00Z")),
            from = Instant.parse("2026-03-28T00:00:00Z"),
            to = Instant.parse("2026-03-31T00:00:00Z"),
            zone = london,
        )
        assertEquals(
            listOf(
                Instant.parse("2026-03-28T09:00:00Z"), // GMT
                Instant.parse("2026-03-29T08:00:00Z"), // BST from here on
                Instant.parse("2026-03-30T08:00:00Z"),
            ),
            result,
        )
    }

    @Test
    fun resultIsAscending() {
        val result = expectedDoseTimes(
            schedule(frequency = Frequency.TimesPerDay(3), startedAt = Instant.parse("2026-05-01T00:00:00Z")),
            from = Instant.parse("2026-06-01T00:00:00Z"),
            to = Instant.parse("2026-06-08T00:00:00Z"),
            zone = utc,
        )
        assertTrue(result.zipWithNext().all { (a, b) -> a < b })
    }
}
