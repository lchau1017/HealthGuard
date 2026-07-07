package com.medguard.confirm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.medguard.shared.extraction.ExtractedField
import com.medguard.shared.extraction.ExtractionResult
import com.medguard.shared.extraction.Frequency
import com.medguard.shared.extraction.MedicationExtraction
import com.medguard.shared.extraction.VisionExtractor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One display row on the confirmation screen. */
data class ReviewField(
    val key: String,
    val label: String,
    val value: String,
    val confidence: Double,
    val needsReview: Boolean,
    val userConfirmed: Boolean = false,
)

sealed interface ConfirmUiState {
    data object Idle : ConfirmUiState
    data object Extracting : ConfirmUiState
    data class Review(val fields: List<ReviewField>) : ConfirmUiState
    data class Error(val message: String, val retriable: Boolean) : ConfirmUiState
}

/**
 * Drives the capture -> extract -> review flow. All extraction work runs on
 * [ioDispatcher]; the extractor itself never throws (see [VisionExtractor]).
 */
class ConfirmViewModel(
    private val extractor: VisionExtractor,
    private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val _state = MutableStateFlow<ConfirmUiState>(ConfirmUiState.Idle)
    val state: StateFlow<ConfirmUiState> = _state.asStateFlow()

    private var lastImageBase64: String? = null

    /** True when every field flagged for review has been confirmed or edited. */
    val canAccept: Boolean
        get() = (_state.value as? ConfirmUiState.Review)
            ?.fields
            ?.none { it.needsReview && !it.userConfirmed }
            ?: false

    fun onImagePicked(imageJpegBase64: String) {
        lastImageBase64 = imageJpegBase64
        extract(imageJpegBase64)
    }

    fun onRetry() {
        val image = lastImageBase64 ?: return
        extract(image)
    }

    fun onFieldEdited(key: String, newValue: String) {
        updateField(key) { field ->
            if (newValue.isBlank()) {
                // A blank value can never stand in for a reviewed one: typing
                // (or clearing to) whitespace must not unlock Accept.
                field.copy(value = newValue, userConfirmed = false, needsReview = true)
            } else {
                field.copy(value = newValue, userConfirmed = true, needsReview = false)
            }
        }
    }

    fun onFieldConfirmed(key: String) {
        updateField(key) { it.copy(userConfirmed = true) }
    }

    fun reset() {
        lastImageBase64 = null
        _state.value = ConfirmUiState.Idle
    }

    private fun extract(imageJpegBase64: String) {
        _state.value = ConfirmUiState.Extracting
        viewModelScope.launch {
            val result = withContext(ioDispatcher) { extractor.extract(imageJpegBase64) }
            _state.value = when (result) {
                is ExtractionResult.Success ->
                    ConfirmUiState.Review(result.extraction.toReviewFields())
                is ExtractionResult.Malformed ->
                    ConfirmUiState.Error(MESSAGE_MALFORMED, retriable = true)
                is ExtractionResult.Unavailable ->
                    ConfirmUiState.Error(MESSAGE_UNAVAILABLE, retriable = true)
            }
        }
    }

    private fun updateField(key: String, transform: (ReviewField) -> ReviewField) {
        _state.update { current ->
            if (current !is ConfirmUiState.Review) return@update current
            current.copy(
                fields = current.fields.map { if (it.key == key) transform(it) else it },
            )
        }
    }

    private fun MedicationExtraction.toReviewFields(): List<ReviewField> = buildList {
        add(drugName.toReviewField(KEY_DRUG_NAME, "Drug name") { it })
        add(dosage.toReviewField(KEY_DOSAGE, "Dosage") { it })
        add(form.toReviewField(KEY_FORM, "Form") { it })
        add(frequency.toReviewField(KEY_FREQUENCY, "Frequency") { it.render() })
        add(withFood.toReviewField(KEY_WITH_FOOD, "Take with food") { if (it) "Yes" else "No" })
        if (activeIngredients.isNotEmpty()) {
            add(
                ReviewField(
                    key = KEY_INGREDIENTS,
                    label = "Active ingredients",
                    value = activeIngredients.mapNotNull { it.value }.joinToString(", "),
                    confidence = activeIngredients.minOf { it.confidence },
                    needsReview = activeIngredients.any { it.needsReview },
                ),
            )
        }
    }

    private fun <T> ExtractedField<T>.toReviewField(
        key: String,
        label: String,
        render: (T) -> String,
    ) = ReviewField(
        key = key,
        label = label,
        value = value?.let(render) ?: "",
        confidence = confidence,
        needsReview = needsReview,
    )

    private fun Frequency.render(): String = when (this) {
        is Frequency.TimesPerDay -> if (count == 1) "once a day" else "$count times a day"
        is Frequency.EveryHours -> if (hours == 1) "every hour" else "every $hours hours"
    }

    companion object {
        const val KEY_DRUG_NAME = "drugName"
        const val KEY_DOSAGE = "dosage"
        const val KEY_FORM = "form"
        const val KEY_FREQUENCY = "frequency"
        const val KEY_WITH_FOOD = "withFood"
        const val KEY_INGREDIENTS = "ingredients"

        const val MESSAGE_MALFORMED = "Couldn't read the label — try another photo"
        const val MESSAGE_UNAVAILABLE = "Service unavailable — check connection"
    }
}
