package com.healthguard.activity

/** Locale-simple 12-hour label for a 0-23 [hour]: "12 AM", "11 AM", "2 PM". */
fun hourLabel(hour: Int): String = when {
    hour == 0 -> "12 AM"
    hour < 12 -> "$hour AM"
    hour == 12 -> "12 PM"
    else -> "${hour - 12} PM"
}
