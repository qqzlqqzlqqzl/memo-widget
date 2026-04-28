package dev.aria.memo.data.oauth

import io.ktor.client.HttpClient
import io.ktor.client.request.accept
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException

/**
 * Thin wrapper around GitHub's OAuth **device flow** endpoints.
 *
 * Two calls make the whole flow:
 *  - [requestDeviceCode] — ask GitHub for a `device_code` + `user_code`. The
 *    user types `user_code` into `https://github.com/login/device` in a
 *    browser.
 *  - [pollAccessToken] — poll GitHub every `interval` seconds with the device
 *    code until the user authorises (success), denies, or the code expires.
 *
 * Error handling parallels [dev.aria.memo.data.GitHubApi]: no exceptions cross
 * the API; every failure becomes an [OAuthResult.Err]. This keeps the calling
 * ViewModel free of try/catch and lets the UI render a state-machine cleanly.
 *
 * We parse responses manually instead of relying on ContentNegotiation because
 * both endpoints return a 200 OK even when they carry an `error` payload — the
 * only way to tell "success" from "please wait" is to peek the JSON body.
 */
class GitHubOAuthClient(
    private val httpClient: HttpClient,
    private val delayProvider: suspend (Long) -> Unit = { delay(it) },
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /**
     * Ask GitHub to mint a fresh `device_code`. The `scope=repo` grant is all
     * we need for the Contents API (same ceiling as a fine-grained PAT with
     * "Contents: read+write" on a single repo).
     */
    suspend fun requestDeviceCode(clientId: String): OAuthResult<DeviceCodeResponse> {
        if (clientId.isBlank()) {
            return OAuthResult.Err(OAuthErrorKind.BadClientId, "client_id is blank")
        }
        return runCatchingHttp {
            val response = httpClient.post(DEVICE_CODE_URL) {
                accept(ContentType.Application.Json)
                contentType(ContentType.Application.FormUrlEncoded)
                // Bug-1 M3 fix (#116): URL-encode form fields. clientId / scope
                // 含特殊字符 (如 base64 padding `=` / 空格 / `+`) 时拼字符串会让
                // GitHub 解错。java.net.URLEncoder.encode 用 application/x-www-form-urlencoded
                // 编码空格→`+`、保留字符→%hex,正是 FormUrlEncoded 期望的格式。
                setBody("client_id=${urlEncode(clientId)}&scope=${urlEncode(SCOPE)}")
            }
            val body = response.bodyAsText()
            if (!response.status.value.isSuccess()) {
                return@runCatchingHttp OAuthResult.Err(
                    OAuthErrorKind.Network,
                    "device/code HTTP ${response.status.value}: ${body.take(200)}",
                )
            }
            val root = parseJsonObjectOrNull(body)
                ?: return@runCatchingHttp OAuthResult.Err(
                    OAuthErrorKind.Malformed,
                    "device/code body was not a JSON object",
                )
            val err = root.readString("error")
            if (err != null) {
                return@runCatchingHttp OAuthResult.Err(
                    OAuthErrorKind.BadClientId,
                    "$err: ${root.readString("error_description").orEmpty()}",
                )
            }
            runCatching {
                JSON.decodeFromString(DeviceCodeResponse.serializer(), body)
            }.fold(
                onSuccess = { OAuthResult.Ok(it) },
                onFailure = {
                    OAuthResult.Err(
                        OAuthErrorKind.Malformed,
                        "device/code decode failed: ${it.message}",
                    )
                },
            )
        }
    }

    /**
     * Poll `/login/oauth/access_token` until the user authorises (returns the
     * token) or the flow fails terminally. `authorization_pending` triggers a
     * sleep of `interval`; `slow_down` bumps the interval by +5s (per spec).
     *
     * This suspends until one of the terminal states fires, or [expiresIn]
     * elapses, which maps to [OAuthErrorKind.ExpiredToken].
     */
    suspend fun pollAccessToken(
        clientId: String,
        deviceCode: String,
        interval: Int,
        expiresIn: Int = DEFAULT_EXPIRES_IN_SECONDS,
    ): OAuthResult<AccessTokenResponse> {
        if (clientId.isBlank()) {
            return OAuthResult.Err(OAuthErrorKind.BadClientId, "client_id is blank")
        }
        var currentInterval = interval.coerceAtLeast(MIN_INTERVAL_SECONDS)
        val deadline = clock() + expiresIn.coerceAtLeast(1) * 1000L

        while (clock() < deadline) {
            val pollResult = singlePoll(clientId, deviceCode)
            when (pollResult) {
                is OAuthResult.Ok -> return pollResult
                is OAuthResult.Err -> when (pollResult.kind) {
                    OAuthErrorKind.AuthorizationPending -> {
                        delayProvider(currentInterval * 1000L)
                    }
                    OAuthErrorKind.SlowDown -> {
                        // Spec: bump interval by 5s when GitHub says slow_down.
                        currentInterval += SLOW_DOWN_BUMP_SECONDS
                        delayProvider(currentInterval * 1000L)
                    }
                    else -> return pollResult
                }
            }
        }
        return OAuthResult.Err(
            OAuthErrorKind.ExpiredToken,
            "device_code expired before user authorised",
        )
    }

    /** One HTTP round-trip to the access-token endpoint. Never throws. */
    private suspend fun singlePoll(
        clientId: String,
        deviceCode: String,
    ): OAuthResult<AccessTokenResponse> = runCatchingHttp {
        val response: HttpResponse = httpClient.post(ACCESS_TOKEN_URL) {
            accept(ContentType.Application.Json)
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(
                "client_id=${urlEncode(clientId)}" +
                    "&device_code=${urlEncode(deviceCode)}" +
                    "&grant_type=${urlEncode(GRANT_TYPE)}",
            )
        }
        val body = response.bodyAsText()
        if (!response.status.value.isSuccess()) {
            return@runCatchingHttp OAuthResult.Err(
                OAuthErrorKind.Network,
                "access_token HTTP ${response.status.value}: ${body.take(200)}",
            )
        }
        val root = parseJsonObjectOrNull(body)
            ?: return@runCatchingHttp OAuthResult.Err(
                OAuthErrorKind.Malformed,
                "access_token body was not a JSON object",
            )
        val err = root.readString("error")
        if (err != null) {
            val desc = root.readString("error_description").orEmpty()
            return@runCatchingHttp OAuthResult.Err(errorKindFor(err), if (desc.isBlank()) err else desc)
        }
        runCatching {
            JSON.decodeFromString(AccessTokenResponse.serializer(), body)
        }.fold(
            onSuccess = { OAuthResult.Ok(it) },
            onFailure = {
                OAuthResult.Err(
                    OAuthErrorKind.Malformed,
                    "access_token decode failed: ${it.message}",
                )
            },
        )
    }

    // ---- helpers ---------------------------------------------------------

    private fun Int.isSuccess(): Boolean = this in 200..299

    private fun parseJsonObjectOrNull(raw: String): JsonObject? = runCatching {
        JSON.parseToJsonElement(raw) as? JsonObject
    }.getOrNull()

    private fun JsonObject.readString(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull

    private fun errorKindFor(error: String): OAuthErrorKind = when (error) {
        "authorization_pending" -> OAuthErrorKind.AuthorizationPending
        "slow_down" -> OAuthErrorKind.SlowDown
        "expired_token" -> OAuthErrorKind.ExpiredToken
        "access_denied" -> OAuthErrorKind.AccessDenied
        "unsupported_grant_type", "incorrect_client_credentials" -> OAuthErrorKind.BadClientId
        "incorrect_device_code", "device_flow_disabled" -> OAuthErrorKind.BadRequest
        else -> OAuthErrorKind.Unknown
    }

    private suspend inline fun <T> runCatchingHttp(
        block: () -> OAuthResult<T>,
    ): OAuthResult<T> = try {
        block()
    } catch (e: IOException) {
        OAuthResult.Err(OAuthErrorKind.Network, e.message ?: "network error")
    } catch (e: Throwable) {
        // kotlinx.coroutines cancellation must propagate.
        if (e is kotlinx.coroutines.CancellationException) throw e
        val cause = generateSequence<Throwable>(e) { it.cause }.firstOrNull { it is IOException }
        if (cause != null) {
            OAuthResult.Err(OAuthErrorKind.Network, cause.message ?: "network error")
        } else {
            OAuthResult.Err(OAuthErrorKind.Unknown, e::class.simpleName + ": " + (e.message ?: ""))
        }
    }

    private companion object {
        const val DEVICE_CODE_URL = "https://github.com/login/device/code"
        const val ACCESS_TOKEN_URL = "https://github.com/login/oauth/access_token"
        const val GRANT_TYPE = "urn:ietf:params:oauth:grant-type:device_code"
        const val SCOPE = "repo"

        /** Bug-1 M3 fix (#116): URLEncoder.encode 用 UTF-8 + form-urlencoded 规则。 */
        fun urlEncode(s: String): String =
            java.net.URLEncoder.encode(s, "UTF-8")
        const val MIN_INTERVAL_SECONDS = 1
        const val SLOW_DOWN_BUMP_SECONDS = 5
        const val DEFAULT_EXPIRES_IN_SECONDS = 900

        val JSON: Json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            explicitNulls = false
        }
    }
}

/** Companion result type — mirrors [dev.aria.memo.data.MemoResult] in spirit. */
sealed class OAuthResult<out T> {
    data class Ok<T>(val value: T) : OAuthResult<T>()
    data class Err(val kind: OAuthErrorKind, val message: String) : OAuthResult<Nothing>()
}

/**
 * Classification of device-flow failures.
 *
 * [AuthorizationPending] and [SlowDown] are *internal* to `pollAccessToken`
 * and never leak out — they drive the poll loop. Callers will only see the
 * terminal kinds.
 */
enum class OAuthErrorKind {
    AuthorizationPending,
    SlowDown,
    ExpiredToken,
    AccessDenied,
    BadClientId,
    BadRequest,
    Network,
    Malformed,
    Unknown,
}
