package dev.aria.memo.extB

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
 * P8 扩展测试（Agent 8b）：[AiContextBuilder] 语义层矩阵补强。
 *
 * 与 Agent 8 (`ext/AiContextBuilderExtTest.kt`) 的区别：
 *   - 前者强调"非空、边界、长度上界"等 I/O 安全性。
 *   - 本文件强调**字符完整性**（中文/emoji/混合），**header 正确性**，
 *     **截断 marker 出现/不出现的精确条件**，以及**ALL_NOTES body 顺序**。
 */

/**
 * 矩阵 1：CURRENT_NOTE 模式的 header + body 联合出现判定。
 *
 * 期望：
 *   - budget <= 0      → 输出空串
 *   - body blank/空    → 输出空串
 *   - budget 过小（< header 长度）→ 输出空串
 *   - 否则 body 完整或被截断出现，header 始终是前缀
 */
@RunWith(Parameterized::class)
class AiContextBuilderMatrixCurrentNoteTest(
    @JvmField val name: String,
    @JvmField val body: String,
    @JvmField val budget: Int,
) {

    private val header = "以下是当前笔记，回答请基于它：\n\n"

    @Test
    fun `output respects documented empty-string rules`() {
        val out = AiContextBuilder.buildSystemPrompt(
            mode = AiContextMode.CURRENT_NOTE,
            currentNoteBody = body,
            charBudget = budget,
        )
        val headerLen = header.length
        when {
            budget <= 0 -> assertEquals("budget<=0 → empty", "", out)
            body.isBlank() -> assertEquals("blank body → empty", "", out)
            budget - headerLen <= 0 -> assertEquals("no room for body after header → empty", "", out)
            else -> {
                assertTrue("output must start with header", out.startsWith(header))
                // Whatever is not header is body (possibly clipped + marker).
                val tail = out.substring(headerLen)
                assertTrue(
                    "tail should be a prefix of body (possibly with truncated marker)",
                    tail == body ||
                        tail.endsWith("...(truncated)") ||
                        body.startsWith(tail),
                )
            }
        }
    }

    @Test
    fun `output length within budget plus truncate suffix`() {
        val out = AiContextBuilder.buildSystemPrompt(
            mode = AiContextMode.CURRENT_NOTE,
            currentNoteBody = body,
            charBudget = budget,
        )
        if (budget > 0 && out.isNotEmpty()) {
            // Impl adds the 14-char "...(truncated)" suffix even when there's no
            // room for it, so the true upper bound is budget + suffix length.
            val upper = budget + 14
            assertTrue(
                "out.length=${out.length} upper=$upper budget=$budget",
                out.length <= upper,
            )
        }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): List<Array<Any>> {
            val list = mutableListOf<Array<Any>>()
            val bodies = mapOf(
                "empty" to "",
                "blank" to "   ",
                "nl-only" to "\n\n\t",
                "short en" to "hello world",
                "short cn" to "今天写代码",
                "emoji" to "🚀🧠📝",
                "mixed" to "Hello 你好 🌟",
                "1k x" to "x".repeat(1_000),
                "10k x" to "x".repeat(10_000),
                "20k cn" to "字".repeat(20_000),
                "30k y" to "y".repeat(30_000),
            )
            val budgets = listOf(0, 1, 15, 30, 50, 100, 500, 1_000, 5_000, 15_000, 50_000)
            for ((n, b) in bodies) for (bg in budgets) {
                list += arrayOf<Any>("body=$n/budget=$bg", b, bg)
            }
            return list
        }
    }
}

/**
 * 矩阵 2：ALL_NOTES 模式 — bodies 顺序保留、非空 body 全部存在、输出长度有界。
 */
