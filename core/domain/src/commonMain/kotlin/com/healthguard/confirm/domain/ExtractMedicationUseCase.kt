package com.healthguard.confirm.domain

import com.healthguard.domain.extraction.ExtractionResult
import com.healthguard.domain.extraction.VisionExtractor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Reads structured medication data from a label photo. The extraction work
 * runs on [ioDispatcher]; the extractor itself never throws (see
 * [VisionExtractor]), so every outcome comes back as an [ExtractionResult].
 */
class ExtractMedicationUseCase(
    private val extractor: VisionExtractor,
    private val ioDispatcher: CoroutineDispatcher,
) {
    suspend operator fun invoke(imageBase64: String): ExtractionResult =
        withContext(ioDispatcher) { extractor.extract(imageBase64) }
}
