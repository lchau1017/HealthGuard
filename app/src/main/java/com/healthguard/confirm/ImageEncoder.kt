package com.healthguard.confirm

import android.content.Context
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Prepares a picked/captured image for the extraction proxy: decode,
 * downsample, JPEG-compress, and base64-encode. Returns null when the Uri
 * cannot be decoded. Owned by the confirm feature so [ConfirmViewModel] can
 * run the decode in its own scope — a decode tied to the composition would be
 * silently cancelled by rotation, losing the capture.
 */
fun interface ImageEncoder {
    suspend fun encode(uri: String): String?
}

/**
 * Android implementation over the ScanImage helpers. Decoding runs on the
 * injected [ioDispatcher] (bound in the composition root, never hardcoded
 * here) so callers can stay on the main thread.
 */
class AndroidImageEncoder(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher,
) : ImageEncoder {
    override suspend fun encode(uri: String): String? = withContext(ioDispatcher) {
        loadDownsampledBitmap(context, uri.toUri())?.toUploadJpegBase64()
    }
}
