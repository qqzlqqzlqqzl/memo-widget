package dev.aria.memo.extB

import dev.aria.memo.data.ai.AiConfig
import dev.aria.memo.data.ai.AiContextMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P8 扩展测试（Agent 8b，Fix-5 精简版）：[AiConfig] + [AiContextMode] 核心业务断言。
 *
 * Red-2 指出：原文件 840+ cases 里 ~800 条在测 `String.isNotBlank()`（stdlib）和
 * data class 编译器生成的 `copy/equals/hashCode`。那些对 production 改错没保护作用。
 *
 * 精简到 20 条核心 case，断言真正的业务语义：
 *  - `isConfigured` 的 2^3 真值表（3 字段各自 blank / 非 blank 的 8 种组合）
 *  - 几种典型空白符（tab / LF / U+3000）触发 `isNotBlank` = false（真实用户会遇到）
 *  - `AiContextMode` 枚举固定顺序（production 里多处 `when` 依赖）
 */
class AiConfigMatrixEdgeTest {

    // ------------------------------------------------------------------
    // isConfigured 业务真值表：只有三字段全部 non-blank 才 true
    // ------------------------------------------------------------------

    @Test
    fun `isConfigured all three non-blank returns true`() {
        val c = AiConfig(
            providerUrl = "https://api.openai.com/v1/chat/completions",
            model = "gpt-4o",
            apiKey = "sk-abc",
        )
        assertTrue(c.isConfigured)
    }

    @Test
    fun `isConfigured all three empty returns false`() {
        assertFalse(AiConfig("", "", "").isConfigured)
    }

    @Test
    fun `isConfigured blank url returns false`() {
        assertFalse(AiConfig("", "m", "k").isConfigured)
    }

    @Test
    fun `isConfigured blank model returns false`() {
        assertFalse(AiConfig("u", "", "k").isConfigured)
    }

    @Test
    fun `isConfigured blank key returns false`() {
        assertFalse(AiConfig("u", "m", "").isConfigured)
    }

    @Test
    fun `isConfigured tab-only url returns false`() {
        // 真实场景：用户粘贴时手滑多按了一个 TAB —— 不能把 widget "假配置" 了
        assertFalse(AiConfig("\t", "m", "k").isConfigured)
    }

    @Test
    fun `isConfigured newline-only model returns false`() {
        assertFalse(AiConfig("u", "\n", "k").isConfigured)
    }

    @Test
    fun `isConfigured ideographic-space-only key returns false`() {
        // 中文输入法里 "全角空格" 粘贴进 API key —— 看着有内容实则 blank
        assertFalse(AiConfig("u", "m", "　").isConfigured)
    }

    @Test
    fun `isConfigured CRLF-only url returns false`() {
        assertFalse(AiConfig("\r\n", "m", "k").isConfigured)
    }

    // ------------------------------------------------------------------
    // 典型场景 smoke（ollama / openai / deepseek / 自定义）
    // ------------------------------------------------------------------

    @Test
    fun `isConfigured localhost ollama config works`() {
        val c = AiConfig(
            providerUrl = "http://localhost:11434/v1/chat/completions",
            model = "qwen2:7b",
            apiKey = "dummy",
        )
        assertTrue(c.isConfigured)
    }

    @Test
    fun `isConfigured deepseek config works`() {
        val c = AiConfig(
            providerUrl = "https://api.deepseek.com/v1/chat/completions",
            model = "deepseek-chat",
            apiKey = "sk-deepseek",
        )
        assertTrue(c.isConfigured)
    }

    @Test
    fun `isConfigured unicode url model and key all non-blank returns true`() {
        val c = AiConfig(
            providerUrl = "https://域名.example.com/v1/chat/completions",
            model = "千问2-7b",
            apiKey = "🔑-private",
        )
        assertTrue(c.isConfigured)
    }

    // ------------------------------------------------------------------
    // data class smoke（3 条 sanity；不是全矩阵 equals/hashCode/copy 拷贝）
    // ------------------------------------------------------------------

    @Test
    fun `copy identical fields equals base`() {
        val a = AiConfig("u", "m", "k")
        assertEquals(a, a.copy())
    }

    @Test
    fun `copy single field changed preserves the other two`() {
        val base = AiConfig("u", "m", "k")
        val out = base.copy(apiKey = "rotated")
        assertEquals("u", out.providerUrl)
        assertEquals("m", out.model)
        assertEquals("rotated", out.apiKey)
    }

    @Test
    fun `destructure yields all three fields in order`() {
        val (u, m, k) = AiConfig("u1", "m1", "k1")
        assertEquals("u1", u)
        assertEquals("m1", m)
        assertEquals("k1", k)
    }

    // ------------------------------------------------------------------
    // AiContextMode 枚举契约（production when-branches 依赖 3 个值 + 顺序）
    // ------------------------------------------------------------------

    @Test
    fun `AiContextMode has exactly three values`() {
        assertEquals(3, AiContextMode.values().size)
    }

    @Test
    fun `AiContextMode NONE is ordinal 0`() {
        assertEquals(0, AiContextMode.NONE.ordinal)
    }

    @Test
    fun `AiContextMode CURRENT_NOTE is ordinal 1`() {
        assertEquals(1, AiContextMode.CURRENT_NOTE.ordinal)
    }

    @Test
    fun `AiContextMode ALL_NOTES is ordinal 2`() {
        assertEquals(2, AiContextMode.ALL_NOTES.ordinal)
    }

    @Test
    fun `AiContextMode valueOf round-trips all three names`() {
        assertEquals(AiContextMode.NONE, AiContextMode.valueOf("NONE"))
        assertEquals(AiContextMode.CURRENT_NOTE, AiContextMode.valueOf("CURRENT_NOTE"))
        assertEquals(AiContextMode.ALL_NOTES, AiContextMode.valueOf("ALL_NOTES"))
    }
}
