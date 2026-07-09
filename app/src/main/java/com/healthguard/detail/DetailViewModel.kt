@file:OptIn(ExperimentalTime::class)

package com.healthguard.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.healthguard.activity.AdherenceResult
import com.healthguard.activity.DayCount
import com.healthguard.activity.DayDetail
import com.healthguard.activity.DoseDayStatus
import com.healthguard.detail.domain.ComputeDetailStateUseCase
import com.healthguard.detail.domain.LoadDayDetailUseCase
import com.healthguard.detail.domain.SaveMedicationUseCase
import com.healthguard.detail.domain.ToggleTakingUseCase
import com.healthguard.dose.RecordedTake
import com.healthguard.dose.isDoubleDose
import com.healthguard.dose.recordTakenDose
import com.healthguard.format.parseFrequency
import com.healthguard.format.toHumanText
import com.healthguard.home.MedicationPhase
import com.healthguard.home.isActive
import com.healthguard.home.phase
import com.healthguard.shared.data.MedicationRepository
import com.healthguard.shared.data.MedicationWithSchedule
import com.healthguard.shared.extraction.Frequency
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

/** One-shot navigation results: the host pops back to Home on either. */
enum class DetailFinished { SAVED, DELETED }

/**
 * Editable detail form plus the live persisted [item]. Field values are
 * seeded from the first repository emission and never overwritten afterwards
 * (a background re-emission must not clobber typing); [item], [isActive] and
 * [nextDoseAt] track the repository continuously.
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
    val finished: DetailFinished? = null,
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

class DetailViewModel(
    private val repository: MedicationRepository,
    private val clock: () -> Instant,
    private val medicationId: String,
    private val zone: TimeZone = TimeZone.currentSystemDefault(),
) : ViewModel() {

    private val computeDetailState = ComputeDetailStateUseCase(repository, clock, zone)
    private val loadDayDetail = LoadDayDetailUseCase(repository, clock, zone)
    private val saveMedication = SaveMedicationUseCase(repository)
    private val toggleTakingUseCase = ToggleTakingUseCase(repository, clock)

    private val _state = MutableStateFlow(DetailUiState())
    val state: StateFlow<DetailUiState> = _state.asStateFlow()

    /** Bumped after dose writes: they don't retrigger the medications query. */
    private val refresh = MutableStateFlow(0)

    /** Minutes since the last take while the double-dose dialog should show. */
    private val _takeConfirm = MutableStateFlow<Long?>(null)
    val takeConfirm: StateFlow<Long?> = _takeConfirm.asStateFlow()

    /** Non-null while an undo snackbar for the last take should be showing. */
    private val _recentTake = MutableStateFlow<RecordedTake?>(null)
    val recentTake: StateFlow<RecordedTake?> = _recentTake.asStateFlow()

    private var fieldsSeeded = false

    init {
        viewModelScope.launch {
            combine(
                repository.medications(),
                refresh,
                // Writes from other screens (a take on Home, demo reseed)
                // must reach a retained detail too.
                repository.dataChanges.onStart { emit(Unit) },
            ) { rows, _, _ -> rows }.collect { rows ->
                val item = rows.firstOrNull { it.medication.id == medicationId }
                    ?: return@collect // deleted (or bad id): keep last state
                val content = computeDetailState(item)
                _state.update { current ->
                    val tracked = current.copy(
                        item = content.item,
                        nextDoseAt = content.nextDoseAt,
                        lastTakenAt = content.lastTakenAt,
                        history = content.history,
                        dayStatuses = content.dayStatuses,
                        dayTakeCounts = content.dayTakeCounts,
                        adherence = content.adherence,
                        historyFrom = content.historyFrom,
                    )
                    if (fieldsSeeded) {
                        tracked
                    } else {
                        fieldsSeeded = true
                        tracked.copy(
                            name = item.medication.drugName,
                            dosage = item.medication.dosage.orEmpty(),
                            form = item.medication.form.orEmpty(),
                            label = item.medication.label.orEmpty(),
                            ingredients = item.medication.activeIngredients.joinToString(", "),
                            frequencyText = item.schedule.frequency?.toHumanText().orEmpty(),
                            withFood = item.schedule.withFood,
                        )
                    }
                }
            }
        }
    }

    /**
     * Loads the tapped heat-map day's detail sheet, scoped to this
     * medication only — the per-med grid answers for one schedule. Slots
     * still inside the 90-minute answer window are not "not recorded" yet.
     */
    fun selectDay(date: LocalDate) {
        val item = _state.value.item ?: return
        viewModelScope.launch {
            _state.update { it.copy(dayDetail = loadDayDetail(item, date)) }
        }
    }

    /** The day-detail sheet was dismissed. */
    fun dismissDayDetail() = _state.update { it.copy(dayDetail = null) }

    fun onNameChange(value: String) = _state.update { it.copy(name = value) }
    fun onDosageChange(value: String) = _state.update { it.copy(dosage = value) }
    fun onFormChange(value: String) = _state.update { it.copy(form = value) }
    fun onLabelChange(value: String) = _state.update { it.copy(label = value) }
    fun onIngredientsChange(value: String) = _state.update { it.copy(ingredients = value) }
    fun onFrequencyTextChange(value: String) = _state.update { it.copy(frequencyText = value) }
    fun onWithFoodChange(value: Boolean?) = _state.update { it.copy(withFood = value) }

    /**
     * Records the dose as taken now — the same guarded path as the home
     * screen: a take within the double-dose window raises [takeConfirm]
     * instead and nothing is logged until [confirmTakeAnyway].
     */
    fun takeNow() {
        val now = clock()
        val lastTaken = _state.value.lastTakenAt
        if (isDoubleDose(lastTaken, now)) {
            _takeConfirm.value = (now - lastTaken!!).inWholeMinutes
            return
        }
        record()
    }

    /** User accepted the double-dose warning: record it after all. */
    fun confirmTakeAnyway() {
        if (_takeConfirm.value == null) return
        _takeConfirm.value = null
        record()
    }

    fun dismissTakeConfirm() {
        _takeConfirm.value = null
    }

    /** Undoes a take recorded via [takeNow]/[confirmTakeAnyway]. */
    fun undoTake(doseId: String) {
        viewModelScope.launch {
            repository.deleteDoseLog(doseId)
            _recentTake.value = null
            refresh.update { it + 1 }
        }
    }

    /** The undo snackbar timed out or was dismissed. */
    fun clearRecentTake() {
        _recentTake.value = null
    }

    /**
     * Re-queries dose data. The screen calls this on entry: dose logs alone
     * never retrigger the medications flow, so a view model retained across
     * navigation could otherwise keep showing a stale status and history.
     */
    fun refresh() {
        refresh.update { it + 1 }
    }

    private fun record() {
        val current = _state.value
        val item = current.item ?: return
        viewModelScope.launch {
            val doseId = recordTakenDose(
                repository = repository,
                scheduleId = item.schedule.id,
                plannedAt = current.nextDoseAt,
                now = clock(),
            )
            _recentTake.value = RecordedTake(doseId, item.medication.drugName)
            refresh.update { it + 1 }
        }
    }

    /**
     * Persists the edited fields. Never touches activation state — that goes
     * through [toggleTaking]. No-op while invalid ([DetailUiState.canSave]).
     */
    fun save() {
        val current = _state.value
        val item = current.item ?: return
        if (!current.canSave) return
        viewModelScope.launch {
            saveMedication(
                item.medication.copy(
                    drugName = current.name.trim(),
                    label = current.label.trim().takeUnless { it.isEmpty() },
                    activeIngredients = current.ingredients.split(",")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() },
                    dosage = current.dosage.trim().takeUnless { it.isEmpty() },
                    form = current.form.trim().takeUnless { it.isEmpty() },
                ),
                item.schedule.copy(
                    frequency = parseFrequency(current.frequencyText),
                    withFood = current.withFood,
                ),
            )
            _state.update { it.copy(finished = DetailFinished.SAVED) }
        }
    }

    /** Starts a dormant/stopped schedule, stops an active one. */
    fun toggleTaking() {
        val active = _state.value.isActive
        viewModelScope.launch { toggleTakingUseCase(medicationId, active) }
    }

    fun delete() {
        viewModelScope.launch {
            repository.delete(medicationId)
            _state.update { it.copy(finished = DetailFinished.DELETED) }
        }
    }
}
