package com.healthguard.chat.state

/** Every user action the chat screen can raise, sent through [ChatViewModel.onIntent]. */
sealed interface ChatIntent {
    /** Edited the input field. */
    data class InputChanged(val text: String) : ChatIntent

    /** Tapped send: submits the current input. */
    data object Send : ChatIntent

    /** Tapped an empty-state suggestion: sends [text] as the message. */
    data class SendSuggestion(val text: String) : ChatIntent

    /** Tapped Retry on the error row: resends the last user message. */
    data object Retry : ChatIntent
}
