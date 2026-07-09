package com.healthguard.confirm

import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.healthguard.BuildConfig
import java.io.File

/**
 * Entry points for the two ways a label image reaches the confirm flow:
 * a fresh camera capture or a picked gallery photo. Obtain one with
 * [rememberScanImageLauncher].
 */
interface ScanImageLauncher {
    fun takePhoto()
    fun pickFromGallery()
}

/**
 * Owns the activity-result plumbing behind the scan sources: the camera and
 * photo-picker launchers, the FileProvider Uri for the capture target, and
 * the grant/revoke of that Uri around the camera round trip. [onImagePicked]
 * receives the Uri of whichever image the user ended up with.
 */
@Composable
fun rememberScanImageLauncher(onImagePicked: (Uri) -> Unit): ScanImageLauncher {
    val context = LocalContext.current
    // Saveable so rotation (or process death while the camera app is in the
    // foreground) keeps the pending capture target. Uri has no built-in
    // saver; persist its string form instead.
    var pendingCameraUriString by rememberSaveable { mutableStateOf<String?>(null) }

    val takePictureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { success ->
        val uri = pendingCameraUriString?.let(Uri::parse)
        if (uri != null) {
            context.revokeUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            if (success) onImagePicked(uri)
        }
        pendingCameraUriString = null
    }

    val pickImageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) onImagePicked(uri)
    }

    return object : ScanImageLauncher {
        override fun takePhoto() {
            val imagesDir = File(context.cacheDir, "images").apply { mkdirs() }
            // Constant name: only one capture is ever in flight, and reusing
            // the same file keeps abandoned captures from piling up in cache.
            val photoFile = File(imagesDir, "label_capture.jpg")
            val uri = FileProvider.getUriForFile(
                context,
                "${BuildConfig.APPLICATION_ID}.fileprovider",
                photoFile,
            )
            // Modern Android requires an explicit URI grant for the capture
            // intent — the FileProvider Uri is not implicitly readable or
            // writable by the camera app. Give every resolvable camera app a
            // read/write grant (revoked again in the launcher callback).
            // Resolution relies on the IMAGE_CAPTURE <queries> entry in the
            // manifest under Android 11+ package-visibility filtering.
            val captureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            resolveCameraApps(captureIntent).forEach { info ->
                context.grantUriPermission(
                    info.activityInfo.packageName,
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            pendingCameraUriString = uri.toString()
            takePictureLauncher.launch(uri)
        }

        private fun resolveCameraApps(captureIntent: Intent): List<ResolveInfo> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.queryIntentActivities(
                    captureIntent,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()),
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.queryIntentActivities(
                    captureIntent,
                    PackageManager.MATCH_DEFAULT_ONLY,
                )
            }

        override fun pickFromGallery() {
            pickImageLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        }
    }
}
