package com.healthguard

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
import com.healthguard.capture.loadDownsampledBitmap
import com.healthguard.capture.toUploadJpegBase64
import com.healthguard.confirm.ConfirmDialog
import com.healthguard.confirm.ConfirmUiState
import com.healthguard.confirm.ConfirmViewModel
import com.healthguard.detail.DetailFinished
import com.healthguard.detail.DetailScreen
import com.healthguard.detail.DetailViewModel
import com.healthguard.home.HomeScreen
import com.healthguard.home.HomeViewModel
import com.healthguard.ui.theme.HealthGuardTheme
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HealthGuardTheme {
                // Screens carry their own Scaffold (top bar, FAB, snackbar).
                HealthGuardApp(modifier = Modifier.fillMaxSize())
            }
        }
    }
}

/**
 * Two-screen shell driven by plain state: Home when [detailMedicationId] is
 * null, the medication detail page otherwise (saveable, so rotation and
 * process death keep the open detail). The import/confirm flow floats above
 * whichever screen is showing as a dialog whose visibility derives from
 * [ConfirmViewModel]'s state (Idle = hidden), so it survives rotation
 * without extra plumbing.
 */
@Composable
private fun HealthGuardApp(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var detailMedicationId by rememberSaveable { mutableStateOf<String?>(null) }
    // Saveable so rotation (or process death while the camera app is in the
    // foreground) keeps the pending capture target. Uri has no built-in
    // saver; persist its string form instead.
    var pendingCameraUriString by rememberSaveable { mutableStateOf<String?>(null) }

    val confirmViewModel: ConfirmViewModel = koinViewModel()
    val homeViewModel: HomeViewModel = koinViewModel()
    val confirmState by confirmViewModel.state.collectAsState()
    val homeState by homeViewModel.state.collectAsState()
    val homeTakeConfirm by homeViewModel.takeConfirm.collectAsState()
    val homeRecentTake by homeViewModel.recentTake.collectAsState()

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

    fun launchCamera() {
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

    val openDetailId = detailMedicationId
    if (openDetailId != null) {
        val detailViewModel: DetailViewModel = koinViewModel(
            key = "detail-$openDetailId",
            parameters = { parametersOf(openDetailId) },
        )
        val detailState by detailViewModel.state.collectAsState()
        BackHandler { detailMedicationId = null }
        DetailScreen(
            viewModel = detailViewModel,
            onBack = { detailMedicationId = null },
            modifier = modifier,
        )
        LaunchedEffect(detailState.finished) {
            when (detailState.finished) {
                DetailFinished.SAVED -> {
                    Toast.makeText(context, "Changes saved", Toast.LENGTH_SHORT).show()
                    detailMedicationId = null
                }
                DetailFinished.DELETED -> {
                    Toast.makeText(context, "Medication deleted", Toast.LENGTH_SHORT).show()
                    detailMedicationId = null
                }
                null -> Unit
            }
        }
    } else {
        HomeScreen(
            state = homeState,
            takeConfirm = homeTakeConfirm,
            recentTake = homeRecentTake,
            onTakeNow = homeViewModel::takeNow,
            onConfirmTakeAnyway = homeViewModel::confirmTakeAnyway,
            onDismissTakeConfirm = homeViewModel::dismissTakeConfirm,
            onUndoTake = homeViewModel::undoTake,
            onRecentTakeHandled = homeViewModel::clearRecentTake,
            onPlay = homeViewModel::onPlay,
            onDelete = homeViewModel::onDelete,
            onOpenDetail = { detailMedicationId = it },
            onOpenActivity = {}, // Activity dashboard arrives with the next slice.
            onTakePhoto = ::launchCamera,
            onPickFromGallery = {
                pickImageLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
            modifier = modifier,
        )
    }

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
