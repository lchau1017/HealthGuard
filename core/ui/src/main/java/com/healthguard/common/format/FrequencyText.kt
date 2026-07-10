package com.healthguard.common.format

import com.healthguard.domain.extraction.Frequency

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

// Plausibility bounds mirroring ExtractionParser's: more often than hourly
// or an interval beyond a month is never a real dosing instruction.
private val TIMES_PER_DAY_RANGE = 1..24
private val EVERY_HOURS_RANGE = 1..744

/**
 * Best-effort inverse of [Frequency.toHumanText]; null when unrecognised,
 * outside the plausible dosing ranges, or too large to represent.
 */
fun parseFrequency(text: String): Frequency? {
    val normalized = text.trim().lowercase()
    return when {
        normalized == "once a day" || normalized == "once per day" -> Frequency.TimesPerDay(1)
        normalized == "every hour" -> Frequency.EveryHours(1)
        else -> timesPerDayPattern.matchEntire(normalized)
            ?.groupValues?.get(1)?.toIntOrNull()
            ?.takeIf { it in TIMES_PER_DAY_RANGE }
            ?.let { Frequency.TimesPerDay(it) }
            ?: everyHoursPattern.matchEntire(normalized)
                ?.groupValues?.get(1)?.toIntOrNull()
                ?.takeIf { it in EVERY_HOURS_RANGE }
                ?.let { Frequency.EveryHours(it) }
    }
}
