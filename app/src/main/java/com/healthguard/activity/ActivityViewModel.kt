@file:OptIn(ExperimentalTime::class)

package com.healthguard.activity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.healthguard.activity.domain.ActivityContent
import com.healthguard.activity.domain.ComputeActivityStateUseCase
import com.healthguard.activity.domain.LoadActivityDayDetailUseCase
import com.healthguard.activity.domain.MedicationAdherenceContent
import com.healthguard.home.stoppedLabel
import com.healthguard.shared.data.MedicationRepository
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

/**
 * The Activity dashboard's MVI holder. It owns no business logic: every user
 * [ActivityIntent] delegates to a use case or a state edit, and the rendered
 * [ActivityUiState] folds the use case's structured [ActivityContent] over this
 * layer's presentation formatter (the stopped-row label).
 *
 * State loads once per [ActivityIntent.SetFilter] change plus explicit
 * [ActivityIntent.Reload]s (the host raises one on (re)entry, catching takes
 * recorded elsewhere since the last load); the filter itself lives in the
 * retained view-model state, so it survives tab switches. Any write anywhere
 * ([MedicationRepository.dataChanges]) re-queries the current window, so a
 * retained Activity tab never shows stale tiles or grids.
 */
class ActivityViewModel(
    private val computeActivityState: ComputeActivityStateUseCase,
    private val loadActivityDayDetail: LoadActivityDayDetailUseCase,
    private val repository: MedicationRepository,
    private val zone: TimeZone = TimeZone.currentSystemDefault(),
) : ViewModel() {

    private val _state = MutableStateFlow(ActivityUiState())
    val state: StateFlow<ActivityUiState> = _state.asStateFlow()

    init {
        reload()
        viewModelScope.launch {
            repository.dataChanges.collect { reload() }
        }
    }

    /** The single MVI entry point: each branch delegates to a use case or a state edit. */
    fun onIntent(intent: ActivityIntent) {
        when (intent) {
            is ActivityIntent.SetFilter -> setFilter(intent.filter)
            is ActivityIntent.SelectDay -> selectDay(intent.date)
            ActivityIntent.DismissDayDetail -> _state.update { it.copy(dayDetail = null) }
            ActivityIntent.Reload -> reload()
        }
    }

    private fun setFilter(filter: ActivityFilter) {
        if (filter == _state.value.filter) return
        load(filter)
    }

    /** Re-queries the current window. */
    private fun reload() = load(_state.value.filter)

    private fun load(filter: ActivityFilter) {
        viewModelScope.launch {
            _state.value = computeActivityState(filter).toUiState()
        }
    }

    /**
     * Loads the tapped grid day's detail sheet: that day's logs grouped per
     * medicine, plus what in-treatment schedules expected of it.
     */
    private fun selectDay(date: LocalDate) {
        viewModelScope.launch {
            _state.update { it.copy(dayDetail = loadActivityDayDetail(date)) }
        }
    }

    /** Folds the tracked [ActivityContent] into the ViewState, applying the stopped-row label. */
    private fun ActivityContent.toUiState() = ActivityUiState(
        filter = filter,
        from = from,
        stats = stats,
        dayCounts = dayCounts,
        breakdown = breakdown.map { it.toUi(now) },
    )

    private fun MedicationAdherenceContent.toUi(now: Instant) = MedicationAdherence(
        name = name,
        phase = phase,
        asNeeded = asNeeded,
        percent = percent,
        taken = taken,
        skipped = skipped,
        meetsTarget = meetsTarget,
        // Same `now` the content was computed against — no formatting drift.
        stoppedText = stoppedAt?.let { stoppedLabel(it, now, zone) },
    )
}
