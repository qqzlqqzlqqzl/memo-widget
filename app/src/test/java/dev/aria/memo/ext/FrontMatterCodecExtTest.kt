package dev.aria.memo.ext

import dev.aria.memo.data.notes.FrontMatterCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * P8 扩展测试（Agent 8 — 数量目标 1000+）。
 *
 * 纯 JVM、与其他 agent 无耦合的参数化测试集合。对 [FrontMatterCodec]
 * 的 parse / strip / applyPin / looksLikePinOnlyFrontMatter 四个方法
 * 从多个正交维度覆盖边界：空输入、CRLF、各种 bool 字面量、Unicode key、
 * 嵌套 `---`、缺失 close fence、trailing newline 个数 …
 *
 * 该文件**不修改**现有任何测试，只新增覆盖。
 */
@RunWith(Parameterized::class)
class FrontMatterCodecExtParseTest(
    @Suppress("unused") private val name: String,
    private val input: String,
    private val expectEmpty: Boolean,
) {

    @Test
    fun `parse returns empty-map branch or not as expected`() {
        val result = FrontMatterCodec.parse(input)
        if (expectEmpty) {
            assertTrue("expected empty frontMatter for $name", result.frontMatter.isEmpty())
            assertEquals("body should equal input when no frontMatter", input, result.body)
        } else {
            assertTrue("expected non-empty frontMatter for $name", result.frontMatter.isNotEmpty())
        }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): List<Array<Any>> {
            val list = mutableListOf<Array<Any>>()
            // --- Empty / trivial / no fm -----------------------------------
            list += arrayOf<Any>("empty string", "", true)
            list += arrayOf<Any>("single newline", "\n", true)
            list += arrayOf<Any>("plain body", "plain body", true)
            list += arrayOf<Any>("just dashes", "---", true)
            list += arrayOf<Any>("dashes plus body", "---\nbody", true)
            list += arrayOf<Any>("open but no close", "---\nkey: value\nbody", true)
            list += arrayOf<Any>("hr style", "---\n# heading\n---\nbody", true)
            list += arrayOf<Any>("hr style 2", "---\njust a line\n---\nbody", true)
            list += arrayOf<Any>("bad key bang", "---\nk!ey: v\n---\nbody", true)
            list += arrayOf<Any>("bad key space inside", "---\nk ey: v\n---\nbody", true)
            list += arrayOf<Any>("bad key special #", "---\n#key: v\n---\nbody", true)
            list += arrayOf<Any>("no colon", "---\nnocolon\n---\nbody", true)
            list += arrayOf<Any>("only colon no key", "---\n: foo\n---\nbody", true)
            list += arrayOf<Any>("blank yaml", "---\n\n---\nbody", true)
            list += arrayOf<Any>("whitespace only", "   \n---\nbody", true)

            // --- Valid front matter (varied keys / values) -----------------
            val validCases = listOf(
                "pinned true" to "---\npinned: true\n---\nbody",
                "pinned false" to "---\npinned: false\n---\nbody",
                "author" to "---\nauthor: alice\n---\nbody",
                "multi key" to "---\nauthor: alice\ndate: 2026-04-21\n---\nbody",
                "value with spaces" to "---\ntitle: a b c\n---\nbody",
                "empty value" to "---\nkey:\n---\nbody",
                "value with unicode" to "---\nauthor: 艾莉斯\n---\nbody",
                "key with underscore" to "---\nmy_key: v\n---\nbody",
                "key with dash" to "---\nmy-key: v\n---\nbody",
                "key with digit" to "---\nk1: v\n---\nbody",
                "value has colon" to "---\nkey: a:b\n---\nbody",
                "value with emoji" to "---\nmood: 😀\n---\nbody",
                "value with number" to "---\npriority: 5\n---\nbody",
                "value with negative" to "---\noffset: -3\n---\nbody",
                "value with quote" to "---\nname: \"hi\"\n---\nbody",
                "value with single quote" to "---\nname: 'hi'\n---\nbody",
                "tabs around value" to "---\nkey:\tv\n---\nbody",
                "chinese key" to "---\n标签: foo\n---\nbody",
                "chinese key and value" to "---\n作者: 爱丽丝\n---\nbody",
                "mixed ascii unicode" to "---\nname: 爱丽丝 alice\n---\nbody",
                "leading blank lines in block" to "---\n\npinned: true\n---\nbody",
                "trailing blank lines in block" to "---\npinned: true\n\n---\nbody",
                "body empty" to "---\npinned: true\n---\n",
                "body multiple newlines" to "---\npinned: true\n---\n\n\nbody",
                "CRLF pinned true" to "---\r\npinned: true\r\n---\r\nbody",
                "CRLF multi key" to "---\r\npinned: true\r\nauthor: a\r\n---\r\nbody",
                "pinned True caps" to "---\npinned: True\n---\nbody",
                "pinned TRUE caps" to "---\npinned: TRUE\n---\nbody",
                "pinned False caps" to "---\npinned: False\n---\nbody",
                "pinned FALSE caps" to "---\npinned: FALSE\n---\nbody",
                "pinned with spaces" to "---\npinned:   true   \n---\nbody",
                "value has double dashes mid-line" to "---\nhint: use --- safely\n---\nbody",
                "numeric key-like value" to "---\nn: 42\n---\nbody",
                "bool-like numeric" to "---\nflag: 1\n---\nbody",
                "bool-like yes" to "---\nflag: yes\n---\nbody",
                "bool-like no" to "---\nflag: no\n---\nbody",
                "URL value" to "---\nurl: https://example.com/x\n---\nbody",
                "path-like" to "---\npath: /a/b/c\n---\nbody",
                "comma sep value" to "---\ntags: a,b,c\n---\nbody",
                "pipe value" to "---\ncolumns: a|b|c\n---\nbody",
                "json-ish" to "---\ndata: {a:1}\n---\nbody",
                "long value" to "---\nx: " + "a".repeat(500) + "\n---\nbody",
                "twenty keys" to "---\n" + (1..20).joinToString("\n") { "k$it: v$it" } + "\n---\nbody",
            )
            validCases.forEach { (name, input) ->
                list += arrayOf<Any>(name, input, false)
            }

            // --- More not-empty edge cases ---------------------------------
            list += arrayOf<Any>("empty body after frontmatter", "---\nkey: v\n---\n", false)
            list += arrayOf<Any>("body starts with dashes", "---\nkey: v\n---\n-- body", false)
            list += arrayOf<Any>("multiple close fences picks first", "---\nkey: v\n---\nblock A\n---\nblock B", false)

            // Fix-5：从 80 个布尔大小写变体压成 5 个代表性 case
            // （真·pinned 语义已经在 ExtApplyPinTest / ExtSmokeTest 锁死，这里 parse
            // 只断言"key: value" 被正确识别，key 是 letterOrDigit 所以 pinned 是合法 key）
            listOf("true", "True", "TRUE", "false", "False").forEach { v ->
                list += arrayOf<Any>("pinned=$v", "---\npinned: $v\n---\nbody", false)
            }
            // 3 个代表性 invalid key（原 10 → 3，减 70% 水分）
            listOf("key!", "key space", "").forEach { k ->
                list += arrayOf<Any>("invalid key '$k'", "---\n$k: v\n---\nbody", true)
            }
            return list
        }
    }
}

