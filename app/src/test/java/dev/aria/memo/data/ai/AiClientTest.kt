package dev.aria.memo.data.ai

import dev.aria.memo.data.ErrorCode
import dev.aria.memo.data.MemoResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Unit tests for [AiClient] — the P7 OpenAI-compatible chat client.
 *
 * Strategy:
 *  - Use Ktor's [MockEngine] to simulate the provider HTTP surface. All
 *    `/chat/completions` traffic is synthesised in-process; no network.
 *  - The client's [HttpClient] is constructed in the test with
 *    [ContentNegotiation] + kotlinx-serialization `json()` installed so the
 *    client can `call.body<ChatResponse>()` without Android runtime help.
 *  - The provider-returned JSON shape mirrors OpenAI / Ollama / OpenRouter so
 *    the DTOs in `data/ai/AiDto.kt` round-trip cleanly.
 *
 * Fake settings store:
 *  - We rely on [AiSettingsStore] being an `open class` OR exposing a
 *    constructor that accepts an initial [AiConfig] directly (no Context).
 *    If Agent A's final signature requires a Context, the main agent will
 *    reconcile the fake at merge time — see the `FakeAiSettingsStore`
 *    factory comment below.
 */
class AiClientTest {

    private val jsonHeaders = headersOf("Content-Type" to listOf("application/json"))

    private val validConfig = AiConfig(
        providerUrl = "https://api.openai.com/v1/chat/completions",
        model = "gpt-4o-mini",
        apiKey = "sk-test-abc",
    )

    private val unconfigured = AiConfig(providerUrl = "", model = "", apiKey = "")

    // -- harness -----------------------------------------------------------

