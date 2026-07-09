@file:OptIn(ExperimentalTime::class)

package com.healthguard.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.healthguard.dose.isDoubleDose
import com.healthguard.home.domain.ActivateMedicationUseCase
import com.healthguard.home.domain.ComputeHomeStateUseCase
import com.healthguard.home.domain.DeleteMedicationUseCase
import com.healthguard.home.domain.HomeContent
import com.healthguard.home.domain.RecordDoseUseCase
import com.healthguard.home.domain.RemoveDemoDataUseCase
import com.healthguard.home.domain.SeedDemoDataUseCase
import com.healthguard.home.domain.StopMedicationUseCase
import com.healthguard.home.domain.UndoDoseUseCase
import com.healthguard.shared.domain.ObserveMedicationsUseCase
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone

/**
 * The home screen's MVI holder. It owns no business logic: every user
 * [HomeIntent] delegates to a Phase-3 use case, and the rendered [HomeUiState]
 * is folded from the use case's structured [HomeContent] plus this layer's
 * presentation formatters (row status, week caption, due banner).
 *
 * State recomputes on any of the same three triggers as before: the medication
 * list changing, the minute [ticker] (countdowns and slot rollovers depend on
 * wall time), and a manual [refresh] bumped after a dose write — dose logs
 * don't retrigger the medications query.
 */
class HomeViewModel(
    private val computeHomeState: ComputeHomeStateUseCase,
    private val recordDose: RecordDoseUseCase,
    private val undoDose: UndoDoseUseCase,
    private val activateMedication: ActivateMedicationUseCase,
    private val stopMedication: StopMedicationUseCase,
    private val deleteMedication: DeleteMedicationUseCase,
    private val observeMedications: ObserveMedicationsUseCase,
    private val seedDemoData: SeedDemoDataUseCase,
    private val removeDemoData: RemoveDemoDataUseCase,
    private val clock: () -> Instant,
    private val zone: TimeZone = TimeZone.currentSystemDefault(),
    ticker: Flow<Unit> = minuteTicker(),
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private val _effects = Channel<HomeEffect>(Channel.BUFFERED)
    val effects: Flow<HomeEffect> = _effects.receiveAsFlow()

    /** Bumped after dose writes so state recomputes without the medications query firing. */
    private val refresh = MutableStateFlow(0)

    init {
        combine(
            // Dose-log writes from ANY screen (detail take, undo, demo seed)
            // must recompute home state too; the medications query alone
            // doesn't observe the doseLog table, so the use case folds the
            // repository's data-change signal into the medication stream.
            observeMedications(),
            ticker,
            refresh,
        ) { rows, _, _ -> rows }
            .onEach { rows -> _state.update { computeHomeState(rows).toUiState(it, zone) } }
            // Eager for the ViewModel's lifetime (not stateIn/WhileSubscribed):
            // the single mutable state is reduced by hand, and the host always
            // collects `state` while composed, so there is no idle window to gate.
            .launchIn(viewModelScope)
    }

    /** The single MVI entry point: each branch delegates to a use case or a state edit. */
    fun onIntent(intent: HomeIntent) {
        when (intent) {
            is HomeIntent.TakeNow -> takeNow(intent.card)
            HomeIntent.ConfirmTakeAnyway -> confirmTakeAnyway()
            HomeIntent.DismissTakeConfirm -> _state.update { it.copy(takeConfirm = null) }
            is HomeIntent.UndoTake -> launchRefreshing { undoDose(intent.doseId) }
            is HomeIntent.Play -> launchRefreshing { activateMedication(intent.medicationId) }
            is HomeIntent.Stop -> launchRefreshing { stopMedication(intent.medicationId) }
            is HomeIntent.Delete -> launchRefreshing { deleteMedication(intent.medicationId) }
            HomeIntent.LoadDemoData -> launchRefreshing { seedDemoData() }
            HomeIntent.RemoveDemoData -> launchRefreshing { removeDemoData() }
        }
    }

    /**
     * Records the dose as taken now — unless the last take was within the
     * double-dose window, in which case a confirmation is raised into state
     * and nothing is logged until [confirmTakeAnyway].
     */
    private fun takeNow(card: DoseCard) {
        val now = clock()
        val lastTaken = card.lastTaken
        if (isDoubleDose(lastTaken, now)) {
            _state.update {
                it.copy(
                    takeConfirm = TakeConfirmation(card, (now - lastTaken!!).inWholeMinutes),
                )
            }
            return
        }
        record(card)
    }

    /** User accepted the double-dose warning: record it after all. */
    private fun confirmTakeAnyway() {
        val pending = _state.value.takeConfirm ?: return
        _state.update { it.copy(takeConfirm = null) }
        record(pending.card)
    }

    private fun record(card: DoseCard) {
        viewModelScope.launch {
            val take = recordDose(
                card.item.schedule.id,
                card.nextDoseAt,
                card.item.medication.drugName,
            )
            _effects.send(HomeEffect.ShowUndoSnackbar(take))
            refresh.update { it + 1 }
        }
    }

    private fun launchRefreshing(block: suspend () -> Unit) {
        viewModelScope.launch {
            block()
            refresh.update { it + 1 }
        }
    }
}

private const val TICK_MILLIS = 60_000L

/** Emits immediately, then every minute, while collected. */
private fun minuteTicker(): Flow<Unit> = flow {
    while (true) {
        emit(Unit)
        delay(TICK_MILLIS)
    }
}
