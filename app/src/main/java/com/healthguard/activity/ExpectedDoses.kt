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
 * How one local day answered its expected doses — the categorical
 * (ABC-taxonomy-flavoured) day states behind the per-medicine heat map and
 * the home week circles, so "a good day" means the same thing everywhere.
 * Deliberately categorical, not an intensity ramp: five different answers,
 * not five amounts. (The combined 16-week Activity grid stays count-based
 * raw activity across all medications — see ActivityViewModel.)
 */
enum class DoseDayStatus {
    /** Every non-skipped expected dose taken (taken >= expected − skipped > 0). */
    MET,

    /** Some but not all non-skipped expected doses taken. */
    PARTIAL,

    /** Doses were expected, none taken — missed or simply not recorded. */
    NOT_TAKEN,

    /** Every expected dose that day deliberately skipped: a choice, not a lapse. */
    SKIPPED,

    /** No expectation at all: before start, after stop, dormant, or as-needed. */
    OUT_OF_TREATMENT,
}

/**
 * Classifies one day. Takes are measured against the skip-adjusted
 * expectation (expected − skipped); with none taken, a fully skipped day is
 * [DoseDayStatus.SKIPPED] while any unanswered remainder is
 * [DoseDayStatus.NOT_TAKEN] — logged misses and days with no record at all
 * read the same, because from a schedule's point of view they are.
 */
fun doseDayStatus(expected: Int, taken: Int, skipped: Int): DoseDayStatus {
    val adjusted = expected - skipped
    return when {
        expected <= 0 -> DoseDayStatus.OUT_OF_TREATMENT
        taken >= adjusted && taken > 0 -> DoseDayStatus.MET
        taken > 0 -> DoseDayStatus.PARTIAL
        skipped >= expected -> DoseDayStatus.SKIPPED
        else -> DoseDayStatus.NOT_TAKEN
    }
}

/**
 * Buckets [expected] dose instants and [logs] into per-local-day
 * [doseDayStatus]. Days with no expectation at all are absent from the map —
 * hosts render absent days as out-of-treatment blanks. Takes bucket by their
 * takenAt day (falling back to plannedAt); skips by their plannedAt day.
 */
fun doseDayStatusByDay(
    expected: List<Instant>,
    logs: List<StoredDoseLog>,
    zone: TimeZone,
): Map<LocalDate, DoseDayStatus> {
    fun Instant.day(): LocalDate = toLocalDateTime(zone).date
    val expectedByDay = expected.groupingBy { it.day() }.eachCount()
    val takenByDay = logs.filter { it.status == DoseStatus.TAKEN }
        .groupingBy { (it.takenAt ?: it.plannedAt).day() }.eachCount()
    val skippedByDay = logs.filter { it.status == DoseStatus.SKIPPED }
        .groupingBy { it.plannedAt.day() }.eachCount()
    return expectedByDay.keys.associateWith { day ->
        doseDayStatus(
            expected = expectedByDay[day] ?: 0,
            taken = takenByDay[day] ?: 0,
            skipped = skippedByDay[day] ?: 0,
        )
    }
}
