package dev.aria.memo.data.oauth

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [GitHubOAuthClient] covering the four behaviours the dialog
 * depends on:
 *  1. happy path — one poll returns an access token
 *  2. `authorization_pending` → `slow_down` → success across three polls, with
 *     the poll interval bumping on the slow_down cue
 *  3. `expired_token` surfaces as [OAuthResult.Err] with [OAuthErrorKind.ExpiredToken]
 *  4. `access_denied` surfaces as [OAuthResult.Err] with [OAuthErrorKind.AccessDenied]
 *
 * MockEngine ships with Ktor so we don't need an HTTP server. The
 * [delayProvider] dependency on [GitHubOAuthClient] lets us capture requested
 * delays without actually sleeping.
 */
class GitHubOAuthClientTest {

    private val clientId = "Iv1.testClientId"
    private val deviceCode = "dev_code_xyz"
    private val jsonHeaders = headersOf("Content-Type" to listOf("application/json"))

    @Test
    fun `happy path returns access token on first poll`() = runTest {
        val engine = MockEngine { _ ->
            respond(
                content = ByteReadChannel(
                    """{"access_token":"gho_abc","token_type":"bearer","scope":"repo"}""",
                ),
                status = HttpStatusCode.OK,
                headers = jsonHeaders,
            )
        }
        val delays = mutableListOf<Long>()
        val client = GitHubOAuthClient(
            httpClient = HttpClient(engine),
            delayProvider = { delays.add(it) },
        )

        val result = client.pollAccessToken(clientId, deviceCode, interval = 5)

        assertTrue("expected Ok, got $result", result is OAuthResult.Ok)
        val ok = result as OAuthResult.Ok
        assertEquals("gho_abc", ok.value.accessToken)
        assertEquals("repo", ok.value.scope)
        assertTrue(
            "should not have slept before the success response, slept=${delays}",
            delays.isEmpty(),
        )
    }

    @Test
    fun `authorization_pending then slow_down then success with adjusted interval`() = runTest {
        val bodies = listOf(
            """{"error":"authorization_pending","error_description":"user hasn't entered code"}""",
            """{"error":"slow_down","error_description":"poll less frequently","interval":10}""",
            """{"access_token":"gho_final","token_type":"bearer","scope":"repo"}""",
        )
        var callIndex = 0
        val engine = MockEngine { _ ->
            val body = bodies[callIndex]
            callIndex += 1
            respond(
                content = ByteReadChannel(body),
                status = HttpStatusCode.OK,
                headers = jsonHeaders,
            )
        }
        val delays = mutableListOf<Long>()
        val client = GitHubOAuthClient(
            httpClient = HttpClient(engine),
            delayProvider = { delays.add(it) },
        )

        val result = client.pollAccessToken(
            clientId = clientId,
            deviceCode = deviceCode,
            interval = 5,
        )

        assertTrue("expected Ok, got $result", result is OAuthResult.Ok)
        assertEquals("gho_final", (result as OAuthResult.Ok).value.accessToken)

        // We expect exactly two sleeps: one after authorization_pending at the
        // original interval (5s), one after slow_down at the bumped interval
        // (+5s = 10s). The third poll returns the token so no further sleep.
        assertEquals(listOf(5_000L, 10_000L), delays)
        assertEquals(3, callIndex)
    }

    @Test
    fun `expired_token surfaces as Err ExpiredToken`() = runTest {
        val engine = MockEngine { _ ->
            respond(
                content = ByteReadChannel(
                    """{"error":"expired_token","error_description":"device code expired"}""",
                ),
                status = HttpStatusCode.OK,
                headers = jsonHeaders,
            )
        }
        val client = GitHubOAuthClient(
            httpClient = HttpClient(engine),
            delayProvider = { /* no-op */ },
        )

        val result = client.pollAccessToken(clientId, deviceCode, interval = 5)

        assertTrue("expected Err, got $result", result is OAuthResult.Err)
        val err = result as OAuthResult.Err
        assertEquals(OAuthErrorKind.ExpiredToken, err.kind)
    }

    @Test
    fun `access_denied surfaces as Err AccessDenied`() = runTest {
        val engine = MockEngine { _ ->
            respond(
                content = ByteReadChannel(
                    """{"error":"access_denied","error_description":"user cancelled"}""",
                ),
                status = HttpStatusCode.OK,
                headers = jsonHeaders,
            )
        }
        val client = GitHubOAuthClient(
            httpClient = HttpClient(engine),
            delayProvider = { /* no-op */ },
        )

        val result = client.pollAccessToken(clientId, deviceCode, interval = 5)

        assertTrue("expected Err, got $result", result is OAuthResult.Err)
        val err = result as OAuthResult.Err
        assertEquals(OAuthErrorKind.AccessDenied, err.kind)
    }

    @Test
    fun `requestDeviceCode decodes all fields`() = runTest {
        val engine = MockEngine { _ ->
            respond(
                content = ByteReadChannel(
                    """{"device_code":"dc","user_code":"WDJB-MJHT","verification_uri":"https://github.com/login/device","expires_in":900,"interval":5}""",
                ),
                status = HttpStatusCode.OK,
                headers = jsonHeaders,
            )
        }
        val client = GitHubOAuthClient(httpClient = HttpClient(engine))

        val result = client.requestDeviceCode(clientId)

        assertTrue(result is OAuthResult.Ok)
        val body = (result as OAuthResult.Ok).value
        assertEquals("dc", body.deviceCode)
        assertEquals("WDJB-MJHT", body.userCode)
        assertEquals("https://github.com/login/device", body.verificationUri)
        assertEquals(900, body.expiresIn)
        assertEquals(5, body.interval)
    }
}
