package com.healthguard.chat.state

import com.healthguard.chat.ChatRole

/** One rendered chat bubble; [id] keys the lazy list. */
data class ChatMessage(
    val id: Long,
    val role: ChatRole,
    val text: String,
)

/**
 * The chat screen's single immutable ViewState. History is deliberately
 * in-memory only: it lives and dies with this state, never on disk.
 */
data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    /** The draft in the input field. */
    val input: String = "",
    /** True while a send is in flight: sending disabled, typing indicator showing. */
    val sending: Boolean = false,
    /** True after a failed send: renders the inline error row with Retry. */
    val failed: Boolean = false,
) {
    val canSend: Boolean get() = input.isNotBlank() && !sending
}
