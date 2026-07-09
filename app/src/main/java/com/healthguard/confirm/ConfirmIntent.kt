package com.healthguard.confirm

/** Every user action the confirm screen can raise, sent through [ConfirmViewModel.onIntent]. */
sealed interface ConfirmIntent {
    /** A label photo was picked/captured (JPEG bytes as base64). */
    data class ImagePicked(val base64: String) : ConfirmIntent

    /** Re-run extraction on the last picked image after an error. */
    data object Retry : ConfirmIntent

    /** The user edited a review field's text. */
    data class FieldEdited(val key: String, val value: String) : ConfirmIntent

    /** The user tapped "Looks right" on a flagged field. */
    data class FieldConfirmed(val key: String) : ConfirmIntent

    /** Persist the reviewed medication under the optional category [label]. */
    data class Accept(val label: String?) : ConfirmIntent

    /** Dismiss the flow and return to Idle. */
    data object Reset : ConfirmIntent
}
