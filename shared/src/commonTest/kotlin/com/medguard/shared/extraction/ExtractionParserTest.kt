package com.medguard.shared.extraction

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExtractionParserTest {

    private val parser = ExtractionParser()

    private val validPayload = """
        {"drugName":{"value":"Cetirizine 10mg Tablets","confidence":0.96},
         "activeIngredients":[{"value":"Cetirizine hydrochloride","confidence":0.94}],
         "dosage":{"value":"10 mg","confidence":0.91},
         "form":{"value":"tablet","confidence":0.97},
         "frequency":{"value":{"timesPerDay":1},"confidence":0.88},
         "withFood":{"value":null,"confidence":0.2}}
    """.trimIndent()

    private fun parseSuccess(raw: String): MedicationExtraction {
        val result = parser.parse(raw)
        assertIs<ExtractionResult.Success>(result)
        return result.extraction
    }

    @Test
    fun `complete valid payload parses with correct field values`() {
        val extraction = parseSuccess(validPayload)

        assertEquals("Cetirizine 10mg Tablets", extraction.drugName.value)
        assertEquals(0.96, extraction.drugName.confidence)
        assertEquals(1, extraction.activeIngredients.size)
        assertEquals("Cetirizine hydrochloride", extraction.activeIngredients[0].value)
        assertEquals("10 mg", extraction.dosage.value)
        assertEquals("tablet", extraction.form.value)
        assertEquals(Frequency.TimesPerDay(1), extraction.frequency.value)
        assertEquals(0.88, extraction.frequency.confidence)
        assertNull(extraction.withFood.value)
        assertEquals(0.2, extraction.withFood.confidence)
    }

    @Test
    fun `garbage input returns Malformed without throwing`() {
        val result = parser.parse("not json at all {{{")
        assertIs<ExtractionResult.Malformed>(result)
    }

    @Test
    fun `empty string returns Malformed`() {
        val result = parser.parse("")
        assertIs<ExtractionResult.Malformed>(result)
    }

    @Test
    fun `missing drugName returns Malformed`() {
        val payload = """{"dosage":{"value":"10 mg","confidence":0.9}}"""
        val result = parser.parse(payload)
        assertIs<ExtractionResult.Malformed>(result)
    }

    @Test
    fun `drugName with null value returns Malformed`() {
        val payload = """
            {"drugName":{"value":null,"confidence":0.9},
             "activeIngredients":[],
             "dosage":{"value":"10 mg","confidence":0.9},
             "form":{"value":"tablet","confidence":0.9},
             "frequency":{"value":{"timesPerDay":2},"confidence":0.9},
             "withFood":{"value":true,"confidence":0.9}}
        """.trimIndent()
        val result = parser.parse(payload)
        assertIs<ExtractionResult.Malformed>(result)
    }

    @Test
    fun `confidence above one clamps to one and below zero clamps to zero`() {
        val payload = """
            {"drugName":{"value":"Ibuprofen","confidence":1.7},
             "activeIngredients":[],
             "dosage":{"value":"200 mg","confidence":-0.3},
             "form":{"value":"tablet","confidence":0.9},
             "frequency":{"value":{"timesPerDay":3},"confidence":0.9},
             "withFood":{"value":true,"confidence":0.9}}
        """.trimIndent()
        val extraction = parseSuccess(payload)

        assertEquals(1.0, extraction.drugName.confidence)
        assertEquals(0.0, extraction.dosage.confidence)
    }

    @Test
    fun `needsReview reflects confidence threshold and null values`() {
        assertTrue(ExtractedField("x", 0.79).needsReview)
        assertFalse(ExtractedField("x", 0.8).needsReview)
        assertFalse(ExtractedField("x", 0.95).needsReview)
        assertTrue(ExtractedField(null, 0.99).needsReview)
    }

    @Test
    fun `everyHours frequency wire shape maps to EveryHours`() {
        val payload = """
            {"drugName":{"value":"Ibuprofen","confidence":0.9},
             "activeIngredients":[],
             "dosage":{"value":"200 mg","confidence":0.9},
             "form":{"value":"tablet","confidence":0.9},
             "frequency":{"value":{"everyHours":6},"confidence":0.9},
             "withFood":{"value":true,"confidence":0.9}}
        """.trimIndent()
        val extraction = parseSuccess(payload)

        assertEquals(Frequency.EveryHours(6), extraction.frequency.value)
    }

    @Test
    fun `unknown extra keys are ignored`() {
        val payload = validPayload.dropLast(1) + ""","unexpectedExtra":{"value":42,"confidence":0.5}}"""
        val extraction = parseSuccess(payload)

        assertEquals("Cetirizine 10mg Tablets", extraction.drugName.value)
    }

    @Test
    fun `unknown frequency shape yields null frequency needing review`() {
        // Chosen behavior: an unrecognized frequency object degrades to a
        // null-value field with needsReview=true instead of Malformed, so one
        // bad field does not discard the whole scan.
        val payload = """
            {"drugName":{"value":"Ibuprofen","confidence":0.9},
             "activeIngredients":[],
             "dosage":{"value":"200 mg","confidence":0.9},
             "form":{"value":"tablet","confidence":0.9},
             "frequency":{"value":{"weekly":2},"confidence":0.9},
             "withFood":{"value":true,"confidence":0.9}}
        """.trimIndent()
        val extraction = parseSuccess(payload)

        assertNull(extraction.frequency.value)
        assertTrue(extraction.frequency.needsReview)
    }

    @Test
    fun `non-finite confidence sanitizes to zero and needs review`() {
        // "NaN"/"Infinity" string contents survive doubleOrNull as non-finite
        // doubles; NaN defeats both coerceIn and the needsReview threshold
        // comparison, so non-finite confidence must be forced to 0.0.
        val payload = """
            {"drugName":{"value":"Ibuprofen","confidence":"NaN"},
             "activeIngredients":[],
             "dosage":{"value":"200 mg","confidence":"Infinity"},
             "form":{"value":"tablet","confidence":"-Infinity"},
             "frequency":{"value":{"timesPerDay":3},"confidence":0.9},
             "withFood":{"value":true,"confidence":0.9}}
        """.trimIndent()
        val extraction = parseSuccess(payload)

        assertEquals(0.0, extraction.drugName.confidence)
        assertTrue(extraction.drugName.needsReview)
        assertEquals(0.0, extraction.dosage.confidence)
        assertTrue(extraction.dosage.needsReview)
        assertEquals(0.0, extraction.form.confidence)
        assertTrue(extraction.form.needsReview)
    }

    @Test
    fun `empty activeIngredients array parses`() {
        val payload = """
            {"drugName":{"value":"Ibuprofen","confidence":0.9},
             "activeIngredients":[],
             "dosage":{"value":"200 mg","confidence":0.9},
             "form":{"value":"tablet","confidence":0.9},
             "frequency":{"value":{"timesPerDay":3},"confidence":0.9},
             "withFood":{"value":false,"confidence":0.9}}
        """.trimIndent()
        val extraction = parseSuccess(payload)

        assertTrue(extraction.activeIngredients.isEmpty())
    }
}
