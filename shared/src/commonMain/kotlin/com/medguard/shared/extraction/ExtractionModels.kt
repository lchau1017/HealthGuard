package com.medguard.shared.extraction

/**
 * A single field extracted from a medication-label photo, wrapped with the
 * vision model's confidence. A field needs human review when the value is
 * missing or the confidence falls below [CONFIDENCE_THRESHOLD].
 */
data class ExtractedField<T>(val value: T?, val confidence: Double) {
    val needsReview: Boolean get() = value == null || confidence < CONFIDENCE_THRESHOLD

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
    data class TimesPerDay(val timesPerDay: Int) : Frequency
    data class EveryHours(val everyHours: Int) : Frequency
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
}
