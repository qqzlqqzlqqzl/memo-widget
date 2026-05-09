package dev.aria.memo.data.ai

import android.util.Log
import dev.aria.memo.data.ErrorCode
import dev.aria.memo.data.MemoResult
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.SerializationException
import java.io.IOException
import java.net.URI

/**
 * Non-streaming client for any OpenAI-compatible chat-completions endpoint.
 *
 * Contract (mirrors [dev.aria.memo.data.GitHubApi]):
 *  - Never throws; every failure is a [MemoResult.Err] with a mapped
 *    [ErrorCode]:
 *      * HTTP 401/403                          → [ErrorCode.UNAUTHORIZED]
 *      * IO / timeout / connection reset       → [ErrorCode.NETWORK]
 *      * Settings store empty (url/model/key)  → [ErrorCode.NOT_CONFIGURED]
 *      * JSON decode, empty choices, other 4xx/5xx → [ErrorCode.UNKNOWN]
 *  - Never logs, prints or surfaces the api key in messages. The bearer token
 *    only appears on the outgoing `Authorization` header.
 *
 * The shared [HttpClient] comes from [dev.aria.memo.data.ServiceLocator]; this
 * class does not instantiate its own engine. That's deliberate — we piggy-back
 * on the already-configured CIO timeouts (30s request / 15s connect) and JSON
 * content negotiation, keeping AI requests within the same budget as GitHub
 * traffic so a hung provider can't freeze a Worker.
 */
