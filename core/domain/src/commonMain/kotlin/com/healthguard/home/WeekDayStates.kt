@file:OptIn(ExperimentalTime::class)

package com.healthguard.home

import com.healthguard.activity.DAYS_PER_WEEK
import com.healthguard.activity.DoseDayStatus
import com.healthguard.activity.doseDayStatus
import com.healthguard.shared.data.DoseStatus
import com.healthguard.shared.data.StoredDoseLog
import com.healthguard.shared.data.StoredSchedule
import com.healthguard.shared.domain.expectedDoseTimes
import com.healthguard.shared.extraction.Frequency
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

/** One circle of the home "This week" card. */
data class WeekDay(val date: LocalDate, val state: DoseDayStatus)

/**
 * The last seven days ending today, each measured against what every
 * schedule with wall-clock slots (times-per-day) owed that day, combined:
 * expected doses come from [expectedDoseTimes] — so a day the user never
 * opened the app still counts — and takes/skips from [logs]. Interval
 * (as-needed) schedules neither owe doses nor answer scheduled ones; their
 * logs are ignored here. Today's future slots are not owed yet (`to = now`),
 * so a day on pace reads [DoseDayStatus.MET] all day long.
 */
fun weekDayStates(
    schedules: List<StoredSchedule>,
    logs: List<StoredDoseLog>,
    now: Instant,
    zone: TimeZone,
): List<WeekDay> {
    val today = now.toLocalDateTime(zone).date
    val weekFrom = today.minus(DAYS_PER_WEEK - 1, DateTimeUnit.DAY).atStartOfDayIn(zone)

    val expectedByDay = schedules
        .flatMap { expectedDoseTimes(it, weekFrom, now, zone) }
        .groupingBy { it.toLocalDateTime(zone).date }
        .eachCount()

    // Only logs of schedules that owe slot doses count toward answering them.
    val slotScheduleIds = schedules
        .filter { it.frequency is Frequency.TimesPerDay }
        .mapTo(mutableSetOf()) { it.id }
    val slotLogs = logs.filter { it.scheduleId in slotScheduleIds }
    val takenByDay = slotLogs.filter { it.status == DoseStatus.TAKEN }
        .groupingBy { (it.takenAt ?: it.plannedAt).toLocalDateTime(zone).date }
        .eachCount()
    val skippedByDay = slotLogs.filter { it.status == DoseStatus.SKIPPED }
        .groupingBy { it.plannedAt.toLocalDateTime(zone).date }
        .eachCount()

    return (DAYS_PER_WEEK - 1 downTo 0).map { back ->
        val date = today.minus(back, DateTimeUnit.DAY)
        WeekDay(
            date = date,
            state = doseDayStatus(
                expected = expectedByDay[date] ?: 0,
                taken = takenByDay[date] ?: 0,
                skipped = skippedByDay[date] ?: 0,
            ),
        )
    }
}

/** True while any schedule still has a dose slot later today. */
fun todayHasPendingSlots(
    schedules: List<StoredSchedule>,
    now: Instant,
    zone: TimeZone,
): Boolean {
    val endOfToday = now.toLocalDateTime(zone).date
        .plus(1, DateTimeUnit.DAY)
        .atStartOfDayIn(zone)
    return schedules.any { expectedDoseTimes(it, now, endOfToday, zone).isNotEmpty() }
}
