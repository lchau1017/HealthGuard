@file:OptIn(ExperimentalTime::class)

package com.healthguard.confirm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.healthguard.format.parseFrequency
import com.healthguard.format.parseWithFood
import com.healthguard.format.toHumanText
import com.healthguard.shared.data.MedicationRepository
import com.healthguard.shared.data.StoredMedication
import com.healthguard.shared.data.StoredSchedule
import com.healthguard.shared.extraction.ExtractedField
import com.healthguard.shared.extraction.ExtractionResult
import com.healthguard.shared.extraction.Frequency
import com.healthguard.shared.extraction.MedicationExtraction
import com.healthguard.shared.extraction.VisionExtractor
import java.util.UUID
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
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

    /**
     * Editable review of the extraction. [frequency] and [withFood] carry the
     * typed values behind their display rows so Accept never has to re-parse
     * human-readable text the user did not touch.
     */
    data class Review(
        val fields: List<ReviewField>,
        val frequency: Frequency?,
        val withFood: Boolean?,
    ) : ConfirmUiState

    data class Error(val message: String, val retriable: Boolean) : ConfirmUiState

    /** One-shot: the medication was persisted; the UI should close and reset. */
    data object Saved : ConfirmUiState
}

/**
 * Drives the capture -> extract -> review -> save flow. All extraction work
 * runs on [ioDispatcher]; the extractor itself never throws (see
 * [VisionExtractor]). [clock] is injected so tests control timestamps.
 */
class ConfirmViewModel(
    private val extractor: VisionExtractor,
    private val repository: MedicationRepository,
    private val ioDispatcher: CoroutineDispatcher,
    private val clock: () -> Instant,
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
        // Keep the typed values in sync with what the user now sees: a stale
        // typed frequency must never silently override an edited display text.
        _state.update { current ->
            if (current !is ConfirmUiState.Review) return@update current
            when (key) {
                KEY_FREQUENCY -> current.copy(frequency = parseFrequency(newValue))
                KEY_WITH_FOOD -> current.copy(withFood = parseWithFood(newValue))
                else -> current
            }
        }
    }

    fun onFieldConfirmed(key: String) {
        updateField(key) { it.copy(userConfirmed = true) }
    }

    /**
     * Persists the reviewed medication with a dormant schedule (started later
     * from the home list). No-op unless every flagged field is confirmed.
     */
    fun onAccept(label: String?) {
        val review = _state.value as? ConfirmUiState.Review ?: return
        if (!canAccept || saving) return
        saving = true

        val byKey = review.fields.associateBy { it.key }
        fun value(key: String): String? =
            byKey[key]?.value?.trim()?.takeUnless { it.isEmpty() }

        val medicationId = UUID.randomUUID().toString()
        val medication = StoredMedication(
            id = medicationId,
            drugName = value(KEY_DRUG_NAME).orEmpty(),
            label = label?.trim()?.takeUnless { it.isEmpty() },
            activeIngredients = value(KEY_INGREDIENTS)
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty(),
            dosage = value(KEY_DOSAGE),
            form = value(KEY_FORM),
            extractionConfidence = review.fields.minOfOrNull { it.confidence } ?: 0.0,
            createdAt = clock(),
        )
        val schedule = StoredSchedule(
            id = UUID.randomUUID().toString(),
            medicationId = medicationId,
            frequency = review.frequency,
            withFood = review.withFood,
            startedAt = null,
            stoppedAt = null,
        )
        viewModelScope.launch {
            try {
                repository.insertMedication(medication, schedule)
                _state.value = ConfirmUiState.Saved
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                _state.value = ConfirmUiState.Error(MESSAGE_SAVE_FAILED, retriable = true)
            } finally {
                saving = false
            }
        }
    }

    fun reset() {
        lastImageBase64 = null
        saving = false
        _state.value = ConfirmUiState.Idle
    }

    private var saving = false

    private fun extract(imageJpegBase64: String) {
        _state.value = ConfirmUiState.Extracting
        viewModelScope.launch {
            val result = withContext(ioDispatcher) { extractor.extract(imageJpegBase64) }
            _state.value = when (result) {
                is ExtractionResult.Success ->
                    ConfirmUiState.Review(
                        fields = result.extraction.toReviewFields(),
                        frequency = result.extraction.frequency.value,
                        withFood = result.extraction.withFood.value,
                    )
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
        add(frequency.toReviewField(KEY_FREQUENCY, "Frequency") { it.toHumanText() })
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

    companion object {
        const val KEY_DRUG_NAME = "drugName"
        const val KEY_DOSAGE = "dosage"
        const val KEY_FORM = "form"
        const val KEY_FREQUENCY = "frequency"
        const val KEY_WITH_FOOD = "withFood"
        const val KEY_INGREDIENTS = "ingredients"

        const val MESSAGE_MALFORMED = "Couldn't read the label — try another photo"
        const val MESSAGE_UNAVAILABLE = "Service unavailable — check connection"
        const val MESSAGE_SAVE_FAILED = "Couldn't save — try again"
    }
}
