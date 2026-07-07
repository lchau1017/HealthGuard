package com.medguard

import android.net.Uri
import android.os.Bundle
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.medguard.capture.loadDownsampledBitmap
import com.medguard.capture.toUploadJpegBase64
import com.medguard.confirm.ConfirmScreen
import com.medguard.confirm.ConfirmViewModel
import com.medguard.di.ServiceLocator
import com.medguard.home.HomeScreen
import com.medguard.ui.theme.MedGuardTheme
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    var screen by remember { mutableStateOf(Screen.Home) }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }

    val viewModel: ConfirmViewModel = viewModel(
        factory = viewModelFactory {
            initializer { ConfirmViewModel(ServiceLocator.visionExtractor, Dispatchers.IO) }
        },
    )

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
        val uri = pendingCameraUri
        if (success && uri != null) processPickedImage(uri)
        pendingCameraUri = null
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
                pendingCameraUri = uri
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
