package com.healthguard.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer

private const val DEFAULT_PORT = 8787

/** Upper bound for one full upstream call; vision models can be slow, but never unbounded. */
private const val UPSTREAM_REQUEST_TIMEOUT_MS = 60_000L

private const val UPSTREAM_CONNECT_TIMEOUT_MS = 10_000L

fun main() {
    val apiKey = requireNotNull(System.getenv("OPENROUTER_API_KEY")) {
        "OPENROUTER_API_KEY environment variable is not set"
    }
    val modelId = System.getenv("MODEL_ID") ?: DEFAULT_MODEL
    val chatModelId = System.getenv("CHAT_MODEL_ID") ?: DEFAULT_CHAT_MODEL
    val port = System.getenv("PORT")?.toIntOrNull() ?: DEFAULT_PORT

    // Bound every upstream call: without a timeout a hung provider would pin
    // request handlers forever. A timed-out call surfaces as the same 502 as
    // any other upstream failure.
    val upstream = HttpClient(ClientCIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = UPSTREAM_REQUEST_TIMEOUT_MS
            connectTimeoutMillis = UPSTREAM_CONNECT_TIMEOUT_MS
        }
    }

    embeddedServer(CIO, port = port) {
        extractionProxy(upstream = upstream, apiKey = apiKey, modelId = modelId)
        chatProxy(upstream = upstream, apiKey = apiKey, modelId = chatModelId)
    }.start(wait = true)
}
