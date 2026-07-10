package com.healthguard.common.format

import com.healthguard.domain.extraction.Frequency

/**
 * Human-readable rendering of a dosing frequency, shared by the review dialog
 * and the home medication list. The domain's `parseFrequency` inverts it
 * (leniently) so an edited review value can be mapped back to a typed
 * frequency.
 */
fun Frequency.toHumanText(): String = when (this) {
    is Frequency.TimesPerDay -> if (count == 1) "once a day" else "$count times a day"
    is Frequency.EveryHours -> if (hours == 1) "every hour" else "every $hours hours"
}