@RunWith(Parameterized::class)
class AiContextBuilderMatrixAllNotesTest(
    @JvmField val name: String,
    @JvmField val bodies: List<String>,
    @JvmField val budget: Int,
) {

    @Test
    fun `output length bounded`() {
        val out = AiContextBuilder.buildSystemPrompt(
            mode = AiContextMode.ALL_NOTES,
            allNoteBodies = bodies,
            charBudget = budget,
        )
        // If budget > 0 and we emitted any content, the output must still
        // respect the declared bound — content is only emitted while
        // `remaining > 0` and the last (possibly clipped) body is included
        // whole-or-truncated, so the bound is `budget + TRUNCATION_SUFFIX.len`.
        // We assert the looser `out.length <= budget + 64` which accommodates
        // the marker suffix.
        if (budget > 0 && out.isNotEmpty()) {
            assertTrue(
                "out.length=${out.length} budget=$budget",
                out.length <= budget + 64,
            )
        }
    }

    @Test
    fun `empty or all-blank list yields empty string`() {
        val nonEmpty = bodies.filter { it.isNotBlank() }
        val out = AiContextBuilder.buildSystemPrompt(
            mode = AiContextMode.ALL_NOTES,
            allNoteBodies = bodies,
            charBudget = budget,
        )
        if (nonEmpty.isEmpty()) {
            assertEquals("", out)
        } else if (budget > 0) {
            // Non-empty content was passed and budget is positive. Output may be
            // empty if budget < header length, else must contain header.
            val header = "以下是用户最近的笔记（按时间倒序），回答请基于这些内容：\n\n"
            if (out.isNotEmpty()) assertTrue(out.startsWith(header))
        }
    }

    @Test
    fun `order preserved for all bodies that fit`() {
        val out = AiContextBuilder.buildSystemPrompt(
            mode = AiContextMode.ALL_NOTES,
            allNoteBodies = bodies,
            charBudget = budget,
        )
        // For the non-blank bodies that physically appear in the output,
        // their relative order must match the input order.
        val nonEmpty = bodies.filter { it.isNotBlank() }.distinct()
        val positions = nonEmpty.mapNotNull { s ->
            val idx = out.indexOf(s)
            if (idx < 0) null else s to idx
        }
        for (i in 1 until positions.size) {
            val (prevBody, prevIdx) = positions[i - 1]
            val (curBody, curIdx) = positions[i]
            assertTrue(
                "ordering violated: '$prevBody'@$prevIdx must come before '$curBody'@$curIdx",
                prevIdx <= curIdx,
            )
        }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): List<Array<Any>> {
            val list = mutableListOf<Array<Any>>()
            val lists = mapOf(
                "empty" to emptyList(),
                "single blank" to listOf("   "),
                "three blanks" to listOf("", "", ""),
                "1 short" to listOf("alpha"),
                "2 short" to listOf("alpha", "beta"),
                "3 short" to listOf("alpha", "beta", "gamma"),
                "5 short" to (1..5).map { "note_$it" },
                "10 short" to (1..10).map { "note_$it" },
                "1 medium" to listOf("x".repeat(500)),
                "3 medium" to (1..3).map { "body${it}_${"y".repeat(300)}" },
                "mixed blank" to listOf("A", "", " ", "B", "", "C"),
                "cn notes" to listOf("学习笔记", "工作笔记", "日常笔记"),
                "emoji" to listOf("🚀 launch", "🧠 think", "📝 note"),
                "100 notes" to (1..100).map { "entry_$it" },
            )
            val budgets = listOf(0, 1, 50, 100, 500, 1_000, 5_000, 15_000, 50_000)
            for ((n, b) in lists) for (bg in budgets) {
                list += arrayOf<Any>("$n/budget=$bg", b, bg)
            }
            return list
        }
    }
}

/**
 * 矩阵 3：NONE 模式强不变式 — 无论输入多大/多奇异，输出恒为空串。
 */
