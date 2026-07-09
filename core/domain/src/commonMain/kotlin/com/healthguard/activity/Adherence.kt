@file:OptIn(ExperimentalTime::class)

package com.healthguard.activity

import com.healthguard.shared.data.DoseStatus
import com.healthguard.shared.data.StoredDoseLog
import com.healthguard.shared.data.StoredSchedule
import com.healthguard.shared.domain.expectedDoseTimes
import kotlin.math.roundToInt
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.TimeZone

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

    /**
     * Whether [percent] reaches the 80% threshold commonly used in clinical
     * adherence research (PDC ≥ 80%). Informational, never alarming — the
     * app surfaces it as a quiet caption, not an error. Null when there is
     * no percent to measure.
     */
    val meetsTarget: Boolean?
        get() = percent?.let { it >= TARGET_PERCENT }

    companion object {
        /** The conventional PDC adherence target. */
        const val TARGET_PERCENT = 80
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
