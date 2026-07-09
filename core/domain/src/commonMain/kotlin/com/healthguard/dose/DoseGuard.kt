@file:OptIn(ExperimentalTime::class)

package com.healthguard.dose

import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * One shared take-now path for the home and detail screens: the same
 * double-dose safety window and the same undoable TAKEN log everywhere.
 */
val DOUBLE_DOSE_WINDOW = 30.minutes

/** True when recording now would repeat a dose within the safety window. */
fun isDoubleDose(lastTaken: Instant?, now: Instant): Boolean =
    lastTaken != null && now - lastTaken < DOUBLE_DOSE_WINDOW

/** A just-recorded take the UI can offer to undo. */
data class RecordedTake(val doseId: String, val drugName: String)
