package dev.aria.memo.data.sync

import dev.aria.memo.data.AppConfig
import dev.aria.memo.data.ErrorCode
import dev.aria.memo.data.GitHubApi
import dev.aria.memo.data.MemoResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fix-D1 / Review-W #1 verification.
 *
 * Drives the [pushSingleNoteWithConflictRetry] helper that lives in
 * [dev.aria.memo.data.sync.PushWorker.kt] against a Ktor [MockEngine] that
 * orchestrates pre-canned 409 / 200 responses in the order GitHub would emit
 * them during a cross-device write storm.
 *
 * The cases below mirror the contract spelled out in the helper KDoc:
 *
 *   1. **Happy path** — first PUT returns 201; no SHA refresh, no retry.
 *   2. **One CONFLICT then success** — second PUT (with refreshed SHA) lands
 *      on 201 and the caller observes the new SHA. This is the cross-device
 *      "loser refreshes once" case the day-file path already handled and
 *      that the single-note path now also handles. (Fix-D1 core scenario.)
 *   3. **Two CONFLICTs then success** — exhausts every retry except the last;
 *      proves the retry budget really is `2`, not `1`.
 *   4. **Three CONFLICTs (all PUTs 409)** — exhausts the retry budget and
 *      surfaces [ErrorCode.CONFLICT] so the worker emits
 *      [SyncStatus.Error(CONFLICT, ...)] and the UI can route the user into
 *      conflict resolution.
 *   5. **Refresh GET fails mid-retry** — a NETWORK error during the SHA
 *      refresh propagates verbatim instead of looping forever.
 *   6. **Non-CONFLICT error first PUT** — UNAUTHORIZED is surfaced after one
 *      attempt; no refresh is performed. (Guards against accidentally
 *      retrying the wrong error class.)
 *
 * MockEngine is the same fixture pattern [GitHubOAuthClientTest] uses. We
 * keep tests pure-JVM (no Robolectric / WorkManager / Room) by exercising
 * the helper directly — see [pushSingleNoteWithConflictRetry] for the
 * per-step contract.
 */
class PushWorkerSingleNoteConflictTest {

    private val config = AppConfig(
        pat = "test_pat",
        owner = "vergil",
        repo = "memo",
        branch = "main",
        pathTemplate = "{yyyy}-{MM}-{dd}.md",
    )

    private val filePath = "notes/2026-04-24-1015-cross-device.md"
    private val initialSha = "sha-initial"
    private val refreshedShaA = "sha-after-first-refresh"
    private val refreshedShaB = "sha-after-second-refresh"
    private val landedSha = "sha-final-landed"

    private val jsonHeaders = headersOf("Content-Type" to listOf("application/json"))

    /**
     * One scripted response per HTTP call. Each entry is `(status, body)`.
     * The MockEngine handler walks this list in order; tests that need to
     * sanity-check call counts read [callLog] which records `(method, path)`
     * pairs in the same order responses were served.
     */
    private fun engineFromScript(
        script: List<Pair<HttpStatusCode, String>>,
        callLog: MutableList<Pair<HttpMethod, String>>,
    ): MockEngine = MockEngine { request ->
        callLog.add(request.method to request.url.encodedPath)
        val idx = callLog.size - 1
        if (idx >= script.size) {
            error(
                "Test script exhausted at call #${idx + 1}: " +
                    "${request.method} ${request.url.encodedPath}; " +
                    "scripted only ${script.size} responses.",
            )
        }
        val (status, body) = script[idx]
        respond(
            content = ByteReadChannel(body),
            status = status,
            headers = jsonHeaders,
        )
    }

