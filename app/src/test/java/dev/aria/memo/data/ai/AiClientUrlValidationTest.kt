package dev.aria.memo.data.ai

import dev.aria.memo.data.ErrorCode
import dev.aria.memo.data.MemoResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sec-1 caveat regression: the bearer token must never travel on plain HTTP.
 *
 * [AiClient.chat] guards [AiConfig.providerUrl] at the entry point so that a
 * mistyped `http://...` URL — including the saved "测试连接" path — never
 * reaches the wire. Three black-box scenarios pin the contract:
 *
 *  1. https://… is accepted (no false positive),
 *  2. http://… is rejected before any network IO,
 *  3. an empty URL is rejected (delegated to the existing NOT_CONFIGURED
 *     short-circuit; verified here to make sure adding the new check did not
 *     regress the empty-url path).
 *
 * The "wireHits" counter on the MockEngine is the load-bearing assertion:
 * any number > 0 on the rejection paths would mean the apiKey already left
 * the device on a cleartext HTTP request — defeating the entire point.
 */
class AiClientUrlValidationTest {

    private val jsonHeaders = headersOf("Content-Type" to listOf("application/json"))

    private fun httpClientCounting(hits: IntArray): HttpClient = HttpClient(
        MockEngine { _ ->
            hits[0]++
            respond(
                content = ByteReadChannel(
                    """{"choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}""",
                ),
                status = HttpStatusCode.OK,
                headers = jsonHeaders,
            )
        },
    ) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    private fun clientWith(config: AiConfig, hits: IntArray = intArrayOf(0)): Pair<AiClient, IntArray> {
        val http = httpClientCounting(hits)
        return AiClient(http = http, settings = FakeStore(config)) to hits
    }

    @Test
    fun `https url is accepted and reaches the wire`() = runTest {
        val (ai, hits) = clientWith(
            AiConfig(
                providerUrl = "https://api.openai.com/v1/chat/completions",
                model = "gpt-4o-mini",
                apiKey = "sk-test",
            ),
        )

        val result = ai.chat(systemPrompt = "", userMessage = "ping")

        // Reaches the wire and parses the canned 200 response → Ok.
        assertTrue("expected Ok for https://, got $result", result is MemoResult.Ok)
        assertEquals(
            "https URL must reach the engine exactly once",
            1, hits[0],
        )
    }

    @Test
    fun `http url is rejected before any network IO`() = runTest {
        val (ai, hits) = clientWith(
            AiConfig(
                providerUrl = "http://api.openai.com/v1/chat/completions",
                model = "gpt-4o-mini",
                apiKey = "sk-leak-on-cleartext",
            ),
        )

        val result = ai.chat(systemPrompt = "", userMessage = "ping")

        assertTrue("expected Err for http://, got $result", result is MemoResult.Err)
        val err = result as MemoResult.Err
        assertEquals(
            "http URL must short-circuit before any HTTP request — wireHits=${hits[0]}",
            0, hits[0],
        )
        // The user-visible message must mention the https:// requirement so
        // "测试连接" surfaces actionable feedback. Status-code based mapping
        // categorises this as UNKNOWN (client-side validation failure mirrors
        // the bucket already used for malformed JSON / unmapped 4xx).
        assertEquals(ErrorCode.UNKNOWN, err.code)
        assertTrue(
            "Err.message must mention https://, was=${err.message}",
            err.message.contains("https://"),
        )
        // And critically: the apiKey must NOT appear in the surfaced message
        // (the same Sec-1 leak surface this fix is designed to close).
        assertTrue(
            "apiKey must NOT appear in Err.message — actual=${err.message}",
            !err.message.contains("sk-leak-on-cleartext"),
        )
    }

    @Test
    fun `empty url is rejected before any network IO`() = runTest {
        val (ai, hits) = clientWith(
            AiConfig(
                providerUrl = "",
                model = "gpt-4o-mini",
                apiKey = "sk-test",
            ),
        )

        val result = ai.chat(systemPrompt = "", userMessage = "ping")

        assertTrue("expected Err for empty url, got $result", result is MemoResult.Err)
        val err = result as MemoResult.Err
        assertEquals(
            "empty URL must short-circuit before any HTTP request — wireHits=${hits[0]}",
            0, hits[0],
        )
        // Empty URL flunks isConfigured first and stays on the existing
        // NOT_CONFIGURED branch; the new https:// check only kicks in once
        // every field is filled in. Asserting the code here makes sure the
        // new guard didn't accidentally swallow this earlier branch.
        assertEquals(ErrorCode.NOT_CONFIGURED, err.code)
    }

    private class FakeStore(private val config: AiConfig) : AiSettingsStore() {
        override suspend fun current(): AiConfig = config
        override fun observe(): Flow<AiConfig> = flow {
            emit(config)
            awaitCancellation()
        }
    }
}
