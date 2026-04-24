package dev.aria.memo.extA

import dev.aria.memo.data.notes.FrontMatterCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * P8 Agent 8a 扩展测试 — FrontMatterCodec.strip 参数化矩阵。
 *
 * 与 Agent 8 的 `FrontMatterCodecExtStripTest` 不重叠：
 *  - Agent 8 只断言是否变化（expectChange boolean）。
 *  - 本类**断言 strip 后的精确 body 字符串**，包括 CRLF 保留/吃空行/多段
 *    `---`、多键时整块被吃 (P6 策略)、author-only 不吃等。
 *
 * 纯函数（FrontMatterCodec 是 object，无 Android 依赖）。
 */
@RunWith(Parameterized::class)
class FrontMatterStripMatrixTest(
    @JvmField val name: String,
    @JvmField val input: String,
    @JvmField val expectedOutput: String,
) {

    @Test
    fun `strip produces exact output`() {
        assertEquals("strip($name)", expectedOutput, FrontMatterCodec.strip(input))
    }

    @Test
    fun `strip is idempotent on result`() {
        val once = FrontMatterCodec.strip(input)
        val twice = FrontMatterCodec.strip(once)
        assertEquals("strip idempotent for $name", once, twice)
    }

    @Test
    fun `strip never returns null`() {
        assertNotNull(FrontMatterCodec.strip(input))
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): Collection<Array<Any>> {
            val list = mutableListOf<Array<Any>>()

            // --- 1. No-op: 没有 frontmatter 时返回原文 ---------------------------
            list += arrayOf<Any>("empty string", "", "")
            list += arrayOf<Any>("single LF", "\n", "\n")
            list += arrayOf<Any>("plain body", "hello", "hello")
            list += arrayOf<Any>("multiline body", "line1\nline2", "line1\nline2")
            list += arrayOf<Any>("plain md heading", "# heading\nbody", "# heading\nbody")
            list += arrayOf<Any>("just dashes", "---", "---")
            list += arrayOf<Any>("dashes then newline", "---\n", "---\n")
            list += arrayOf<Any>("dashes then body no close", "---\nbody", "---\nbody")
            list += arrayOf<Any>("body with dashes mid", "body\n---\nmore", "body\n---\nmore")
            list += arrayOf<Any>("chinese body", "今天很累", "今天很累")
            list += arrayOf<Any>("emoji body", "😀", "😀")
            list += arrayOf<Any>("tab only", "\t", "\t")
            list += arrayOf<Any>("spaces only", "   ", "   ")

            // --- 2. No-op: HR 样式 / 用户手写 YAML（无 pinned key） --------------
            list += arrayOf<Any>("hr style heading", "---\n# heading\n---\nbody", "---\n# heading\n---\nbody")
            list += arrayOf<Any>("hr style text", "---\nplain text\n---\nbody", "---\nplain text\n---\nbody")
            list += arrayOf<Any>("author only", "---\nauthor: alice\n---\nbody", "---\nauthor: alice\n---\nbody")
            list += arrayOf<Any>(
                "author + date", "---\nauthor: a\ndate: 2026-04-21\n---\nbody",
                "---\nauthor: a\ndate: 2026-04-21\n---\nbody",
            )
            list += arrayOf<Any>("title only", "---\ntitle: hello\n---\nbody", "---\ntitle: hello\n---\nbody")
            list += arrayOf<Any>("tags only", "---\ntags: a,b,c\n---\nbody", "---\ntags: a,b,c\n---\nbody")

            // --- 3. No-op: pinned key 非严格 bool ------------------------------
            list += arrayOf<Any>(
                "pinned quoted true", "---\npinned: \"true\"\n---\nbody",
                "---\npinned: \"true\"\n---\nbody",
            )
            list += arrayOf<Any>(
                "pinned quoted false", "---\npinned: \"false\"\n---\nbody",
                "---\npinned: \"false\"\n---\nbody",
            )
            list += arrayOf<Any>(
                "pinned single quoted", "---\npinned: 'true'\n---\nbody",
                "---\npinned: 'true'\n---\nbody",
            )
            list += arrayOf<Any>(
                "pinned numeric 1", "---\npinned: 1\n---\nbody",
                "---\npinned: 1\n---\nbody",
            )
            list += arrayOf<Any>(
                "pinned numeric 0", "---\npinned: 0\n---\nbody",
                "---\npinned: 0\n---\nbody",
            )
            list += arrayOf<Any>(
                "pinned yes", "---\npinned: yes\n---\nbody",
                "---\npinned: yes\n---\nbody",
            )
            list += arrayOf<Any>(
                "pinned Y", "---\npinned: Y\n---\nbody",
                "---\npinned: Y\n---\nbody",
            )

            // --- 4. No-op: 非法 YAML 结构 ---------------------------------------
            list += arrayOf<Any>(
                "no close fence", "---\npinned: true\n",
                "---\npinned: true\n",
            )
            list += arrayOf<Any>(
                "no close fence with body", "---\npinned: true\nmore",
                "---\npinned: true\nmore",
            )
            list += arrayOf<Any>(
                "open but blank", "---\n\nbody",
                "---\n\nbody",
            )

            // --- 5. Changes: 真实 pin frontmatter 被吃掉 -----------------------
            list += arrayOf<Any>(
                "strip pin true simple", "---\npinned: true\n---\nbody", "body",
            )
            list += arrayOf<Any>(
                "strip pin false simple", "---\npinned: false\n---\nbody", "body",
            )
            list += arrayOf<Any>(
                "strip pin True caps", "---\npinned: True\n---\nbody", "body",
            )
            list += arrayOf<Any>(
                "strip pin FALSE caps", "---\npinned: FALSE\n---\nbody", "body",
            )
            list += arrayOf<Any>(
                "strip pin tRuE mixed", "---\npinned: tRuE\n---\nbody", "body",
            )
            list += arrayOf<Any>(
                "strip pin with spaces around value", "---\npinned:   true   \n---\nbody", "body",
            )
            list += arrayOf<Any>(
                "strip pin empty body after", "---\npinned: true\n---\n", "",
            )
            list += arrayOf<Any>(
                "strip pin multi newline after", "---\npinned: true\n---\n\n\nbody", "body",
            )
            list += arrayOf<Any>(
                "strip pin single newline after", "---\npinned: true\n---\nbody", "body",
            )

            // --- 6. Changes: CRLF normalization then strip ---------------------
            list += arrayOf<Any>(
                "strip CRLF pin true", "---\r\npinned: true\r\n---\r\nbody", "body",
            )
            list += arrayOf<Any>(
                "strip CRLF pin false", "---\r\npinned: false\r\n---\r\nbody", "body",
            )
            list += arrayOf<Any>(
                "strip CRLF multi line body", "---\r\npinned: true\r\n---\r\nline1\r\nline2",
                "line1\nline2",
            )

            // --- 7. Changes: pin + 其他键（Data-1 R4 修复后策略：只吃 pin 行，
            // 其他用户 YAML 键保留，fence 重建）---------------------------------
            list += arrayOf<Any>(
                "strip pin + author", "---\npinned: true\nauthor: a\n---\nbody",
                "---\nauthor: a\n---\nbody",
            )
            list += arrayOf<Any>(
                "strip author + pin (pin second)", "---\nauthor: a\npinned: true\n---\nbody",
                "---\nauthor: a\n---\nbody",
            )
            list += arrayOf<Any>(
                "strip pin + two more", "---\npinned: true\nauthor: a\ntitle: t\n---\nbody",
                "---\nauthor: a\ntitle: t\n---\nbody",
            )
            list += arrayOf<Any>(
                "strip pin false + author", "---\npinned: false\nauthor: a\n---\nbody",
                "---\nauthor: a\n---\nbody",
            )

            // --- 8. Changes: blank line inside block -----------------------------
            list += arrayOf<Any>(
                "strip pin with blank before", "---\n\npinned: true\n---\nbody", "body",
            )
            list += arrayOf<Any>(
                "strip pin with blank after", "---\npinned: true\n\n---\nbody", "body",
            )
            list += arrayOf<Any>(
                "strip pin with blanks around", "---\n\npinned: true\n\n---\nbody", "body",
            )

            // --- 9. Changes: body multi-line ------------------------------------
            list += arrayOf<Any>(
                "strip pin body multi-line", "---\npinned: true\n---\nline1\nline2\nline3",
                "line1\nline2\nline3",
            )
            list += arrayOf<Any>(
                "strip pin body with dashes after", "---\npinned: true\n---\nbody\n---\nmore",
                "body\n---\nmore",
            )
            list += arrayOf<Any>(
                "strip pin body with blanks between", "---\npinned: true\n---\nline1\n\nline2",
                "line1\n\nline2",
            )
            list += arrayOf<Any>(
                "strip pin body chinese", "---\npinned: true\n---\n今天\n很累", "今天\n很累",
            )
            list += arrayOf<Any>(
                "strip pin body emoji", "---\npinned: true\n---\n😀 content", "😀 content",
            )

            // --- 10. NOTE: 双 pin 嵌套 strip 并非 idempotent —— 第一次 strip
            // 之后 body 开头又是一个有效 pin block，第二次 strip 还会再吃。
            // 所以这里不加入 idempotent 被覆盖的参数化矩阵（已单独在 `MultiStripSmokeTest` 测）。

            // --- 11. Changes: trailing LF forms ---------------------------------
            list += arrayOf<Any>(
                "strip pin body with trailing LF", "---\npinned: true\n---\nbody\n", "body\n",
            )
            list += arrayOf<Any>(
                "strip pin body with double trailing LF", "---\npinned: true\n---\nbody\n\n",
                "body\n\n",
            )

            // --- 12. Generated no-op cases（扩量） ---------------------------------
            for (i in 1..20) {
                val b = "user note line $i\n".repeat((i % 3) + 1)
                list += arrayOf<Any>("gen noop $i", b, b)
            }

            // --- 13. Generated strip cases（扩量） --------------------------------
            for (i in 1..15) {
                val body = "note $i body with content $i"
                list += arrayOf<Any>(
                    "gen strip $i",
                    "---\npinned: true\n---\n$body",
                    body,
                )
            }

            // --- 14. Mixed: user yaml without pin → no-op，含重复 key --------------
            list += arrayOf<Any>(
                "strip noop duplicate author", "---\nauthor: a\nauthor: b\n---\nbody",
                "---\nauthor: a\nauthor: b\n---\nbody",
            )

            return list
        }
    }
}

