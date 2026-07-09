package com.healthguard.detail

import kotlinx.datetime.LocalDate

/** Every user action the detail screen can raise, sent through [DetailViewModel.onIntent]. */
sealed interface DetailIntent {
    data class NameChanged(val value: String) : DetailIntent
    data class DosageChanged(val value: String) : DetailIntent
    data class FormChanged(val value: String) : DetailIntent
    data class LabelChanged(val value: String) : DetailIntent
    data class IngredientsChanged(val value: String) : DetailIntent
    data class FrequencyChanged(val value: String) : DetailIntent
    data class WithFoodChanged(val value: Boolean?) : DetailIntent

    data object TakeNow : DetailIntent
    data object ConfirmTakeAnyway : DetailIntent
    data object DismissTakeConfirm : DetailIntent
    data class UndoTake(val doseId: String) : DetailIntent

    data class SelectDay(val date: LocalDate) : DetailIntent
    data object DismissDayDetail : DetailIntent

    data object Save : DetailIntent
    data object ToggleTaking : DetailIntent
    data object Delete : DetailIntent
    data object Refresh : DetailIntent
}
