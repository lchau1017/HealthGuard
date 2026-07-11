package com.healthguard.chat.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.healthguard.chat.ChatRole
import com.healthguard.chat.state.ChatMessage
import com.healthguard.chat.state.ChatUiState
import com.healthguard.common.theme.HealthGuardTheme

/*
 * Design-time previews for the Chat tab. Sample data is fixed strings only,
 * so renders are reproducible. The populated conversation gets a light and
 * a dark variant.
 */

private val previewConversation = ChatUiState(
    messages = listOf(
        ChatMessage(0, ChatRole.USER, "What's my adherence rate?"),
        ChatMessage(
            1,
            ChatRole.ASSISTANT,
            "Over the last 30 days you're at 85% overall — Aspirin 90%, Vitamin D 60%.",
        ),
        ChatMessage(2, ChatRole.USER, "Which week did I miss the most doses?"),
    ),
    sending = true,
)

@Preview(showBackground = true)
@Composable
private fun ChatScreenPreview() {
    HealthGuardTheme(darkTheme = false) {
        ChatScreen(state = previewConversation, onIntent = {})
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1B1B1F)
@Composable
private fun ChatScreenPreviewDark() {
    HealthGuardTheme(darkTheme = true) {
        ChatScreen(state = previewConversation, onIntent = {})
    }
}

@Preview(showBackground = true)
@Composable
private fun ChatScreenEmptyPreview() {
    HealthGuardTheme(darkTheme = false) {
        ChatScreen(state = ChatUiState(), onIntent = {})
    }
}

@Preview(showBackground = true)
@Composable
private fun ChatScreenFailedPreview() {
    HealthGuardTheme(darkTheme = false) {
        ChatScreen(
            state = previewConversation.copy(sending = false, failed = true),
            onIntent = {},
        )
    }
}
