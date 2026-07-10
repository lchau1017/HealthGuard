package com.healthguard.confirm.state

import com.healthguard.common.format.toHumanText
import com.healthguard.domain.extraction.ExtractedField
import com.healthguard.domain.extraction.ExtractionResult
import com.healthguard.domain.extraction.MedicationExtraction

/**
 * Folds an [ExtractionResult] into the [ConfirmUiState] the review screen
 * renders: a [ConfirmUiState.Review] built from the extraction's field rows,
 * or a retriable [ConfirmUiState.Error] when the label couldn't be read or
 * the service was unavailable.
 */
internal fun ExtractionResult.toUiState(): ConfirmUiState = when (this) {
    is ExtractionResult.Success ->
        ConfirmUiState.Review(
            fields = extraction.toReviewFields(),
            frequency = extraction.frequency.value,
            withFood = extraction.withFood.value,
        )
    is ExtractionResult.Malformed ->
        ConfirmUiState.Error(ConfirmMessages.MALFORMED, retriable = true)
    is ExtractionResult.Unavailable ->
        ConfirmUiState.Error(ConfirmMessages.UNAVAILABLE, retriable = true)
}

private fun MedicationExtraction.toReviewFields(): List<ReviewField> = buildList {
    add(drugName.toReviewField(ReviewFieldKey.DRUG_NAME, "Drug name") { it })
    add(dosage.toReviewField(ReviewFieldKey.DOSAGE, "Dosage") { it })
    add(form.toReviewField(ReviewFieldKey.FORM, "Form") { it })
    add(frequency.toReviewField(ReviewFieldKey.FREQUENCY, "Frequency") { it.toHumanText() })
    add(withFood.toReviewField(ReviewFieldKey.WITH_FOOD, "Take with food") { if (it) "Yes" else "No" })
    if (activeIngredients.isNotEmpty()) {
        add(
            ReviewField(
                key = ReviewFieldKey.INGREDIENTS,
                label = "Active ingredients",
                value = activeIngredients.mapNotNull { it.value }.joinToString(", "),
                confidence = activeIngredients.minOf { it.confidence },
                needsReview = activeIngredients.any { it.needsReview },
            ),
        )
    }
}

private fun <T> ExtractedField<T>.toReviewField(
    key: ReviewFieldKey,
    label: String,
    render: (T) -> String,
) = ReviewField(
    key = key,
    label = label,
    value = value?.let(render) ?: "",
    confidence = confidence,
    needsReview = needsReview,
)