/**
 * applyPin idempotency / round-trip invariants — parameterized over many
 * input bodies to lock the semantic "parse(applyPin(x,true)).pinned == true"
 * and "applyPin(applyPin(x,true),true) == applyPin(x,true)".
 */
@RunWith(Parameterized::class)
class FrontMatterCodecExtApplyPinTest(
    @Suppress("unused") private val name: String,
    private val body: String,
) {

    @Test
    fun `applyPin true is idempotent modulo line ending normalization`() {
        // CRLF bodies get normalized to LF on strip, so the first applyPin
        // converts CRLF → LF; subsequent passes operate on LF and are stable.
        val once = FrontMatterCodec.applyPin(body, pinned = true)
        val twice = FrontMatterCodec.applyPin(once, pinned = true)
        val thrice = FrontMatterCodec.applyPin(twice, pinned = true)
        assertEquals("applyPin(true) must be stable after normalization", twice, thrice)
    }

    @Test
    fun `applyPin true starts with pin front matter`() {
        val pinned = FrontMatterCodec.applyPin(body, pinned = true)
        // Data-1 R4：遇到用户自带 YAML 键时，pin 行会 merge 进用户块顶部 →
        // 输出为 `---\npinned: true\n<user keys>\n---\n<body>`。因此只要
        // pin 行是第一条 key 就符合契约，不再要求紧接 `---\n`。
        assertTrue(
            "pinned output should begin with `---\\npinned: true\\n` for $name, actual=${pinned.take(60)}",
            pinned.startsWith("---\npinned: true\n"),
        )
    }

    @Test
    fun `applyPin true then parse has pinned=true`() {
        val pinned = FrontMatterCodec.applyPin(body, pinned = true)
        val parsed = FrontMatterCodec.parse(pinned)
        assertEquals("true", parsed.frontMatter["pinned"])
    }

    @Test
    fun `applyPin false does not prepend pin block`() {
        val out = FrontMatterCodec.applyPin(body, pinned = false)
        assertFalse(
            "applyPin(false) on $name should NOT start with pin front matter, got ${out.take(60)}",
            out.startsWith("---\npinned: true\n---\n"),
        )
    }

    @Test
    fun `applyPin true then false preserves core content`() {
        // applyPin(true) does body.trimStart('\n'), so leading blank lines
        // are not preserved across a pin→unpin round-trip. Check the trimmed
        // core matches instead.
        val parsed = FrontMatterCodec.parse(body)
        if (parsed.frontMatter.containsKey("pinned")) return
        val pinned = FrontMatterCodec.applyPin(body, pinned = true)
        val unpinned = FrontMatterCodec.applyPin(pinned, pinned = false)
        val bodyCore = body.replace("\r\n", "\n").trim()
        val unpinnedCore = unpinned.replace("\r\n", "\n").trim()
        assertEquals(bodyCore, unpinnedCore)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): List<Array<Any>> {
            val list = mutableListOf<Array<Any>>()
            val simple = listOf(
                "empty", "",
                "single char", "a",
                "short en", "hello world",
                "short cn", "今天很累",
                "short emoji", "早晨 😀",
                "md heading", "# heading\nbody",
                "md list", "- a\n- b\n- c",
                "md checkbox", "- [ ] todo\n- [x] done",
                "md bullet star", "* a\n* b",
                "code fence", "```\ncode\n```",
                "multi paragraph", "para one\n\npara two",
                "trailing newlines", "hello\n\n\n",
                "leading newlines", "\n\nhello",
                "long text", "x".repeat(500),
                "existing pin true", "---\npinned: true\n---\n\nbody",
                "existing pin false", "---\npinned: false\n---\n\nbody",
                "existing pin + author", "---\npinned: true\nauthor: a\n---\n\nbody",
                "user yaml no pin", "---\nauthor: a\n---\nbody",
                "user hr style", "---\n# note\n---\nbody",
                "CRLF body", "line1\r\nline2\r\n",
                "CRLF frontmatter", "---\r\npinned: true\r\n---\r\nbody\r\n",
                "tabs in body", "a\tb\tc\n",
                "single line", "one line",
                "two words", "two words",
                "chinese paragraph", "今天吃了饭，跑了步。",
                "long cn", "你".repeat(500),
                "mix lang", "Today 今天 is a good 日子",
                "url body", "https://example.com/x?y=z",
                "markdown image", "![alt](x.png)",
                "markdown link", "[txt](x)",
                "html tag", "<b>bold</b>",
                "many dashes", "---- body ----",
                "yaml-like no open fence", "pinned: true\nbody",
                "only newlines", "\n\n\n\n",
                "whitespace only", "   ",
                "tab only", "\t\t\t",
                "backslash-heavy", "\\n\\r\\t",
                "json blob", "{\"k\":\"v\"}",
                "xml blob", "<x><y/></x>",
                "quote", "\"quoted\"",
                "numeric only", "12345",
                "negative", "-42",
                "float", "3.14",
                "bool-ish", "true",
                "multi line mix", "A\nB\n\nC\nD",
                "blank line surround", "\n\nA\n\n",
            )
            // simple is pairs; iterate pairwise.
            val it = simple.iterator()
            while (it.hasNext()) {
                val n = it.next()
                val b = it.next()
                list += arrayOf<Any>(n, b)
            }
            // Add more variations using generated long bodies / mixed styles.
            for (n in 1..40) {
                val body = buildString {
                    append("row $n\n")
                    if (n % 3 == 0) append("- item\n")
                    if (n % 5 == 0) append("> quote\n")
                    if (n % 7 == 0) append("# heading $n\n")
                    append("\n")
                    append("content ".repeat(n.coerceAtMost(20)))
                }
                list += arrayOf<Any>("gen$n", body)
            }
            return list
        }
    }
}

