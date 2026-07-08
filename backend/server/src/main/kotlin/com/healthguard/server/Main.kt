package com.healthguard.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer

fun main() {
    val apiKey = requireNotNull(System.getenv("OPENROUTER_API_KEY")) {
        "OPENROUTER_API_KEY environment variable is not set"
    }
    val modelId = System.getenv("MODEL_ID") ?: DEFAULT_MODEL
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8787

    embeddedServer(CIO, port = port) {
        extractionProxy(upstream = HttpClient(ClientCIO), apiKey = apiKey, modelId = modelId)
    }.start(wait = true)
}
