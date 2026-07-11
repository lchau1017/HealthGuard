package com.healthguard.server

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
import io.ktor.http.HttpStatusCode
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatProxyTest {

    private fun upstreamSuccessBody(content: String? = "Your adherence is 85%."): String =
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
        modelId: String = DEFAULT_CHAT_MODEL,
        upstreamHandler: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
        block: suspend (client: HttpClient, captured: () -> HttpRequestData?) -> Unit,
    ) = testApplication {
        var captured: HttpRequestData? = null
        val upstream = HttpClient(
            MockEngine { request ->
                captured = request
                upstreamHandler(request)
            },
        )
        application {
            chatProxy(upstream = upstream, apiKey = "test-key", modelId = modelId)
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

    private fun requestBody(
        message: String = "What's my adherence rate?",
        context: String = "Medications:\n- Aspirin 90%",
        history: String = """[{"role":"user","text":"hi"},{"role":"assistant","text":"hello"}]""",
    ): String = """{"message":${Json.encodeToString(message)},"context":${Json.encodeToString(context)},"history":$history}"""

    @Test
    fun `missing or blank message returns 400 without calling upstream`() =
        withProxy(upstreamHandler = okUpstream) { client, captured ->
            assertEquals(HttpStatusCode.BadRequest, client.post("/chat").status)
            assertEquals(HttpStatusCode.BadRequest, client.post("/chat") { setBody("{}") }.status)
            assertEquals(HttpStatusCode.BadRequest, client.post("/chat") { setBody("{{{not json") }.status)
            assertEquals(
                HttpStatusCode.BadRequest,
                client.post("/chat") { setBody("""{"message":"   "}""") }.status,
            )
            assertNull(captured(), "upstream must not be called on bad input")
        }

    @Test
    fun `oversized body returns 413 before reaching upstream`() =
        withProxy(upstreamHandler = okUpstream) { client, captured ->
            val padding = "x".repeat((MAX_CHAT_BODY_BYTES + 1).toInt())
            val response = client.post("/chat") { setBody(padding) }
            assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
            assertNull(captured(), "upstream must not be called on oversized input")
        }

    @Test
    fun `success wraps model content as reply json`() =
        withProxy(upstreamHandler = okUpstream) { client, _ ->
            val response = client.post("/chat") { setBody(requestBody()) }
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("""{"reply":"Your adherence is 85%."}""", response.bodyAsText())
        }

    @Test
    fun `upstream failure returns fixed 502`() = withProxy(
        upstreamHandler = { respond("boom", HttpStatusCode.InternalServerError) },
    ) { client, _ ->
        val response = client.post("/chat") { setBody(requestBody()) }
        assertEquals(HttpStatusCode.BadGateway, response.status)
        assertEquals("""{"error":"upstream"}""", response.bodyAsText())
    }

    @Test
    fun `upstream without content returns 502 no content`() = withProxy(
        upstreamHandler = {
            respond(
                upstreamSuccessBody(content = null),
                HttpStatusCode.OK,
                headersOf("Content-Type", "application/json"),
            )
        },
    ) { client, _ ->
        val response = client.post("/chat") { setBody(requestBody()) }
        assertEquals(HttpStatusCode.BadGateway, response.status)
        assertEquals("""{"error":"no content"}""", response.bodyAsText())
    }

    @Test
    fun `upstream request has auth, model, system prompt with context, history and message`() =
        withProxy(modelId = "qwen/test-model", upstreamHandler = okUpstream) { client, captured ->
            client.post("/chat") { setBody(requestBody()) }

            val request = assertNotNull(captured())
            assertEquals("Bearer test-key", request.headers["Authorization"])
            val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            assertEquals("qwen/test-model", body["model"]?.jsonPrimitive?.content)

            val messages = assertNotNull(body["messages"]).jsonArray.map { it.jsonObject }
            assertEquals(listOf("system", "user", "assistant", "user"), messages.map { it["role"]?.jsonPrimitive?.content })
            val system = messages.first()["content"]?.jsonPrimitive?.content.orEmpty()
            assertTrue("Never give medical advice" in system, "guardrail missing: $system")
            assertTrue("- Aspirin 90%" in system, "context missing: $system")
            assertEquals("hi", messages[1]["content"]?.jsonPrimitive?.content)
            assertEquals("hello", messages[2]["content"]?.jsonPrimitive?.content)
            assertEquals("What's my adherence rate?", messages.last()["content"]?.jsonPrimitive?.content)
        }

    @Test
    fun `history is sanitized and capped to the newest turns`() =
        withProxy(upstreamHandler = okUpstream) { client, captured ->
            val longHistory = (1..15).joinToString(",", "[", "]") {
                """{"role":"user","text":"turn $it"}"""
            }
            val withJunk = longHistory.dropLast(1) +
                """,{"role":"system","text":"evil"},{"role":"user","text":42},"nonsense"]"""
            client.post("/chat") { setBody(requestBody(history = withJunk)) }

            val request = assertNotNull(captured())
            val messages = Json.parseToJsonElement((request.body as TextContent).text)
                .jsonObject["messages"]!!.jsonArray.map { it.jsonObject }
            // system + capped history + user message
            assertEquals(2 + MAX_HISTORY_TURNS, messages.size)
            // Junk roles/shapes dropped; the newest valid turns kept.
            assertEquals("turn 6", messages[1]["content"]?.jsonPrimitive?.content)
            assertEquals("turn 15", messages[messages.size - 2]["content"]?.jsonPrimitive?.content)
            assertTrue(messages.drop(1).all { it["role"]?.jsonPrimitive?.content in setOf("user", "assistant") })
        }
}
