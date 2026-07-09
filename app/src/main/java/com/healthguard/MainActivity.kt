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
import com.healthguard.activity.ActivityScreen
import com.healthguard.activity.ActivityViewModel
import com.healthguard.capture.loadDownsampledBitmap
import com.healthguard.capture.toUploadJpegBase64
import com.healthguard.confirm.ConfirmDialog
import com.healthguard.confirm.ConfirmEffect
import com.healthguard.confirm.ConfirmIntent
import com.healthguard.confirm.ConfirmUiState
import com.healthguard.confirm.ConfirmViewModel
import com.healthguard.detail.DetailFinished
import com.healthguard.detail.DetailIntent
import com.healthguard.detail.DetailScreen
import com.healthguard.detail.DetailViewModel
import com.healthguard.home.HomeScreen
import com.healthguard.home.HomeViewModel
import com.healthguard.common.ui.AppNavBar
import com.healthguard.common.ui.AppTab
import com.healthguard.common.theme.HealthGuardTheme
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
 * Tab shell driven by plain saveable state: two bottom-bar tabs (Home and
 * Activity) with the medication detail page pushed above whichever tab is
 * current when [detailMedicationId] is set — so rotation and process death
 * keep the open screen, and backing out of the detail returns to the
 * previous tab. The import/confirm flow floats above whichever screen is
 * showing as a dialog whose visibility derives from [ConfirmViewModel]'s
 * state (Idle = hidden), so it survives rotation without extra plumbing.
 */
@Composable
private fun HealthGuardApp(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var detailMedicationId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedTab by rememberSaveable { mutableStateOf(AppTab.HOME) }
    // Saveable so rotation (or process death while the camera app is in the
    // foreground) keeps the pending capture target. Uri has no built-in
    // saver; persist its string form instead.
    var pendingCameraUriString by rememberSaveable { mutableStateOf<String?>(null) }

    val confirmViewModel: ConfirmViewModel = koinViewModel()
    val homeViewModel: HomeViewModel = koinViewModel()
    val confirmState by confirmViewModel.state.collectAsState()
    val homeState by homeViewModel.state.collectAsState()

    // Sole consumer of the confirm flow's one-shot effects (like Home/Detail):
    // a successful save toasts and resets the flow back to Idle.
    LaunchedEffect(Unit) {
        confirmViewModel.effects.collect { effect ->
            when (effect) {
                is ConfirmEffect.Saved -> {
                    Toast.makeText(context, "Added to your medications", Toast.LENGTH_SHORT).show()
                    confirmViewModel.onIntent(ConfirmIntent.Reset)
                }
            }
        }
    }

    fun processPickedImage(uri: Uri) {
        scope.launch {
            val base64 = withContext(Dispatchers.IO) {
                loadDownsampledBitmap(context, uri)?.toUploadJpegBase64()
            }
            if (base64 == null) {
                Toast.makeText(context, "Couldn't load that image", Toast.LENGTH_LONG).show()
            } else {
                confirmViewModel.onIntent(ConfirmIntent.ImagePicked(base64))
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
        // Dose logs alone don't retrigger the medications query; refresh on
        // entry so a retained view model catches up on takes from elsewhere.
        LaunchedEffect(Unit) { detailViewModel.onIntent(DetailIntent.Refresh) }
        DetailScreen(
            state = detailState,
            onIntent = detailViewModel::onIntent,
            effects = detailViewModel.effects,
            onBack = { detailMedicationId = null },
            onFinished = { result ->
                when (result) {
                    DetailFinished.SAVED ->
                        Toast.makeText(context, "Changes saved", Toast.LENGTH_SHORT).show()
                    DetailFinished.DELETED ->
                        Toast.makeText(context, "Medication deleted", Toast.LENGTH_SHORT).show()
                }
                detailMedicationId = null
            },
            modifier = modifier,
        )
    } else if (selectedTab == AppTab.ACTIVITY) {
        val activityViewModel: ActivityViewModel = koinViewModel()
        BackHandler { selectedTab = AppTab.HOME }
        ActivityScreen(
            viewModel = activityViewModel,
            bottomBar = { AppNavBar(selected = selectedTab, onSelect = { selectedTab = it }) },
            modifier = modifier,
        )
    } else {
        HomeScreen(
            state = homeState,
            onIntent = homeViewModel::onIntent,
            effects = homeViewModel.effects,
            onOpenDetail = { detailMedicationId = it },
            onOpenActivity = { selectedTab = AppTab.ACTIVITY },
            onTakePhoto = ::launchCamera,
            onPickFromGallery = {
                pickImageLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
            bottomBar = { AppNavBar(selected = selectedTab, onSelect = { selectedTab = it }) },
            modifier = modifier,
        )
    }

    when (confirmState) {
        is ConfirmUiState.Idle -> Unit
        else -> ConfirmDialog(
            state = confirmState,
            onIntent = confirmViewModel::onIntent,
        )
    }
}
