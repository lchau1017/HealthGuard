package com.healthguard.server

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.server.application.Application
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** A cheap text model is enough: the app precomputes every number; the model only phrases. */
const val DEFAULT_CHAT_MODEL = "qwen/qwen3-30b-a3b-instruct-2507"

/** Largest accepted /chat body: a message, capped history and a rendered context block. */
internal const val MAX_CHAT_BODY_BYTES: Long = 256L * 1024

/** Prior turns forwarded upstream; older turns are silently dropped. */
internal const val MAX_HISTORY_TURNS = 10

// Same stance as the extraction prompt: report, never advise. Whether to
// take, skip or change a dose is a medical decision this product never makes.
private val CHAT_SYSTEM_PROMPT = """
    You are the HealthGuard medication-adherence assistant.
    Answer the user's question using ONLY the adherence data below — a factual
    export of their own medication log.
    Rules:
    - Never give medical advice. Never tell the user to take, skip, stop or
      change a medication or dose. If asked for advice, say you can only
      report their schedule and history, and suggest asking a doctor or
      pharmacist.
    - Stating factual schedule information (such as when the next dose is
      due) is allowed.
    - If the data does not answer the question, say what is missing. Never
      invent numbers. Report percentages ONLY where the data shows one; a
      medication marked "no scheduled expectation" is as-needed or not
      started — report its taken count, never a percent.
    - Reply in plain, concise English prose. No markdown headings or tables.
""".trimIndent()

/** One sanitized prior turn: a whitelisted role plus its text. */
private data class HistoryTurn(val role: String, val text: String)

internal fun chatUpstreamBody(
    modelId: String,
    message: String,
    history: List<Pair<String, String>>,
    context: String,
): JsonObject = buildJsonObject {
    put("model", modelId)
    putJsonArray("messages") {
        add(
            buildJsonObject {
                put("role", "system")
                put("content", "$CHAT_SYSTEM_PROMPT\n\nADHERENCE DATA:\n$context")
            },
        )
        history.forEach { (role, text) ->
            add(
                buildJsonObject {
                    put("role", role)
                    put("content", text)
                },
            )
        }
        add(
            buildJsonObject {
                put("role", "user")
                put("content", message)
            },
        )
    }
}

/**
 * The chat proxy: like the extraction proxy, the only holder of the
 * OpenRouter API key. The message and adherence context pass straight
 * through to the model provider — never persisted or logged here — and
 * upstream error bodies are never echoed to the client.
 */
fun Application.chatProxy(upstream: HttpClient, apiKey: String, modelId: String) {
    routing {
        post("/chat") {
            // Bound the body before reading it: a message plus rendered
            // context never legitimately outgrows this.
            val contentLength = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            if (contentLength != null && contentLength > MAX_CHAT_BODY_BYTES) {
                call.respondError(HttpStatusCode.PayloadTooLarge, "body too large")
                return@post
            }

            val body = runCatching {
                Json.parseToJsonElement(call.receiveText())
            }.getOrNull() as? JsonObject
            val message = (body?.get("message") as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?.content?.trim()
            if (message.isNullOrEmpty()) {
                call.respondError(HttpStatusCode.BadRequest, "message required")
                return@post
            }
            val context = (body["context"] as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?.content.orEmpty()
            val history = sanitizedHistory(body["history"] as? JsonArray)

            val response = try {
                upstream.post(OPENROUTER_URL) {
                    header(HttpHeaders.Authorization, "Bearer $apiKey")
                    contentType(ContentType.Application.Json)
                    setBody(
                        chatUpstreamBody(
                            modelId = modelId,
                            message = message,
                            history = history.map { it.role to it.text },
                            context = context,
                        ).toString(),
                    )
                }
            } catch (cancellation: CancellationException) {
                // Never swallow cancellation: a cancelled client call must
                // cancel the upstream request, not read as a 502.
                throw cancellation
            } catch (_: Exception) {
                null
            }
            if (response == null || !response.status.isSuccess()) {
                call.respondError(HttpStatusCode.BadGateway, "upstream")
                return@post
            }

            val content = runCatching {
                Json.parseToJsonElement(response.bodyAsText())
                    .jsonObject["choices"]?.jsonArray?.firstOrNull()
                    ?.jsonObject?.get("message")
                    ?.jsonObject?.get("content")
                    ?.jsonPrimitive?.takeIf { it.isString }?.content
            }.getOrNull()
            if (content.isNullOrEmpty()) {
                call.respondError(HttpStatusCode.BadGateway, "no content")
            } else {
                call.respondText(
                    buildJsonObject { put("reply", content) }.toString(),
                    ContentType.Application.Json,
                )
            }
        }
    }
}

/**
 * Keeps only well-formed turns with a whitelisted role — the client owns the
 * conversation, but a forged "system" turn must never outrank the guardrail
 * prompt — and caps depth to the newest [MAX_HISTORY_TURNS].
 */
private fun sanitizedHistory(history: JsonArray?): List<HistoryTurn> =
    history.orEmpty()
        .mapNotNull { element ->
            val turn = element as? JsonObject ?: return@mapNotNull null
            val role = (turn["role"] as? JsonPrimitive)
                ?.takeIf { it.isString }?.content
                ?.takeIf { it == "user" || it == "assistant" }
                ?: return@mapNotNull null
            val text = (turn["text"] as? JsonPrimitive)
                ?.takeIf { it.isString }?.content
                ?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            HistoryTurn(role, text)
        }
        .takeLast(MAX_HISTORY_TURNS)