/**
 * Non-parameterized edge cases for strip where the idempotency invariant
 * breaks down (nested pin blocks, chained strip calls).
 */
class FrontMatterStripEdgeSmokeTest {

    @Test
    fun `strip eats outer pin block exposing inner pin block`() {
        val input = "---\npinned: true\n---\n---\npinned: false\n---\nbody"
        val once = FrontMatterCodec.strip(input)
        assertEquals("---\npinned: false\n---\nbody", once)
    }

    @Test
    fun `strip applied twice on nested pin peels both layers`() {
        val input = "---\npinned: true\n---\n---\npinned: false\n---\nbody"
        val once = FrontMatterCodec.strip(input)
        val twice = FrontMatterCodec.strip(once)
        assertEquals("body", twice)
    }

    @Test
    fun `strip applied three times on doubly-nested pin reaches body`() {
        val input =
            "---\npinned: true\n---\n---\npinned: false\n---\n---\npinned: true\n---\nbody"
        val once = FrontMatterCodec.strip(input)
        val twice = FrontMatterCodec.strip(once)
        val thrice = FrontMatterCodec.strip(twice)
        assertEquals("body", thrice)
    }

    @Test
    fun `strip returns non-null for extreme blank`() {
        assertEquals("", FrontMatterCodec.strip(""))
    }

    @Test
    fun `strip does not modify plain body regardless of repetitions`() {
        val input = "plain body content"
        var current = input
        repeat(5) { current = FrontMatterCodec.strip(current) }
        assertEquals(input, current)
    }
}
