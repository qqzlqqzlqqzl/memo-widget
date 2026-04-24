package dev.aria.memo.extA

import dev.aria.memo.data.notes.FrontMatterCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * P8 Agent 8a 扩展测试 — FrontMatterCodec.applyPin 参数化组合矩阵。
 *
 * 与 Agent 8 的 `FrontMatterCodecExtApplyPinTest` 不重叠：
 *  - Agent 8 只测 idempotent / 开头串 / parse-then-pinned=true 三个断言。
 *  - 本类**强调 `pinned` 布尔维度的笛卡尔积**：原文是否有 frontmatter、
 *    原 pin 值 vs 目标 pin 值、切换场景 (F2T / T2F / T2T / F2F)，并
 *    断言**完整输出字符串**（在可预测的简单输入上）。
 *
 * 算法（applyPin）：
 *  strip 原输入 → pin=true 时前缀 `---\npinned: true\n---\n\n<body>` 并保证
 *  以单一 `\n` 结尾；pin=false 直接返回 strip 结果。
 */
@RunWith(Parameterized::class)
class FrontMatterApplyPinMatrixTest(
    @JvmField val name: String,
    @JvmField val input: String,
    @JvmField val pinned: Boolean,
) {

    @Test
    fun `applyPin result is not null`() {
        assertNotNull(FrontMatterCodec.applyPin(input, pinned))
    }

    @Test
    fun `applyPin is idempotent`() {
        val once = FrontMatterCodec.applyPin(input, pinned)
        val twice = FrontMatterCodec.applyPin(once, pinned)
        assertEquals("applyPin idempotent for ($name, pinned=$pinned)", once, twice)
    }

    @Test
    fun `applyPin true starts with pin fm header`() {
        if (!pinned) return
        val out = FrontMatterCodec.applyPin(input, true)
        // Data-1 R4：用户 yaml + pin=true 时，pin 行会 merge 进用户 YAML 块顶部 →
        // 输出形如 `---\npinned: true\n<user keys>\n---\n<body>`。
        // 因此只断言以 `---\npinned: true\n` 开头，不再要求紧跟 `---\n`。
        assertTrue(
            "pinned output should start with `---\\npinned: true\\n` for $name, actual=${out.take(40)}",
            out.startsWith("---\npinned: true\n"),
        )
    }

    @Test
    fun `applyPin true parses back to pinned true`() {
        if (!pinned) return
        val out = FrontMatterCodec.applyPin(input, true)
        val parsed = FrontMatterCodec.parse(out)
        assertEquals("pinned key in parse result", "true", parsed.frontMatter["pinned"])
    }

    @Test
    fun `applyPin false does not prepend pin block`() {
        if (pinned) return
        val out = FrontMatterCodec.applyPin(input, false)
        assertFalse(
            "unpinned output should NOT begin with pin fm header for $name, actual=${out.take(40)}",
            out.startsWith("---\npinned: true\n---\n") ||
                out.startsWith("---\npinned: True\n---\n") ||
                out.startsWith("---\npinned: TRUE\n---\n"),
        )
    }

    @Test
    fun `applyPin true result ends with single newline`() {
        if (!pinned) return
        val out = FrontMatterCodec.applyPin(input, true)
        assertTrue("applyPin(true) should end with \\n", out.endsWith("\n"))
        // Not two newlines at the end — the impl trims trailing \n then adds one.
        assertFalse(
            "applyPin(true) output should not end with two newlines",
            out.endsWith("\n\n"),
        )
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): Collection<Array<Any>> {
            val list = mutableListOf<Array<Any>>()

            // Fix-5 精简：从 24 个 body × 2 = 48 case 压到 6 × 2 = 12 代表 case
            // （极端场景保留：空 / 多行 / 带 markdown 的三种前缀 / CJK / 已带 newline）
            val bodies = listOf(
                "empty" to "",
                "hello" to "hello",
                "multiline" to "line1\nline2",
                "chinese" to "今天很累",
                "md heading" to "# heading\nbody",
                "trailing newlines" to "hello\n\n\n",
            )
            for ((n, b) in bodies) {
                list += arrayOf<Any>("$n @true", b, true)
                list += arrayOf<Any>("$n @false", b, false)
            }

            // Fix-5 精简：从 20 个 fm body × 2 = 40 压到 4 × 2 = 8
            // （保留真正测 strict-bool gating 的 case：严格 pin / quoted pin / user yaml / CRLF）
            val fmBodies = listOf(
                "existing pin true" to "---\npinned: true\n---\nbody",
                "existing pinned quoted (not strict bool)" to "---\npinned: \"true\"\n---\nbody",
                "existing user yaml no pin" to "---\nauthor: a\n---\nbody",
                "existing CRLF pin true" to "---\r\npinned: true\r\n---\r\nbody",
            )
            for ((n, b) in fmBodies) {
                list += arrayOf<Any>("$n @true", b, true)
                list += arrayOf<Any>("$n @false", b, false)
            }
            // gen 10 已删 —— 纯放大，无独立业务
            return list
        }
    }
}

