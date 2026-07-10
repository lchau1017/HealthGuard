package com.healthguard.dose

import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * One shared take-now path for the home and detail screens: the same
 * double-dose safety window and the same undoable TAKEN log everywhere.
 */
val DOUBLE_DOSE_WINDOW = 30.minutes

/**
 * Minutes since [lastTaken] when recording now would repeat a dose inside
 * the safety window — the figure the confirmation dialog quotes. Null when
 * it would not (no previous take, or the window has passed, strictly:
 * `now - lastTaken >= DOUBLE_DOSE_WINDOW`) and the dose can be recorded
 * without asking.
 */
fun minutesSinceLastTake(lastTaken: Instant?, now: Instant): Long? =
    if (lastTaken != null && now - lastTaken < DOUBLE_DOSE_WINDOW) {
        (now - lastTaken).inWholeMinutes
    } else {
        null
    }
