@file:OptIn(ExperimentalTime::class)

package com.healthguard.activity

import com.healthguard.shared.data.DoseStatus
import com.healthguard.shared.data.StoredDoseLog
import com.healthguard.shared.data.StoredSchedule
import com.healthguard.shared.domain.expectedDoseTimes
import kotlin.math.roundToInt
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Schedule-based adherence for one medication over a window: what was owed
 * ([expected], from [expectedDoseTimes]) against what was recorded. This
 * replaces log-only counting, where days the user never opened the app
 * silently vanished from the figure.
 */
data class AdherenceResult(
    val taken: Int,
    val expected: Int,
    val skipped: Int,
) {
    /**
     * taken / (expected − skipped), rounded, capped to 0..100. Skips leave
     * the denominator because they are deliberate decisions, not lapses;
     * they are surfaced as "· N skipped" text instead. MISSED and
     * unrecorded slots both stay in the denominator — they are expected
     * doses that did not happen. Null when nothing was expected (dormant
     * stretch or an interval/as-needed medication): no meaningful percent.
     * A fully skipped window floors the denominator at 1 instead of
     * dividing by zero.
     */
    val percent: Int?
        get() {
            if (expected == 0) return null
            val denominator = (expected - skipped).coerceAtLeast(1)
            return (taken * 100.0 / denominator).roundToInt().coerceIn(0, 100)
        }
}

/**
 * Computes [AdherenceResult] for [schedule] over `[from, to)`. Expected
 * doses come from the schedule (interval schedules expect nothing); taken
 * and skipped are counted from [logs], which callers pass already limited
 * to the same window.
 */
fun adherenceResult(
    schedule: StoredSchedule,
    logs: List<StoredDoseLog>,
    from: Instant,
    to: Instant,
    zone: TimeZone,
): AdherenceResult = AdherenceResult(
    taken = logs.count { it.status == DoseStatus.TAKEN },
    expected = expectedDoseTimes(schedule, from, to, zone).size,
    skipped = logs.count { it.status == DoseStatus.SKIPPED },
)

/**
 * How completely one local day's expected doses were answered. Shared by
 * the detail heat map and the home week circles so "a good day" means the
 * same thing everywhere. (The 16-week Activity grid deliberately stays
 * count-based raw activity across all medications — see ActivityViewModel.)
 */
enum class DayCompleteness {
    /** Every non-skipped expected dose taken (taken >= adjusted > 0). */
    FULL,

    /** Some but not all non-skipped expected doses taken. */
    PARTIAL,

    /** Doses were expected, none taken — missed or simply not recorded. */
    NONE,

    /** Nothing (left) expected: inactive day or every dose skipped. */
    EMPTY,
}

/**
 * Classifies one day. The day's expectation is adjusted for deliberate
 * skips (adjusted = expected − skipped): a fully skipped day is [DayCompleteness.EMPTY],
 * not a lapse. [DayCompleteness.NONE] covers both logged misses and days
 * with no record at all — from a schedule's point of view they are the same.
 */
fun dayCompleteness(expected: Int, taken: Int, skipped: Int): DayCompleteness {
    val adjusted = expected - skipped
    return when {
        adjusted <= 0 -> DayCompleteness.EMPTY
        taken >= adjusted -> DayCompleteness.FULL
        taken > 0 -> DayCompleteness.PARTIAL
        else -> DayCompleteness.NONE
    }
}

/**
 * Buckets [expected] dose instants and [logs] into per-local-day
 * [dayCompleteness]. [DayCompleteness.EMPTY] days are absent from the map —
 * hosts render absent days as blank. Takes bucket by their takenAt day
 * (falling back to plannedAt); skips by their plannedAt day.
 */
fun dayCompletenessByDay(
    expected: List<Instant>,
    logs: List<StoredDoseLog>,
    zone: TimeZone,
): Map<LocalDate, DayCompleteness> {
    fun Instant.day(): LocalDate = toLocalDateTime(zone).date
    val expectedByDay = expected.groupingBy { it.day() }.eachCount()
    val takenByDay = logs.filter { it.status == DoseStatus.TAKEN }
        .groupingBy { (it.takenAt ?: it.plannedAt).day() }.eachCount()
    val skippedByDay = logs.filter { it.status == DoseStatus.SKIPPED }
        .groupingBy { it.plannedAt.day() }.eachCount()
    return expectedByDay.keys.mapNotNull { day ->
        val completeness = dayCompleteness(
            expected = expectedByDay[day] ?: 0,
            taken = takenByDay[day] ?: 0,
            skipped = skippedByDay[day] ?: 0,
        )
        if (completeness == DayCompleteness.EMPTY) null else day to completeness
    }.toMap()
}
