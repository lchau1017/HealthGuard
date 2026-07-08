@file:OptIn(ExperimentalTime::class)

package com.healthguard.format

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.TimeZone

/**
 * Per-second countdown used by the detail page's status card: "in 1h 04m 32s",
 * "due now" (within a second either side), "overdue by 20m 15s", or "" when
 * there is no next dose. Multi-day spans drop to "in 2d 3h" — seconds at that
 * distance are noise.
 */
@Suppress("UnusedParameter") // zone kept so callers pass one source of truth
fun countdownTextSeconds(nextDoseAt: Instant?, now: Instant, zone: TimeZone): String {
    if (nextDoseAt == null) return ""
    val untilDose = nextDoseAt - now
    return when {
        untilDose >= 1.seconds -> "in ${untilDose.humanSpanSeconds()}"
        untilDose > (-1).seconds -> "due now"
        else -> "overdue by ${(-untilDose).humanSpanSeconds()}"
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

private fun Number.pad2(): String = toString().padStart(2, '0')
