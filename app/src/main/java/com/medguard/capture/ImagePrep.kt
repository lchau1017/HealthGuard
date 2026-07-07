package com.medguard.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * Prepares captured photos for upload: downscale so the longest edge is at
 * most [maxEdgePx] (preserving aspect ratio), JPEG-compress at [quality],
 * and Base64-encode without line wraps — the format the extraction proxy
 * expects.
 */
fun Bitmap.toUploadJpegBase64(maxEdgePx: Int = 1568, quality: Int = 80): String {
    val scaled = downscaleToMaxEdge(maxEdgePx)
    val bytes = ByteArrayOutputStream().use { out ->
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
        out.toByteArray()
    }
    if (scaled !== this) scaled.recycle()
    return Base64.encodeToString(bytes, Base64.NO_WRAP)
}

private fun Bitmap.downscaleToMaxEdge(maxEdgePx: Int): Bitmap {
    val longest = maxOf(width, height)
    if (longest <= maxEdgePx) return this
    val scale = maxEdgePx.toFloat() / longest
    val targetWidth = (width * scale).toInt().coerceAtLeast(1)
    val targetHeight = (height * scale).toInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(this, targetWidth, targetHeight, true)
}

/**
 * Loads a bitmap from [uri] already downsampled near [maxEdgePx] so full-res
 * camera output never lands in memory. Returns null when the Uri cannot be
 * decoded. The result may still slightly exceed [maxEdgePx]; callers pass it
 * through [toUploadJpegBase64] which enforces the exact bound.
 */
fun loadDownsampledBitmap(context: Context, uri: Uri, maxEdgePx: Int = 1568): Bitmap? = try {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
            val longest = maxOf(info.size.width, info.size.height)
            if (longest > maxEdgePx) {
                val scale = maxEdgePx.toFloat() / longest
                decoder.setTargetSize(
                    (info.size.width * scale).toInt().coerceAtLeast(1),
                    (info.size.height * scale).toInt().coerceAtLeast(1),
                )
            }
        }
    } else {
        decodeWithSampleSize(context, uri, maxEdgePx)
    }
} catch (_: Exception) {
    null
}

private fun decodeWithSampleSize(context: Context, uri: Uri, maxEdgePx: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { stream ->
        BitmapFactory.decodeStream(stream, null, bounds)
    } ?: return null
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sampleSize = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sampleSize * 2) >= maxEdgePx) {
        sampleSize *= 2
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    return context.contentResolver.openInputStream(uri)?.use { stream ->
        BitmapFactory.decodeStream(stream, null, options)
    }
}
