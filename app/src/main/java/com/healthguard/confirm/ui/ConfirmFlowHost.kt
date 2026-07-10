package com.healthguard.confirm.ui

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.healthguard.confirm.ConfirmViewModel
import com.healthguard.confirm.state.ConfirmEffect
import com.healthguard.confirm.state.ConfirmUiState
import org.koin.androidx.compose.koinViewModel

/**
 * Hosts the import/confirm flow above whichever screen is showing: the
 * dialog's visibility derives from [ConfirmViewModel]'s state (Idle =
 * hidden), so it survives rotation without extra plumbing. Sole consumer of
 * the flow's one-shot effects (like Home/Detail): a successful save toasts —
 * the view model itself returns the flow to Idle.
 */
@Composable
fun ConfirmFlowHost(viewModel: ConfirmViewModel = koinViewModel()) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Collect only while STARTED so a background toast can't fire; the
    // Channel-backed effects are buffered, so nothing emitted while STOPPED
    // is lost — it is delivered when collection resumes.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    is ConfirmEffect.Saved ->
                        Toast.makeText(context, "Added to your medications", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    when (state) {
        is ConfirmUiState.Idle -> Unit
        else -> ConfirmDialog(
            state = state,
            onIntent = viewModel::onIntent,
        )
    }
}