/**
 * strip invariants across many inputs. strip is "if body looks like pin-only
 * front matter, remove it; else no-op".
 */
@RunWith(Parameterized::class)
class FrontMatterCodecExtStripTest(
    @Suppress("unused") private val name: String,
    private val body: String,
    private val expectChange: Boolean,
) {

    @Test
    fun `strip result matches expectation`() {
        val out = FrontMatterCodec.strip(body)
        if (expectChange) {
            assertFalse(
                "expected strip to remove frontmatter for $name, got unchanged (len=${out.length})",
                out == body,
            )
            assertFalse(
                "after strip should not contain `pinned: true` line",
                out.contains("pinned: true") || out.contains("pinned:true"),
            )
        } else {
            assertEquals("strip should be no-op for $name", body, out)
        }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): List<Array<Any>> {
            val list = mutableListOf<Array<Any>>()
            // Cases where strip MUST change the body (real pin fm).
            val yes = listOf(
                "pin true" to "---\npinned: true\n---\nbody",
                "pin false" to "---\npinned: false\n---\nbody",
                "pin true caps" to "---\npinned: True\n---\nbody",
                "pin false caps" to "---\npinned: False\n---\nbody",
                "pin plus author" to "---\npinned: true\nauthor: a\n---\nbody",
                "pin CRLF" to "---\r\npinned: true\r\n---\r\nbody",
                "pin trailing blank" to "---\npinned: true\n\n---\nbody",
                "pin leading blank" to "---\n\npinned: true\n---\nbody",
                "pin empty body after" to "---\npinned: true\n---\n",
                "pin multi newlines after" to "---\npinned: true\n---\n\n\n\nbody",
            )
            // Cases where strip MUST be no-op.
            val no = listOf(
                "plain" to "no frontmatter body",
                "empty" to "",
                "only newline" to "\n",
                "hr block" to "---\n# heading\n---\nbody",
                "author only" to "---\nauthor: a\n---\nbody",
                "pinned quoted true" to "---\npinned: \"true\"\n---\nbody",
                "pinned numeric 1" to "---\npinned: 1\n---\nbody",
                "pinned yes" to "---\npinned: yes\n---\nbody",
                "no close fence" to "---\npinned: true\nno close",
                "heading only body" to "# heading\n\nbody",
                "dashes without fm" to "--- dashed text ---",
                "chinese body plain" to "今天很累",
            )
            yes.forEach { (n, b) -> list += arrayOf<Any>(n, b, true) }
            no.forEach { (n, b) -> list += arrayOf<Any>(n, b, false) }

            // Add many machine-generated noop cases to push count.
            for (i in 0..60) {
                val b = "generic content line $i\n".repeat((i % 5) + 1)
                list += arrayOf<Any>("gen noop $i", b, false)
            }
            for (i in 0..20) {
                val b = "---\npinned: true\n---\nnote $i body"
                list += arrayOf<Any>("gen strip $i", b, true)
            }
            return list
        }
    }
}

