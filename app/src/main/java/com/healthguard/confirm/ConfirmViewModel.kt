package com.healthguard.confirm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.healthguard.common.format.parseFrequency
import com.healthguard.confirm.domain.ExtractMedicationUseCase
import com.healthguard.confirm.domain.NewMedication
import com.healthguard.confirm.domain.SaveNewMedicationUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The confirm screen's MVI holder. It owns no business logic: extraction runs
 * through [extractMedication] and persistence through [saveNewMedication].
 * Every user [ConfirmIntent] delegates to a use case or a state edit; the
 * rendered [ConfirmUiState] folds the extraction result over this layer's
 * presentation mapping (field rows, frequency/with-food parsing).
 */
class ConfirmViewModel(
    private val extractMedication: ExtractMedicationUseCase,
    private val saveNewMedication: SaveNewMedicationUseCase,
    private val imageEncoder: ImageEncoder,
) : ViewModel() {

    private val _state = MutableStateFlow<ConfirmUiState>(ConfirmUiState.Idle)
    val state: StateFlow<ConfirmUiState> = _state.asStateFlow()

    private val _effects = Channel<ConfirmEffect>(Channel.BUFFERED)
    val effects: Flow<ConfirmEffect> = _effects.receiveAsFlow()

    private var lastImageBase64: String? = null
    private var saving = false

    /**
     * The review (and its accept label) as it stood when a save failed, so
     * Retry can restore it and attempt the save again. Re-extracting instead
     * would rebuild the review from scratch and throw away every edit and
     * confirmation the user made. Null unless the current Error came from a
     * failed save.
     */
    private var reviewAwaitingSaveRetry: ConfirmUiState.Review? = null
    private var lastAcceptLabel: String? = null

    /**
     * The single in-flight extraction/save job. [reset] cancels it: a
     * dismissed dialog must stay dismissed, not pop back open when a
     * long-running network call finally returns.
     */
    private var workJob: Job? = null

    /** The single MVI entry point: each branch delegates to a use case or a state edit. */
    fun onIntent(intent: ConfirmIntent) {
        when (intent) {
            is ConfirmIntent.ImagePicked -> encodeAndExtract(intent.uri)
            ConfirmIntent.Retry -> retry()
            is ConfirmIntent.FieldEdited -> fieldEdited(intent.key, intent.value)
            is ConfirmIntent.FieldConfirmed -> updateField(intent.key) { it.copy(userConfirmed = true) }
            is ConfirmIntent.Accept -> accept(intent.label)
            ConfirmIntent.Reset -> reset()
        }
    }

    /**
     * A save failure retries the save with the user's reviewed fields intact;
     * only extraction errors re-run extraction on the last image.
     */
    private fun retry() {
        val review = reviewAwaitingSaveRetry
        if (review != null) {
            reviewAwaitingSaveRetry = null
            _state.value = review
            accept(lastAcceptLabel)
        } else {
            lastImageBase64?.let { extract(it) }
        }
    }

    private fun fieldEdited(key: String, newValue: String) {
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

    /**
     * Persists the reviewed medication with a dormant schedule (started later
     * from the home list). No-op unless every flagged field is confirmed.
     */
    private fun accept(label: String?) {
        val review = _state.value as? ConfirmUiState.Review ?: return
        if (!review.canAccept || saving) return
        saving = true

        val byKey = review.fields.associateBy { it.key }
        fun value(key: String): String? =
            byKey[key]?.value?.trim()?.takeUnless { it.isEmpty() }

        val medication = NewMedication(
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
            frequency = review.frequency,
            withFood = review.withFood,
        )
        workJob?.cancel()
        workJob = viewModelScope.launch {
            try {
                saveNewMedication(medication)
                _effects.send(ConfirmEffect.Saved)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // Only surface the failure while the review is still showing;
                // a dialog dismissed mid-save must stay dismissed. Keep the
                // reviewed fields (with any mid-save edits) so Retry can
                // restore them instead of re-extracting.
                val current = _state.value as? ConfirmUiState.Review
                if (current != null) {
                    reviewAwaitingSaveRetry = current
                    lastAcceptLabel = label
                    _state.value = ConfirmUiState.Error(MESSAGE_SAVE_FAILED, retriable = true)
                }
            } finally {
                saving = false
            }
        }
    }

    private fun reset() {
        workJob?.cancel()
        workJob = null
        lastImageBase64 = null
        reviewAwaitingSaveRetry = null
        lastAcceptLabel = null
        saving = false
        _state.value = ConfirmUiState.Idle
    }

    /**
     * Decodes the picked image and feeds it into extraction, all inside the
     * view-model scope so a rotation mid-decode can't discard the capture.
     * Extracting shows immediately — the dialog is visible for the whole
     * decode+extract stretch. An undecodable image is a terminal error:
     * there is no image to retry against.
     */
    private fun encodeAndExtract(uri: String) {
        // A fresh image starts a fresh flow; no stale save retry may linger.
        reviewAwaitingSaveRetry = null
        lastAcceptLabel = null
        _state.value = ConfirmUiState.Extracting
        workJob?.cancel()
        workJob = viewModelScope.launch {
            val base64 = imageEncoder.encode(uri)
            if (base64 == null) {
                applyIfExtracting(ConfirmUiState.Error(MESSAGE_IMAGE_LOAD_FAILED, retriable = false))
            } else {
                lastImageBase64 = base64
                applyIfExtracting(extractMedication(base64).toUiState())
            }
        }
    }

    private fun extract(imageJpegBase64: String) {
        _state.value = ConfirmUiState.Extracting
        workJob?.cancel()
        workJob = viewModelScope.launch {
            applyIfExtracting(extractMedication(imageJpegBase64).toUiState())
        }
    }

    /**
     * Applies an extraction outcome only while the flow is still Extracting.
     * Cancellation in [reset] already stops in-flight work; this guards the
     * remaining race where the result was computed before the dismissal
     * landed — a dismissed dialog must never resurrect itself.
     */
    private fun applyIfExtracting(result: ConfirmUiState) {
        if (_state.value is ConfirmUiState.Extracting) _state.value = result
    }

    private fun updateField(key: String, transform: (ReviewField) -> ReviewField) {
        _state.update { current ->
            if (current !is ConfirmUiState.Review) return@update current
            current.copy(
                fields = current.fields.map { if (it.key == key) transform(it) else it },
            )
        }
    }

    companion object {
        const val KEY_DRUG_NAME = "drugName"
        const val KEY_DOSAGE = "dosage"
        const val KEY_FORM = "form"
        const val KEY_FREQUENCY = "frequency"
        const val KEY_WITH_FOOD = "withFood"
        const val KEY_INGREDIENTS = "ingredients"

        const val MESSAGE_IMAGE_LOAD_FAILED = "Couldn't load that image"
        const val MESSAGE_MALFORMED = "Couldn't read the label — try another photo"
        const val MESSAGE_UNAVAILABLE = "Service unavailable — check connection"
        const val MESSAGE_SAVE_FAILED = "Couldn't save — try again"
    }
}
