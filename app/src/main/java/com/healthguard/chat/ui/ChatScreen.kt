@file:OptIn(ExperimentalMaterial3Api::class)

package com.healthguard.chat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.healthguard.chat.ChatRole
import com.healthguard.chat.state.ChatIntent
import com.healthguard.chat.state.ChatMessage
import com.healthguard.chat.state.ChatUiState
import com.healthguard.common.theme.Spacing

/** Empty-state starter questions; tapping one sends it as-is. */
private val SUGGESTIONS = listOf(
    "What's my adherence rate?",
    "Which week did I miss the most doses?",
    "When is my next dose?",
    "Which medicine am I most consistent with?",
)

/**
 * The Chat tab: the conversation list (or starter suggestions while empty),
 * an inline retry row after a failed send, and the input bar with a standing
 * not-medical-advice caption. Stateless — everything renders from [state]
 * and every interaction goes through [onIntent].
 */
@Composable
fun ChatScreen(
    state: ChatUiState,
    onIntent: (ChatIntent) -> Unit,
    modifier: Modifier = Modifier,
    bottomBar: @Composable () -> Unit = {},
) {
    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("Chat") }) },
        bottomBar = bottomBar,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding(),
        ) {
            if (state.messages.isEmpty()) {
                EmptyChat(
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
            InputBar(state = state, onIntent = onIntent)
        }
    }
}

@Composable
private fun EmptyChat(
    onSuggestion: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.xl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Ask about your medications",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = "Answers come from your own dose log.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = Spacing.xs, bottom = Spacing.lg),
        )
        SUGGESTIONS.forEach { suggestion ->
            OutlinedButton(
                onClick = { onSuggestion(suggestion) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = Spacing.xs),
            ) {
                Text(suggestion)
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
) {
    Column(modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
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
