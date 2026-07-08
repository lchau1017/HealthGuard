@file:OptIn(ExperimentalTime::class)

package com.healthguard.home

import com.healthguard.shared.data.DoseStatus
import com.healthguard.shared.data.StoredDoseLog
import kotlin.time.ExperimentalTime
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime

/** How one of the last seven days went. */
enum class WeekDayState {
    /** At least one take and no misses: the filled circle. */
    ON_TRACK,

    /** Takes and misses on the same day: the mid-tone circle. */
    PARTIAL,

    /** No takes at all (possibly missed-only): the outlined circle. */
    EMPTY,
}

/**
 * One circle of the home "This week" card. [hasMiss] survives even for
 * [WeekDayState.EMPTY] days (missed-only) — the caption needs it to decide
 * whether an incomplete today already counts as off-track.
 */
data class WeekDay(val date: LocalDate, val state: WeekDayState, val hasMiss: Boolean)

private const val WEEK_DAYS = 7

/**
 * Buckets dose logs into the last seven days ending [today] (in [zone]).
 * TAKEN doses land on their takenAt day (falling back to plannedAt); MISSED
 * doses land on their plannedAt day. SKIPPED and PENDING never influence a
 * day — a deliberate skip is not a lapse. A day is on-track with at least
 * one take and no misses, partial with both, and empty otherwise.
 */
fun weekDayStates(logs: List<StoredDoseLog>, today: LocalDate, zone: TimeZone): List<WeekDay> {
    val takenDays = logs.filter { it.status == DoseStatus.TAKEN }
        .groupingBy { (it.takenAt ?: it.plannedAt).toLocalDateTime(zone).date }
        .eachCount()
    val missedDays = logs.filter { it.status == DoseStatus.MISSED }
        .groupingBy { it.plannedAt.toLocalDateTime(zone).date }
        .eachCount()
    return (WEEK_DAYS - 1 downTo 0).map { back ->
        val date = today.minus(back, DateTimeUnit.DAY)
        val taken = takenDays[date] ?: 0
        val missed = missedDays[date] ?: 0
        WeekDay(
            date = date,
            state = when {
                taken > 0 && missed == 0 -> WeekDayState.ON_TRACK
                taken > 0 -> WeekDayState.PARTIAL
                else -> WeekDayState.EMPTY
            },
            hasMiss = missed > 0,
        )
    }
}

/**
 * The line under the week circles. Today only joins the tally once it is
 * decided: [todayComplete] (no pending doses left) or already carrying a
 * miss (off-track either way). While today is still open the caption says
 * so; a full 7-of-7 week earns the celebration variant.
 */
fun weekCaption(days: List<WeekDay>, todayComplete: Boolean): String {
    val today = days.last()
    val todayCounts = todayComplete || today.hasMiss
    val counted = if (todayCounts) days else days.dropLast(1)
    val onTrack = counted.count { it.state == WeekDayState.ON_TRACK }
    return when {
        onTrack == WEEK_DAYS -> "7 of 7 days on track — nice work."
        todayCounts -> "$onTrack of ${counted.size} days on track."
        else -> "$onTrack of ${counted.size} days on track. Today still to come."
    }
}
