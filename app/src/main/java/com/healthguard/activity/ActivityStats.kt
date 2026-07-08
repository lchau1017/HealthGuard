@file:OptIn(ExperimentalTime::class)

package com.healthguard.activity

import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

/**
 * One tracked event: something the user did at some instant. Tracker-neutral —
 * medication code maps its TakenDose rows into these; future trackers
 * (running, food, sleep) map their own records the same way.
 */
data class ActivityEvent(val itemName: String, val at: Instant)

/** Aggregate history figures for a set of [ActivityEvent]s. */
data class ActivityStats(
    val totalEvents: Int,
    /** Distinct local days with at least one event. */
    val activeDays: Int,
    /**
     * Consecutive event days ending today or yesterday (a streak is not
     * broken just because today's dose hasn't happened yet); 0 otherwise.
     */
    val currentStreakDays: Int,
    val longestStreakDays: Int,
    /** Local hour (0-23) with the most events; ties pick the earliest hour. */
    val peakHour: Int?,
    /** The most-logged item; ties pick the alphabetically first name. */
    val topItem: TopItem?,
) {
    data class TopItem(val name: String, val count: Int)
}

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

/** Computes [ActivityStats]. Pure: [now] and [zone] are injected. */
fun activityStats(events: List<ActivityEvent>, now: Instant, zone: TimeZone): ActivityStats {
    val eventDays = events.mapTo(sortedSetOf()) { it.at.toLocalDateTime(zone).date }

    var longest = 0
    var run = 0
    var previous: LocalDate? = null
    for (day in eventDays) {
        run = if (previous?.plus(1, DateTimeUnit.DAY) == day) run + 1 else 1
        if (run > longest) longest = run
        previous = day
    }

    val today = now.toLocalDateTime(zone).date
    var current = 0
    var cursor = if (today in eventDays) today else today.minus(1, DateTimeUnit.DAY)
    while (cursor in eventDays) {
        current++
        cursor = cursor.minus(1, DateTimeUnit.DAY)
    }

    val peakHour = events
        .groupingBy { it.at.toLocalDateTime(zone).hour }
        .eachCount()
        .entries
        .minWithOrNull(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key })
        ?.key

    val topItem = events
        .groupingBy { it.itemName }
        .eachCount()
        .entries
        .minWithOrNull(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        ?.let { ActivityStats.TopItem(it.key, it.value) }

    return ActivityStats(
        totalEvents = events.size,
        activeDays = eventDays.size,
        currentStreakDays = current,
        longestStreakDays = longest,
        peakHour = peakHour,
        topItem = topItem,
    )
}
