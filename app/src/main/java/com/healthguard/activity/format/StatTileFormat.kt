package com.healthguard.activity.format

import com.healthguard.activity.ActivityStats
import com.healthguard.common.format.hourLabel

/** One stat tile's display content. */
data class Tile(val value: String, val label: String, val description: String)

/** Builds the four tiles' text from the window's stats. Pure string formatting. */
fun statTiles(stats: ActivityStats): List<Tile> {
    val streakValue =
        if (stats.currentStreakDays == 1) "1 day" else "${stats.currentStreakDays} days"
    return listOf(
        Tile(
            value = "${stats.totalEvents}",
            label = "Doses taken",
            description = "${stats.totalEvents} doses recorded as taken in the selected window",
        ),
        Tile(
            value = streakValue,
            label = "Day streak",
            description = "Day streak: ${stats.currentStreakDays} consecutive days " +
                "with at least one dose",
        ),
        Tile(
            value = "${stats.activeDays}",
            label = "Active days",
            description = "Active days: ${stats.activeDays} days with at least one dose " +
                "in the selected window",
        ),
        Tile(
            value = stats.peakHour?.let(::hourLabel) ?: "—",
            label = "Usual dose time",
            description = stats.peakHour
                ?.let { "Usual dose time: doses are most often taken around ${hourLabel(it)}" }
                ?: "Usual dose time: not enough doses yet",
        ),
    )
}
