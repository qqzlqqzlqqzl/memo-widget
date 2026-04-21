package dev.aria.memo.data.oauth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * DTOs for the GitHub OAuth **device flow** (the same flow `gh auth login` uses).
 *
 * Spec: https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/authorizing-oauth-apps#device-flow
 *
 * The flow has two JSON endpoints, each with a "success" shape and a shared
 * error shape:
 *   1. POST `/login/device/code` → [DeviceCodeResponse] on success.
 *   2. POST `/login/oauth/access_token` → [AccessTokenResponse] on success or
 *      [OAuthErrorResponse] when GitHub reports `authorization_pending`,
 *      `slow_down`, `expired_token`, `access_denied`, etc.
 *
 * GitHub returns a 200 OK even for the error cases at step 2, distinguished by
 * the presence of an `error` field instead of `access_token` — so every poll
 * response must be peeked before trying either shape. We keep both classes
 * tolerant (`ignoreUnknownKeys` is set on the shared Json config via Ktor).
 */

/** Response from `POST /login/device/code`. */
@Serializable
data class DeviceCodeResponse(
    @SerialName("device_code") val deviceCode: String,
    @SerialName("user_code") val userCode: String,
    @SerialName("verification_uri") val verificationUri: String,
    @SerialName("expires_in") val expiresIn: Int,
    val interval: Int,
)

/** Success response from `POST /login/oauth/access_token`. */
@Serializable
data class AccessTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String = "bearer",
    val scope: String = "",
)

/**
 * Error response from either device-flow endpoint.
 *
 * Polling returns `authorization_pending` until the user authorises, then one
 * of: `slow_down` (bump the interval), `expired_token` (terminal), or
 * `access_denied` (terminal — user clicked "cancel").
 */
@Serializable
data class OAuthErrorResponse(
    val error: String,
    @SerialName("error_description") val errorDescription: String = "",
    @SerialName("error_uri") val errorUri: String = "",
)
