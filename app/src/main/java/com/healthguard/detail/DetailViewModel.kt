@file:OptIn(ExperimentalTime::class)

package com.healthguard.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.healthguard.domain.extraction.parseFrequency
import com.healthguard.common.format.toHumanText
import com.healthguard.detail.domain.ComputeDetailStateUseCase
import com.healthguard.detail.domain.DetailContent
import com.healthguard.detail.domain.LoadDayDetailUseCase
import com.healthguard.detail.domain.SaveMedicationUseCase
import com.healthguard.detail.state.DetailEffect
import com.healthguard.detail.state.DetailFinished
import com.healthguard.detail.state.DetailIntent
import com.healthguard.detail.state.DetailUiState
import com.healthguard.detail.state.toTrackedState
import com.healthguard.dose.minutesSinceLastTake
import com.healthguard.home.domain.ActivateMedicationUseCase
import com.healthguard.home.domain.DeleteMedicationUseCase
import com.healthguard.home.domain.RecordDoseUseCase
import com.healthguard.home.domain.StopMedicationUseCase
import com.healthguard.home.domain.UndoDoseUseCase
import com.healthguard.domain.model.MedicationId
import com.healthguard.domain.model.MedicationWithSchedule
import com.healthguard.domain.usecase.ObserveMedicationsUseCase
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

/**
 * The detail screen's MVI holder. It owns no business logic: every user
 * [DetailIntent] delegates to a use case or a state edit, and the rendered
 * [DetailUiState] folds the use case's structured [DetailContent] over this
 * layer's editable form fields.
 *
 * The tracked facts recompute on the same triggers as before — the medications
 * list changing, a manual [refresh] bumped after a dose write, and any
 * repository data change — while the editable form is seeded once and never
 * clobbered by a background re-emission.
 */
