package dev.aria.memo.data.ai

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
        // (`http://localhost` / `http://127.0.0.1`) so users running a
        // local Ollama / vLLM provider don't have to set up TLS just
        // to hit their own machine. Anything else (cleartext to a
        // remote host) still bounces with a clear error.
        if (!isProviderUrlAcceptable(config.providerUrl)) {
            return MemoResult.Err(
                ErrorCode.UNKNOWN,
                "AI provider URL 必须以 https:// 开头（当前: ${config.providerUrl.take(20)}）",
            )
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
        MemoResult.Err(ErrorCode.NETWORK, e.message ?: "network error")
    } catch (e: Throwable) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        val cause = generateSequence<Throwable>(e) { it.cause }.firstOrNull { it is IOException }
        if (cause != null) {
            MemoResult.Err(ErrorCode.NETWORK, cause.message ?: "network error")
        } else {
            MemoResult.Err(ErrorCode.UNKNOWN, e::class.simpleName + ": " + (e.message ?: ""))
        }
    }

    /**
     * Decide whether [url] is safe to send the API key over.
     *  - `https://...` — allowed (TLS protects the Bearer header).
     *  - `http://localhost` / `http://127.0.0.1` / `http://[::1]` — allowed
     *    so Ollama / vLLM users running a local model on the same device
     *    don't need to stand up TLS just to talk to their own loopback.
     *  - anything else (e.g. `http://internal-proxy/v1`) — refused so the
     *    API key can't leak in cleartext on a LAN.
     *
     * Fixes #137 (Sec-1 caveat). Visible-for-test so a unit can pin the
     * loopback exception list without rebuilding a whole network stack.
     */
    @androidx.annotation.VisibleForTesting
    internal fun isProviderUrlAcceptable(url: String): Boolean {
        if (url.startsWith("https://", ignoreCase = true)) return true
        // Loopback-only http allowed.
        return LOOPBACK_HTTP_PREFIXES.any { url.startsWith(it, ignoreCase = true) }
    }

    private companion object {
        private val LOOPBACK_HTTP_PREFIXES = listOf(
            "http://localhost",
            "http://127.0.0.1",
            "http://[::1]",
        )
    }
}
