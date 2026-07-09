package com.healthguard.home

import com.healthguard.shared.data.MedicationWithSchedule

/** Everything the home screen renders — the single immutable ViewState. */
data class HomeUiState(
    /** Non-null only while at least one taking entry is due now or overdue. */
    val dueAlert: DueAlert? = null,
    /** Active schedules: overdue first, then soonest next dose, no-frequency last. */
    val taking: List<DoseCard> = emptyList(),
    /** Dormant or stopped medications, newest first. */
    val cabinet: List<MedicationWithSchedule> = emptyList(),
    /** How many taking entries are due now or overdue. */
    val dueCount: Int = 0,
    /** The last seven days ending today, oldest first (the week circles). */
    val weekDays: List<WeekDay> = emptyList(),
    /** Pre-formatted line under the week circles. */
    val weekCaption: String = "",
    /** Non-null while a double-dose confirmation dialog should be showing. */
    val takeConfirm: TakeConfirmation? = null,
)
