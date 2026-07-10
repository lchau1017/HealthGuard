@file:OptIn(ExperimentalTime::class)

package com.healthguard.home.format

import com.healthguard.common.format.shortDayName
import com.healthguard.common.format.timeLabel
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

/** Trailing status of a medication row on the home screen. */
sealed interface DoseRowStatus {
    /** Due now or overdue: the row shows the Take button. */
    data object Due : DoseRowStatus

    /** Nothing left today and the last dose was taken today: "Taken ✓". */
    data object TakenForToday : DoseRowStatus

    /** An upcoming dose: pre-formatted "Next in …"/"Next at …" text. */
    data class Next(val text: String) : DoseRowStatus

    /** No schedule and nothing taken today: no trailing status. */
    data object None : DoseRowStatus
}

/** Doses this close count down ("Next in 3h 20m"); further ones name the slot. */
private val COUNTDOWN_WINDOW = 6.hours

/**
 * Resolves what a home row's trailing status should say. Pure: [now] and
 * [zone] are injected. Branches, in order:
 * 1. due/overdue (or [isDue]) -> [DoseRowStatus.Due];
 * 2. no more doses today and a take happened today -> [DoseRowStatus.TakenForToday];
 * 3. next dose today within 6 hours -> "Next in 3h 20m";
 * 4. next dose later today -> "Next at 9:00 PM";
 * 5. next dose tomorrow -> "Next at 9:00 AM tomorrow", beyond ->
 *    "Next at 8:00 AM, Thu".
 */
fun doseRowStatus(
    nextDoseAt: Instant?,
    lastTaken: Instant?,
    now: Instant,
    zone: TimeZone,
    isDue: Boolean,
): DoseRowStatus {
    if (isDue || (nextDoseAt != null && nextDoseAt - now < 1.minutes)) return DoseRowStatus.Due

    val today = now.toLocalDateTime(zone).date
    val nextLocal = nextDoseAt?.toLocalDateTime(zone)
    val takenToday = lastTaken != null && lastTaken.toLocalDateTime(zone).date == today
    val noMoreToday = nextLocal == null || nextLocal.date > today

    if (noMoreToday && takenToday) return DoseRowStatus.TakenForToday
    if (nextLocal == null) return DoseRowStatus.None

    val text = when {
        nextLocal.date == today && nextDoseAt - now <= COUNTDOWN_WINDOW ->
            "Next in ${(nextDoseAt - now).shortSpan()}"
        nextLocal.date == today -> "Next at ${timeLabel(nextLocal.time)}"
        nextLocal.date == today.plus(1, DateTimeUnit.DAY) ->
            "Next at ${timeLabel(nextLocal.time)} tomorrow"
        else -> "Next at ${timeLabel(nextLocal.time)}, ${nextLocal.date.shortDayName()}"
    }
    return DoseRowStatus.Next(text)
}

/**
 * The due card's action line: "Take by 9:00 AM", growing an "· overdue 12m"
 * suffix once the slot is more than a minute past.
 */
fun takeByText(nextDoseAt: Instant, now: Instant, zone: TimeZone): String {
    val slot = timeLabel(nextDoseAt.toLocalDateTime(zone).time)
    val late = now - nextDoseAt
    return if (late >= 1.minutes) "Take by $slot · overdue ${late.shortSpan()}" else "Take by $slot"
}

/** Compact span: "3h 20m" at an hour or more, else "45m". */
private fun Duration.shortSpan(): String {
    val hours = inWholeHours
    val minutes = inWholeMinutes - hours * 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}
