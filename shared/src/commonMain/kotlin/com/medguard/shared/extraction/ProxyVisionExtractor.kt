package com.medguard.shared.extraction

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
 * [VisionExtractor] backed by the MedGuard backend proxy. Any transport-level
 * failure (unreachable host, non-2xx status) maps to
 * [ExtractionResult.Unavailable]; a 2xx body is handed to [ExtractionParser],
 * which owns the Malformed-vs-Success decision. Never throws.
 *
 * The caller owns [client] configuration: install HttpTimeout (or equivalent)
 * on it, otherwise a hung proxy suspends [extract] indefinitely — Unavailable
 * only covers failures the client itself surfaces.
 */
class ProxyVisionExtractor(
    private val client: HttpClient,
    baseUrl: String,
    private val parser: ExtractionParser = ExtractionParser(),
) : VisionExtractor {

    private val extractUrl = baseUrl.trimEnd('/') + "/extract"

    override suspend fun extract(imageJpegBase64: String): ExtractionResult = try {
        val response = client.post(extractUrl) {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(ExtractRequest(imageJpegBase64)))
        }
        if (response.status.isSuccess()) {
            parser.parse(response.bodyAsText())
        } else {
            ExtractionResult.Unavailable
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        ExtractionResult.Unavailable
    }
}

@Serializable
private data class ExtractRequest(val imageJpegBase64: String)
