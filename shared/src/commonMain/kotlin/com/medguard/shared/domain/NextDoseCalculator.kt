@file:OptIn(ExperimentalTime::class)

package com.medguard.shared.domain

import com.medguard.shared.data.StoredSchedule
import com.medguard.shared.extraction.Frequency
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.hours
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** First dose slot of the waking window, minutes from local midnight (08:00). */
private const val WINDOW_START_MINUTE = 8 * 60

/** Length of the waking window in minutes (08:00-22:00). */
private const val WINDOW_LENGTH_MINUTES = 14 * 60

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
 * [Frequency.TimesPerDay] uses wall-clock slots in [zone], evenly spaced in
 * the waking window 08:00-22:00 local: slot k (0-based) is at
 * `08:00 + (k * 14h) / (n - 1)` for n > 1, and n == 1 has a single 08:00
 * slot. Slots stay at the same local time across DST transitions. The
 * result is the earliest slot instant strictly after a threshold:
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
 * Earliest waking-window slot instant strictly after [threshold]. Only the
 * threshold's local date and the next one need checking: any earlier date's
 * slots all end by 22:00 local, before the threshold's date begins.
 */
private fun nextSlotAfter(threshold: Instant, count: Int, zone: TimeZone): Instant {
    val date = threshold.toLocalDateTime(zone).date
    return sequenceOf(date, date.plus(1, DateTimeUnit.DAY))
        .flatMap { day -> slotMinutes(count).map { minute -> day.atTime(LocalTime(minute / 60, minute % 60)).toInstant(zone) } }
        .first { it > threshold }
}

/** Slot times as minutes from local midnight, ascending. */
private fun slotMinutes(count: Int): Sequence<Int> =
    if (count <= 1) sequenceOf(WINDOW_START_MINUTE)
    else (0 until count).asSequence()
        .map { k -> WINDOW_START_MINUTE + (k * WINDOW_LENGTH_MINUTES) / (count - 1) }