/**
 * applyPin 精确输出断言（小样本，逐字对比）。
 *
 * applyPin 契约（读 impl 得出）：
 *  1. pinned=true：strip 原块，然后
 *     - 对 stripped 做 `trimStart('\n')`
 *     - 前缀 `---\npinned: true\n---\n\n`
 *     - 对整体 `trimEnd('\n') + "\n"` 保证以单一 `\n` 结尾
 *  2. pinned=false：直接返回 strip 结果（strip 只吃 pin block，不动其他 YAML）。
 */
@RunWith(Parameterized::class)
class FrontMatterApplyPinExactOutputTest(
    @JvmField val name: String,
    @JvmField val input: String,
    @JvmField val pinned: Boolean,
    @JvmField val expected: String,
) {

    @Test
    fun `applyPin produces exact output`() {
        assertEquals(
            "applyPin($name, $pinned)",
            expected,
            FrontMatterCodec.applyPin(input, pinned),
        )
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): Collection<Array<Any>> {
            val list = mutableListOf<Array<Any>>()

            // --- pin=true 在空/短 body 上 --------------------------------------
            list += arrayOf<Any>("empty -> pin", "", true, "---\npinned: true\n---\n")
            list += arrayOf<Any>("hello -> pin", "hello", true, "---\npinned: true\n---\n\nhello\n")
            list += arrayOf<Any>("a -> pin", "a", true, "---\npinned: true\n---\n\na\n")
            list += arrayOf<Any>(
                "multiline -> pin", "line1\nline2", true,
                "---\npinned: true\n---\n\nline1\nline2\n",
            )
            list += arrayOf<Any>("chinese -> pin", "今天", true, "---\npinned: true\n---\n\n今天\n")
            list += arrayOf<Any>("emoji -> pin", "😀", true, "---\npinned: true\n---\n\n😀\n")
            list += arrayOf<Any>(
                "leading newlines -> pin", "\n\nhello", true,
                "---\npinned: true\n---\n\nhello\n",
            )
            list += arrayOf<Any>(
                "trailing newlines -> pin", "hello\n\n\n", true,
                "---\npinned: true\n---\n\nhello\n",
            )
            list += arrayOf<Any>(
                "body already with trailing LF -> pin", "hello\n", true,
                "---\npinned: true\n---\n\nhello\n",
            )

            // --- pin=true 覆盖已有 pin block -------------------------------------
            list += arrayOf<Any>(
                "existing pin true body -> pin", "---\npinned: true\n---\nbody", true,
                "---\npinned: true\n---\n\nbody\n",
            )
            list += arrayOf<Any>(
                "existing pin false body -> pin", "---\npinned: false\n---\nbody", true,
                "---\npinned: true\n---\n\nbody\n",
            )
            list += arrayOf<Any>(
                "existing pin True caps body -> pin", "---\npinned: True\n---\nbody", true,
                "---\npinned: true\n---\n\nbody\n",
            )
            // Data-1 R4: pin + user keys → strip pin, preserve user keys, then
            // applyPin(true) merges pin into the preserved user YAML block.
            list += arrayOf<Any>(
                "existing pin + author body -> pin",
                "---\npinned: true\nauthor: a\n---\nbody", true,
                "---\npinned: true\nauthor: a\n---\nbody\n",
            )
            list += arrayOf<Any>(
                "existing author + pin body -> pin",
                "---\nauthor: a\npinned: true\n---\nbody", true,
                "---\npinned: true\nauthor: a\n---\nbody\n",
            )
            list += arrayOf<Any>(
                "existing pin CRLF -> pin",
                "---\r\npinned: true\r\n---\r\nbody", true,
                "---\npinned: true\n---\n\nbody\n",
            )

            // --- pin=true 遇到无 pin 的用户 YAML 块 → merge pin 到用户块顶部 -----
            // Data-1 R4: 避免嵌套两层 `---` 让用户 YAML 被 markdown body 吞掉。
            list += arrayOf<Any>(
                "user yaml no pin -> pin", "---\nauthor: alice\n---\nbody", true,
                "---\npinned: true\nauthor: alice\n---\nbody\n",
            )
            list += arrayOf<Any>(
                "hr style -> pin", "---\n# heading\n---\nbody", true,
                "---\npinned: true\n---\n\n---\n# heading\n---\nbody\n",
            )
            list += arrayOf<Any>(
                "pinned quoted -> pin", "---\npinned: \"true\"\n---\nbody", true,
                "---\npinned: true\n---\n\n---\npinned: \"true\"\n---\nbody\n",
            )

            // --- pin=false 上：没有 pin fm → 原文 ------------------------------
            list += arrayOf<Any>("empty -> unpin", "", false, "")
            list += arrayOf<Any>("hello -> unpin", "hello", false, "hello")
            list += arrayOf<Any>("multiline -> unpin", "a\nb", false, "a\nb")
            list += arrayOf<Any>(
                "plain with trailing LF -> unpin", "hello\n", false, "hello\n",
            )
            list += arrayOf<Any>(
                "user yaml no pin -> unpin", "---\nauthor: a\n---\nbody", false,
                "---\nauthor: a\n---\nbody",
            )
            list += arrayOf<Any>(
                "hr style -> unpin", "---\n# heading\n---\nbody", false,
                "---\n# heading\n---\nbody",
            )
            list += arrayOf<Any>(
                "pinned quoted -> unpin", "---\npinned: \"true\"\n---\nbody", false,
                "---\npinned: \"true\"\n---\nbody",
            )
            list += arrayOf<Any>(
                "pinned numeric -> unpin", "---\npinned: 1\n---\nbody", false,
                "---\npinned: 1\n---\nbody",
            )

            // --- pin=false 上：有 pin fm → 吃掉 --------------------------------
            list += arrayOf<Any>(
                "existing pin true -> unpin", "---\npinned: true\n---\nbody", false, "body",
            )
            list += arrayOf<Any>(
                "existing pin false -> unpin", "---\npinned: false\n---\nbody", false, "body",
            )
            list += arrayOf<Any>(
                "existing pin True caps -> unpin",
                "---\npinned: True\n---\nbody", false, "body",
            )
            list += arrayOf<Any>(
                "existing pin FALSE caps -> unpin",
                "---\npinned: FALSE\n---\nbody", false, "body",
            )
            // Data-1 R4: pin + user key -> unpin 现在保留用户 YAML 键（只去 pin 行）。
            list += arrayOf<Any>(
                "existing pin + author -> unpin",
                "---\npinned: true\nauthor: a\n---\nbody", false,
                "---\nauthor: a\n---\nbody",
            )
            list += arrayOf<Any>(
                "existing CRLF pin -> unpin",
                "---\r\npinned: true\r\n---\r\nbody", false, "body",
            )
            list += arrayOf<Any>(
                "existing pin multi-line body -> unpin",
                "---\npinned: true\n---\nline1\nline2", false, "line1\nline2",
            )
            list += arrayOf<Any>(
                "existing pin body empty -> unpin",
                "---\npinned: true\n---\n", false, "",
            )
            list += arrayOf<Any>(
                "existing pin body with trailing LF -> unpin",
                "---\npinned: true\n---\nbody\n", false, "body\n",
            )
            list += arrayOf<Any>(
                "existing pin multi newlines after -> unpin",
                "---\npinned: true\n---\n\n\nbody", false, "body",
            )

            // --- more pin=true 组合，扩量 -----------------------------------------
            for (i in 1..10) {
                val body = "note $i\ncontent $i"
                list += arrayOf<Any>(
                    "gen pin $i",
                    body, true,
                    "---\npinned: true\n---\n\n$body\n",
                )
            }
            for (i in 1..10) {
                val body = "plain $i"
                list += arrayOf<Any>(
                    "gen unpin noop $i",
                    body, false, body,
                )
            }

            return list
        }
    }
}
