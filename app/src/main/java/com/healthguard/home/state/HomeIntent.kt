package com.healthguard.home.state

/** Every user action the home screen can raise, sent through [HomeViewModel.onIntent]. */
sealed interface HomeIntent {
    data class TakeNow(val card: DoseCard) : HomeIntent
    data object ConfirmTakeAnyway : HomeIntent
    data object DismissTakeConfirm : HomeIntent
    data class UndoTake(val doseId: String) : HomeIntent
    data class Play(val medicationId: String) : HomeIntent
    data object LoadDemoData : HomeIntent
    data object RemoveDemoData : HomeIntent
}
