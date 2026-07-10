package com.healthguard.domain.extraction

/**
 * Plausibility bounds for a dosing [Frequency]: more often than hourly, or
 * less often than monthly, is not a real dosing regimen a label would state.
 * The upper bounds also shield downstream date math from overflow on
 * hallucinated counts. Single source of truth for every layer that accepts
 * a frequency, whether parsed from vision-model JSON or from edited text.
 */
val PLAUSIBLE_TIMES_PER_DAY: IntRange = 1..24
val PLAUSIBLE_EVERY_HOURS: IntRange = 1..31 * 24

/** True when the frequency falls inside the plausible dosing bounds. */
fun Frequency.isPlausible(): Boolean = when (this) {
    is Frequency.TimesPerDay -> count in PLAUSIBLE_TIMES_PER_DAY
    is Frequency.EveryHours -> hours in PLAUSIBLE_EVERY_HOURS
}

private val timesPerDayPattern = Regex("""(\d+)\s*times?\s+(?:a|per)\s+day""")
private val everyHoursPattern = Regex("""every\s+(\d+)\s*hours?""")

/**
 * Parses human frequency text (the renderings produced by the UI layer's
 * `Frequency.toHumanText`, leniently matched) back into a typed [Frequency];
 * null when unrecognised, outside the plausible dosing bounds, or too large
 * to represent.
 */
fun parseFrequency(text: String): Frequency? {
    val normalized = text.trim().lowercase()
    return when {
        normalized == "once a day" || normalized == "once per day" -> Frequency.TimesPerDay(1)
        normalized == "every hour" -> Frequency.EveryHours(1)
        else -> timesPerDayPattern.matchEntire(normalized)
            ?.groupValues?.get(1)?.toIntOrNull()
            ?.let { Frequency.TimesPerDay(it) }
            ?.takeIf { it.isPlausible() }
            ?: everyHoursPattern.matchEntire(normalized)
                ?.groupValues?.get(1)?.toIntOrNull()
                ?.let { Frequency.EveryHours(it) }
                ?.takeIf { it.isPlausible() }
    }
}
