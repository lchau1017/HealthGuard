package com.healthguard.domain.extraction

/**
 * Extracts structured medication data from a photographed label.
 * Implementations never throw: every failure maps to an [ExtractionResult].
 */
interface VisionExtractor {
    suspend fun extract(imageJpegBase64: String): ExtractionResult
}
