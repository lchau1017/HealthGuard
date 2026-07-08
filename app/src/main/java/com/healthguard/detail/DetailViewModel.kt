@file:OptIn(ExperimentalTime::class)

package com.healthguard.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.healthguard.activity.AdherenceResult
import com.healthguard.activity.DayCount
import com.healthguard.activity.DoseDayStatus
import com.healthguard.activity.doseDayStatusByDay
import com.healthguard.activity.dayCounts
import com.healthguard.activity.mondayOf
import com.healthguard.dose.RecordedTake
import com.healthguard.dose.isDoubleDose
import com.healthguard.dose.recordTakenDose
import com.healthguard.format.parseFrequency
import com.healthguard.format.toHumanText
import com.healthguard.home.MedicationPhase
import com.healthguard.home.isActive
import com.healthguard.home.phase
import com.healthguard.shared.data.DoseStatus
import com.healthguard.shared.data.MedicationRepository
import com.healthguard.shared.data.MedicationWithSchedule
import com.healthguard.shared.data.StoredDoseLog
import com.healthguard.shared.data.StoredSchedule
import com.healthguard.shared.domain.expectedDoseTimes
import com.healthguard.shared.domain.nextDose
import com.healthguard.shared.extraction.Frequency
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime

/** One-shot navigation results: the host pops back to Home on either. */
enum class DetailFinished { SAVED, DELETED }

/** History rows shown on the detail page. */
private const val HISTORY_LIMIT = 30

/** Heat-map columns on the detail page (current week included). */
private const val HEAT_MAP_WEEKS = 16

/** How far back derived "Not recorded" rows reach. */
private val GAP_LOOKBACK = 14.days

/**
 * A slot stays open this long before it can become a "Not recorded" row —
 * the same distance a log may sit from a slot and still answer it, so a
 * slot is never declared unrecorded while a matching take is still likely.
 */
private val SLOT_ANSWER_GRACE = 90.minutes

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
            combine(repository.medications(), refresh) { rows, _ -> rows }.collect { rows ->
                val item = rows.firstOrNull { it.medication.id == medicationId }
                    ?: return@collect // deleted (or bad id): keep last state
                val latest = repository.latestDose(item.schedule.id)
                val lastTaken = latest?.let { it.takenAt ?: it.plannedAt }
                val now = clock()
                val today = now.toLocalDateTime(zone).date
                val historyFrom = mondayOf(today).minus(HEAT_MAP_WEEKS - 1, DateTimeUnit.WEEK)
                val windowFrom = historyFrom.atStartOfDayIn(zone)
                // Range query is on plannedAt (exclusive upper bound, hence
                // the pad); statuses are then bucketed by their effective day.
                val windowLogs = repository.dosesInRange(
                    scheduleId = item.schedule.id,
                    from = windowFrom,
                    to = now + 1.minutes,
                )
                // What the schedule owed over the window; `to = now` so
                // today's future slots are never counted against the user.
                val expected = expectedDoseTimes(item.schedule, windowFrom, now, zone)
                _state.update { current ->
                    val tracked = current.copy(
                        item = item,
                        nextDoseAt = nextDose(item.schedule, lastTaken, now, zone),
                        lastTakenAt = lastTaken,
                        history = historyEntries(item.schedule, windowLogs, now),
                        dayStatuses = doseDayStatusByDay(expected, windowLogs, zone),
                        dayTakeCounts = dayCounts(
                            windowLogs.filter { it.status == DoseStatus.TAKEN }
                                .map { it.takenAt ?: it.plannedAt },
                            zone,
                        ),
                        adherence = AdherenceResult(
                            taken = windowLogs.count { it.status == DoseStatus.TAKEN },
                            expected = expected.size,
                            skipped = windowLogs.count { it.status == DoseStatus.SKIPPED },
                        ),
                        historyFrom = historyFrom,
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
     * The recent-list rows, newest first, capped at [HISTORY_LIMIT]: the
     * last 14 days' logs interleaved with derived "Not recorded" slots
     * (matched against the complete window logs, so a capped recent query
     * can never fake a gap), then older logs from the capped recent query.
     */
    private suspend fun historyEntries(
        schedule: StoredSchedule,
        windowLogs: List<StoredDoseLog>,
        now: Instant,
    ): List<HistoryEntry> {
        val cutoff = now - GAP_LOOKBACK
        val recentLogs = windowLogs.filter { (it.takenAt ?: it.plannedAt) >= cutoff }
        val gapSlots = expectedDoseTimes(schedule, cutoff, now - SLOT_ANSWER_GRACE, zone)
        val older = repository.recentDoses(schedule.id, HISTORY_LIMIT)
            .filter { (it.takenAt ?: it.plannedAt) < cutoff }
            .map { HistoryEntry.Logged(it) }
        return (historyWithGaps(recentLogs, gapSlots) + older).take(HISTORY_LIMIT)
    }

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
            repository.updateMedication(
                item.medication.copy(
                    drugName = current.name.trim(),
                    label = current.label.trim().takeUnless { it.isEmpty() },
                    activeIngredients = current.ingredients.split(",")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() },
                    dosage = current.dosage.trim().takeUnless { it.isEmpty() },
                    form = current.form.trim().takeUnless { it.isEmpty() },
                ),
            )
            repository.updateSchedule(
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
        viewModelScope.launch {
            if (active) {
                repository.stop(medicationId, clock())
            } else {
                repository.activate(medicationId, clock())
            }
        }
    }

    fun delete() {
        viewModelScope.launch {
            repository.delete(medicationId)
            _state.update { it.copy(finished = DetailFinished.DELETED) }
        }
    }
}
