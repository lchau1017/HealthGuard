package com.healthguard.domain.schedule

import com.healthguard.domain.model.StoredSchedule
import com.healthguard.domain.extraction.Frequency
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * The dose instants a schedule owed inside `[from, to)`, ascending. This is
 * the denominator source for schedule-based adherence: what should have
 * happened, independent of what was logged.
 *
 * Only active stretches count — the window is clipped to
 * `[max(from, startedAt), min(to, stoppedAt ?: to))`; a dormant schedule
 * (null startedAt) or a missing frequency expects nothing.
 *
 * [Frequency.TimesPerDay] expects the meal-aligned wall-clock anchors of
 * [doseSlots] on every local day of the clipped window (half-open, so slots
 * before a mid-day start are never owed and a slot at exactly `to` is not
 * yet owed — callers pass `to = now`, so today's future slots never count).
 * Anchors are wall-clock: 09:00 stays 09:00 local across DST transitions.
 *
 * [Frequency.EveryHours] always returns empty, deliberately: "every N hours"
 * on a label is a maximum frequency — an as-needed ceiling, not a mandatory
 * around-the-clock plan. Counting phantom doses through the night would
 * wrongly punish users, so interval medications are excluded from
 * expected-dose adherence and surfaced as "As needed" instead of a percent.
 */
fun expectedDoseTimes(
    schedule: StoredSchedule,
    from: Instant,
    to: Instant,
    zone: TimeZone,
): List<Instant> {
    val startedAt = schedule.startedAt ?: return emptyList()
    val frequency = schedule.frequency as? Frequency.TimesPerDay ?: return emptyList()
    val windowFrom = maxOf(from, startedAt)
    val windowTo = schedule.stoppedAt?.let { minOf(to, it) } ?: to
    if (windowFrom >= windowTo) return emptyList()

    val slots = doseSlots(frequency)
    val firstDay = windowFrom.toLocalDateTime(zone).date
    val lastDay = windowTo.toLocalDateTime(zone).date
    return generateSequence(firstDay) { it.plus(1, DateTimeUnit.DAY) }
        .takeWhile { it <= lastDay }
        .flatMap { day -> slots.map { slot -> day.atTime(slot).toInstant(zone) } }
        .filter { it >= windowFrom && it < windowTo }
        .toList()
}
