package com.healthguard.activity

import kotlinx.datetime.LocalDate

/** Locale-simple 12-hour label for a 0-23 [hour]: "12 AM", "11 AM", "2 PM". */
fun hourLabel(hour: Int): String = when {
    hour == 0 -> "12 AM"
    hour < 12 -> "$hour AM"
    hour == 12 -> "12 PM"
    else -> "${hour - 12} PM"
}

/** Compact day text for captions and snackbars: "Wed 3 Jul". */
fun dayLabel(date: LocalDate): String {
    val dayName = date.dayOfWeek.name.lowercase()
        .replaceFirstChar { it.uppercase() }.take(3)
    val monthName = date.month.name.lowercase()
        .replaceFirstChar { it.uppercase() }.take(3)
    return "$dayName ${date.day} $monthName"
}
