package com.healthguard.shared.extraction

/**
 * A single field extracted from a medication-label photo, wrapped with the
 * vision model's confidence. A field needs human review when the value is
 * missing or the confidence falls below [CONFIDENCE_THRESHOLD].
 */
data class ExtractedField<T>(val value: T?, val confidence: Double) {
    // Inverted comparison so a NaN confidence (which compares false either
    // way) fails safe onto the review side regardless of who built the field.
    val needsReview: Boolean get() = value == null || !(confidence >= CONFIDENCE_THRESHOLD)

    companion object {
        const val CONFIDENCE_THRESHOLD = 0.8
    }
}

/**
 * Dosing frequency. On the wire this arrives as either {"timesPerDay": N}
 * or {"everyHours": N} without a class discriminator, so it is decoded
 * manually by [ExtractionParser] rather than via polymorphic serialization.
 */
sealed interface Frequency {
    data class TimesPerDay(val count: Int) : Frequency
    data class EveryHours(val hours: Int) : Frequency
}

data class MedicationExtraction(
    val drugName: ExtractedField<String>,
    val activeIngredients: List<ExtractedField<String>>,
    val dosage: ExtractedField<String>,
    val form: ExtractedField<String>,
    val frequency: ExtractedField<Frequency>,
    val withFood: ExtractedField<Boolean>,
)

sealed interface ExtractionResult {
    data class Success(val extraction: MedicationExtraction) : ExtractionResult
    data class Malformed(val reason: String) : ExtractionResult

    /** The extraction service could not be reached or answered with an error. */
    data object Unavailable : ExtractionResult
}
