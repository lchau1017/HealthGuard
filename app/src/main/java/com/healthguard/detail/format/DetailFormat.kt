@file:OptIn(ExperimentalTime::class)

package com.healthguard.detail.format

import com.healthguard.common.format.shortDayName
import com.healthguard.common.format.shortName
import com.healthguard.common.format.timeLabel
import com.healthguard.shared.data.DoseStatus
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime

/** Compact full date for schedule metadata: "27 Jun 2026". */
fun mediumDateLabel(date: LocalDate): String =
    "${date.day} ${date.month.shortName()} ${date.year}"

/** Dose timestamp with a relative day: "Today, 8:02 AM" / "Mon 6 Jul, 8:00 AM". */
fun dayTimeLabel(at: Instant, now: Instant, zone: TimeZone): String {
    val local = at.toLocalDateTime(zone)
    val today = now.toLocalDateTime(zone).date
    val day = when (local.date) {
        today -> "Today"
        today.minus(1, DateTimeUnit.DAY) -> "Yesterday"
        else -> "${local.date.shortDayName()} ${local.date.day} ${local.date.month.shortName()}"
    }
    return "$day, ${timeLabel(local.time)}"
}

/** Status-card line: "Last taken today, 8:02 AM". */
fun lastTakenLabel(takenAt: Instant, now: Instant, zone: TimeZone): String {
    val label = dayTimeLabel(takenAt, now, zone)
    val decapitalized = when {
        label.startsWith("Today") || label.startsWith("Yesterday") ->
            label.replaceFirstChar { it.lowercase() }
        else -> label
    }
    return "Last taken $decapitalized"
}

/** Doses taken within this much of the plan count as "on time". */
private val ON_TIME_WINDOW = 10.minutes

/**
 * Secondary annotation of a history row: "Taken · on time",
 * "Taken · 4 min late" (or "… early", whole minutes), "Missed", "Skipped".
 */
fun doseAnnotation(status: DoseStatus, plannedAt: Instant, takenAt: Instant?): String =
    when (status) {
        DoseStatus.MISSED -> "Missed"
        DoseStatus.SKIPPED -> "Skipped"
        DoseStatus.PENDING -> "Pending"
        DoseStatus.TAKEN -> {
            val offset = takenAt?.minus(plannedAt)
            when {
                offset == null -> "Taken"
                offset.absoluteValue <= ON_TIME_WINDOW -> "Taken · on time"
                offset.isPositive() -> "Taken · ${offset.inWholeMinutes} min late"
                else -> "Taken · ${(-offset).inWholeMinutes} min early"
            }
        }
    }

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