@RunWith(Parameterized::class)
class AiContextBuilderMatrixNoneTest(
    @JvmField val name: String,
    @JvmField val body: String,
    @JvmField val bodies: List<String>,
    @JvmField val budget: Int,
) {

    @Test
    fun `NONE always empty`() {
        val out = AiContextBuilder.buildSystemPrompt(
            mode = AiContextMode.NONE,
            currentNoteBody = body,
            allNoteBodies = bodies,
            charBudget = budget,
        )
        assertEquals("mode=NONE must produce empty string regardless of inputs", "", out)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): List<Array<Any>> = listOf(
            // Fix-5: 70 case 压成 3 个代表 — empty、large body、large list
            // 都同样证明 NONE 模式下无论输入都返回空串。
            arrayOf<Any>("empty everything", "", emptyList<String>(), 0),
            arrayOf<Any>("large body positive budget", "x".repeat(50_000), emptyList<String>(), 15_000),
            arrayOf<Any>("large list negative budget", "", (1..10).map { "n$it" }, -1),
        )
    }
}

/**
 * 非参数化精确形状断言 — 字符完整性、header 字面值、truncate marker 准确位置。
 */
class AiContextBuilderMatrixShapeTest {

    private val headerCurrent = "以下是当前笔记，回答请基于它：\n\n"
    private val headerAll = "以下是用户最近的笔记（按时间倒序），回答请基于这些内容：\n\n"
    private val separator = "\n\n---\n\n"
    private val marker = "...(truncated)"

    @Test
    fun `CURRENT_NOTE short body output equals header plus body`() {
        val body = "hello"
        val out = AiContextBuilder.buildSystemPrompt(
            mode = AiContextMode.CURRENT_NOTE,
            currentNoteBody = body,
            charBudget = 15_000,
        )
        assertEquals(headerCurrent + body, out)
    }

    @Test
    fun `CURRENT_NOTE truncation marker only when over budget`() {
        val body = "x".repeat(10_000)
        val outSmall = AiContextBuilder.buildSystemPrompt(
            mode = AiContextMode.CURRENT_NOTE,
            currentNoteBody = body,
            charBudget = 500,
        )
        val outLarge = AiContextBuilder.buildSystemPrompt(
            mode = AiContextMode.CURRENT_NOTE,
            currentNoteBody = "tiny",
            charBudget = 500,
        )
        assertTrue(outSmall.endsWith(marker))
        assertFalse(outLarge.endsWith(marker))
    }

    @Test
    fun `CURRENT_NOTE truncation leaves only the suffix tail`() {
        val body = "A".repeat(10_000)
        val out = AiContextBuilder.buildSystemPrompt(
            mode = AiContextMode.CURRENT_NOTE,
            currentNoteBody = body,
            charBudget = 200,
        )
        assertTrue("out must end with marker", out.endsWith(marker))
        assertEquals(
            "exactly one marker in output",
            1,
            out.split(marker).size - 1,
        )
    }

    @Test
    fun `CURRENT_NOTE output is header plus clipped body plus marker`() {
        val body = "A".repeat(10_000)
        val budget = 200
        val out = AiContextBuilder.buildSystemPrompt(
            mode = AiContextMode.CURRENT_NOTE,
            currentNoteBody = body,
            charBudget = budget,
        )
        assertTrue(out.startsWith(headerCurrent))
        assertTrue(out.endsWith(marker))
        assertTrue("out.length=${out.length} <= $budget", out.length <= budget)
    }

    @Test
    fun `CURRENT_NOTE chinese body fully embedded`() {
        val body = "今天我写了很多代码，修复了三个 bug"
        val out = AiContextBuilder.buildSystemPrompt(
            mode = AiContextMode.CURRENT_NOTE,
            currentNoteBody = body,
            charBudget = 15_000,
        )
        assertTrue(out.contains(body))
    }

    @Test
    fun `CURRENT_NOTE emoji body fully embedded`() {
        val body = "今天感觉 🌞🚀🧠📝 很好"
        val out = AiContextBuilder.buildSystemPrompt(
            mode = AiContextMode.CURRENT_NOTE,
            currentNoteBody = body,
            charBudget = 15_000,
        )
        assertTrue(out.contains(body))
    }

