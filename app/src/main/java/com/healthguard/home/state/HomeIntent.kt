package com.healthguard.home.state

import com.healthguard.domain.model.DoseId
import com.healthguard.domain.model.MedicationId
import com.healthguard.domain.model.ScheduleId

/** Every user action the home screen can raise, sent through [HomeViewModel.onIntent]. */
sealed interface HomeIntent {
    data class TakeNow(val scheduleId: ScheduleId) : HomeIntent
    data object ConfirmTakeAnyway : HomeIntent
    data object DismissTakeConfirm : HomeIntent
    data class UndoTake(val doseId: DoseId) : HomeIntent
    data class Play(val medicationId: MedicationId) : HomeIntent
    data object LoadDemoData : HomeIntent
    data object RemoveDemoData : HomeIntent
}
