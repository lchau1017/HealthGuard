@file:OptIn(ExperimentalTime::class)

package com.healthguard.activity.state

import com.healthguard.activity.ActivityFilter
import com.healthguard.activity.ActivityStats
import com.healthguard.activity.DayCount
import com.healthguard.activity.DayDetail
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.LocalDate

private val EMPTY_STATS = ActivityStats(0, 0, 0, 0, null, null)

/**
 * The Activity dashboard's single immutable ViewState. Everything — stat tiles,
 * the record grid, and the per-medicine adherence — is computed for the
 * selected [filter]'s window by [ActivityViewModel]. [dayDetail] is the only
 * transient overlay: non-null while a tapped grid day's sheet shows.
 */
data class ActivityUiState(
    /**
     * The wall-clock instant the state was computed against (from
     * `ActivityContent.now`). Carried in the state so the screen derives
     * "today" from it instead of reading the clock — and so a date rollover
     * always produces an unequal state that survives `MutableStateFlow`'s
     * equal-value conflation.
     */
    val now: Instant = Instant.DISTANT_PAST,
    val filter: ActivityFilter = ActivityFilter.DAYS_30,
    /** First day of the selected window; the record grid starts here too. */
    val from: LocalDate? = null,
    val stats: ActivityStats = EMPTY_STATS,
    /** Per-day take counts over the selected window. */
    val dayCounts: List<DayCount> = emptyList(),
    /** Best adherence first; ties break alphabetically. */
    val breakdown: List<MedicationAdherence> = emptyList(),
    /** Non-null while the day-detail sheet for a tapped grid day shows. */
    val dayDetail: DayDetail? = null,
)
