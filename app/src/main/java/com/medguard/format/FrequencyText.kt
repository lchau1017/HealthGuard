package com.medguard.format

import com.medguard.shared.extraction.Frequency

/**
 * Human-readable rendering of a dosing frequency, shared by the review dialog
 * and the home medication list. [parseFrequency] inverts it (leniently) so an
 * edited review value can be mapped back to a typed frequency.
 */
fun Frequency.toHumanText(): String = when (this) {
    is Frequency.TimesPerDay -> if (count == 1) "once a day" else "$count times a day"
    is Frequency.EveryHours -> if (hours == 1) "every hour" else "every $hours hours"
}

private val timesPerDayPattern = Regex("""(\d+)\s*times?\s+(?:a|per)\s+day""")
private val everyHoursPattern = Regex("""every\s+(\d+)\s*hours?""")

/** Best-effort inverse of [Frequency.toHumanText]; null when unrecognised. */
fun parseFrequency(text: String): Frequency? {
    val normalized = text.trim().lowercase()
    return when {
        normalized == "once a day" || normalized == "once per day" -> Frequency.TimesPerDay(1)
        normalized == "every hour" -> Frequency.EveryHours(1)
        else -> timesPerDayPattern.matchEntire(normalized)
            ?.let { Frequency.TimesPerDay(it.groupValues[1].toInt()) }
            ?: everyHoursPattern.matchEntire(normalized)
                ?.let { Frequency.EveryHours(it.groupValues[1].toInt()) }
    }
}

/** Maps a yes/no review answer back to a typed value; null when unclear. */
fun parseWithFood(text: String): Boolean? = when (text.trim().lowercase()) {
    "yes", "y", "true" -> true
    "no", "n", "false" -> false
    else -> null
}
