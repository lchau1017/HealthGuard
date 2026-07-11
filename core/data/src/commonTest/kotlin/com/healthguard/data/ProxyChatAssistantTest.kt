package com.healthguard.data

import com.healthguard.chat.ChatContext
import com.healthguard.chat.ChatResult
import com.healthguard.chat.ChatRole
import com.healthguard.chat.ChatTurn
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.datetime.TimeZone

class ProxyChatAssistantTest {

    private val context = ChatContext(
        generatedAt = Instant.parse("2025-06-18T12:00:00Z"),
        medications = emptyList(),
        weeks = emptyList(),
        events = emptyList(),
    )

    private val history = listOf(
        ChatTurn(ChatRole.USER, "hi"),
        ChatTurn(ChatRole.ASSISTANT, "hello"),
    )

    private fun assistantReturning(
        status: HttpStatusCode,
        body: String,
        baseUrl: String = "https://proxy.example",
        capture: (HttpRequestData) -> Unit = {},
    ): ProxyChatAssistant {
        val engine = MockEngine { request ->
            capture(request)
            respond(
                content = body,
                status = status,
                headers = headersOf("Content-Type", "application/json"),
            )
        }
        return ProxyChatAssistant(
            HttpClient(engine),
            baseUrl,
            ChatContextRenderer(TimeZone.of("UTC")),
        )
    }

    @Test
    fun sendsMessageHistoryAndRenderedContextToChatEndpoint() = runTest {
        var captured: HttpRequestData? = null
        val assistant = assistantReturning(HttpStatusCode.OK, """{"reply":"ok"}""") {
            captured = it
        }

        assistant.send("What's my adherence?", history, context)

        val request = assertNotNull(captured, "request should have been sent")
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("https://proxy.example/chat", request.url.toString())
        assertEquals(ContentType.Application.Json, request.body.contentType?.withoutParameters())
        val bodyText = request.body.toByteArray().decodeToString()
        assertTrue("What's my adherence?" in bodyText, "message missing: $bodyText")
        assertTrue(""""role":"user","text":"hi"""" in bodyText, "history missing: $bodyText")
        assertTrue(""""role":"assistant"""" in bodyText, "assistant turn missing: $bodyText")
        assertTrue("No medications are tracked yet." in bodyText, "context missing: $bodyText")
    }

    @Test
    fun okResponseWithReplyIsReply() = runTest {
        val assistant = assistantReturning(HttpStatusCode.OK, """{"reply":"85% overall"}""")

        val result = assistant.send("adherence?", emptyList(), context)

        assertEquals(ChatResult.Reply("85% overall"), result)
    }

    @Test
    fun okResponseWithGarbageBodyIsUnavailableNotThrown() = runTest {
        val assistant = assistantReturning(HttpStatusCode.OK, "not json {{{")

        assertEquals(ChatResult.Unavailable, assistant.send("hi", emptyList(), context))
    }

    @Test
    fun okResponseWithBlankReplyIsUnavailable() = runTest {
        val assistant = assistantReturning(HttpStatusCode.OK, """{"reply":""}""")

        assertEquals(ChatResult.Unavailable, assistant.send("hi", emptyList(), context))
    }

    @Test
    fun serverErrorIsUnavailable() = runTest {
        val assistant = assistantReturning(HttpStatusCode.BadGateway, """{"error":"upstream"}""")

        assertEquals(ChatResult.Unavailable, assistant.send("hi", emptyList(), context))
    }

    @Test
    fun networkFailureIsUnavailableNotThrown() = runTest {
        val engine = MockEngine { throw IOException("connection reset") }
        val assistant = ProxyChatAssistant(
            HttpClient(engine),
            "https://proxy.example",
            ChatContextRenderer(TimeZone.of("UTC")),
        )

        assertEquals(ChatResult.Unavailable, assistant.send("hi", emptyList(), context))
    }

    @Test
    fun trailingSlashBaseUrlStillHitsChat() = runTest {
        var captured: HttpRequestData? = null
        val assistant = assistantReturning(
            HttpStatusCode.OK,
            """{"reply":"ok"}""",
            baseUrl = "https://proxy.example/",
        ) { captured = it }

        assistant.send("hi", emptyList(), context)

        assertEquals("https://proxy.example/chat", assertNotNull(captured).url.toString())
    }
}
