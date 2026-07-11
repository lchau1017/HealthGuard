@file:OptIn(ExperimentalMaterial3Api::class)

package com.healthguard.chat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.healthguard.chat.ChatRole
import com.healthguard.chat.state.AssistantSnapshot
import com.healthguard.chat.state.ChatIntent
import com.healthguard.chat.state.ChatMessage
import com.healthguard.chat.state.ChatUiState
import com.healthguard.common.theme.Spacing
import com.healthguard.common.ui.PhotoSourceSheet

/** Empty-state starter questions; tapping one sends it as-is. */
private val SUGGESTIONS = listOf(
    "What's my adherence rate?",
    "Which week did I miss the most doses?",
    "When is my next dose?",
    "Which medicine am I most consistent with?",
)

/**
 * The Assistant tab — the app's landing screen. Empty state is a hub: the
 * today snapshot card (tap through to Home), a scan action card into the
 * import flow, and starter questions. Once a conversation starts it becomes
 * the bubble list; scanning stays one tap away on the input bar. Stateless —
 * everything renders from [state] and every interaction goes through
 * [onIntent] or a shell callback.
 */
@Composable
fun ChatScreen(
    state: ChatUiState,
    onIntent: (ChatIntent) -> Unit,
    onOpenHome: () -> Unit,
    onTakePhoto: () -> Unit,
    onPickFromGallery: () -> Unit,
    modifier: Modifier = Modifier,
    bottomBar: @Composable () -> Unit = {},
) {
    var showSourceSheet by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("Assistant") }) },
        bottomBar = bottomBar,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding(),
        ) {
            if (state.messages.isEmpty()) {
                LandingHub(
                    snapshot = state.snapshot,
                    onOpenHome = onOpenHome,
                    onScan = { showSourceSheet = true },
                    onSuggestion = { onIntent(ChatIntent.SendSuggestion(it)) },
                    modifier = Modifier.weight(1f),
                )
            } else {
                MessageList(
                    messages = state.messages,
                    sending = state.sending,
                    modifier = Modifier.weight(1f),
                )
            }
            if (state.failed) {
                FailedRow(onRetry = { onIntent(ChatIntent.Retry) })
            }
            InputBar(
                state = state,
                onIntent = onIntent,
                onScan = { showSourceSheet = true },
            )
        }
    }

    if (showSourceSheet) {
        PhotoSourceSheet(
            onDismiss = { showSourceSheet = false },
            onTakePhoto = onTakePhoto,
            onPickFromGallery = onPickFromGallery,
        )
    }
}

@Composable
private fun LandingHub(
    snapshot: AssistantSnapshot?,
    onOpenHome: () -> Unit,
    onScan: () -> Unit,
    onSuggestion: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.xl, vertical = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Text(
            text = "Ask about your medications",
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = "Answers come from your own dose log.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.xs))
        if (snapshot != null) {
            SnapshotCard(snapshot = snapshot, onClick = onOpenHome)
        }
        ScanCard(onClick = onScan)
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = "Try asking",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SUGGESTIONS.forEach { suggestion ->
            OutlinedButton(
                onClick = { onSuggestion(suggestion) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(suggestion)
            }
        }
    }
}

/** Today at a glance; taps through to the Home tab for the full picture. */
@Composable
private fun SnapshotCard(
    snapshot: AssistantSnapshot,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(Spacing.lg)) {
            Text(
                text = "Today",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                text = snapshot.headline,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            snapshot.caption?.let { caption ->
                Text(
                    text = caption,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(top = Spacing.xs),
                )
            }
        }
    }
}

/** The import entry on the landing hub: straight into the scan flow. */
@Composable
private fun ScanCard(onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(Spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Column(modifier = Modifier.padding(start = Spacing.md)) {
                Text(
                    text = "Scan a medication",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "Photograph a box or label to add it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MessageList(
    messages: List<ChatMessage>,
    sending: Boolean,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val itemCount = messages.size + if (sending) 1 else 0
    // Follow the conversation: a new bubble (or the typing indicator)
    // scrolls itself into view.
    LaunchedEffect(itemCount) {
        if (itemCount > 0) listState.animateScrollToItem(itemCount - 1)
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        items(messages, key = { it.id }) { message -> MessageBubble(message) }
        if (sending) {
            item(key = "typing") {
                Bubble(fromUser = false, text = "Thinking…")
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    Bubble(fromUser = message.role == ChatRole.USER, text = message.text)
}

@Composable
private fun Bubble(fromUser: Boolean, text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = if (fromUser) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md),
            )
        }
    }
}

@Composable
private fun FailedRow(onRetry: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Couldn't reach the assistant.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRetry) { Text("Retry") }
    }
}

@Composable
private fun InputBar(
    state: ChatUiState,
    onIntent: (ChatIntent) -> Unit,
    onScan: () -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onScan) {
                Icon(Icons.Filled.Add, contentDescription = "Scan a medication label")
            }
            OutlinedTextField(
                value = state.input,
                onValueChange = { onIntent(ChatIntent.InputChanged(it)) },
                placeholder = { Text("Ask about your medications…") },
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = { onIntent(ChatIntent.Send) },
                enabled = state.canSend,
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }
        Text(
            text = "Describes your logged data only — not medical advice.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.xs),
        )
    }
}
