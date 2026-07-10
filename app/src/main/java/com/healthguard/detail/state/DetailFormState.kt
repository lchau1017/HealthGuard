package com.healthguard.detail.state

/**
 * The slice of [DetailUiState] the editable "Details" form renders: the
 * seven form fields plus their validation flags and the Save gate.
 */
data class DetailFormState(
    val name: String,
    val dosage: String,
    val form: String,
    val label: String,
    /** Comma-separated active ingredients. */
    val ingredients: String,
    /** Human frequency text; blank = no schedule. */
    val frequencyText: String,
    val withFood: Boolean?,
    val nameError: Boolean,
    val frequencyError: Boolean,
    val canSave: Boolean,
)
