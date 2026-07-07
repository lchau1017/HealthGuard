@file:OptIn(ExperimentalTime::class)

package com.medguard.format

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Live countdown to the next dose, refreshed by the home screen's minute
 * ticker: "in 2h 05m", "in 45m", "due now" (within a minute either side),
 * "overdue by 20m", or "" when there is no next dose. Multi-day spans drop
 * the minutes ("in 2d 3h") — at that distance they are noise.
 */
@Suppress("UnusedParameter") // zone kept so callers pass one source of truth
fun countdownText(nextDoseAt: Instant?, now: Instant, zone: TimeZone): String {
    if (nextDoseAt == null) return ""
    val untilDose = nextDoseAt - now
    return when {
        untilDose >= 1.minutes -> "in ${untilDose.humanSpan()}"
        untilDose > (-1).minutes -> "due now"
        else -> "overdue by ${(-untilDose).humanSpan()}"
    }
}

/** The dose's wall-clock time in [zone], zero-padded 24h "14:00". */
fun doseTimeText(at: Instant, zone: TimeZone): String {
    val time = at.toLocalDateTime(zone).time
    return "${time.hour.pad2()}:${time.minute.pad2()}"
}

private fun Duration.humanSpan(): String {
    val days = inWholeDays
    val hours = inWholeHours
    return when {
        days > 0 -> "${days}d ${hours - days * 24}h"
        hours > 0 -> "${hours}h ${(inWholeMinutes - hours * 60).pad2()}m"
        else -> "${inWholeMinutes}m"
    }
}

private fun Number.pad2(): String = toString().padStart(2, '0')