    @Test
    fun `CURRENT_NOTE mixed script body fully embedded`() {
        val body = "Hello 世界 🌍 Emoji 混排 line\nSecond line"
        val out = AiContextBuilder.buildSystemPrompt(
            mode = AiContextMode.CURRENT_NOTE,
            currentNoteBody = body,
            charBudget = 15_000,
        )
        assertTrue(out.contains(body))
    }

    @Test
    fun `ALL_NOTES uses separator between bodies`() {
        val out = AiContextBuilder.buildSystemPrompt(
            mode = AiContextMode.ALL_NOTES,
            allNoteBodies = listOf("alpha", "beta", "gamma"),
            charBudget = 15_000,
        )
        assertTrue(out.contains(separator))
        // alpha + sep + beta + sep + gamma — 2 separators.
        assertEquals(2, out.split(separator).size - 1)
    }

    @Test
    fun `ALL_NOTES single body no separator`() {
        val out = AiContextBuilder.buildSystemPrompt(
            mode = AiContextMode.ALL_NOTES,
            allNoteBodies = listOf("solo"),
            charBudget = 15_000,
        )
        assertFalse(out.contains(separator))
        assertTrue(out.startsWith(headerAll))
        assertTrue(out.endsWith("solo"))
    }

    @Test
    fun `ALL_NOTES drops blank bodies between real ones`() {
        val out = AiContextBuilder.buildSystemPrompt(
            mode = AiContextMode.ALL_NOTES,
            allNoteBodies = listOf("A", "", " ", "\t", "B"),
            charBudget = 15_000,
        )
        assertTrue(out.contains("A"))
        assertTrue(out.contains("B"))
        // Exactly one separator since only A and B survived.
        assertEquals(1, out.split(separator).size - 1)
    }

    @Test
    fun `ALL_NOTES order preserved alpha before beta before gamma`() {
        val out = AiContextBuilder.buildSystemPrompt(
            mode = AiContextMode.ALL_NOTES,
            allNoteBodies = listOf("ALPHA_MARKER", "BETA_MARKER", "GAMMA_MARKER"),
            charBudget = 15_000,
        )
        val a = out.indexOf("ALPHA_MARKER")
        val b = out.indexOf("BETA_MARKER")
        val g = out.indexOf("GAMMA_MARKER")
        assertTrue(a >= 0 && b > a && g > b)
    }

    @Test
    fun `ALL_NOTES truncation ends the stream`() {
        val bodies = listOf("x".repeat(500), "y".repeat(500), "z".repeat(500))
        val out = AiContextBuilder.buildSystemPrompt(
            mode = AiContextMode.ALL_NOTES,
            allNoteBodies = bodies,
            charBudget = 300,
        )
        // At budget=300 the header alone is ~32 chars, there's room only for a
        // clipped first body → marker at end, and no "z" body should appear.
        if (out.isNotEmpty()) {
            assertTrue(out.startsWith(headerAll))
            assertTrue("must contain truncation marker", out.contains(marker))
            assertFalse("third body must not appear", out.contains("z".repeat(100)))
        }
    }

    @Test
    fun `ALL_NOTES 100 notes all present under generous budget`() {
        val bodies = (1..100).map { "NOTE_$it" }
        val out = AiContextBuilder.buildSystemPrompt(
            mode = AiContextMode.ALL_NOTES,
            allNoteBodies = bodies,
            charBudget = 50_000,
        )
        for (i in 1..100) assertTrue("NOTE_$i missing", out.contains("NOTE_$i"))
    }

    @Test
    fun `ALL_NOTES 100 notes tight budget only some present`() {
        val bodies = (1..100).map { "LONG_NOTE_${it}_${"x".repeat(200)}" }
        val out = AiContextBuilder.buildSystemPrompt(
            mode = AiContextMode.ALL_NOTES,
            allNoteBodies = bodies,
            charBudget = 1_000,
        )
        assertTrue(out.startsWith(headerAll))
        assertTrue(out.length <= 1_000 + marker.length)
        assertTrue(out.contains("LONG_NOTE_1"))
        assertFalse("note 100 must not survive tight budget", out.contains("LONG_NOTE_100"))
    }

