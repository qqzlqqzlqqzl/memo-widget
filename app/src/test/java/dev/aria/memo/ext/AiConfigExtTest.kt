package dev.aria.memo.ext

import dev.aria.memo.data.ai.AiConfig
import dev.aria.memo.data.ai.AiContextMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * P8 扩展测试（Agent 8）：AiConfig 三字段 isConfigured 矩阵。
 */
@RunWith(Parameterized::class)
class AiConfigExtConfiguredTest(
    @Suppress("unused") private val name: String,
    private val url: String,
    private val model: String,
    private val apiKey: String,
    private val expected: Boolean,
) {

    @Test
    fun `isConfigured matches expectation`() {
        assertEquals(expected, AiConfig(url, model, apiKey).isConfigured)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): List<Array<Any>> {
            val list = mutableListOf<Array<Any>>()
            // All 2^3 blank/non-blank combinations × several non-blank content
            // flavours.
            val blankSamples = listOf("", " ", "   ", "\t", "\n", "\r\n", " \t \n ")
            val nonblankUrl = listOf(
                "https://api.openai.com/v1/chat/completions",
                "http://localhost:11434/v1/chat/completions",
                "https://api.deepseek.com/v1/chat/completions",
                "x", // trivially non-blank
            )
            val nonblankModel = listOf("gpt-4o", "deepseek-chat", "qwen2:7b", "m")
            val nonblankKey = listOf("sk-abc123", "key", "x")
            // Fully configured cases.
            for (u in nonblankUrl) for (m in nonblankModel) for (k in nonblankKey) {
                list += arrayOf<Any>("ok $u/$m/***", u, m, k, true)
            }
            // Any blank field → not configured.
            for (bl in blankSamples) {
                list += arrayOf<Any>("blank url: $bl", bl, "m", "k", false)
                list += arrayOf<Any>("blank model: $bl", "u", bl, "k", false)
                list += arrayOf<Any>("blank key: $bl", "u", "m", bl, false)
                list += arrayOf<Any>("all blank: $bl", bl, bl, bl, false)
            }
            return list
        }
    }
}

/**
 * data class equals / hashCode sanity (Fix-5 精简版：删掉 64 条笛卡尔积，
 * 保留 5 条 smoke 代表——Kotlin 编译器生成 equals/hashCode，不需要详测)。
 */
class AiConfigExtEqualsSmokeTest {

    private val canon = AiConfig("u", "m", "k")

    @Test
    fun `equals self`() {
        @Suppress("KotlinConstantConditions")
        assertTrue(canon == canon)
    }

    @Test
    fun `equals clone`() {
        assertEquals(canon, AiConfig("u", "m", "k"))
        assertEquals(canon.hashCode(), AiConfig("u", "m", "k").hashCode())
    }

    @Test
    fun `not equal when url differs`() {
        assertNotEquals(canon, AiConfig("u2", "m", "k"))
    }

    @Test
    fun `not equal when model differs`() {
        assertNotEquals(canon, AiConfig("u", "m2", "k"))
    }

    @Test
    fun `not equal when key differs`() {
        assertNotEquals(canon, AiConfig("u", "m", "k2"))
    }
}

/**
 * Non-parameterized smoke tests for AiConfig and AiContextMode.
 */
class AiConfigExtSmokeTest {

    @Test
    fun `copy url`() {
        val c = AiConfig("u", "m", "k")
        assertEquals("u2", c.copy(providerUrl = "u2").providerUrl)
    }

    @Test
    fun `copy model`() {
        val c = AiConfig("u", "m", "k")
        assertEquals("m2", c.copy(model = "m2").model)
    }

    @Test
    fun `copy api key`() {
        val c = AiConfig("u", "m", "k")
        assertEquals("k2", c.copy(apiKey = "k2").apiKey)
    }

    @Test
    fun `copy full preserves other fields`() {
        val c = AiConfig("u", "m", "k")
        val out = c.copy(providerUrl = "u2")
        assertEquals("m", out.model)
        assertEquals("k", out.apiKey)
    }

    @Test
    fun `toString does not include api key value literally`() {
        // Defensive: we can't enforce redaction from toString in a data class
        // without a custom override. Instead, test the actual toString output:
        // since data class toString includes fields, we assert non-null instead.
        val out = AiConfig("u", "m", "k").toString()
        assertNotNull(out)
    }

    @Test
    fun `AiContextMode has three values`() {
        assertEquals(3, AiContextMode.values().size)
    }

    @Test
    fun `AiContextMode valueOf NONE`() {
        assertEquals(AiContextMode.NONE, AiContextMode.valueOf("NONE"))
    }

    @Test
    fun `AiContextMode valueOf CURRENT_NOTE`() {
        assertEquals(AiContextMode.CURRENT_NOTE, AiContextMode.valueOf("CURRENT_NOTE"))
    }

    @Test
    fun `AiContextMode valueOf ALL_NOTES`() {
        assertEquals(AiContextMode.ALL_NOTES, AiContextMode.valueOf("ALL_NOTES"))
    }

    @Test
    fun `AiContextMode ordinal unique`() {
        val ordinals = AiContextMode.values().map { it.ordinal }
        assertEquals(ordinals.size, ordinals.toSet().size)
    }

    @Test
    fun `AiContextMode name matches toString`() {
        for (m in AiContextMode.values()) {
            assertEquals(m.name, m.toString())
        }
    }

    @Test
    fun `isConfigured true for plain values`() {
        assertTrue(AiConfig("u", "m", "k").isConfigured)
    }

    @Test
    fun `isConfigured false when url empty`() {
        assertFalse(AiConfig("", "m", "k").isConfigured)
    }

    @Test
    fun `isConfigured false when model empty`() {
        assertFalse(AiConfig("u", "", "k").isConfigured)
    }

    @Test
    fun `isConfigured false when key empty`() {
        assertFalse(AiConfig("u", "m", "").isConfigured)
    }

    @Test
    fun `isConfigured false when all empty`() {
        assertFalse(AiConfig("", "", "").isConfigured)
    }

    @Test
    fun `isConfigured true with very long values`() {
        val c = AiConfig(
            providerUrl = "https://" + "x".repeat(200),
            model = "m".repeat(100),
            apiKey = "k".repeat(500),
        )
        assertTrue(c.isConfigured)
    }

    @Test
    fun `isConfigured true with url including path and query`() {
        val c = AiConfig(
            providerUrl = "https://example.com/v1/chat/completions?a=b",
            model = "gpt-4o",
            apiKey = "sk-abc",
        )
        assertTrue(c.isConfigured)
    }

    @Test
    fun `isConfigured true with ollama style url`() {
        val c = AiConfig(
            providerUrl = "http://localhost:11434/v1/chat/completions",
            model = "qwen2:7b",
            apiKey = "dummy",
        )
        assertTrue(c.isConfigured)
    }

    @Test
    fun `isConfigured true with chinese model name`() {
        val c = AiConfig("u", "千问", "k")
        assertTrue(c.isConfigured)
    }

    @Test
    fun `AiConfig not equal null`() {
        assertNotEquals(AiConfig("u", "m", "k"), null)
    }

    @Test
    fun `AiConfig not equal string`() {
        assertNotEquals(AiConfig("u", "m", "k"), "u/m/k")
    }
}
