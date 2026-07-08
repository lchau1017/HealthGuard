@file:OptIn(ExperimentalTime::class)

package com.healthguard.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.healthguard.format.parseFrequency
import com.healthguard.format.toHumanText
import com.healthguard.home.isActive
import com.healthguard.shared.data.MedicationRepository
import com.healthguard.shared.data.MedicationWithSchedule
import com.healthguard.shared.domain.nextDose
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
    val finished: DetailFinished? = null,
) {
    val isActive: Boolean get() = item?.isActive == true
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

    private var fieldsSeeded = false

    init {
        viewModelScope.launch {
            repository.medications().collect { rows ->
                val item = rows.firstOrNull { it.medication.id == medicationId }
                    ?: return@collect // deleted (or bad id): keep last state
                val latest = repository.latestDose(item.schedule.id)
                val lastTaken = latest?.let { it.takenAt ?: it.plannedAt }
                _state.update { current ->
                    val tracked = current.copy(
                        item = item,
                        nextDoseAt = nextDose(item.schedule, lastTaken, clock(), zone),
                        lastTakenAt = lastTaken,
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

    fun onNameChange(value: String) = _state.update { it.copy(name = value) }
    fun onDosageChange(value: String) = _state.update { it.copy(dosage = value) }
    fun onFormChange(value: String) = _state.update { it.copy(form = value) }
    fun onLabelChange(value: String) = _state.update { it.copy(label = value) }
    fun onIngredientsChange(value: String) = _state.update { it.copy(ingredients = value) }
    fun onFrequencyTextChange(value: String) = _state.update { it.copy(frequencyText = value) }
    fun onWithFoodChange(value: Boolean?) = _state.update { it.copy(withFood = value) }

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
