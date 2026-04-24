package dev.aria.memo.data.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * DTOs for the OpenAI-compatible chat-completions protocol (non-streaming).
 *
 * Every compliant provider we care about (OpenAI, DeepSeek, Azure OpenAI,
 * ollama, vLLM, LM Studio …) accepts the same request shape — we only need
 * `model`, `messages` and `stream=false`. Unknown fields in responses are
 * ignored by the ServiceLocator-wide [kotlinx.serialization.json.Json]
 * instance (`ignoreUnknownKeys = true`), so provider-specific extras like
 * `usage`, `id`, `created`, `system_fingerprint` don't break parsing.
 */
@Serializable
data class AiMessage(
    val role: String, // "system" | "user" | "assistant"
    val content: String,
)

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<AiMessage>,
    val stream: Boolean = false,
)

@Serializable
data class ChatResponse(
    val choices: List<ChatChoice> = emptyList(),
)

@Serializable
data class ChatChoice(
    val index: Int = 0,
    val message: AiMessage,
    @SerialName("finish_reason") val finishReason: String? = null,
)