class DetailViewModel(
    private val computeDetailState: ComputeDetailStateUseCase,
    private val loadDayDetail: LoadDayDetailUseCase,
    private val saveMedication: SaveMedicationUseCase,
    private val recordDose: RecordDoseUseCase,
    private val undoDose: UndoDoseUseCase,
    private val activateMedication: ActivateMedicationUseCase,
    private val stopMedication: StopMedicationUseCase,
    private val deleteMedication: DeleteMedicationUseCase,
    private val observeMedications: ObserveMedicationsUseCase,
    private val clock: () -> Instant,
    private val medicationId: MedicationId,
    private val zone: TimeZone = TimeZone.currentSystemDefault(),
) : ViewModel() {

    private val _state = MutableStateFlow(DetailUiState())
    val state: StateFlow<DetailUiState> = _state.asStateFlow()

    private val _effects = Channel<DetailEffect>(Channel.BUFFERED)
    val effects: Flow<DetailEffect> = _effects.receiveAsFlow()

    /** Bumped after dose writes: they don't retrigger the medications query. */
    private val refresh = MutableStateFlow(0)

    private var fieldsSeeded = false

    /**
     * The latest persisted row, refreshed on every emission — the working
     * domain reference [save]'s entity copies and [selectDay] act on. It
     * lives here, never in [DetailUiState]: the UI renders view data only.
     */
    private var latestItem: MedicationWithSchedule? = null

    init {
        combine(
            // Writes from other screens (a take on Home, demo reseed) must
            // reach a retained detail too; the use case folds the repository's
            // data-change signal into the medication stream.
            observeMedications(),
            refresh,
        ) { rows, _ -> rows }
            .onEach { rows ->
                val item = rows.firstOrNull { it.medication.id == medicationId }
                    ?: return@onEach // deleted (or bad id): keep last state
                latestItem = item
                _state.update { it.applyContent(computeDetailState(item)) }
            }
            .launchIn(viewModelScope)
    }

    /**
     * Folds the tracked [DetailContent] into the ViewState. The editable form
     * fields are seeded from the first emission and never overwritten
     * afterwards — a background re-emission must not clobber typing.
     */
    private fun DetailUiState.applyContent(c: DetailContent): DetailUiState {
        val tracked = c.toTrackedState(this, zone)
        return if (fieldsSeeded) {
            tracked
        } else {
            fieldsSeeded = true
            val medication = c.item.medication
            tracked.copy(
                name = medication.drugName,
                dosage = medication.dosage.orEmpty(),
                form = medication.form.orEmpty(),
                label = medication.label.orEmpty(),
                ingredients = medication.activeIngredients.joinToString(", "),
                frequencyText = c.item.schedule.frequency?.toHumanText().orEmpty(),
                withFood = c.item.schedule.withFood,
            )
        }
    }

    /** The single MVI entry point: each branch delegates to a use case or a state edit. */
    fun onIntent(intent: DetailIntent) {
        when (intent) {
            is DetailIntent.NameChanged -> _state.update { it.copy(name = intent.value) }
            is DetailIntent.DosageChanged -> _state.update { it.copy(dosage = intent.value) }
            is DetailIntent.FormChanged -> _state.update { it.copy(form = intent.value) }
            is DetailIntent.LabelChanged -> _state.update { it.copy(label = intent.value) }
            is DetailIntent.IngredientsChanged -> _state.update { it.copy(ingredients = intent.value) }
            is DetailIntent.FrequencyChanged -> _state.update {
                it.copy(
                    frequencyText = intent.value,
                    // Parse once per edit, not on every state read; blank
                    // means "no schedule", never an error.
                    frequencyError = intent.value.isNotBlank() &&
                        parseFrequency(intent.value) == null,
                )
            }
            is DetailIntent.WithFoodChanged -> _state.update { it.copy(withFood = intent.value) }
            DetailIntent.TakeNow -> takeNow()
            DetailIntent.ConfirmTakeAnyway -> confirmTakeAnyway()
            DetailIntent.DismissTakeConfirm -> _state.update { it.copy(takeConfirm = null) }
            is DetailIntent.UndoTake -> launchRefreshing { undoDose(intent.doseId) }
            is DetailIntent.SelectDay -> selectDay(intent.date)
            DetailIntent.DismissDayDetail -> _state.update { it.copy(dayDetail = null) }
            DetailIntent.Save -> save()
            DetailIntent.ToggleTaking -> toggleTaking()
            DetailIntent.Delete -> delete()
            DetailIntent.Refresh -> refresh.update { it + 1 }
        }
    }

    /**
     * Records the dose as taken now — the same guarded path as the home
     * screen: a take within the double-dose window raises the confirmation
     * into state instead and nothing is logged until [confirmTakeAnyway].
     */
    private fun takeNow() {
        minutesSinceLastTake(_state.value.lastTakenAt, clock())?.let { minutes ->
            _state.update { it.copy(takeConfirm = minutes) }
        } ?: record()
    }

    /** User accepted the double-dose warning: record it after all. */
    private fun confirmTakeAnyway() {
        if (_state.value.takeConfirm == null) return
        _state.update { it.copy(takeConfirm = null) }
        record()
    }

    private fun record() {
        val current = _state.value
        val item = latestItem ?: return
        viewModelScope.launch {
            val take = recordDose(
                item.schedule.id,
                current.nextDoseAt,
                item.medication.drugName,
            )
            _effects.send(DetailEffect.ShowUndoSnackbar(take))
            refresh.update { it + 1 }
        }
    }

    /**
     * Loads the tapped heat-map day's detail sheet, scoped to this
     * medication only — the per-med grid answers for one schedule.
     */
    private fun selectDay(date: LocalDate) {
        val item = latestItem ?: return
        viewModelScope.launch {
            _state.update { it.copy(dayDetail = loadDayDetail(item, date)) }
        }
    }

    /**
     * Persists the edited fields. Never touches activation state — that goes
     * through [toggleTaking]. No-op while invalid ([DetailUiState.canSave]).
     */
    private fun save() {
        val current = _state.value
        val item = latestItem ?: return
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
            _effects.send(DetailEffect.Finished(DetailFinished.SAVED))
        }
    }

    /** Starts a dormant/stopped schedule, stops an active one. */
    private fun toggleTaking() {
        val active = _state.value.isActive
        launchRefreshing {
            if (active) stopMedication(medicationId) else activateMedication(medicationId)
        }
    }

    private fun delete() {
        viewModelScope.launch {
            deleteMedication(medicationId)
            _effects.send(DetailEffect.Finished(DetailFinished.DELETED))
        }
    }

    private fun launchRefreshing(block: suspend () -> Unit) {
        viewModelScope.launch {
            block()
            refresh.update { it + 1 }
        }
    }
}
