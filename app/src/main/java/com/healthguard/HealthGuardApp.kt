package com.healthguard

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.healthguard.activity.ActivityViewModel
import com.healthguard.activity.ui.ActivityScreen
import com.healthguard.common.ui.AppNavBar
import com.healthguard.common.ui.AppTab
import com.healthguard.chat.ChatViewModel
import com.healthguard.chat.ui.ChatScreen
import com.healthguard.confirm.ConfirmViewModel
import com.healthguard.confirm.state.ConfirmIntent
import com.healthguard.confirm.ui.ConfirmFlowHost
import com.healthguard.confirm.ui.rememberScanImageLauncher
import com.healthguard.detail.DetailViewModel
import com.healthguard.detail.state.DetailFinished
import com.healthguard.detail.state.DetailIntent
import com.healthguard.detail.ui.DetailScreen
import com.healthguard.domain.model.MedicationId
import com.healthguard.home.HomeViewModel
import com.healthguard.home.ui.HomeScreen
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Tab shell driven by plain saveable state: three bottom-bar tabs (Home,
 * Activity and Chat) with the medication detail page pushed above whichever
 * tab is current when [detailMedicationId] is set — so rotation and process death
 * keep the open screen, and backing out of the detail returns to the
 * previous tab. The import/confirm flow floats above whichever screen is
 * showing via [ConfirmFlowHost].
 */
@Composable
fun HealthGuardApp(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var detailMedicationId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedTab by rememberSaveable { mutableStateOf(AppTab.ASSISTANT) }

    val confirmViewModel: ConfirmViewModel = koinViewModel()
    val homeViewModel: HomeViewModel = koinViewModel()
    val homeState by homeViewModel.state.collectAsStateWithLifecycle()

    // Decode and error handling live in the confirm feature: the view model
    // survives rotation (a composition-scoped decode would not) and renders
    // progress/failure through its own dialog states.
    val scanImageLauncher = rememberScanImageLauncher { uri ->
        confirmViewModel.onIntent(ConfirmIntent.ImagePicked(uri.toString()))
    }

    // Scope the detail view model to the detail "destination" instead of the
    // Activity: resolving it against the Activity's ViewModelStore with a
    // per-id key would retain one DetailViewModel for every medication ever
    // visited until the Activity dies. The per-id child stores live in
    // [DetailStoreHolder] — itself an Activity-retained ViewModel (resolved
    // with plain androidx `viewModel()`, deliberately not Koin) — so the open
    // detail's view model, and the form edits it holds, survive rotation.
    val detailStores: DetailStoreHolder = viewModel()

    // The SAVED form stays the raw string — a value class would need a custom
    // Saver for no gain — and is wrapped back into [MedicationId] right here
    // at the storage edge.
    val openDetailId = detailMedicationId?.let(::MedicationId)
    if (openDetailId != null) {
        // The single "detail actually closed" path: releases the id's
        // retained store, so opening A → back → opening B → back leaves no
        // retained detail view models. Deliberately NOT a DisposableEffect
        // onDispose — that would also fire on configuration change, which is
        // exactly the recreation the retained holder exists to survive. (The
        // shell can never jump from detail A straight to detail B — the only
        // opener, Home's onOpenDetail, is unreachable while a detail shows —
        // so close-time clearing covers every id transition.)
        val closeDetail = {
            detailMedicationId = null
            detailStores.clear(openDetailId)
        }
        CompositionLocalProvider(LocalViewModelStoreOwner provides detailStores.ownerFor(openDetailId)) {
            val detailViewModel: DetailViewModel = koinViewModel(
                parameters = { parametersOf(openDetailId) },
            )
            val detailState by detailViewModel.state.collectAsStateWithLifecycle()
            BackHandler(onBack = closeDetail)
            // Dose logs alone don't retrigger the medications query; refresh on
            // entry so a retained view model catches up on takes from elsewhere.
            LaunchedEffect(Unit) { detailViewModel.onIntent(DetailIntent.Refresh) }
            DetailScreen(
                state = detailState,
                onIntent = detailViewModel::onIntent,
                effects = detailViewModel.effects,
                onBack = closeDetail,
                onFinished = { result ->
                    when (result) {
                        DetailFinished.SAVED ->
                            Toast.makeText(context, "Changes saved", Toast.LENGTH_SHORT).show()
                        DetailFinished.DELETED ->
                            Toast.makeText(context, "Medication deleted", Toast.LENGTH_SHORT).show()
                    }
                    closeDetail()
                },
                modifier = modifier,
            )
        }
    } else if (selectedTab == AppTab.ASSISTANT) {
        val chatViewModel: ChatViewModel = koinViewModel()
        val chatState by chatViewModel.state.collectAsStateWithLifecycle()
        ChatScreen(
            state = chatState,
            onIntent = chatViewModel::onIntent,
            bottomBar = { AppNavBar(selected = selectedTab, onSelect = { selectedTab = it }) },
            modifier = modifier,
        )
    } else if (selectedTab == AppTab.ACTIVITY) {
        val activityViewModel: ActivityViewModel = koinViewModel()
        val activityState by activityViewModel.state.collectAsStateWithLifecycle()
        BackHandler { selectedTab = AppTab.ASSISTANT }
        ActivityScreen(
            state = activityState,
            onIntent = activityViewModel::onIntent,
            bottomBar = { AppNavBar(selected = selectedTab, onSelect = { selectedTab = it }) },
            modifier = modifier,
        )
    } else {
        // The assistant is the shell's root: backing out of a side tab
        // returns there, and only the assistant hands back to the system.
        BackHandler { selectedTab = AppTab.ASSISTANT }
        HomeScreen(
            state = homeState,
            onIntent = homeViewModel::onIntent,
            effects = homeViewModel.effects,
            onOpenDetail = { detailMedicationId = it.value },
            onOpenActivity = { selectedTab = AppTab.ACTIVITY },
            onTakePhoto = scanImageLauncher::takePhoto,
            onPickFromGallery = scanImageLauncher::pickFromGallery,
            bottomBar = { AppNavBar(selected = selectedTab, onSelect = { selectedTab = it }) },
            modifier = modifier,
        )
    }

    ConfirmFlowHost(viewModel = confirmViewModel)
}
