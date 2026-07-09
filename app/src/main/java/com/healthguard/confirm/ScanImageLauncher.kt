package com.healthguard.confirm

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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
            val photoFile = File(imagesDir, "label_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(
                context,
                "${BuildConfig.APPLICATION_ID}.fileprovider",
                photoFile,
            )
            // Android 18+ stops granting the capture Uri implicitly; give
            // every resolvable camera app an explicit read/write grant
            // (revoked again in the launcher callback).
            val captureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            context.packageManager
                .queryIntentActivities(captureIntent, PackageManager.MATCH_DEFAULT_ONLY)
                .forEach { info ->
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

        override fun pickFromGallery() {
            pickImageLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        }
    }
}
