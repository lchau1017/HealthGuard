package com.healthguard.activity

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus

/**
 * Time windows on the Activity dashboard. GitHub-style: the selected window
 * drives everything on the tab — the stat tiles, the record grid, and the
 * adherence breakdown all recompute for the same date range.
 */
enum class ActivityFilter { DAYS_7, DAYS_30, MONTHS_12 }

/**
 * First day of a window ending [today] (inclusive on both ends): 7 and 30
 * calendar days, or 12 calendar months — the natural ceiling for a
 * streak/history dashboard, and one bounded query.
 */
fun activityWindowStart(filter: ActivityFilter, today: LocalDate): LocalDate = when (filter) {
    ActivityFilter.DAYS_7 -> today.minus(6, DateTimeUnit.DAY)
    ActivityFilter.DAYS_30 -> today.minus(29, DateTimeUnit.DAY)
    ActivityFilter.MONTHS_12 -> today.minus(12, DateTimeUnit.MONTH)
}

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
