package com.healthguard.data

import com.healthguard.chat.ChatAssistant
import com.healthguard.chat.ChatContext
import com.healthguard.chat.ChatResult
import com.healthguard.chat.ChatRole
import com.healthguard.chat.ChatTurn
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * [ChatAssistant] backed by the HealthGuard backend proxy. The context is
 * rendered to prompt text on-device by [renderer]; the proxy only splices it
 * into the model prompt. Any transport-level failure (unreachable host,
 * non-2xx status, unparseable or blank reply) maps to
 * [ChatResult.Unavailable]. Never throws.
 *
 * The caller owns [client] configuration: install HttpTimeout (or
 * equivalent) on it, otherwise a hung proxy suspends [send] indefinitely —
 * Unavailable only covers failures the client itself surfaces.
 */
class ProxyChatAssistant(
    private val client: HttpClient,
    baseUrl: String,
    private val renderer: ChatContextRenderer,
) : ChatAssistant {

    private val chatUrl = baseUrl.trimEnd('/') + "/chat"
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun send(
        message: String,
        history: List<ChatTurn>,
        context: ChatContext,
    ): ChatResult = try {
        val response = client.post(chatUrl) {
            contentType(ContentType.Application.Json)
            setBody(
                Json.encodeToString(
                    ChatRequest(
                        message = message,
                        history = history.map { TurnDto(it.role.wire, it.text) },
                        context = renderer.render(context),
                    ),
                ),
            )
        }
        if (response.status.isSuccess()) {
            val reply = json.decodeFromString<ChatResponse>(response.bodyAsText()).reply
            if (reply.isNullOrBlank()) ChatResult.Unavailable else ChatResult.Reply(reply)
        } else {
            ChatResult.Unavailable
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        ChatResult.Unavailable
    }

    private val ChatRole.wire: String
        get() = when (this) {
            ChatRole.USER -> "user"
            ChatRole.ASSISTANT -> "assistant"
        }
}

@Serializable
private data class TurnDto(val role: String, val text: String)

@Serializable
private data class ChatRequest(
    val message: String,
    val history: List<TurnDto>,
    val context: String,
)

@Serializable
private data class ChatResponse(val reply: String? = null)
