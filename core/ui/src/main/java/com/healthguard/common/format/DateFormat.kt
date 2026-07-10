package com.healthguard.common.format

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.Month

/** Locale-simple 12-hour clock text: "9:00 PM", "8:02 AM", "12:05 AM". */
fun timeLabel(time: LocalTime): String {
    val hour12 = when {
        time.hour == 0 -> 12
        time.hour > 12 -> time.hour - 12
        else -> time.hour
    }
    val amPm = if (time.hour < 12) "AM" else "PM"
    return "$hour12:${time.minute.toString().padStart(2, '0')} $amPm"
}

/**
 * Locale-simple 12-hour label for a 0-23 [hour]: "12 AM", "11 AM", "2 PM".
 * Deliberately minute-less — [timeLabel] renders "9:00 AM" — because it
 * names an hour-of-day bucket, not a clock reading.
 */
fun hourLabel(hour: Int): String = when {
    hour == 0 -> "12 AM"
    hour < 12 -> "$hour AM"
    hour == 12 -> "12 PM"
    else -> "${hour - 12} PM"
}

/** The home header's date line: "Tuesday, 8 July". */
fun todayLabel(date: LocalDate): String =
    "${date.dayOfWeek.titleCase()}, ${date.day} ${date.month.titleCase()}"

fun LocalDate.shortDayName(): String = dayOfWeek.titleCase().take(3)

/** Three-letter month name — "Jul" — shared by every short date label. */
fun Month.shortName(): String = titleCase().take(3)

internal fun Enum<*>.titleCase(): String =
    name.lowercase().replaceFirstChar { it.uppercase() }
