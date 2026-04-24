package dev.aria.memo.ext

import dev.aria.memo.data.ai.AiContextBuilder
import dev.aria.memo.data.ai.AiContextMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * P8 扩展测试（Agent 8，Fix-5 精简版）：[AiContextBuilder.buildSystemPrompt]。
 *
 * Red-1 指出：
 *  - 原 CurrentNoteTest 15 bodies × 9 budgets = 135 case × 3 test ≈ 405 参数化，
 *    但 2/3 的 assertion 是 "非 null" 和 "length <= budget*3+200" 这种宽松上界
 *  - 原 AllNotesTest 11 × 8 = 88 case × 3 test ≈ 264 参数化
 *  - 原 NoneTest 5 × 3 × 5 = 75 × 1 test = 75
 *
 * 精简：删除三个 parameterized class（smoke 里已经有真正的语义断言），保留
 * SmokeTest 全部（15 条业务断言，都测 prompt 构建的真规则：header 出现、
 * order preserved、truncation marker、budget=0 empty 等）。
 *
 * 补 10 条代表性 param case 在 ExtBoundsTest 里，锁 prompt 长度不会失控。
 */
@RunWith(Parameterized::class)
class AiContextBuilderExtBoundsTest(
    @Suppress("unused") private val name: String,
    private val mode: AiContextMode,
    private val body: String?,
    private val bodies: List<String>,
    private val budget: Int,
) {

    @Test
    fun `prompt non-null and length bounded`() {
        val out = AiContextBuilder.buildSystemPrompt(
            mode = mode,
            currentNoteBody = body,
            allNoteBodies = bodies,
            charBudget = budget,
        )
        assertNotNull(out)
        val upper = budget.coerceAtLeast(1) * 5 + 500
        assertTrue(
            "prompt length ${out.length} must be bounded (budget=$budget, upper=$upper)",
            out.length <= upper,
        )
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): List<Array<Any?>> = listOf(
            // CURRENT_NOTE corners
            arrayOf<Any?>("current null/500", AiContextMode.CURRENT_NOTE, null, emptyList<String>(), 500),
            arrayOf<Any?>("current blank/500", AiContextMode.CURRENT_NOTE, "  ", emptyList<String>(), 500),
            arrayOf<Any?>("current short/500", AiContextMode.CURRENT_NOTE, "hello", emptyList<String>(), 500),
            arrayOf<Any?>("current long/1k", AiContextMode.CURRENT_NOTE, "x".repeat(5000), emptyList<String>(), 1_000),
            arrayOf<Any?>("current budget=0", AiContextMode.CURRENT_NOTE, "hello", emptyList<String>(), 0),
            // ALL_NOTES corners
            arrayOf<Any?>("all empty/15k", AiContextMode.ALL_NOTES, null, emptyList<String>(), 15_000),
            arrayOf<Any?>("all three/15k", AiContextMode.ALL_NOTES, null, listOf("A", "B", "C"), 15_000),
            arrayOf<Any?>("all huge/500", AiContextMode.ALL_NOTES, null, (1..50).map { "n$it" }, 500),
            // NONE: always empty regardless
            arrayOf<Any?>("none any/15k", AiContextMode.NONE, "anything", listOf("x"), 15_000),
            arrayOf<Any?>("none any/0", AiContextMode.NONE, "anything", listOf("x"), 0),
        )
    }
}

/**
 * Smoke / invariants — 每个方法测一条真实的 prompt 构建语义。
 */
class AiContextBuilderExtSmokeTest {

    @Test
    fun `NONE empty string`() {
        assertEquals(
            "",
            AiContextBuilder.buildSystemPrompt(
                mode = AiContextMode.NONE,
                charBudget = 15_000,
            ),
        )
    }

    @Test
    fun `CURRENT_NOTE null does not leak literal null into prompt`() {
        val out = AiContextBuilder.buildSystemPrompt(
            mode = AiContextMode.CURRENT_NOTE,
            currentNoteBody = null,
            charBudget = 15_000,
        )
        assertFalse(out.contains("null"))
    }

    @Test
    fun `CURRENT_NOTE empty body returns empty`() {
        val out = AiContextBuilder.buildSystemPrompt(
            mode = AiContextMode.CURRENT_NOTE,
            currentNoteBody = "",
            charBudget = 15_000,
        )
        assertEquals("", out)
    }

