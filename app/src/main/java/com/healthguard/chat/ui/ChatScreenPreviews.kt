package com.healthguard.chat.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.healthguard.chat.ChatRole
import com.healthguard.chat.state.AssistantSnapshot
import com.healthguard.chat.state.ChatMessage
import com.healthguard.chat.state.ChatUiState
import com.healthguard.common.theme.HealthGuardTheme

/*
 * Design-time previews for the Chat tab. Sample data is fixed strings only,
 * so renders are reproducible. The populated conversation gets a light and
 * a dark variant.
 */

/** Landing hub with a populated snapshot card. */
private val previewLanding = ChatUiState(
    snapshot = AssistantSnapshot(
        headline = "Aspirin — next at 9:00 PM",
        caption = "5 of 6 days on track. Today still to come.",
    ),
)

@Composable
private fun ChatScreenSample(state: ChatUiState) {
    ChatScreen(
        state = state,
        onIntent = {},
        onOpenHome = {},
        onTakePhoto = {},
        onPickFromGallery = {},
    )
}

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
        ChatScreenSample(previewConversation)
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1B1B1F)
@Composable
private fun ChatScreenPreviewDark() {
    HealthGuardTheme(darkTheme = true) {
        ChatScreenSample(previewConversation)
    }
}

@Preview(showBackground = true)
@Composable
private fun ChatScreenEmptyPreview() {
    HealthGuardTheme(darkTheme = false) {
        ChatScreenSample(previewLanding)
    }
}

@Preview(showBackground = true)
@Composable
private fun ChatScreenFailedPreview() {
    HealthGuardTheme(darkTheme = false) {
        ChatScreenSample(previewConversation.copy(sending = false, failed = true))
    }
}
