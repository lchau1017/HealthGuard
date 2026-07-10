package com.healthguard.activity.state

import com.healthguard.activity.ActivityFilter
import kotlinx.datetime.LocalDate

/** Every user action the Activity dashboard can raise, sent through [ActivityViewModel.onIntent]. */
sealed interface ActivityIntent {
    /** Changed the window filter chip; ignored when it already matches. */
    data class SetFilter(val filter: ActivityFilter) : ActivityIntent

    /** Tapped a record-grid day to open its detail sheet. */
    data class SelectDay(val date: LocalDate) : ActivityIntent

    /** Dismissed the day-detail sheet. */
    data object DismissDayDetail : ActivityIntent
}
