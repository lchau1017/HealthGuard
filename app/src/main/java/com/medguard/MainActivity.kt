package com.medguard

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.medguard.capture.loadDownsampledBitmap
import com.medguard.capture.toUploadJpegBase64
import com.medguard.confirm.ConfirmDialog
import com.medguard.confirm.ConfirmUiState
import com.medguard.confirm.ConfirmViewModel
import com.medguard.home.HomeScreen
import com.medguard.home.HomeViewModel
import com.medguard.ui.theme.MedGuardTheme
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.androidx.compose.koinViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MedGuardTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MedGuardApp(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

/**
 * Single-screen shell: the home medication list is always the backdrop; the
 * import/confirm flow floats above it as a dialog whose visibility derives
 * from [ConfirmViewModel]'s state (Idle = hidden), so it survives rotation
 * without extra plumbing.
 */
@Composable
private fun MedGuardApp(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Saveable so rotation (or process death while the camera app is in the
    // foreground) keeps the pending capture target. Uri has no built-in
    // saver; persist its string form instead.
    var pendingCameraUriString by rememberSaveable { mutableStateOf<String?>(null) }

    val confirmViewModel: ConfirmViewModel = koinViewModel()
    val homeViewModel: HomeViewModel = koinViewModel()
    val confirmState by confirmViewModel.state.collectAsState()
    val medications by homeViewModel.medications.collectAsState()

    fun processPickedImage(uri: Uri) {
        scope.launch {
            val base64 = withContext(Dispatchers.IO) {
                loadDownsampledBitmap(context, uri)?.toUploadJpegBase64()
            }
            if (base64 == null) {
                Toast.makeText(context, "Couldn't load that image", Toast.LENGTH_LONG).show()
            } else {
                confirmViewModel.onImagePicked(base64)
            }
        }
    }

    val takePictureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { success ->
        val uri = pendingCameraUriString?.let(Uri::parse)
        if (uri != null) {
            context.revokeUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            if (success) processPickedImage(uri)
        }
        pendingCameraUriString = null
    }

    val pickImageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) processPickedImage(uri)
    }

    HomeScreen(
        medications = medications,
        onPlay = homeViewModel::onPlay,
        onStop = homeViewModel::onStop,
        onDelete = homeViewModel::onDelete,
        onTakePhoto = {
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
        },
        onPickFromGallery = {
            pickImageLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        },
        modifier = modifier,
    )

    when (confirmState) {
        is ConfirmUiState.Idle -> Unit
        is ConfirmUiState.Saved -> LaunchedEffect(Unit) {
            Toast.makeText(context, "Added to your medications", Toast.LENGTH_SHORT).show()
            confirmViewModel.reset()
        }
        else -> ConfirmDialog(
            viewModel = confirmViewModel,
            onCancel = confirmViewModel::reset,
        )
    }
}
