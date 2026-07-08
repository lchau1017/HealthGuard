@file:OptIn(ExperimentalTime::class)

package com.healthguard.detail

import com.healthguard.shared.data.DoseStatus
import com.healthguard.shared.data.StoredDoseLog
import kotlin.time.ExperimentalTime
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** Completeness of one day of a medication's history. */
enum class DayDoseStatus {
    /** Every logged dose that day was taken: the deepest tint. */
    ALL,

    /** Takes mixed with misses/skips: the mid tint. */
    SOME,

    /** Only misses/skips, nothing taken: the palest tint. */
    MISSED,
}

/**
 * Buckets a medication's dose logs into per-day completeness. The app never
 * materialises the full "how many doses were scheduled that day" plan, so
 * completeness is defined pragmatically from what was actually logged:
 * a day whose logs are all TAKEN is [DayDoseStatus.ALL], a mix of takes and
 * misses/skips is [DayDoseStatus.SOME], and misses/skips without a single
 * take is [DayDoseStatus.MISSED]. Days with no logs are absent from the map
 * (rendered empty). TAKEN doses land on their takenAt day (falling back to
 * plannedAt); everything else lands on its plannedAt day in [zone].
 */
fun doseDayStatuses(
    logs: List<StoredDoseLog>,
    zone: TimeZone,
): Map<LocalDate, DayDoseStatus> =
    logs.groupBy { log ->
        val effective = if (log.status == DoseStatus.TAKEN) log.takenAt ?: log.plannedAt else log.plannedAt
        effective.toLocalDateTime(zone).date
    }.mapValues { (_, dayLogs) ->
        val taken = dayLogs.count { it.status == DoseStatus.TAKEN }
        when {
            taken == dayLogs.size -> DayDoseStatus.ALL
            taken > 0 -> DayDoseStatus.SOME
            else -> DayDoseStatus.MISSED
        }
    }
