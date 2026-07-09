package com.healthguard.common.format

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

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

/** The home header's date line: "Tuesday, 8 July". */
fun todayLabel(date: LocalDate): String =
    "${date.dayOfWeek.titleCase()}, ${date.day} ${date.month.titleCase()}"

internal fun LocalDate.shortDayName(): String = dayOfWeek.titleCase().take(3)

internal fun Enum<*>.titleCase(): String =
    name.lowercase().replaceFirstChar { it.uppercase() }
