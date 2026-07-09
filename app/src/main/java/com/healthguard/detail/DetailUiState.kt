@file:OptIn(ExperimentalTime::class)

package com.healthguard.detail

import com.healthguard.activity.AdherenceResult
import com.healthguard.activity.DayCount
import com.healthguard.activity.DayDetail
import com.healthguard.activity.DoseDayStatus
import com.healthguard.common.format.parseFrequency
import com.healthguard.home.MedicationPhase
import com.healthguard.home.isActive
import com.healthguard.home.phase
import com.healthguard.shared.data.MedicationWithSchedule
import com.healthguard.shared.extraction.Frequency
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.LocalDate

/**
 * Editable detail form plus the live persisted [item] — the single immutable
 * ViewState. Field values are seeded from the first repository emission and
 * never overwritten afterwards (a background re-emission must not clobber
 * typing); [item], [isActive] and [nextDoseAt] track the repository
 * continuously.
 */
data class DetailUiState(
    val item: MedicationWithSchedule? = null,
    val name: String = "",
    val dosage: String = "",
    val form: String = "",
    val label: String = "",
    /** Comma-separated active ingredients. */
    val ingredients: String = "",
    /** Human frequency text, parsed with [parseFrequency]; blank = no schedule. */
    val frequencyText: String = "",
    val withFood: Boolean? = null,
    val nextDoseAt: Instant? = null,
    val lastTakenAt: Instant? = null,
    /**
     * Latest dose logs newest first, interleaved with derived "Not
     * recorded" rows for recent expected slots nothing answered, capped
     * at 30 entries.
     */
    val history: List<HistoryEntry> = emptyList(),
    /** Non-null while the day-detail sheet for a tapped heat-map day shows. */
    val dayDetail: DayDetail? = null,
    /**
     * Per-day status against the schedule over the heat-map window; days
     * with no expectation at all are absent (rendered as out-of-treatment
     * blanks). Empty for as-needed medications.
     */
    val dayStatuses: Map<LocalDate, DoseDayStatus> = emptyMap(),
    /**
     * Per-day take counts over the window — the heat-map fallback for
     * as-needed medications, where no dose is ever owed.
     */
    val dayTakeCounts: List<DayCount> = emptyList(),
    /** Schedule-based adherence over the heat-map window. */
    val adherence: AdherenceResult = AdherenceResult(0, 0, 0),
    /** First day of the heat-map window (a Monday). */
    val historyFrom: LocalDate? = null,
    /** Minutes since the last take while the double-dose dialog should show. */
    val takeConfirm: Long? = null,
) {
    val isActive: Boolean get() = item?.isActive == true

    /** Treatment lifecycle phase; an unloaded item reads as not started. */
    val phase: MedicationPhase
        get() = item?.schedule?.phase ?: MedicationPhase.NOT_STARTED

    /** Interval dosing ("every N hours") is an as-needed ceiling, not a plan. */
    val isAsNeeded: Boolean get() = item?.schedule?.frequency is Frequency.EveryHours
    val nameError: Boolean get() = item != null && name.isBlank()
    val frequencyError: Boolean
        get() = frequencyText.isNotBlank() && parseFrequency(frequencyText) == null
    val canSave: Boolean get() = item != null && !nameError && !frequencyError
}
