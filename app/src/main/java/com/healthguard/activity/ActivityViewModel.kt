@file:OptIn(ExperimentalTime::class)

package com.healthguard.activity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.healthguard.activity.domain.ActivityContent
import com.healthguard.activity.domain.ComputeActivityStateUseCase
import com.healthguard.activity.domain.LoadActivityDayDetailUseCase
import com.healthguard.shared.domain.ObserveDataChangesUseCase
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.Job
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
 * State loads once at construction and once per [ActivityIntent.SetFilter]
 * change; the filter itself lives in the retained view-model state, so it
 * survives tab switches. Any write anywhere ([observeDataChanges]) re-queries
 * the current window, so a retained Activity tab never shows stale tiles or
 * grids — the host raises nothing on (re)entry.
 */
class ActivityViewModel(
    private val computeActivityState: ComputeActivityStateUseCase,
    private val loadActivityDayDetail: LoadActivityDayDetailUseCase,
    private val observeDataChanges: ObserveDataChangesUseCase,
    private val zone: TimeZone = TimeZone.currentSystemDefault(),
) : ViewModel() {

    private val _state = MutableStateFlow(ActivityUiState())
    val state: StateFlow<ActivityUiState> = _state.asStateFlow()

    /**
     * The single in-flight window load. [load] cancels it before launching:
     * without this, two rapid filter taps race and the last query to FINISH
     * wins the state, which need not be the last filter the user tapped.
     */
    private var loadJob: Job? = null

    init {
        reload()
        viewModelScope.launch {
            observeDataChanges().collect { reload() }
        }
    }

    /** The single MVI entry point: each branch delegates to a use case or a state edit. */
    fun onIntent(intent: ActivityIntent) {
        when (intent) {
            is ActivityIntent.SetFilter -> setFilter(intent.filter)
            is ActivityIntent.SelectDay -> selectDay(intent.date)
            ActivityIntent.DismissDayDetail -> _state.update { it.copy(dayDetail = null) }
        }
    }

    private fun setFilter(filter: ActivityFilter) {
        if (filter == _state.value.filter) return
        // Fold the chosen filter in eagerly (the chip selects immediately),
        // and so a background [reload] racing this load re-queries the user's
        // latest choice rather than the last window that finished loading.
        _state.update { it.copy(filter = filter) }
        load(filter)
    }

    /** Re-queries the current window. */
    private fun reload() = load(_state.value.filter)

    private fun load(filter: ActivityFilter) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val content = computeActivityState(filter)
            // Fold over the current state: the open day-detail sheet must
            // survive a background re-query, not get dismissed by it.
            _state.update { content.toUiState(it, zone) }
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

}
