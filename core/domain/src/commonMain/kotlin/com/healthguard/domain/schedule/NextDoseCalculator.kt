package com.healthguard.domain.schedule

import com.healthguard.domain.model.StoredSchedule
import com.healthguard.domain.extraction.Frequency
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** First dose slot of the waking window, minutes from local midnight (08:00). */
private const val WINDOW_START_MINUTE = 8 * 60

/** Length of the waking window in minutes (08:00-22:00). */
private const val WINDOW_LENGTH_MINUTES = 14 * 60

/**
 * Wall-clock dose slots for a frequency, ascending. TimesPerDay slots are
 * aligned to waking and meal times rather than spread mechanically — 1x/day
 * is a morning-routine 09:00, 2x/day brackets the day at 09:00 and 21:00,
 * 3x/day follows meals (08:00, 14:00, 21:00) and 4x/day adds a midday dose
 * (08:00, 12:00, 17:00, 21:00). Five or more doses fall back to even spacing
 * inside the 08:00-22:00 waking window. Nothing is ever scheduled between
 * 22:00 and 08:00.
 *
 * [Frequency.EveryHours] (and null) return an empty list: interval doses
 * anchor to the schedule start / last take, not to wall-clock slots.
 */
fun doseSlots(frequency: Frequency?): List<LocalTime> = when (frequency) {
    is Frequency.TimesPerDay -> slotTimes(frequency.count)
    is Frequency.EveryHours, null -> emptyList()
}

/**
 * Computes when the next dose of a schedule is due. Pure: never reads the
 * clock — [now] and [zone] are injected by the caller.
 *
 * Returns null when the schedule is dormant ([StoredSchedule.startedAt] is
 * null), stopped ([StoredSchedule.stoppedAt] is set), or has no frequency.
 *
 * [Frequency.EveryHours] uses real-time instant arithmetic (a 6-hour gap is
 * 6 elapsed hours even across a DST shift):
 * - lastTaken != null -> `lastTaken + h` hours. This may be in the past;
 *   callers decide how to present an overdue dose.
 * - lastTaken == null -> the first dose is due at `startedAt` (pressing
 *   start means "ready to take"), also possibly in the past.
 *
 * [Frequency.TimesPerDay] uses the meal-aligned wall-clock slots of
 * [doseSlots] in [zone]. Slots stay at the same local time across DST
 * transitions. The result is the earliest slot instant strictly after a
 * threshold:
 * - lastTaken != null -> threshold = maxOf(lastTaken, now): the next
 *   upcoming slot; missed days never accumulate overdue slots.
 * - lastTaken == null -> threshold = startedAt: the first untaken slot
 *   after the schedule started, even if it is already in the past
 *   (overdue-now). Slots before startedAt are never returned.
 *
 * Assumes lastTaken, when present, is not before startedAt.
 */
fun nextDose(
    schedule: StoredSchedule,
    lastTaken: Instant?,
    now: Instant,
    zone: TimeZone,
): Instant? {
    val startedAt = schedule.startedAt ?: return null
    if (schedule.stoppedAt != null) return null
    return when (val frequency = schedule.frequency ?: return null) {
        is Frequency.EveryHours ->
            lastTaken?.plus(frequency.hours.hours) ?: startedAt

        is Frequency.TimesPerDay -> {
            val threshold = if (lastTaken != null) maxOf(lastTaken, now) else startedAt
            nextSlotAfter(threshold, frequency.count, zone)
        }
    }
}

/**
 * Earliest slot instant strictly after [threshold]. Only the threshold's
 * local date and the next one need checking: any earlier date's slots all
 * end by 22:00 local, before the threshold's date begins.
 */
private fun nextSlotAfter(threshold: Instant, count: Int, zone: TimeZone): Instant {
    val date = threshold.toLocalDateTime(zone).date
    val slots = slotTimes(count)
    return sequenceOf(date, date.plus(1, DateTimeUnit.DAY))
        .flatMap { day -> slots.map { slot -> day.atTime(slot).toInstant(zone) } }
        .first { it > threshold }
}

/** Slot times for a times-per-day count, ascending; see [doseSlots]. */
private fun slotTimes(count: Int): List<LocalTime> = when {
    count <= 1 -> listOf(LocalTime(9, 0))
    count == 2 -> listOf(LocalTime(9, 0), LocalTime(21, 0))
    count == 3 -> listOf(LocalTime(8, 0), LocalTime(14, 0), LocalTime(21, 0))
    count == 4 -> listOf(LocalTime(8, 0), LocalTime(12, 0), LocalTime(17, 0), LocalTime(21, 0))
    else -> (0 until count).map { k ->
        val minute = WINDOW_START_MINUTE + (k * WINDOW_LENGTH_MINUTES) / (count - 1)
        LocalTime(minute / 60, minute % 60)
    }
}
