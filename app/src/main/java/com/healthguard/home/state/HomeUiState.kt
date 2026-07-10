@file:OptIn(ExperimentalTime::class)

package com.healthguard.home.state

import com.healthguard.home.WeekDay
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** Everything the home screen renders — the single immutable ViewState. */
data class HomeUiState(
    /**
     * The wall-clock instant the state was computed against (from
     * `HomeContent.now`). Carried in the state so the screen never reads the
     * clock itself, and so every minute tick produces an unequal state —
     * a `MutableStateFlow` drops equal re-emissions, which used to freeze
     * clock-derived text (the overdue countdown, "Stopped today" chips)
     * whenever nothing else changed.
     */
    val now: Instant = Instant.DISTANT_PAST,
    /** Non-null only while at least one taking entry is due now or overdue. */
    val dueAlert: DueAlert? = null,
    /** Active schedules: overdue first, then soonest next dose, no-frequency last. */
    val taking: List<DoseCard> = emptyList(),
    /** Dormant or stopped medications, newest first. */
    val cabinet: List<CabinetRow> = emptyList(),
    /** How many taking entries are due now or overdue. */
    val dueCount: Int = 0,
    /** The last seven days ending today, oldest first (the week circles). */
    val weekDays: List<WeekDay> = emptyList(),
    /** Pre-formatted line under the week circles. */
    val weekCaption: String = "",
    /** Non-null while a double-dose confirmation dialog should be showing. */
    val takeConfirm: TakeConfirmation? = null,
)