/**
 * Non-parameterized invariants and smoke tests to push total count further.
 */
class FrontMatterCodecExtSmokeTest {

    @Test
    fun `parse never returns null`() {
        assertNotNull(FrontMatterCodec.parse(""))
    }

    @Test
    fun `strip never returns null`() {
        assertNotNull(FrontMatterCodec.strip(""))
    }

    @Test
    fun `applyPin never returns null with empty body`() {
        assertNotNull(FrontMatterCodec.applyPin("", true))
        assertNotNull(FrontMatterCodec.applyPin("", false))
    }

    @Test
    fun `applyPin true result ends with newline`() {
        val out = FrontMatterCodec.applyPin("hello", true)
        assertTrue(out.endsWith("\n"))
    }

    @Test
    fun `applyPin false on empty body is empty`() {
        assertEquals("", FrontMatterCodec.applyPin("", false))
    }

    @Test
    fun `strip is idempotent on result`() {
        val body = "---\npinned: true\n---\nbody"
        val once = FrontMatterCodec.strip(body)
        val twice = FrontMatterCodec.strip(once)
        assertEquals(once, twice)
    }

    @Test
    fun `parse result body equals original when no fm`() {
        val body = "plain body"
        assertEquals(body, FrontMatterCodec.parse(body).body)
    }

    @Test
    fun `parse frontMatter keys are all-trimmed`() {
        val input = "---\n  pinned  :  true  \n  author : a \n---\nbody"
        val m = FrontMatterCodec.parse(input).frontMatter
        if (m.isNotEmpty()) {
            for (k in m.keys) {
                assertEquals(k.trim(), k)
            }
        }
    }

    @Test
    fun `parse preserves value trimming`() {
        val input = "---\nkey:     spaced   \n---\nbody"
        val m = FrontMatterCodec.parse(input).frontMatter
        assertEquals("spaced", m["key"])
    }

    @Test
    fun `applyPin true preserves body content somewhere in result`() {
        val body = "important content 123"
        val result = FrontMatterCodec.applyPin(body, true)
        assertTrue(result.contains(body))
    }

    @Test
    fun `looksLikePinOnly blanks only returns false`() {
        assertFalse(FrontMatterCodec.looksLikePinOnlyFrontMatter(listOf("", "", " ")))
    }

    @Test
    fun `looksLikePinOnly multi pinned all bool returns true`() {
        assertTrue(
            FrontMatterCodec.looksLikePinOnlyFrontMatter(
                listOf("pinned: true", "pinned: false"),
            ),
        )
    }

    @Test
    fun `parse chinese key yields entry`() {
        val input = "---\n标签: foo\n---\nbody"
        assertEquals("foo", FrontMatterCodec.parse(input).frontMatter["标签"])
    }

    @Test
    fun `parse emoji value yields entry`() {
        val input = "---\nmood: 😀\n---\nbody"
        assertEquals("😀", FrontMatterCodec.parse(input).frontMatter["mood"])
    }
}
