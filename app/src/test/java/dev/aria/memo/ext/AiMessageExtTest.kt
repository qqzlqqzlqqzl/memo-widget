package dev.aria.memo.ext

import dev.aria.memo.data.ai.AiMessage
import dev.aria.memo.data.ai.ChatChoice
import dev.aria.memo.data.ai.ChatRequest
import dev.aria.memo.data.ai.ChatResponse
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * P8 扩展测试（Agent 8，Fix-5 精简版）：AiMessage / ChatRequest / ChatResponse
 * data class + kotlinx.serialization 契约。
 *
 * Red-1 指出：原 AiMessageExtTest 4 roles × 9 contents × 6 tests ≈ 216 cases，
 * 其中 5 个 test（preserves / equals / hashCode / copy）完全是 Kotlin data class
 * 编译器行为，一个测试也抓不到 production 真 bug。
 *
 * 精简：
 *  - 删 AiMessageExtTest 大参数化
 *  - 删 ChatRequestExtTest 大参数化
 *  - 保留 ChatResponseExtSmokeTest（JSON roundtrip + 字段约定是 OpenAI 协议独有）
 *  - 新加 AiMessageJsonRoundTripTest 10 条代表性 case 作为 kotlinx.serialization 守门
 */
@RunWith(Parameterized::class)
class AiMessageJsonRoundTripTest(
    @Suppress("unused") private val name: String,
    private val role: String,
    private val content: String,
) {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `round-trip preserves role and content`() {
        val msg = AiMessage(role = role, content = content)
        val text = json.encodeToString(AiMessage.serializer(), msg)
        val parsed = json.decodeFromString(AiMessage.serializer(), text)
        assertEquals(role, parsed.role)
        assertEquals(content, parsed.content)
    }

    @Test
    fun `serialized text contains role and content keys`() {
        val text = json.encodeToString(AiMessage.serializer(), AiMessage(role, content))
        assertTrue("text=$text", text.contains("\"role\""))
        assertTrue("text=$text", text.contains("\"content\""))
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): List<Array<Any>> = listOf(
            arrayOf<Any>("system/hi", "system", "hi"),
            arrayOf<Any>("user/empty", "user", ""),
            arrayOf<Any>("assistant/long", "assistant", "x".repeat(500)),
            arrayOf<Any>("user/multiline", "user", "line1\nline2\nline3"),
            arrayOf<Any>("user/quotes", "user", "say \"hello\""),
            arrayOf<Any>("user/chinese", "user", "今天很累"),
            arrayOf<Any>("user/emoji", "user", "😀😁"),
            arrayOf<Any>("user/json-inside", "user", "{\"k\":\"v\"}"),
            arrayOf<Any>("user/escapes", "user", "\\r\\n escaped"),
            arrayOf<Any>("tool/tool-result", "tool", "{\"result\":42}"),
        )
    }
}

/**
 * ChatResponse / ChatChoice invariants —— OpenAI 兼容协议的字段命名 (snake_case)
 * 和默认值规则不是 Kotlin 语言规则，必须锁定。
 */
class ChatResponseExtSmokeTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `ChatChoice default finishReason null`() {
        val c = ChatChoice(index = 0, message = AiMessage("assistant", "hi"))
        assertEquals(null, c.finishReason)
    }

    @Test
    fun `ChatChoice equals clone`() {
        val a = ChatChoice(0, AiMessage("assistant", "hi"))
        val b = ChatChoice(0, AiMessage("assistant", "hi"))
        assertEquals(a, b)
    }

    @Test
    fun `ChatChoice differing index not equal`() {
        val a = ChatChoice(0, AiMessage("assistant", "hi"))
        val b = ChatChoice(1, AiMessage("assistant", "hi"))
        assertNotEquals(a, b)
    }

    @Test
    fun `ChatChoice finishReason stop`() {
        val c = ChatChoice(0, AiMessage("assistant", "hi"), finishReason = "stop")
        assertEquals("stop", c.finishReason)
    }

    @Test
    fun `ChatResponse empty default`() {
        val r = ChatResponse()
        assertEquals(emptyList<ChatChoice>(), r.choices)
    }

    @Test
    fun `ChatResponse with two choices`() {
        val r = ChatResponse(
            choices = listOf(
                ChatChoice(0, AiMessage("assistant", "hi")),
                ChatChoice(1, AiMessage("assistant", "bye")),
            ),
        )
        assertEquals(2, r.choices.size)
    }

    @Test
    fun `JSON decode ignores unknown keys`() {
        val text = "{\"role\":\"user\",\"content\":\"hi\",\"extra\":\"ignored\"}"
        val m = json.decodeFromString(AiMessage.serializer(), text)
        assertEquals("user", m.role)
        assertEquals("hi", m.content)
    }

    @Test
    fun `JSON decode nested ChatResponse with finish_reason snake_case`() {
        val text = """
            {"choices":[{"index":0,"message":{"role":"assistant","content":"hi"},"finish_reason":"stop"}]}
        """.trimIndent()
        val r = json.decodeFromString(ChatResponse.serializer(), text)
        assertEquals(1, r.choices.size)
        assertEquals("hi", r.choices[0].message.content)
        assertEquals("stop", r.choices[0].finishReason)
    }

    @Test
    fun `JSON decode ChatResponse missing choices gives empty list`() {
        val r = json.decodeFromString(ChatResponse.serializer(), "{}")
        assertEquals(emptyList<ChatChoice>(), r.choices)
    }

    @Test
    fun `JSON decode ChatChoice default index 0`() {
        val text = "{\"message\":{\"role\":\"assistant\",\"content\":\"hi\"}}"
        val c = json.decodeFromString(ChatChoice.serializer(), text)
        assertEquals(0, c.index)
    }

    @Test
    fun `ChatRequest default stream is false`() {
        val r = ChatRequest(model = "gpt-4o", messages = emptyList())
        assertFalse(r.stream)
    }

    @Test
    fun `ChatRequest messages list preserved`() {
        val msgs = listOf(AiMessage("system", "s"), AiMessage("user", "u"))
        val r = ChatRequest("gpt-4o", msgs)
        assertEquals(msgs, r.messages)
    }

    @Test
    fun `JSON encode ChatRequest includes messages field`() {
        val text = json.encodeToString(
            ChatRequest.serializer(),
            ChatRequest("m", listOf(AiMessage("user", "hi"))),
        )
        assertTrue(text.contains("messages"))
    }

    @Test
    fun `JSON encode ChatRequest includes model field`() {
        val text = json.encodeToString(
            ChatRequest.serializer(),
            ChatRequest("m", listOf(AiMessage("user", "hi"))),
        )
        assertTrue(text.contains("model"))
    }

    @Test
    fun `JSON round-trip ChatRequest stream=true preserves stream`() {
        val req = ChatRequest("m", listOf(AiMessage("user", "hi")), stream = true)
        val text = json.encodeToString(ChatRequest.serializer(), req)
        val parsed = json.decodeFromString(ChatRequest.serializer(), text)
        assertTrue(parsed.stream)
    }

    @Test
    fun `JSON round-trip ChatRequest preserves model and message count`() {
        val req = ChatRequest("gpt-4o", listOf(AiMessage("user", "hi"), AiMessage("assistant", "ok")))
        val text = json.encodeToString(ChatRequest.serializer(), req)
        val parsed = json.decodeFromString(ChatRequest.serializer(), text)
        assertEquals(req.model, parsed.model)
        assertEquals(req.messages.size, parsed.messages.size)
    }

    @Test
    fun `AiMessage empty content allowed`() {
        assertEquals("", AiMessage("user", "").content)
    }

    @Test
    fun `AiMessage unicode content preserved`() {
        assertEquals("早安 😀", AiMessage("user", "早安 😀").content)
    }

    @Test
    fun `ChatResponse toString non-null`() {
        assertNotNull(ChatResponse().toString())
    }
}
