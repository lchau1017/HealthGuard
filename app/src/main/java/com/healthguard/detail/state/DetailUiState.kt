@file:OptIn(ExperimentalTime::class)

package com.healthguard.detail.state

import com.healthguard.activity.AdherenceResult
import com.healthguard.activity.DayCount
import com.healthguard.domain.tracking.DayDetail
import com.healthguard.activity.DoseDayStatus
import com.healthguard.common.format.parseFrequency
import com.healthguard.home.MedicationPhase
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.LocalDate

/**
 * Editable detail form plus the tracked, render-ready facts — the single
 * immutable ViewState, all view data (pre-formatted strings, ids and value
 * types; no domain entities). Field values are seeded from the first
 * repository emission and never overwritten afterwards (a background
 * re-emission must not clobber typing); everything else tracks the
 * repository continuously through the mapper.
 */
data class DetailUiState(
    /** True once the first repository emission for this medication landed. */
    val isLoaded: Boolean = false,
    /** The PERSISTED drug name (header/dialogs) — not the edited form field. */
    val drugName: String? = null,
    /** Pre-formatted header subtitle: "500 mg · Capsule"; empty = none. */
    val subtitle: String = "",
    /** Persisted category label; null = uncategorised. */
    val categoryLabel: String? = null,
    /** Pre-formatted phase chip ("Not started" / "Stopped 3 Jul"); null = no chip. */
    val phaseChipText: String? = null,
    /** Treatment lifecycle phase; an unloaded item reads as not started. */
    val phase: MedicationPhase = MedicationPhase.NOT_STARTED,
    val isActive: Boolean = false,
    /** Interval dosing ("every N hours") is an as-needed ceiling, not a plan. */
    val isAsNeeded: Boolean = false,
    /** Pre-formatted schedule "Times" row; null = none. */
    val scheduleTimesText: String? = null,
    /** Pre-formatted schedule "Started" row (active schedules only); null = none. */
    val scheduleStartedText: String? = null,
    /**
     * The wall-clock instant the tracked facts were computed against (from
     * `DetailContent.now`). Minute-grained rendering (last-taken line, heat
     * map today) reads this one clock, so the labels never drift from the
     * derived facts; only the countdown text owns a live per-second ticker
     * (`LiveCountdown`).
     */
    val now: Instant = Instant.DISTANT_PAST,
    val name: String = "",
    val dosage: String = "",
    val form: String = "",
    val label: String = "",
    /** Comma-separated active ingredients. */
    val ingredients: String = "",
    /** Human frequency text, parsed with [parseFrequency]; blank = no schedule. */
    val frequencyText: String = "",
    /**
     * True when [frequencyText] is non-blank yet unparseable. Precomputed by
     * the view model on each frequency edit (seeded false — the persisted
     * frequency always round-trips) so reading state never re-runs the parser.
     */
    val frequencyError: Boolean = false,
    val withFood: Boolean? = null,
    val nextDoseAt: Instant? = null,
    val lastTakenAt: Instant? = null,
    /**
     * Latest dose logs newest first, interleaved with derived "Not
     * recorded" rows for recent expected slots nothing answered, capped
     * at 30 entries — pre-formatted view rows.
     */
    val history: List<HistoryRowData> = emptyList(),
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
    val nameError: Boolean get() = isLoaded && name.isBlank()
    val canSave: Boolean get() = isLoaded && !nameError && !frequencyError

    /** The form slice `DetailForm` renders. */
    val formState: DetailFormState
        get() = DetailFormState(
            name = name,
            dosage = dosage,
            form = form,
            label = label,
            ingredients = ingredients,
            frequencyText = frequencyText,
            withFood = withFood,
            nameError = nameError,
            frequencyError = frequencyError,
            canSave = canSave,
        )
}
