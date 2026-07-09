package com.healthguard.shared.extraction

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProxyVisionExtractorTest {

    private val validPayload = """
        {
          "drugName": {"value": "Amoxicillin", "confidence": 0.97},
          "activeIngredients": [{"value": "Amoxicillin trihydrate", "confidence": 0.92}],
          "dosage": {"value": "500 mg", "confidence": 0.95},
          "form": {"value": "capsule", "confidence": 0.9},
          "frequency": {"value": {"timesPerDay": 3}, "confidence": 0.88},
          "withFood": {"value": true, "confidence": 0.85}
        }
    """.trimIndent()

    private fun extractorReturning(
        status: HttpStatusCode,
        body: String,
        baseUrl: String = "https://proxy.example",
        capture: (HttpRequestData) -> Unit = {},
    ): ProxyVisionExtractor {
        val engine = MockEngine { request ->
            capture(request)
            respond(
                content = body,
                status = status,
                headers = headersOf("Content-Type", "application/json"),
            )
        }
        return ProxyVisionExtractor(HttpClient(engine), baseUrl)
    }

    @Test
    fun sendsJsonPostToExtractEndpoint() = runTest {
        var captured: HttpRequestData? = null
        val extractor = extractorReturning(HttpStatusCode.OK, validPayload) { captured = it }

        extractor.extract("aGVsbG8=")

        val request = assertNotNull(captured, "request should have been sent")
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("https://proxy.example/extract", request.url.toString())
        assertEquals(ContentType.Application.Json, request.body.contentType?.withoutParameters())
        val bodyText = request.body.toByteArray().decodeToString()
        assertTrue(
            bodyText.contains("\"imageJpegBase64\"") && bodyText.contains("\"aGVsbG8=\""),
            "body should carry the base64 image under imageJpegBase64, was: $bodyText",
        )
    }

    @Test
    fun okResponseWithValidPayloadParsesToSuccess() = runTest {
        val extractor = extractorReturning(HttpStatusCode.OK, validPayload)

        val result = extractor.extract("aGVsbG8=")

        val success = assertIs<ExtractionResult.Success>(result)
        assertEquals("Amoxicillin", success.extraction.drugName.value)
    }

    @Test
    fun okResponseWithGarbageBodyIsMalformedNotThrown() = runTest {
        val extractor = extractorReturning(HttpStatusCode.OK, "not json at all {{{")

        val result = extractor.extract("aGVsbG8=")

        assertIs<ExtractionResult.Malformed>(result)
    }

    @Test
    fun serverErrorIsUnavailable() = runTest {
        val extractor = extractorReturning(HttpStatusCode.InternalServerError, """{"error":"boom"}""")

        val result = extractor.extract("aGVsbG8=")

        assertEquals(ExtractionResult.Unavailable, result)
    }

    @Test
    fun networkFailureIsUnavailableNotThrown() = runTest {
        val engine = MockEngine { throw IOException("connection reset") }
        val extractor = ProxyVisionExtractor(HttpClient(engine), "https://proxy.example")

        val result = extractor.extract("aGVsbG8=")

        assertEquals(ExtractionResult.Unavailable, result)
    }

    @Test
    fun trailingSlashBaseUrlStillHitsExtract() = runTest {
        var captured: HttpRequestData? = null
        val extractor = extractorReturning(
            HttpStatusCode.OK,
            validPayload,
            baseUrl = "https://proxy.example/",
        ) { captured = it }

        extractor.extract("aGVsbG8=")

        val request = assertNotNull(captured)
        assertEquals("https://proxy.example/extract", request.url.toString())
    }
}