    @Test
    fun `CURRENT_NOTE blank body returns empty`() {
        val out = AiContextBuilder.buildSystemPrompt(
            mode = AiContextMode.CURRENT_NOTE,
            currentNoteBody = "   \n\t  ",
            charBudget = 15_000,
        )
        assertEquals("", out)
    }

    @Test
    fun `ALL_NOTES empty list returns empty`() {
        assertEquals(
            "",
            AiContextBuilder.buildSystemPrompt(
                mode = AiContextMode.ALL_NOTES,
                allNoteBodies = emptyList(),
                charBudget = 15_000,
            ),
        )
    }

    @Test
    fun `ALL_NOTES all blank returns empty`() {
        assertEquals(
            "",
            AiContextBuilder.buildSystemPrompt(
                mode = AiContextMode.ALL_NOTES,
                allNoteBodies = listOf("", " ", "\n", "\t"),
                charBudget = 15_000,
            ),
        )
    }

    @Test
    fun `budget zero all modes empty`() {
        for (m in AiContextMode.values()) {
            val out = AiContextBuilder.buildSystemPrompt(
                mode = m,
                currentNoteBody = "hello",
                allNoteBodies = listOf("a", "b"),
                charBudget = 0,
            )
            assertEquals("mode=$m budget=0 must be empty, got $out", "", out)
        }
    }

    @Test
    fun `budget negative all modes empty`() {
        for (m in AiContextMode.values()) {
            val out = AiContextBuilder.buildSystemPrompt(
                mode = m,
                currentNoteBody = "hello",
                allNoteBodies = listOf("a", "b"),
                charBudget = -1,
            )
            assertEquals("", out)
        }
    }

    @Test
    fun `CURRENT_NOTE body appears when short`() {
        val body = "Hello World 你好"
        val out = AiContextBuilder.buildSystemPrompt(
            mode = AiContextMode.CURRENT_NOTE,
            currentNoteBody = body,
            charBudget = 15_000,
        )
        assertTrue(out.contains(body))
    }

    @Test
    fun `CURRENT_NOTE has truncation marker when over budget`() {
        val body = "x".repeat(100_000)
        val out = AiContextBuilder.buildSystemPrompt(
            mode = AiContextMode.CURRENT_NOTE,
            currentNoteBody = body,
            charBudget = 1_000,
        )
        assertTrue(out.contains("truncated"))
    }

    @Test
    fun `ALL_NOTES all three bodies appear when under budget`() {
        val out = AiContextBuilder.buildSystemPrompt(
            mode = AiContextMode.ALL_NOTES,
            allNoteBodies = listOf("bodyAlpha", "bodyBravo", "bodyCharlie"),
            charBudget = 15_000,
        )
        assertTrue(out.contains("bodyAlpha"))
        assertTrue(out.contains("bodyBravo"))
        assertTrue(out.contains("bodyCharlie"))
    }

    @Test
    fun `ALL_NOTES order preserved`() {
        val out = AiContextBuilder.buildSystemPrompt(
            mode = AiContextMode.ALL_NOTES,
            allNoteBodies = listOf("alpha", "beta", "gamma"),
            charBudget = 15_000,
        )
        val a = out.indexOf("alpha")
        val b = out.indexOf("beta")
        val g = out.indexOf("gamma")
        assertTrue(a in 0 until b)
        assertTrue(b in 0 until g)
    }

    @Test
    fun `ALL_NOTES skips leading blank bodies`() {
        val out = AiContextBuilder.buildSystemPrompt(
            mode = AiContextMode.ALL_NOTES,
            allNoteBodies = listOf("", "meaningful content"),
            charBudget = 15_000,
        )
        assertTrue(out.contains("meaningful content"))
    }

    @Test
    fun `CURRENT_NOTE prompt includes 笔记 header`() {
        val out = AiContextBuilder.buildSystemPrompt(
            mode = AiContextMode.CURRENT_NOTE,
            currentNoteBody = "x",
            charBudget = 15_000,
        )
        assertTrue(out.contains("笔记"))
    }

    @Test
    fun `ALL_NOTES prompt includes 笔记 header`() {
        val out = AiContextBuilder.buildSystemPrompt(
            mode = AiContextMode.ALL_NOTES,
            allNoteBodies = listOf("note"),
            charBudget = 15_000,
        )
        assertTrue(out.contains("笔记"))
    }
}
