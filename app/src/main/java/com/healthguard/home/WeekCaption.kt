package com.healthguard.home

import com.healthguard.activity.DoseDayStatus

/**
 * The line under the week circles. Days that never owed anything
 * ([DoseDayStatus.OUT_OF_TREATMENT]) and fully skipped days (a deliberate
 * choice, not a lapse) leave the "K of N" tally entirely. Today joins it
 * once decided: either its slots are all in the past, or it is already
 * behind (fewer takes than passed slots — off-track immediately). While
 * today is still on pace with slots pending, the caption says so; a full
 * 7-of-7 week earns the celebration variant.
 */
fun weekCaption(days: List<WeekDay>, todayPending: Boolean): String {
    val today = days.last()
    val untallied = setOf(DoseDayStatus.OUT_OF_TREATMENT, DoseDayStatus.SKIPPED)
    val todayOnPace = today.state == DoseDayStatus.MET || today.state in untallied
    val todayExcluded = todayPending && todayOnPace
    val counted = days.filter { day ->
        day.state !in untallied && !(todayExcluded && day === today)
    }
    val onTrack = counted.count { it.state == DoseDayStatus.MET }
    return when {
        counted.isEmpty() && !todayExcluded -> "No scheduled doses this week."
        // Every day in the window counted and every one on track — the full-week
        // celebration. Derives the total from the input, not a hard-coded 7, so
        // it can't silently drift from the domain's window length.
        counted.size == days.size && onTrack == days.size ->
            "$onTrack of ${counted.size} days on track — nice work."
        todayExcluded -> "$onTrack of ${counted.size} days on track. Today still to come."
        else -> "$onTrack of ${counted.size} days on track."
    }
}