class AiClient(
    private val http: HttpClient,
    private val settings: AiSettingsStore,
) {

    /**
     * Send a single chat turn.
     *
     * [systemPrompt] may be empty (pure chat — "ping"); it's emitted as a
     * leading `system` message only when non-blank. [priorMessages] preserves
     * conversation history in whatever order the caller supplies; [userMessage]
     * is appended as the final `user` message.
     *
     * Returns the assistant reply content on success, or an [ErrorCode]-tagged
     * error. Empty `choices` → [ErrorCode.UNKNOWN] with an explanatory message
     * so callers don't misread it as "network fine, no reply".
     */
    suspend fun chat(
        systemPrompt: String,
        userMessage: String,
        priorMessages: List<AiMessage> = emptyList(),
    ): MemoResult<String> {
        val config = settings.current()
        if (!config.isConfigured) {
            return MemoResult.Err(ErrorCode.NOT_CONFIGURED, "AI 尚未配置")
        }
        // Fix-A2 (Sec-1 caveat) + #137: refuse plain HTTP to avoid
        // Bearer apiKey leakage on the wire — but allow loopback URLs
        // (`http://localhost` / `http://127.0.0.1` / `http://[::1]`) so
        // users running a local Ollama / vLLM provider on the same device
        // don't need TLS. RFC1918 / link-local http is blocked (SSRF +
        // cleartext key risk). userInfo injection is also rejected.
        val urlCheck = checkProviderUrl(config.providerUrl)
        if (urlCheck != UrlCheckResult.ALLOWED) {
            val message = when (urlCheck) {
                UrlCheckResult.REJECTED_PRIVATE_HTTP ->
                    "AI provider URL 不允许使用 http:// 连接局域网地址（如 ${config.providerUrl.take(30)}）。" +
                        "如需访问本机 Ollama，请改用 http://localhost:<端口>/v1；" +
                        "如需访问局域网服务，请使用 https:// 或在本机做端口转发。"
                UrlCheckResult.REJECTED_USERINFO ->
                    "AI provider URL 包含用户信息（@ 符号），不允许此格式，请使用 https:// 或纯 http://localhost。"
                UrlCheckResult.REJECTED_MALFORMED ->
                    "AI provider URL 格式无效，无法解析，请检查地址填写是否正确。"
                else ->
                    "AI provider URL 必须以 https:// 开头（当前: ${config.providerUrl.take(20)}）"
            }
            return MemoResult.Err(ErrorCode.UNKNOWN, message)
        }

        val messages = buildList {
            if (systemPrompt.isNotBlank()) {
                add(AiMessage(role = "system", content = systemPrompt))
            }
            addAll(priorMessages)
            add(AiMessage(role = "user", content = userMessage))
        }
        val request = ChatRequest(model = config.model, messages = messages, stream = false)

        return runCatchingHttp {
            val response: HttpResponse = http.post(config.providerUrl) {
                // Auth + content headers — api key lives only on this header,
                // never logged. `expectSuccess = false` on the shared client
                // means Ktor won't throw for 4xx/5xx; we map them ourselves.
                header("Authorization", "Bearer ${config.apiKey}")
                header("Accept", ContentType.Application.Json.toString())
                contentType(ContentType.Application.Json)
                setBody(request)
            }
            when (response.status.value) {
                in 200..299 -> decodeChat(response)
                401, 403 -> MemoResult.Err(ErrorCode.UNAUTHORIZED, "AI 鉴权失败（检查 API Key）")
                in 500..599 -> MemoResult.Err(
                    ErrorCode.UNKNOWN,
                    "AI 服务不可用（${response.status.value}），请稍后重试",
                )
                else -> MemoResult.Err(
                    ErrorCode.UNKNOWN,
                    // Fixes #63 (P7.0.1): don't splice the provider body into
                    // the user-visible error message. Some providers echo the
                    // user's prompt (→ the user's own note content) back in
                    // error.message; that body would then travel to snackbar /
                    // UI and could leak into screenshots. Keep only the HTTP
                    // status code and a generic phrase. For debugging, drop
                    // the raw body to logcat under a DEBUG guard (reserved —
                    // BuildConfig.DEBUG is available but left off by default
                    // to avoid any accidental body surface).
                    "AI 请求失败（HTTP ${response.status.value}）",
                )
            }
        }
    }

    private suspend fun decodeChat(response: HttpResponse): MemoResult<String> = try {
        val body: ChatResponse = response.body()
        val content = body.choices.firstOrNull()?.message?.content
        if (content.isNullOrBlank()) {
            MemoResult.Err(ErrorCode.UNKNOWN, "AI 返回为空")
        } else {
            MemoResult.Ok(content)
        }
    } catch (_: SerializationException) {
        MemoResult.Err(ErrorCode.UNKNOWN, "AI 响应格式不符合 OpenAI 规范")
    }

    private inline fun <T> runCatchingHttp(block: () -> MemoResult<T>): MemoResult<T> = try {
        block()
    } catch (e: IOException) {
        // Don't surface raw e.message to users: OkHttp/CIO wrap low-level
        // network failures with technical strings ("Connection refused",
        // "Software caused connection abort", "Trust anchor for certification
        // path not found") that confuse non-engineering users. Keep the
        // friendly Chinese label here; the original cause is still on logcat
        // for the developer (Ktor logs it before this catch fires).
        Log.w(TAG, "AI request failed (network)", e)
        MemoResult.Err(ErrorCode.NETWORK, "网络错误，请重试")
    } catch (e: Throwable) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        val cause = generateSequence<Throwable>(e) { it.cause }.firstOrNull { it is IOException }
        if (cause != null) {
            Log.w(TAG, "AI request failed (wrapped network)", e)
            MemoResult.Err(ErrorCode.NETWORK, "网络错误，请重试")
        } else {
            // Avoid exposing Java class names like "NullPointerException" or
            // "SerializationException" directly in the UI Snackbar.
            Log.e(TAG, "AI request failed (unexpected)", e)
            MemoResult.Err(ErrorCode.UNKNOWN, "AI 请求失败，请稍后重试")
        }
    }

    /**
     * Decide whether [url] is safe to send the API key over.
     *
     * Rules (applied in order):
     *  1. URL must parse as a valid [URI]; malformed → rejected.
     *  2. URL must NOT contain userInfo (the `user:pass@` segment before the
     *     host). Presence of userInfo is a credential-injection vector and
     *     would bypass host checks (e.g. `http://attacker@localhost`).
     *  3. `https://` scheme:
     *       - RFC1918 / link-local hosts: log a warning but allow (enterprise
     *         HTTPS-over-LAN is legitimate). See note below on SSRF risk.
     *       - All other hosts: allowed.
     *  4. `http://` scheme — only true loopback is allowed:
     *       - host strictly equals "localhost", "127.0.0.1", or "::1"
     *         (case-insensitive, exact match — no startsWith tricks).
     *       - RFC1918 / link-local hosts: rejected with a descriptive error
     *         so users know to switch to `http://localhost` + port forwarding.
     *       - Anything else: rejected (Bearer key would travel in cleartext).
     *  5. Any other scheme: rejected.
     *
     * Returns [UrlCheckResult] rather than a bare Boolean so the caller can
     * surface the precise rejection reason without re-parsing the URL.
     *
     * Fixes #137 (Sec-1 caveat), hardens against userInfo injection and
     * RFC1918 SSRF via http. Visible-for-test.
     */
    @androidx.annotation.VisibleForTesting
    internal fun isProviderUrlAcceptable(url: String): Boolean =
        checkProviderUrl(url) == UrlCheckResult.ALLOWED

    /**
     * Full validation result; used by [chat] to pick the right error message.
     */
    @androidx.annotation.VisibleForTesting
    internal fun checkProviderUrl(url: String): UrlCheckResult {
        val uri = try {
            URI(url)
        } catch (_: Exception) {
            return UrlCheckResult.REJECTED_MALFORMED
        }

        // Rule 2: block userInfo injection (e.g. http://attacker@localhost).
        if (!uri.rawUserInfo.isNullOrEmpty()) {
            return UrlCheckResult.REJECTED_USERINFO
        }

        val scheme = uri.scheme?.lowercase() ?: return UrlCheckResult.REJECTED_SCHEME
        // URI.host strips brackets from IPv6 literals; use that for comparison.
        val host = uri.host?.lowercase() ?: return UrlCheckResult.REJECTED_MALFORMED

        val isPrivate = isRfc1918OrLinkLocal(host)

        return when (scheme) {
            "https" -> {
                if (isPrivate) {
                    Log.w(TAG, "AI provider URL points to a private/link-local host over HTTPS — " +
                        "verify this is intentional (potential SSRF risk): $host")
                }
                UrlCheckResult.ALLOWED
            }
            "http" -> when {
                LOOPBACK_HOSTS.contains(host) -> UrlCheckResult.ALLOWED
                isPrivate -> UrlCheckResult.REJECTED_PRIVATE_HTTP
                else -> UrlCheckResult.REJECTED_HTTP
            }
            else -> UrlCheckResult.REJECTED_SCHEME
        }
    }

    /**
     * Returns true when [host] falls inside RFC1918 private address space
     * (10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16) or IPv4 link-local
     * (169.254.0.0/16). IPv6 ULA and link-local are also matched.
     *
     * The host string must already be lowercase and have brackets stripped
     * (as returned by [URI.getHost]).
     */
    private fun isRfc1918OrLinkLocal(host: String): Boolean {
        // IPv6 ULA (fc00::/7) and link-local (fe80::/10).
        if (host.contains(':')) {
            val lower = host.lowercase()
            return lower.startsWith("fc") || lower.startsWith("fd") ||
                lower.startsWith("fe8") || lower.startsWith("fe9") ||
                lower.startsWith("fea") || lower.startsWith("feb")
        }
        // IPv4: parse octets.
        val octets = host.split('.').mapNotNull { it.toIntOrNull() }
        if (octets.size != 4) return false
        val (a, b) = octets
        return when {
            a == 10 -> true                                  // 10.0.0.0/8
            a == 172 && b in 16..31 -> true                  // 172.16.0.0/12
            a == 192 && b == 168 -> true                     // 192.168.0.0/16
            a == 169 && b == 254 -> true                     // 169.254.0.0/16 link-local
            else -> false
        }
    }

    internal enum class UrlCheckResult {
        ALLOWED,
        REJECTED_MALFORMED,
        REJECTED_USERINFO,
        REJECTED_PRIVATE_HTTP,
        REJECTED_HTTP,
        REJECTED_SCHEME,
    }

    private companion object {
        private const val TAG = "AiClient"

        /** Exact loopback host strings (brackets already stripped by URI.host). */
        private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "::1")
    }
}