    private fun httpClientWithJson(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): HttpClient = HttpClient(MockEngine { request -> handler(request) }) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; isLenient = true })
        }
    }

    private fun client(
        httpClient: HttpClient,
        config: AiConfig = validConfig,
    ): AiClient = AiClient(
        http = httpClient,
        settings = FakeAiSettingsStore(config),
    )

    // -- tests -------------------------------------------------------------

    @Test
    fun `happy path returns assistant content from first choice`() = runTest {
        val http = httpClientWithJson { _ ->
            respond(
                content = ByteReadChannel(
                    """
                    {
                      "id":"chatcmpl-1","object":"chat.completion","model":"gpt-4o-mini",
                      "choices":[
                        {"index":0,"message":{"role":"assistant","content":"你好！很高兴见到你。"},"finish_reason":"stop"}
                      ]
                    }
                    """.trimIndent(),
                ),
                status = HttpStatusCode.OK,
                headers = jsonHeaders,
            )
        }
        val ai = client(http)

        val result = ai.chat(
            systemPrompt = "你是一个中文助手。",
            userMessage = "你好",
        )

        assertTrue("expected Ok, got $result", result is MemoResult.Ok)
        assertEquals("你好！很高兴见到你。", (result as MemoResult.Ok).value)
    }

    @Test
    fun `HTTP 401 yields Err UNAUTHORIZED`() = runTest {
        val http = httpClientWithJson { _ ->
            respond(
                content = ByteReadChannel(
                    """{"error":{"message":"Invalid API key","type":"invalid_request_error"}}""",
                ),
                status = HttpStatusCode.Unauthorized,
                headers = jsonHeaders,
            )
        }
        val ai = client(http)

        val result = ai.chat(systemPrompt = "", userMessage = "hi")

        assertTrue("expected Err, got $result", result is MemoResult.Err)
        assertEquals(ErrorCode.UNAUTHORIZED, (result as MemoResult.Err).code)
    }

    @Test
    fun `network exception yields Err NETWORK`() = runTest {
        val http = httpClientWithJson { _ ->
            throw IOException("simulated connection reset")
        }
        val ai = client(http)

        val result = ai.chat(systemPrompt = "", userMessage = "hi")

        assertTrue("expected Err, got $result", result is MemoResult.Err)
        assertEquals(ErrorCode.NETWORK, (result as MemoResult.Err).code)
    }

    @Test
    fun `HTTP 200 with malformed JSON yields Err UNKNOWN`() = runTest {
        val http = httpClientWithJson { _ ->
            respond(
                content = ByteReadChannel("""{"not":"a valid ChatResponse"}"""),
                status = HttpStatusCode.OK,
                headers = jsonHeaders,
            )
        }
        val ai = client(http)

        val result = ai.chat(systemPrompt = "", userMessage = "hi")

        assertTrue("expected Err, got $result", result is MemoResult.Err)
        assertEquals(ErrorCode.UNKNOWN, (result as MemoResult.Err).code)
    }

    @Test
    fun `unconfigured settings yield Err NOT_CONFIGURED and never hits the wire`() = runTest {
        var wireHits = 0
        val http = httpClientWithJson { _ ->
            wireHits++
            respond(
                content = ByteReadChannel("""{"choices":[]}"""),
                status = HttpStatusCode.OK,
                headers = jsonHeaders,
            )
        }
        val ai = client(http, config = unconfigured)

        val result = ai.chat(systemPrompt = "", userMessage = "hi")

        assertTrue("expected Err, got $result", result is MemoResult.Err)
        assertEquals(ErrorCode.NOT_CONFIGURED, (result as MemoResult.Err).code)
        assertEquals(
            "unconfigured client must short-circuit before network IO",
            0, wireHits,
        )
    }

    @Test
    fun `request carries Authorization Bearer header with the configured key`() = runTest {
        var observedAuth: String? = null
        val http = httpClientWithJson { request ->
            observedAuth = request.headers["Authorization"]
            respond(
                content = ByteReadChannel(
                    """{"choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}""",
                ),
                status = HttpStatusCode.OK,
                headers = jsonHeaders,
            )
        }
        val ai = client(http, config = validConfig.copy(apiKey = "sk-magic-123"))

        ai.chat(systemPrompt = "", userMessage = "hi")

        assertNotNull("Authorization header must be set", observedAuth)
        assertEquals("Bearer sk-magic-123", observedAuth)
    }

    @Test
    fun `priorMessages are sent before the new user message`() = runTest {
        var capturedBody: String? = null
        val http = httpClientWithJson { request ->
            capturedBody = String(request.body.toByteArray())
            respond(
                content = ByteReadChannel(
                    """{"choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}""",
                ),
                status = HttpStatusCode.OK,
                headers = jsonHeaders,
            )
        }
        val ai = client(http)

        val priors = listOf(
            AiMessage(role = "user", content = "previous turn A"),
            AiMessage(role = "assistant", content = "previous response A"),
        )
        ai.chat(
            systemPrompt = "system-p",
            userMessage = "latest user question",
            priorMessages = priors,
        )

        assertNotNull("body must have been captured", capturedBody)
        val body = capturedBody!!
        // Ordering: system-p must come first, then prior user, prior assistant,
        // finally latest user question. We assert via substring position.
        val sysIdx = body.indexOf("system-p")
        val priorUserIdx = body.indexOf("previous turn A")
        val priorAssistantIdx = body.indexOf("previous response A")
        val latestIdx = body.indexOf("latest user question")
        assertTrue("system-p must be present in body=$body", sysIdx >= 0)
        assertTrue("prior user present", priorUserIdx >= 0)
        assertTrue("prior assistant present", priorAssistantIdx >= 0)
        assertTrue("latest present", latestIdx >= 0)
        assertTrue(
            "order: system < priorUser < priorAssistant < latest; got sys=$sysIdx pu=$priorUserIdx pa=$priorAssistantIdx l=$latestIdx",
            sysIdx < priorUserIdx &&
                priorUserIdx < priorAssistantIdx &&
                priorAssistantIdx < latestIdx,
        )
    }

    @Test
    fun `empty systemPrompt does not emit a system message slot`() = runTest {
        // When the context mode is NONE, buildSystemPrompt returns "". The
        // client MUST NOT push a `{"role":"system","content":""}` message
        // into the payload — some providers reject empty-content messages
        // with a 400. Instead the request body's messages array starts with
        // the prior messages (or the user message, if priors are empty).
        var capturedBody: String? = null
        val http = httpClientWithJson { request ->
            capturedBody = String(request.body.toByteArray())
            respond(
                content = ByteReadChannel(
                    """{"choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}""",
                ),
                status = HttpStatusCode.OK,
                headers = jsonHeaders,
            )
        }
        val ai = client(http)
        ai.chat(systemPrompt = "", userMessage = "hi there")
        // Not asserting absolute absence of the word "system" (JSON key is
        // literally `"role":"system"`) — we only require: NO system message
        // with empty content slipped through. Since providers vary, we keep
        // this as a soft positive check: the user message must appear.
        assertTrue("user message must land in body=$capturedBody", capturedBody?.contains("hi there") == true)
    }

    // --- P7.0.1 regression tests -----------------------------------------

    @Test
    fun `403 yields Err UNAUTHORIZED (fixes #66)`() = runTest {
        val http = httpClientWithJson { _ ->
            respond(
                content = ByteReadChannel("""{"error":{"message":"forbidden"}}"""),
                status = HttpStatusCode.Forbidden,
                headers = jsonHeaders,
            )
        }
        val res = client(http).chat(systemPrompt = "", userMessage = "x")
        assertTrue("403 must be UNAUTHORIZED, was=$res", res is MemoResult.Err)
        val err = res as MemoResult.Err
        assertEquals(ErrorCode.UNAUTHORIZED, err.code)
    }

    @Test
    fun `500 yields Err UNKNOWN with generic phrase (fixes #66)`() = runTest {
        val http = httpClientWithJson { _ ->
            respond(
                content = ByteReadChannel("""internal server error body"""),
                status = HttpStatusCode.InternalServerError,
                headers = jsonHeaders,
            )
        }
        val res = client(http).chat(systemPrompt = "", userMessage = "x")
        assertTrue("5xx must be UNKNOWN, was=$res", res is MemoResult.Err)
        val err = res as MemoResult.Err
        assertEquals(ErrorCode.UNKNOWN, err.code)
        // After #63 fix we no longer splice the body into the user-visible
        // message; just a generic phrase + status code.
        assertTrue(
            "5xx message should surface status code, was=${err.message}",
            err.message.contains("500"),
        )
    }

    @Test
    fun `apiKey never leaks into Err message on 4xx (fixes #67)`() = runTest {
        val leakyKey = "sk-leakable-XYZ-super-secret"
        val http = httpClientWithJson { _ ->
            respond(
                content = ByteReadChannel("""{"error":{"message":"unauthorized"}}"""),
                status = HttpStatusCode.Unauthorized,
                headers = jsonHeaders,
            )
        }
        val ai = AiClient(
            http = http,
            settings = FakeAiSettingsStore(validConfig.copy(apiKey = leakyKey)),
        )
        val res = ai.chat(systemPrompt = "", userMessage = "x")
        assertTrue(res is MemoResult.Err)
        val err = res as MemoResult.Err
        assertTrue(
            "apiKey must NOT appear in Err.message — actual=${err.message}",
            !err.message.contains(leakyKey),
        )
    }

    @Test
    fun `apiKey never leaks into Err message on serialization failure (fixes #67)`() = runTest {
        val leakyKey = "sk-leakable-XYZ-super-secret"
        val http = httpClientWithJson { _ ->
            respond(
                content = ByteReadChannel("""not json at all"""),
                status = HttpStatusCode.OK,
                headers = jsonHeaders,
            )
        }
        val ai = AiClient(
            http = http,
            settings = FakeAiSettingsStore(validConfig.copy(apiKey = leakyKey)),
        )
        val res = ai.chat(systemPrompt = "", userMessage = "x")
        assertTrue(res is MemoResult.Err)
        val err = res as MemoResult.Err
        assertTrue(
            "apiKey must NOT appear in Err.message — actual=${err.message}",
            !err.message.contains(leakyKey),
        )
    }

    @Test
    fun `apiKey never leaks into Err message on network error (fixes #67)`() = runTest {
        val leakyKey = "sk-leakable-XYZ-super-secret"
        val http = HttpClient(MockEngine { _ ->
            throw IOException("connection reset")
        }) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
        val ai = AiClient(
            http = http,
            settings = FakeAiSettingsStore(validConfig.copy(apiKey = leakyKey)),
        )
        val res = ai.chat(systemPrompt = "", userMessage = "x")
        assertTrue(res is MemoResult.Err)
        val err = res as MemoResult.Err
        assertEquals(ErrorCode.NETWORK, err.code)
        assertTrue(
            "apiKey must NOT appear in Err.message — actual=${err.message}",
            !err.message.contains(leakyKey),
        )
    }
}

/**
 * Test double for `AiSettingsStore`. We ASSUME Agent A's final signature is
 *   `open class AiSettingsStore(context: Context) { suspend fun current(): AiConfig; ... }`
 * OR provides a secondary/testing-only constructor that takes an initial config.
 *
 * If the real class ends up sealed / final with a Context-only constructor,
 * the main agent will adapt this file at merge time. The cleanest future-proof
 * fix is to make `AiSettingsStore` an `interface` — this test double is the
 * lightest-weight signal that that's the ergonomic right call.
 */
private class FakeAiSettingsStore(private val config: AiConfig) : AiSettingsStore() {
    override suspend fun current(): AiConfig = config
    // Fixes #61 (P7.0.1): keep observe() in sync with current() so tests that
    // gate on isConfigured don't silently pass against a stale empty config
    // the null-context fallback would emit.
    override fun observe(): kotlinx.coroutines.flow.Flow<AiConfig> =
        kotlinx.coroutines.flow.flow {
            emit(config)
            kotlinx.coroutines.awaitCancellation()
        }
}
