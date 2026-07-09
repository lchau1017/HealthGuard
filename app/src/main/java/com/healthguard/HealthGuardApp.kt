package com.healthguard

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.healthguard.activity.ActivityScreen
import com.healthguard.activity.ActivityViewModel
import com.healthguard.common.ui.AppNavBar
import com.healthguard.common.ui.AppTab
import com.healthguard.confirm.ConfirmFlowHost
import com.healthguard.confirm.ConfirmIntent
import com.healthguard.confirm.ConfirmViewModel
import com.healthguard.confirm.rememberScanImageLauncher
import com.healthguard.detail.DetailFinished
import com.healthguard.detail.DetailIntent
import com.healthguard.detail.DetailScreen
import com.healthguard.detail.DetailViewModel
import com.healthguard.home.HomeScreen
import com.healthguard.home.HomeViewModel
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Tab shell driven by plain saveable state: two bottom-bar tabs (Home and
 * Activity) with the medication detail page pushed above whichever tab is
 * current when [detailMedicationId] is set — so rotation and process death
 * keep the open screen, and backing out of the detail returns to the
 * previous tab. The import/confirm flow floats above whichever screen is
 * showing via [ConfirmFlowHost].
 */
@Composable
fun HealthGuardApp(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var detailMedicationId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedTab by rememberSaveable { mutableStateOf(AppTab.HOME) }

    val confirmViewModel: ConfirmViewModel = koinViewModel()
    val homeViewModel: HomeViewModel = koinViewModel()
    val homeState by homeViewModel.state.collectAsState()

    // Decode and error handling live in the confirm feature: the view model
    // survives rotation (a composition-scoped decode would not) and renders
    // progress/failure through its own dialog states.
    val scanImageLauncher = rememberScanImageLauncher { uri ->
        confirmViewModel.onIntent(ConfirmIntent.ImagePicked(uri.toString()))
    }

    val openDetailId = detailMedicationId
    if (openDetailId != null) {
        // Scope the detail view model to the detail "destination" instead of
        // the Activity: resolving it against the Activity's ViewModelStore
        // with a per-id key would retain one DetailViewModel for every
        // medication ever visited until the Activity dies. This child owner
        // lives exactly as long as the detail is open for this id — the
        // DisposableEffect clears its store when the detail closes (or the id
        // changes), releasing the view model. Opening A → back → opening B →
        // back therefore leaves no retained detail view models.
        val detailStoreOwner = remember(openDetailId) {
            object : ViewModelStoreOwner {
                override val viewModelStore = ViewModelStore()
            }
        }
        DisposableEffect(detailStoreOwner) {
            onDispose { detailStoreOwner.viewModelStore.clear() }
        }
        CompositionLocalProvider(LocalViewModelStoreOwner provides detailStoreOwner) {
            val detailViewModel: DetailViewModel = koinViewModel(
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
        }
    } else if (selectedTab == AppTab.ACTIVITY) {
        val activityViewModel: ActivityViewModel = koinViewModel()
        val activityState by activityViewModel.state.collectAsState()
        BackHandler { selectedTab = AppTab.HOME }
        ActivityScreen(
            state = activityState,
            onIntent = activityViewModel::onIntent,
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
            onTakePhoto = scanImageLauncher::takePhoto,
            onPickFromGallery = scanImageLauncher::pickFromGallery,
            bottomBar = { AppNavBar(selected = selectedTab, onSelect = { selectedTab = it }) },
            modifier = modifier,
        )
    }

    ConfirmFlowHost(viewModel = confirmViewModel)
}
