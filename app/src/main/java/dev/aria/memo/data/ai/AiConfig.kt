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
}
