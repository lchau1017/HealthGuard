package com.medguard

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
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
import com.medguard.confirm.ConfirmScreen
import com.medguard.confirm.ConfirmViewModel
import com.medguard.home.HomeScreen
import com.medguard.ui.theme.MedGuardTheme
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.androidx.compose.koinViewModel

private enum class Screen { Home, Confirm }

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

@Composable
private fun MedGuardApp(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Saveable so rotation (or process death while the camera app is in the
    // foreground) keeps the current screen and the pending capture target.
    var screen by rememberSaveable { mutableStateOf(Screen.Home) }
    // Uri has no built-in saver; persist its string form instead.
    var pendingCameraUriString by rememberSaveable { mutableStateOf<String?>(null) }

    val viewModel: ConfirmViewModel = koinViewModel()

    fun goHome() {
        viewModel.reset()
        screen = Screen.Home
    }

    fun processPickedImage(uri: Uri) {
        screen = Screen.Confirm
        scope.launch {
            val base64 = withContext(Dispatchers.IO) {
                loadDownsampledBitmap(context, uri)?.toUploadJpegBase64()
            }
            if (base64 == null) {
                Toast.makeText(context, "Couldn't load that image", Toast.LENGTH_LONG).show()
                goHome()
            } else {
                viewModel.onImagePicked(base64)
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

    when (screen) {
        Screen.Home -> HomeScreen(
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
        Screen.Confirm -> {
            BackHandler { goHome() }
            ConfirmScreen(
                viewModel = viewModel,
                onAccept = {
                    Toast.makeText(
                        context,
                        "Saved (storage coming soon)",
                        Toast.LENGTH_LONG,
                    ).show()
                    goHome()
                },
                onBack = ::goHome,
                modifier = modifier,
            )
        }
    }
}
