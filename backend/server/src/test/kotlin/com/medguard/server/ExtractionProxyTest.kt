package com.medguard.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.content.TextContent
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExtractionProxyTest {

    private val extractionJson =
        """{"drugName":{"value":"Cetirizine","confidence":0.96}}"""

    private fun upstreamSuccessBody(content: String? = extractionJson): String =
        buildJsonObject {
            putJsonArray("choices") {
                add(
                    buildJsonObject {
                        putJsonObject("message") {
                            put("role", "assistant")
                            if (content != null) put("content", content)
                        }
                    },
                )
            }
        }.toString()

    /** Boots the app with a stubbed OpenRouter and returns test client + captured request. */
    private fun withProxy(
        modelId: String = DEFAULT_MODEL,
        upstreamHandler: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
        block: suspend (client: io.ktor.client.HttpClient, captured: () -> HttpRequestData?) -> Unit,
    ) = testApplication {
        var captured: HttpRequestData? = null
        val upstream = HttpClient(
            MockEngine { request ->
                captured = request
                upstreamHandler(request)
            },
        )
        application {
            extractionProxy(upstream = upstream, apiKey = "test-key", modelId = modelId)
        }
        block(client, { captured })
    }

    private val okUpstream: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = {
        respond(
            content = upstreamSuccessBody(),
            status = HttpStatusCode.OK,
            headers = headersOf("Content-Type", "application/json"),
        )
    }

    @Test
    fun `unknown route returns 404`() = withProxy(upstreamHandler = okUpstream) { client, _ ->
        assertEquals(HttpStatusCode.NotFound, client.post("/nope").status)
    }

    @Test
    fun `missing body returns 400`() = withProxy(upstreamHandler = okUpstream) { client, captured ->
        val response = client.post("/extract")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("""{"error":"imageJpegBase64 required"}""", response.bodyAsText())
        assertNull(captured(), "upstream must not be called on bad input")
    }

    @Test
    fun `empty object and malformed json return 400`() =
        withProxy(upstreamHandler = okUpstream) { client, _ ->
            assertEquals(HttpStatusCode.BadRequest, client.post("/extract") { setBody("{}") }.status)
            assertEquals(HttpStatusCode.BadRequest, client.post("/extract") { setBody("{{{not json") }.status)
            assertEquals(HttpStatusCode.BadRequest, client.post("/extract") { setBody("""{"imageJpegBase64":42}""") }.status)
        }

    @Test
    fun `success passes model content through verbatim`() =
        withProxy(upstreamHandler = okUpstream) { client, _ ->
            val response = client.post("/extract") {
                setBody("""{"imageJpegBase64":"aGVsbG8="}""")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(extractionJson, response.bodyAsText())
            assertEquals(ContentType.Application.Json, response.contentType()?.withoutParameters())
        }

    @Test
    fun `upstream failure returns 502 upstream`() = withProxy(
        upstreamHandler = { respond("boom", HttpStatusCode.InternalServerError) },
    ) { client, _ ->
        val response = client.post("/extract") { setBody("""{"imageJpegBase64":"aGVsbG8="}""") }
        assertEquals(HttpStatusCode.BadGateway, response.status)
        assertEquals("""{"error":"upstream"}""", response.bodyAsText())
    }

    @Test
    fun `upstream without content returns 502 no content`() = withProxy(
        upstreamHandler = {
            respond(upstreamSuccessBody(content = null), HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        },
    ) { client, _ ->
        val response = client.post("/extract") { setBody("""{"imageJpegBase64":"aGVsbG8="}""") }
        assertEquals(HttpStatusCode.BadGateway, response.status)
        assertEquals("""{"error":"no content"}""", response.bodyAsText())
    }

    @Test
    fun `upstream request has auth model structured output and image`() =
        withProxy(upstreamHandler = okUpstream) { client, captured ->
            client.post("/extract") { setBody("""{"imageJpegBase64":"aGVsbG8="}""") }

            val request = assertNotNull(captured())
            assertEquals(OPENROUTER_URL, request.url.toString())
            assertEquals("Bearer test-key", request.headers["Authorization"])

            val sent = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            assertEquals(DEFAULT_MODEL, sent["model"]?.jsonPrimitive?.content)
            assertFalse("tools" in sent, "must use structured output, not tool calling")
            val responseFormat = assertNotNull(sent["response_format"]).jsonObject
            assertEquals("json_schema", responseFormat["type"]?.jsonPrimitive?.content)
            val jsonSchema = assertNotNull(responseFormat["json_schema"]).jsonObject
            assertEquals("report_extraction", jsonSchema["name"]?.jsonPrimitive?.content)
            assertEquals(true, jsonSchema["strict"]?.jsonPrimitive?.content?.toBoolean())

            val parts = sent["messages"]!!.jsonArray.first().jsonObject["content"]!!.jsonArray
            val imageUrl = parts.last().jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content
            assertTrue(imageUrl.startsWith("data:image/jpeg;base64,aGVsbG8="))
        }

    @Test
    fun `model id override is forwarded`() = withProxy(
        modelId = "qwen/other-model",
        upstreamHandler = okUpstream,
    ) { client, captured ->
        client.post("/extract") { setBody("""{"imageJpegBase64":"aGVsbG8="}""") }
        val sent = Json.parseToJsonElement((assertNotNull(captured()).body as TextContent).text).jsonObject
        assertEquals("qwen/other-model", sent["model"]?.jsonPrimitive?.content)
    }
}