    private fun apiWith(engine: MockEngine): GitHubApi {
        val client = HttpClient(engine) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
            expectSuccess = false
        }
        return GitHubApi(client)
    }

    private fun putBody(sha: String): String =
        """{"content":{"sha":"$sha","path":"$filePath"}}"""

    private fun getBody(sha: String): String =
        """{"sha":"$sha","content":"","encoding":"base64"}"""

    // ---------------------------------------------------------------------
    // Tests
    // ---------------------------------------------------------------------

    @Test
    fun `happy path - first PUT succeeds, no SHA refresh, no retry`() = runTest {
        val callLog = mutableListOf<Pair<HttpMethod, String>>()
        val script = listOf(
            HttpStatusCode.Created to putBody(landedSha),
        )
        val api = apiWith(engineFromScript(script, callLog))

        val result = pushSingleNoteWithConflictRetry(
            api = api,
            config = config,
            filePath = filePath,
            body = "hello world",
            title = "Hello",
            currentSha = initialSha,
        )

        assertTrue("expected Ok, got $result", result is MemoResult.Ok)
        assertEquals(landedSha, (result as MemoResult.Ok).value.content.sha)
        assertEquals(1, callLog.size)
        assertEquals(HttpMethod.Put, callLog[0].first)
    }

    @Test
    fun `one CONFLICT then success - SHA refresh + retry lands the note`() = runTest {
        val callLog = mutableListOf<Pair<HttpMethod, String>>()
        val script = listOf(
            // 1. PUT with stale sha → 409
            HttpStatusCode.Conflict to """{"message":"sha conflict"}""",
            // 2. GET to refresh sha → 200 with refreshedShaA
            HttpStatusCode.OK to getBody(refreshedShaA),
            // 3. PUT with refreshed sha → 201, lands as landedSha
            HttpStatusCode.Created to putBody(landedSha),
        )
        val api = apiWith(engineFromScript(script, callLog))

        val result = pushSingleNoteWithConflictRetry(
            api = api,
            config = config,
            filePath = filePath,
            body = "device B's edit",
            title = "Cross-device",
            currentSha = initialSha,
        )

        assertTrue("expected Ok after one refresh, got $result", result is MemoResult.Ok)
        assertEquals(
            "caller must see the SHA from the SUCCESSFUL retry, not the original",
            landedSha,
            (result as MemoResult.Ok).value.content.sha,
        )
        // Exactly 1 PUT (409) + 1 GET (refresh) + 1 PUT (success) = 3 calls.
        assertEquals(3, callLog.size)
        assertEquals(HttpMethod.Put, callLog[0].first)
        assertEquals(HttpMethod.Get, callLog[1].first)
        assertEquals(HttpMethod.Put, callLog[2].first)
    }

    @Test
    fun `two CONFLICTs then success - exhausts most retries but still lands`() = runTest {
        val callLog = mutableListOf<Pair<HttpMethod, String>>()
        val script = listOf(
            // PUT #1 → 409
            HttpStatusCode.Conflict to """{"message":"conflict 1"}""",
            // GET #1 → refreshedShaA
            HttpStatusCode.OK to getBody(refreshedShaA),
            // PUT #2 (with refreshedShaA) → 409 again (third device pushed)
            HttpStatusCode.Conflict to """{"message":"conflict 2"}""",
            // GET #2 → refreshedShaB
            HttpStatusCode.OK to getBody(refreshedShaB),
            // PUT #3 (with refreshedShaB) → 201, lands
            HttpStatusCode.Created to putBody(landedSha),
        )
        val api = apiWith(engineFromScript(script, callLog))

        val result = pushSingleNoteWithConflictRetry(
            api = api,
            config = config,
            filePath = filePath,
            body = "third try lucky",
            title = "Storm",
            currentSha = initialSha,
        )

        assertTrue("expected Ok after two refreshes, got $result", result is MemoResult.Ok)
        assertEquals(landedSha, (result as MemoResult.Ok).value.content.sha)
        // 3 PUTs (2 conflicts + 1 success) + 2 GETs (each refresh) = 5 calls.
        assertEquals(5, callLog.size)
    }

    @Test
    fun `three CONFLICTs - exhausts retry budget and surfaces CONFLICT err`() = runTest {
        val callLog = mutableListOf<Pair<HttpMethod, String>>()
        val script = listOf(
            // PUT #1 (with row's stale sha) → 409
            HttpStatusCode.Conflict to """{"message":"conflict 1"}""",
            // GET #1 → refreshedShaA
            HttpStatusCode.OK to getBody(refreshedShaA),
            // PUT #2 → 409
            HttpStatusCode.Conflict to """{"message":"conflict 2"}""",
            // GET #2 → refreshedShaB
            HttpStatusCode.OK to getBody(refreshedShaB),
            // PUT #3 → 409 (still losing)
            HttpStatusCode.Conflict to """{"message":"conflict 3"}""",
            // No further calls — the helper must give up here.
        )
        val api = apiWith(engineFromScript(script, callLog))

        val result = pushSingleNoteWithConflictRetry(
            api = api,
            config = config,
            filePath = filePath,
            body = "stuck-in-loop",
            title = "Concurrent",
            currentSha = initialSha,
        )

        assertTrue("expected Err after exhausting retries, got $result", result is MemoResult.Err)
        val err = result as MemoResult.Err
        assertEquals(
            "exhausted retry budget must surface as ErrorCode.CONFLICT so the " +
                "outer worker emits SyncStatus.Error(CONFLICT, ...) → conflict UI",
            ErrorCode.CONFLICT,
            err.code,
        )
        // Exactly 3 PUTs and 2 GETs — no fourth PUT, no third GET.
        assertEquals(5, callLog.size)
        val putCount = callLog.count { it.first == HttpMethod.Put }
        val getCount = callLog.count { it.first == HttpMethod.Get }
        assertEquals("3 PUTs (initial + 2 retries)", 3, putCount)
        assertEquals("2 SHA-refresh GETs (one per retry)", 2, getCount)
    }

    @Test
    fun `refresh GET fails mid-retry - surfaces NETWORK without further looping`() = runTest {
        val callLog = mutableListOf<Pair<HttpMethod, String>>()
        val script = listOf(
            // PUT #1 → 409
            HttpStatusCode.Conflict to """{"message":"conflict"}""",
            // GET to refresh → 500 (server error → ErrorCode.UNKNOWN). We use
            // 500 instead of network exception because MockEngine treats
            // thrown IOExceptions awkwardly; the contract is "non-Ok refresh
            // → propagate verbatim", which 5xx exercises just as well.
            HttpStatusCode.InternalServerError to """{"message":"upstream"}""",
        )
        val api = apiWith(engineFromScript(script, callLog))

        val result = pushSingleNoteWithConflictRetry(
            api = api,
            config = config,
            filePath = filePath,
            body = "refresh fails",
            title = "Refresh-fail",
            currentSha = initialSha,
        )

        assertTrue("expected Err when refresh fails, got $result", result is MemoResult.Err)
        val err = result as MemoResult.Err
        // 5xx maps to ErrorCode.UNKNOWN per GitHubApi.mapGetOrPut.
        assertEquals(ErrorCode.UNKNOWN, err.code)
        // Exactly 1 PUT (the 409) + 1 GET (the failed refresh). No second PUT.
        assertEquals(2, callLog.size)
        assertEquals(HttpMethod.Put, callLog[0].first)
        assertEquals(HttpMethod.Get, callLog[1].first)
    }

    @Test
    fun `non-CONFLICT error on first PUT propagates without refresh`() = runTest {
        val callLog = mutableListOf<Pair<HttpMethod, String>>()
        val script = listOf(
            // PUT → 401 with X-RateLimit-Remaining > 0 → ErrorCode.UNAUTHORIZED
            HttpStatusCode.Unauthorized to """{"message":"bad creds"}""",
        )
        val api = apiWith(engineFromScript(script, callLog))

        val result = pushSingleNoteWithConflictRetry(
            api = api,
            config = config,
            filePath = filePath,
            body = "auth fail",
            title = "Auth",
            currentSha = initialSha,
        )

        assertTrue("expected Err for 401, got $result", result is MemoResult.Err)
        assertEquals(ErrorCode.UNAUTHORIZED, (result as MemoResult.Err).code)
        // Critically: no SHA refresh on a non-CONFLICT error.
        assertEquals(1, callLog.size)
        assertEquals(HttpMethod.Put, callLog[0].first)
    }
}
