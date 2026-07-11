package com.healthguard.server

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The proxies' only error shape: a fixed short reason, never an upstream body —
 * provider error text could leak details the client has no business seeing.
 */
internal suspend fun ApplicationCall.respondError(status: HttpStatusCode, reason: String) =
    respondText(
        buildJsonObject { put("error", reason) }.toString(),
        ContentType.Application.Json,
        status,
    )
