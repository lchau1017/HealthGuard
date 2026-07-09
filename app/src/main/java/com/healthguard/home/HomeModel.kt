@file:OptIn(ExperimentalTime::class)

package com.healthguard.home

import com.healthguard.format.DoseRowStatus
import com.healthguard.shared.data.MedicationWithSchedule
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** One "Taking now" entry: the medication plus its dose timing. */
data class DoseCard(
    val item: MedicationWithSchedule,
    /** When the next dose is due; past = overdue; null = no frequency. */
    val nextDoseAt: Instant?,
    val lastTaken: Instant?,
    /** Due now or overdue (as of the state's computation time). */
    val isDue: Boolean = false,
    /** What the row's trailing status shows (Take button, "Taken ✓", next time). */
    val status: DoseRowStatus = DoseRowStatus.None,
)

/** The due-now banner: the most overdue card plus how many others are due. */
data class DueAlert(val card: DoseCard, val othersDueCount: Int)

/** A takeNow blocked by the double-dose window, awaiting user confirmation. */
data class TakeConfirmation(val card: DoseCard, val minutesAgo: Long)
