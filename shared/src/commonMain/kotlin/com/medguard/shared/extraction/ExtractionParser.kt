package com.medguard.shared.extraction

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Trust boundary between vision-model output and the app. The backend proxy
 * returns JSON with per-field `{"value": ..., "confidence": 0-1}` wrappers,
 * but the model can misbehave, so this parser never throws: every failure
 * path yields [ExtractionResult.Malformed], and individually unusable
 * optional fields degrade to a null value flagged for human review rather
 * than discarding the whole scan.
 */
class ExtractionParser {

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(raw: String): ExtractionResult = runCatching {
        val root = json.parseToJsonElement(raw) as? JsonObject
            ?: return@runCatching ExtractionResult.Malformed("payload is not a JSON object")

        val drugName = root.field("drugName") { it.stringOrNull() }
        if (drugName.value == null) {
            return@runCatching ExtractionResult.Malformed("drugName missing or unreadable")
        }

        ExtractionResult.Success(
            MedicationExtraction(
                drugName = drugName,
                activeIngredients = (root["activeIngredients"] as? JsonArray)
                    .orEmpty()
                    .map { element -> (element as? JsonObject).toField { it.stringOrNull() } },
                dosage = root.field("dosage") { it.stringOrNull() },
                form = root.field("form") { it.stringOrNull() },
                frequency = root.field("frequency") { it.toFrequencyOrNull() },
                withFood = root.field("withFood") { (it as? JsonPrimitive)?.booleanOrNull },
            )
        )
    }.getOrElse { failure ->
        ExtractionResult.Malformed(failure.message ?: "unparseable payload")
    }

    private fun <T> JsonObject.field(name: String, readValue: (JsonElement) -> T?): ExtractedField<T> =
        (this[name] as? JsonObject).toField(readValue)

    /**
     * Reads a `{"value": ..., "confidence": ...}` wrapper. A missing or
     * malformed wrapper, value, or confidence degrades to null / 0.0 so the
     * field surfaces as needsReview instead of failing the parse.
     */
    private fun <T> JsonObject?.toField(readValue: (JsonElement) -> T?): ExtractedField<T> {
        val wrapper = this ?: return ExtractedField(null, 0.0)
        val value = wrapper["value"]?.let(readValue)
        val confidence = (wrapper["confidence"] as? JsonPrimitive)?.doubleOrNull
            // NaN slips through coerceIn and compares false against the
            // review threshold, so non-finite values are treated as 0.0.
            ?.takeIf { it.isFinite() }
            ?.coerceIn(0.0, 1.0)
            ?: 0.0
        return ExtractedField(value, confidence)
    }

    private fun JsonElement.stringOrNull(): String? =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    // The frequency union arrives without a class discriminator: either
    // {"timesPerDay": N} or {"everyHours": N}. Any other shape, or a
    // non-positive count, maps to a null value (needsReview) rather than
    // Malformed, so one bad field does not discard the whole scan.
    private fun JsonElement.toFrequencyOrNull(): Frequency? {
        val obj = this as? JsonObject ?: return null
        (obj["timesPerDay"] as? JsonPrimitive)?.intOrNull
            ?.takeIf { it > 0 }
            ?.let { return Frequency.TimesPerDay(it) }
        (obj["everyHours"] as? JsonPrimitive)?.intOrNull
            ?.takeIf { it > 0 }
            ?.let { return Frequency.EveryHours(it) }
        return null
    }
}
