@file:OptIn(ExperimentalTime::class)

package com.healthguard.format

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
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

/**
 * Per-second countdown used by the detail page: same buckets as
 * [countdownText] but with seconds precision below a day ("in 1h 04m 32s").
 * Multi-day spans still drop to "in 2d 3h" — seconds at that distance are
 * noise.
 */
fun countdownTextSeconds(nextDoseAt: Instant?, now: Instant, zone: TimeZone): String {
    if (nextDoseAt == null) return ""
    val untilDose = nextDoseAt - now
    return when {
        untilDose >= 1.seconds -> "in ${untilDose.humanSpanSeconds()}"
        untilDose > (-1).seconds -> "due now"
        else -> "overdue by ${(-untilDose).humanSpanSeconds()}"
    }
}

/**
 * When a dose was last taken, compact: today → "08:30", yesterday →
 * "yesterday 21:15", earlier → "12 Jun 09:00". Day boundaries follow [zone].
 */
fun lastTakenText(takenAt: Instant, now: Instant, zone: TimeZone): String {
    val takenLocal = takenAt.toLocalDateTime(zone)
    val today = now.toLocalDateTime(zone).date
    val time = "${takenLocal.time.hour.pad2()}:${takenLocal.time.minute.pad2()}"
    return when (takenLocal.date) {
        today -> time
        today.minus(1, DateTimeUnit.DAY) -> "yesterday $time"
        else -> "${takenLocal.date.day} ${takenLocal.date.month.shortName()} $time"
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

private fun Duration.humanSpanSeconds(): String {
    val days = inWholeDays
    val hours = inWholeHours
    val minutes = inWholeMinutes
    return when {
        days > 0 -> "${days}d ${hours - days * 24}h"
        hours > 0 ->
            "${hours}h ${(minutes - hours * 60).pad2()}m ${(inWholeSeconds - minutes * 60).pad2()}s"
        minutes > 0 -> "${minutes}m ${(inWholeSeconds - minutes * 60).pad2()}s"
        else -> "${inWholeSeconds}s"
    }
}

private fun kotlinx.datetime.Month.shortName(): String =
    name.lowercase().replaceFirstChar { it.uppercase() }.take(3)

private fun Number.pad2(): String = toString().padStart(2, '0')
