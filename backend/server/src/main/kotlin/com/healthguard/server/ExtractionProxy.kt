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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

const val DEFAULT_MODEL = "qwen/qwen2.5-vl-72b-instruct"
internal const val OPENROUTER_URL = "https://openrouter.ai/api/v1/chat/completions"

/** Largest accepted /extract body; a base64 JPEG frame fits comfortably. */
internal const val MAX_BODY_BYTES: Long = 10L * 1024 * 1024

// The model must transcribe, never advise: safety decisions belong to the
// verified drug-data layer, and the prompt must not invite inference.
private val EXTRACTION_PROMPT = """
    You are an OCR extraction engine for medication packaging.
    Extract ONLY what is printed on the label. Do not infer, guess, or add medical advice.
    For each field report a confidence 0-1. Use null when the label does not show the field.
    frequency: {"timesPerDay":N} for "N times a day", {"everyHours":N} for "every N hours".
""".trimIndent()

/** `{"value": <valueSchema>, "confidence": 0..1}` wrapper used by every field. */
private fun field(valueSchema: JsonObject): JsonObject = buildJsonObject {
    put("type", "object")
    putJsonArray("required") { add("value"); add("confidence") }
    putJsonObject("properties") {
        put("value", valueSchema)
        putJsonObject("confidence") {
            put("type", "number")
            put("minimum", 0)
            put("maximum", 1)
        }
    }
}

private fun types(vararg t: String): JsonObject = buildJsonObject {
    putJsonArray("type") { t.forEach { add(it) } }
}

private val SCHEMA: JsonObject = buildJsonObject {
    put("type", "object")
    putJsonArray("required") {
        listOf("drugName", "activeIngredients", "dosage", "form", "frequency", "withFood")
            .forEach { add(it) }
    }
    putJsonObject("properties") {
        put("drugName", field(types("string", "null")))
        putJsonObject("activeIngredients") {
            put("type", "array")
            put("items", field(types("string", "null")))
        }
        put("dosage", field(types("string", "null")))
        put(
            "form",
            field(
                buildJsonObject {
                    putJsonArray("type") { add("string"); add("null") }
                    putJsonArray("enum") {
                        listOf("tablet", "capsule", "liquid", "spray", "cream", "other")
                            .forEach { add(it) }
                        add(JsonNull)
                    }
                },
            ),
        )
        put(
            "frequency",
            field(
                buildJsonObject {
                    putJsonArray("type") { add("object"); add("null") }
                    putJsonObject("properties") {
                        putJsonObject("timesPerDay") { put("type", "integer") }
                        putJsonObject("everyHours") { put("type", "integer") }
                    }
                },
            ),
        )
        put("withFood", field(types("boolean", "null")))
    }
}

internal fun upstreamRequestBody(modelId: String, imageJpegBase64: String): JsonObject =
    buildJsonObject {
        put("model", modelId)
        putJsonArray("messages") {
            add(
                buildJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        add(
                            buildJsonObject {
                                put("type", "text")
                                put("text", EXTRACTION_PROMPT)
                            },
                        )
                        add(
                            buildJsonObject {
                                put("type", "image_url")
                                putJsonObject("image_url") {
                                    put("url", "data:image/jpeg;base64,$imageJpegBase64")
                                }
                            },
                        )
                    }
                },
            )
        }
        // Forced structured output. NOT tool calling: no OpenRouter provider
        // hosting the Qwen VL models supports tools (verified live 2026-07-07).
        putJsonObject("response_format") {
            put("type", "json_schema")
            putJsonObject("json_schema") {
                put("name", "report_extraction")
                put("strict", true)
                put("schema", SCHEMA)
            }
        }
    }

/**
 * The extraction proxy: the only holder of the OpenRouter API key. The image
 * passes straight through to the model provider — it is never persisted or
 * logged here, and upstream error bodies are never echoed to the client (they
 * could leak provider details).
 */
fun Application.extractionProxy(upstream: HttpClient, apiKey: String, modelId: String) {
    routing {
        post("/extract") {
            // Bound the body before reading it: nothing legitimate outgrows
            // one base64 JPEG frame.
            val contentLength = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            if (contentLength != null && contentLength > MAX_BODY_BYTES) {
                call.respondError(HttpStatusCode.PayloadTooLarge, "body too large")
                return@post
            }

            val body = runCatching {
                Json.parseToJsonElement(call.receiveText())
            }.getOrNull() as? JsonObject
            val image = (body?.get("imageJpegBase64") as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?.content
            if (image.isNullOrEmpty()) {
                call.respondError(HttpStatusCode.BadRequest, "imageJpegBase64 required")
                return@post
            }

            val response = try {
                upstream.post(OPENROUTER_URL) {
                    header(HttpHeaders.Authorization, "Bearer $apiKey")
                    contentType(ContentType.Application.Json)
                    setBody(upstreamRequestBody(modelId, image).toString())
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
                call.respondText(content, ContentType.Application.Json)
            }
        }
    }
}
