package com.healthguard.confirm.state

import com.healthguard.domain.extraction.Frequency

/** One display row on the confirmation screen. */
data class ReviewField(
    val key: String,
    val label: String,
    val value: String,
    val confidence: Double,
    val needsReview: Boolean,
    val userConfirmed: Boolean = false,
)

/** Everything the confirm dialog renders — the single sealed ViewState. */
sealed interface ConfirmUiState {
    data object Idle : ConfirmUiState
    data object Extracting : ConfirmUiState

    /**
     * Editable review of the extraction. [frequency] and [withFood] carry the
     * typed values behind their display rows so Accept never has to re-parse
     * human-readable text the user did not touch. [label] is the optional
     * category the medication is saved under — business data, so it lives
     * here (like the detail form's label), not in composition state that
     * dies with the dialog; being inside the Review also means a failed
     * save's Retry restores it along with the fields.
     */
    data class Review(
        val fields: List<ReviewField>,
        val frequency: Frequency?,
        val withFood: Boolean?,
        val label: String = "",
    ) : ConfirmUiState {
        /** True when every field flagged for review has been confirmed or edited. */
        val canAccept: Boolean
            get() = fields.none { it.needsReview && !it.userConfirmed }
    }

    data class Error(val message: String, val retriable: Boolean) : ConfirmUiState
}
