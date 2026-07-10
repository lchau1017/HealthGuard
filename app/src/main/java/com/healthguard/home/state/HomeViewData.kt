@file:OptIn(ExperimentalTime::class)

package com.healthguard.home.state

import com.healthguard.domain.model.MedicationId
import com.healthguard.domain.model.ScheduleId
import com.healthguard.home.MedicationPhase
import com.healthguard.home.format.DoseRowStatus
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * One "Taking now" entry, as pure render-ready view data: pre-formatted
 * strings plus the ids the intents carry back. No domain entities.
 */
data class DoseCard(
    val medicationId: MedicationId,
    val scheduleId: ScheduleId,
    /** Pre-joined primary line: "Ibuprofen 200 mg". */
    val title: String,
    /** Bare drug name for accessibility labels, dialogs and the record path. */
    val drugName: String,
    /** Category label feeding [PillAvatar]/CategoryChip; null = uncategorised. */
    val categoryLabel: String?,
    /** Pre-capitalised form text ("Tablet"); null = none. */
    val formLabel: String?,
    /** When the next dose is due; past = overdue; null = no frequency. */
    val nextDoseAt: Instant?,
    val lastTaken: Instant?,
    /** Due now or overdue (as of the state's computation time). */
    val isDue: Boolean = false,
    /** What the row's trailing status shows (Take button, "Taken ✓", next time). */
    val status: DoseRowStatus = DoseRowStatus.None,
)

/** One "My cabinet" row: a dormant or stopped medication, as pure view data. */
data class CabinetRow(
    val medicationId: MedicationId,
    /** Pre-joined primary line: "Ibuprofen 200 mg". */
    val title: String,
    /** Bare drug name for accessibility labels. */
    val drugName: String,
    /** Category label feeding [PillAvatar]/CategoryChip; null = uncategorised. */
    val categoryLabel: String?,
    /** Pre-capitalised form text ("Tablet"); null = none. */
    val formLabel: String?,
    /** Pre-formatted phase chip ("Not started" / "Stopped 3 Jul"); null = no chip. */
    val phaseChipText: String?,
    /** Treatment lifecycle phase (chip emphasis, Start/Resume verb). */
    val phase: MedicationPhase,
)

/** The due-now banner: the most overdue card plus how many others are due. */
data class DueAlert(val card: DoseCard, val othersDueCount: Int)

/** A takeNow blocked by the double-dose window, awaiting user confirmation. */
data class TakeConfirmation(val card: DoseCard, val minutesAgo: Long)