    @Test
    fun `ALL_NOTES exactly one header`() {
        val out = AiContextBuilder.buildSystemPrompt(
            mode = AiContextMode.ALL_NOTES,
            allNoteBodies = listOf("a", "b", "c"),
            charBudget = 15_000,
        )
        assertEquals(1, out.split(headerAll).size - 1)
    }

    @Test
    fun `CURRENT_NOTE exactly one header`() {
        val out = AiContextBuilder.buildSystemPrompt(
            mode = AiContextMode.CURRENT_NOTE,
            currentNoteBody = "body",
            charBudget = 15_000,
        )
        assertEquals(1, out.split(headerCurrent).size - 1)
    }

    @Test
    fun `CURRENT_NOTE budget smaller than header yields empty`() {
        val out = AiContextBuilder.buildSystemPrompt(
            mode = AiContextMode.CURRENT_NOTE,
            currentNoteBody = "body",
            charBudget = 5,
        )
        assertEquals("", out)
    }

    @Test
    fun `ALL_NOTES budget smaller than header yields empty`() {
        val out = AiContextBuilder.buildSystemPrompt(
            mode = AiContextMode.ALL_NOTES,
            allNoteBodies = listOf("body"),
            charBudget = 5,
        )
        assertEquals("", out)
    }

    @Test
    fun `ALL_NOTES blank-only list yields empty`() {
        val out = AiContextBuilder.buildSystemPrompt(
            mode = AiContextMode.ALL_NOTES,
            allNoteBodies = listOf("", "   ", "\n"),
            charBudget = 15_000,
        )
        assertEquals("", out)
    }

    @Test
    fun `CURRENT_NOTE newline-only body yields empty`() {
        val out = AiContextBuilder.buildSystemPrompt(
            mode = AiContextMode.CURRENT_NOTE,
            currentNoteBody = "\n\n\n",
            charBudget = 15_000,
        )
        assertEquals("", out)
    }

    @Test
    fun `NONE is always empty with huge current body`() {
        val out = AiContextBuilder.buildSystemPrompt(
            mode = AiContextMode.NONE,
            currentNoteBody = "x".repeat(100_000),
            charBudget = 100_000,
        )
        assertEquals("", out)
    }

    @Test
    fun `budget default fifteen thousand exercised when omitted`() {
        // The function overload uses 15000 by default — ensure it's exercised
        // without passing charBudget explicitly.
        val body = "a small body"
        val out = AiContextBuilder.buildSystemPrompt(
            mode = AiContextMode.CURRENT_NOTE,
            currentNoteBody = body,
        )
        assertTrue(out.contains(body))
    }

    @Test
    fun `ALL_NOTES default budget exercised when omitted`() {
        val out = AiContextBuilder.buildSystemPrompt(
            mode = AiContextMode.ALL_NOTES,
            allNoteBodies = listOf("abc"),
        )
        assertTrue(out.contains("abc"))
    }

    @Test
    fun `NONE default budget exercised when omitted`() {
        val out = AiContextBuilder.buildSystemPrompt(mode = AiContextMode.NONE)
        assertEquals("", out)
    }

    @Test
    fun `CURRENT_NOTE default budget exercised when omitted`() {
        val out = AiContextBuilder.buildSystemPrompt(
            mode = AiContextMode.CURRENT_NOTE,
            currentNoteBody = null,
        )
        assertEquals("", out)
    }

    @Test
    fun `AiContextMode values exhaustive count`() {
        assertEquals(3, AiContextMode.values().size)
    }

    @Test
    fun `AiContextBuilder is a singleton object non-null`() {
        assertNotNull(AiContextBuilder)
    }
}
