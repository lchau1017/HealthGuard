package com.healthguard.chat

/** Who authored a chat turn. */
enum class ChatRole { USER, ASSISTANT }

/** One completed exchange turn, sent back upstream as conversational context. */
data class ChatTurn(val role: ChatRole, val text: String)

/**
 * The assistant's answer to one message. Mirrors the extraction convention:
 * transport and upstream failures collapse into [Unavailable] instead of
 * exceptions, so callers branch on a sealed result and never try/catch.
 */
sealed interface ChatResult {
    data class Reply(val text: String) : ChatResult

    /** The chat service could not be reached or answered with an error. */
    data object Unavailable : ChatResult
}
