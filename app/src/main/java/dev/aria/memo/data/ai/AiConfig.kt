package dev.aria.memo.data.ai

/**
 * User-supplied configuration for an OpenAI-compatible chat endpoint.
 *
 * [providerUrl] is the fully-qualified chat completions URL (the UI prompts
 * users with examples such as `https://api.openai.com/v1/chat/completions`
 * or `https://api.deepseek.com/v1/chat/completions`). [model] is whatever
 * string the provider expects for the `model` field in the request body.
 *
 * [apiKey] is a bearer token — the instance itself treats it as opaque, but
 * callers (notably [AiClient] and the settings UI) MUST NOT log, print or
 * round-trip this value through `toString()`. Nothing in this class exposes
 * it to logcat or error messages.
 */
data class AiConfig(
    val providerUrl: String,
    val model: String,
    val apiKey: String,
) {
    /** True when all three fields are filled in — the guard [AiClient] uses. */
    val isConfigured: Boolean
        get() = providerUrl.isNotBlank() && model.isNotBlank() && apiKey.isNotBlank()

    /**
     * Security (Sec-1 / M1): the default [data class] `toString()` would include
     * [apiKey] verbatim, defeating the class-level "MUST NOT log" contract above.
     * We redact while keeping length + prefix/suffix fingerprint so debuggers can
     * still tell "empty key", "truncated-on-paste", and "different key from last
     * time" apart without the secret itself ever reaching logcat / crash logs.
     */
    override fun toString(): String =
        "AiConfig(providerUrl=$providerUrl, model=$model, apiKey=${apiKey.redactApiKey()})"
}

/**
 * Redact a bearer-style secret for logs/crash reports. Mirrors the policy used
 * by [dev.aria.memo.data.AppConfig] for the GitHub PAT: blank → `(empty)`,
 * otherwise `(<len>chars, <first4>***<last2>)`. Package-private so it does not
 * leak out as public API.
 */
internal fun String.redactApiKey(): String =
    if (isBlank()) "(empty)"
    else "(${length}chars, ${take(4)}***${takeLast(2)})"
