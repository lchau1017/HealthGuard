@file:OptIn(ExperimentalTime::class)

package com.healthguard.activity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.healthguard.shared.data.MedicationRepository
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime

/** Time windows on the Activity dashboard. */
enum class ActivityFilter { ALL, DAYS_30, DAYS_7 }

/** One "By medication" row: share of all takes in the current window. */
data class MedicationBreakdown(val name: String, val count: Int, val percent: Int)

private val EMPTY_STATS = ActivityStats(0, 0, 0, 0, null, null)

data class ActivityUiState(
    val filter: ActivityFilter = ActivityFilter.ALL,
    /** First day of the window (heat-map range start). */
    val from: LocalDate? = null,
    val stats: ActivityStats = EMPTY_STATS,
    val dayCounts: List<DayCount> = emptyList(),
    /** Most-taken first; count ties break alphabetically. */
    val breakdown: List<MedicationBreakdown> = emptyList(),
)

/** Days shown by the 7-day window, today included. */
private const val WEEK_WINDOW_DAYS = 7

/** Days shown by the 30-day window, today included. */
private const val MONTH_WINDOW_DAYS = 30

/**
 * "All" is capped at the last 12 months rather than "since the first take":
 * one bounded query, a bounded heat map, and a year is the natural ceiling
 * for a streak/history dashboard.
 */
private const val ALL_WINDOW_MONTHS = 12

/**
 * Backs the Activity dashboard. Data loads once per [filter] change plus
 * explicit [reload]s (the host calls it whenever the screen is (re)entered,
 * catching takes recorded elsewhere since the last load).
 */
class ActivityViewModel(
    private val repository: MedicationRepository,
    private val clock: () -> Instant,
    private val zone: TimeZone = TimeZone.currentSystemDefault(),
) : ViewModel() {

    private val _state = MutableStateFlow(ActivityUiState())
    val state: StateFlow<ActivityUiState> = _state.asStateFlow()

    init {
        reload()
    }

    fun setFilter(filter: ActivityFilter) {
        if (filter == _state.value.filter) return
        load(filter)
    }

    /** Re-queries the current window. */
    fun reload() = load(_state.value.filter)

    private fun load(filter: ActivityFilter) {
        viewModelScope.launch {
            val now = clock()
            val today = now.toLocalDateTime(zone).date
            val from = when (filter) {
                ActivityFilter.ALL -> today.minus(ALL_WINDOW_MONTHS, DateTimeUnit.MONTH)
                ActivityFilter.DAYS_30 -> today.minus(MONTH_WINDOW_DAYS - 1, DateTimeUnit.DAY)
                ActivityFilter.DAYS_7 -> today.minus(WEEK_WINDOW_DAYS - 1, DateTimeUnit.DAY)
            }
            // Exclusive upper bound: pad past `now` so a take recorded at
            // this exact instant still counts.
            val taken = repository.takenDosesInRange(
                from = from.atStartOfDayIn(zone),
                to = now + 1.minutes,
            )
            val byName = taken.groupingBy { it.drugName }.eachCount()
            _state.value = ActivityUiState(
                filter = filter,
                from = from,
                stats = activityStats(
                    taken.map { ActivityEvent(it.drugName, it.takenAt) },
                    now,
                    zone,
                ),
                dayCounts = dayCounts(taken.map { it.takenAt }, zone),
                breakdown = byName.entries
                    .sortedWith(
                        compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key },
                    )
                    .map { (name, count) ->
                        MedicationBreakdown(
                            name = name,
                            count = count,
                            percent = (count * 100.0 / taken.size).roundToInt(),
                        )
                    },
            )
        }
    }
}
