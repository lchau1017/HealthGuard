package com.healthguard.activity

/** The record section's heading for a window. */
fun windowHeading(filter: ActivityFilter): String = when (filter) {
    ActivityFilter.DAYS_7 -> "Last 7 days"
    ActivityFilter.DAYS_30 -> "Last 30 days"
    ActivityFilter.MONTHS_12 -> "Last 12 months"
}

/** The segmented filter chip's label for a window. */
fun windowChipLabel(filter: ActivityFilter): String = when (filter) {
    ActivityFilter.DAYS_7 -> "7 days"
    ActivityFilter.DAYS_30 -> "30 days"
    ActivityFilter.MONTHS_12 -> "12 months"
}
