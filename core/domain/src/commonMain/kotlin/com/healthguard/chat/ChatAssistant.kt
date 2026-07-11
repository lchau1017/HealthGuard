package com.healthguard.chat

/**
 * The adherence chat service boundary. Implementations answer [message]
 * from [context] (and the prior [history] turns) in plain English.
 *
 * Never throws: transport-level failures map to [ChatResult.Unavailable],
 * mirroring [com.healthguard.domain.extraction.VisionExtractor]'s contract.
 * The caller owns request-time bounds on whatever transport backs this.
 */
interface ChatAssistant {
    suspend fun send(
        message: String,
        history: List<ChatTurn>,
        context: ChatContext,
    ): ChatResult
}
