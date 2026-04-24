package dev.aria.memo.extA

import dev.aria.memo.data.notes.FrontMatterCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * P8 Agent 8a 扩展测试 — FrontMatterCodec.parse 参数化矩阵。
 *
 * 与 Agent 8 的 `dev.aria.memo.ext.FrontMatterCodecExtParseTest` 不重叠：
 *  - Agent 8 只断言 `expectEmpty` 两态（frontMatter 是否为空）。
 *  - 本类**断言每条 case 解析后的 frontMatter + body 精确值**，覆盖
 *    trim 行为、value-preserving、多键保留顺序、blank-line tolerance。
 *
 * 纯函数测试，无 Android 依赖；FrontMatterCodec 为 object，稳定。
 * 不修改任何已有文件，不修改生产代码。
 */
@RunWith(Parameterized::class)
class FrontMatterParseMatrixTest(
    @JvmField val name: String,
    @JvmField val input: String,
    @JvmField val expectedMap: Map<String, String>,
    @JvmField val expectedBody: String,
) {

    @Test
    fun `parse produces exact frontMatter map`() {
        val result = FrontMatterCodec.parse(input)
        assertEquals("frontMatter for $name", expectedMap, result.frontMatter)
    }

    @Test
    fun `parse produces exact body`() {
        val result = FrontMatterCodec.parse(input)
        assertEquals("body for $name", expectedBody, result.body)
    }

    @Test
    fun `parse never returns null`() {
        assertNotNull(FrontMatterCodec.parse(input))
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): Collection<Array<Any>> {
            val list = mutableListOf<Array<Any>>()

            // --- 1. 空输入 & 退化情况（empty frontmatter, body == input） ------
            list += arrayOf<Any>("empty string", "", emptyMap<String, String>(), "")
            list += arrayOf<Any>("single LF", "\n", emptyMap<String, String>(), "\n")
            list += arrayOf<Any>("two LFs", "\n\n", emptyMap<String, String>(), "\n\n")
            list += arrayOf<Any>("single space", " ", emptyMap<String, String>(), " ")
            list += arrayOf<Any>("tab only", "\t", emptyMap<String, String>(), "\t")
            list += arrayOf<Any>("plain text", "hello", emptyMap<String, String>(), "hello")
            list += arrayOf<Any>("plain md heading", "# note", emptyMap<String, String>(), "# note")
            list += arrayOf<Any>("only three dashes", "---", emptyMap<String, String>(), "---")
            list += arrayOf<Any>("dashes then newline only", "---\n", emptyMap<String, String>(), "---\n")
            list += arrayOf<Any>("dashes then body no close", "---\nbody", emptyMap<String, String>(), "---\nbody")
            list += arrayOf<Any>("open no close with kv", "---\nk: v\nbody", emptyMap<String, String>(), "---\nk: v\nbody")

            // --- 2. 非法 YAML（HR 样式 / 非 key:value / 无效 key） --------------
            list += arrayOf<Any>(
                "hr style heading", "---\n# title\n---\nrest",
                emptyMap<String, String>(), "---\n# title\n---\nrest",
            )
            list += arrayOf<Any>(
                "hr style plain line", "---\nplain line\n---\nrest",
                emptyMap<String, String>(), "---\nplain line\n---\nrest",
            )
            list += arrayOf<Any>(
                "no colon at all", "---\nno colon line\n---\nb",
                emptyMap<String, String>(), "---\nno colon line\n---\nb",
            )
            list += arrayOf<Any>(
                "empty key with colon leading", "---\n: v\n---\nb",
                emptyMap<String, String>(), "---\n: v\n---\nb",
            )
            list += arrayOf<Any>(
                "key with space inside", "---\nk ey: v\n---\nb",
                emptyMap<String, String>(), "---\nk ey: v\n---\nb",
            )
            list += arrayOf<Any>(
                "key with bang", "---\nk!: v\n---\nb",
                emptyMap<String, String>(), "---\nk!: v\n---\nb",
            )
            list += arrayOf<Any>(
                "key with hash", "---\n#k: v\n---\nb",
                emptyMap<String, String>(), "---\n#k: v\n---\nb",
            )
            list += arrayOf<Any>(
                "key with dot invalid", "---\nk.k: v\n---\nb",
                emptyMap<String, String>(), "---\nk.k: v\n---\nb",
            )
            list += arrayOf<Any>(
                "key with at sign", "---\nk@k: v\n---\nb",
                emptyMap<String, String>(), "---\nk@k: v\n---\nb",
            )
            list += arrayOf<Any>(
                "key with slash", "---\nk/k: v\n---\nb",
                emptyMap<String, String>(), "---\nk/k: v\n---\nb",
            )
            list += arrayOf<Any>(
                "blank yaml block", "---\n\n---\nb",
                emptyMap<String, String>(), "---\n\n---\nb",
            )

            // --- 3. 简单合法：单键 --------------------------------------------
            list += arrayOf<Any>(
                "single pinned true", "---\npinned: true\n---\nbody",
                mapOf("pinned" to "true"), "body",
            )
            list += arrayOf<Any>(
                "single pinned false", "---\npinned: false\n---\nbody",
                mapOf("pinned" to "false"), "body",
            )
            list += arrayOf<Any>(
                "single pinned True caps", "---\npinned: True\n---\nbody",
                mapOf("pinned" to "True"), "body",
            )
            list += arrayOf<Any>(
                "single pinned FALSE caps", "---\npinned: FALSE\n---\nbody",
                mapOf("pinned" to "FALSE"), "body",
            )
            list += arrayOf<Any>(
                "single pinned numeric 1", "---\npinned: 1\n---\nbody",
                mapOf("pinned" to "1"), "body",
            )
            list += arrayOf<Any>(
                "single pinned numeric 0", "---\npinned: 0\n---\nbody",
                mapOf("pinned" to "0"), "body",
            )
            list += arrayOf<Any>(
                "single pinned yes", "---\npinned: yes\n---\nbody",
                mapOf("pinned" to "yes"), "body",
            )
            list += arrayOf<Any>(
                "single pinned no", "---\npinned: no\n---\nbody",
                mapOf("pinned" to "no"), "body",
            )
            list += arrayOf<Any>(
                "single author", "---\nauthor: alice\n---\nbody",
                mapOf("author" to "alice"), "body",
            )
            list += arrayOf<Any>(
                "single title", "---\ntitle: Hello\n---\nbody",
                mapOf("title" to "Hello"), "body",
            )

            // --- 4. 值的 trimming / 空格耐受 ------------------------------------
            list += arrayOf<Any>(
                "value with leading spaces", "---\nkey:     v\n---\nb",
                mapOf("key" to "v"), "b",
            )
            list += arrayOf<Any>(
                "value with trailing spaces", "---\nkey: v     \n---\nb",
                mapOf("key" to "v"), "b",
            )
            list += arrayOf<Any>(
                "value with surrounding spaces", "---\nkey:    v    \n---\nb",
                mapOf("key" to "v"), "b",
            )
            list += arrayOf<Any>(
                "key with leading spaces", "---\n    key: v\n---\nb",
                mapOf("key" to "v"), "b",
            )
            list += arrayOf<Any>(
                "key with trailing spaces", "---\nkey    : v\n---\nb",
                mapOf("key" to "v"), "b",
            )
            list += arrayOf<Any>(
                "empty value", "---\nkey:\n---\nb",
                mapOf("key" to ""), "b",
            )
            list += arrayOf<Any>(
                "empty value with space", "---\nkey: \n---\nb",
                mapOf("key" to ""), "b",
            )
            list += arrayOf<Any>(
                "tab around value", "---\nkey:\tv\n---\nb",
                mapOf("key" to "v"), "b",
            )

            // --- 5. 多键（2~5 keys） -------------------------------------------
            list += arrayOf<Any>(
                "two keys", "---\npinned: true\nauthor: a\n---\nbody",
                mapOf("pinned" to "true", "author" to "a"), "body",
            )
            list += arrayOf<Any>(
                "three keys", "---\npinned: true\nauthor: a\ntitle: t\n---\nb",
                mapOf("pinned" to "true", "author" to "a", "title" to "t"), "b",
            )
            list += arrayOf<Any>(
                "five keys", "---\na: 1\nb: 2\nc: 3\nd: 4\ne: 5\n---\nb",
                mapOf("a" to "1", "b" to "2", "c" to "3", "d" to "4", "e" to "5"), "b",
            )

            // --- 6. 重复 key — 后者覆盖前者 -----------------------------------
            list += arrayOf<Any>(
                "duplicate key last wins", "---\npinned: false\npinned: true\n---\nb",
                mapOf("pinned" to "true"), "b",
            )
            list += arrayOf<Any>(
                "duplicate three", "---\nk: 1\nk: 2\nk: 3\n---\nb",
                mapOf("k" to "3"), "b",
            )

            // --- 7. CRLF normalization -----------------------------------------
            list += arrayOf<Any>(
                "CRLF single key", "---\r\npinned: true\r\n---\r\nbody",
                mapOf("pinned" to "true"), "body",
            )
            list += arrayOf<Any>(
                "CRLF two keys", "---\r\npinned: true\r\nauthor: a\r\n---\r\nbody",
                mapOf("pinned" to "true", "author" to "a"), "body",
            )
            list += arrayOf<Any>(
                "CRLF with crlf body", "---\r\npinned: true\r\n---\r\nline1\r\nline2",
                mapOf("pinned" to "true"), "line1\nline2",
            )

            // --- 8. Unicode keys/values ----------------------------------------
            list += arrayOf<Any>(
                "unicode value", "---\nauthor: 艾莉斯\n---\nbody",
                mapOf("author" to "艾莉斯"), "body",
            )
            list += arrayOf<Any>(
                "unicode emoji value", "---\nmood: 😀\n---\nbody",
                mapOf("mood" to "😀"), "body",
            )
            list += arrayOf<Any>(
                "unicode mixed value", "---\nname: alice 爱丽丝 😀\n---\nbody",
                mapOf("name" to "alice 爱丽丝 😀"), "body",
            )
            // 中文 key 在 parse 中不合法（key.all { it.isLetterOrDigit() ... }
            // 对 CJK 统一表意文字返回 true，所以其实中文 key 是合法的）
            list += arrayOf<Any>(
                "chinese key", "---\n标签: foo\n---\nbody",
                mapOf("标签" to "foo"), "body",
            )
            list += arrayOf<Any>(
                "chinese key and value", "---\n作者: 爱丽丝\n---\nbody",
                mapOf("作者" to "爱丽丝"), "body",
            )

            // --- 9. 合法 key 字符集 --------------------------------------------
            list += arrayOf<Any>(
                "key underscore", "---\nmy_key: v\n---\nb",
                mapOf("my_key" to "v"), "b",
            )
            list += arrayOf<Any>(
                "key dash", "---\nmy-key: v\n---\nb",
                mapOf("my-key" to "v"), "b",
            )
            list += arrayOf<Any>(
                "key digits", "---\nk123: v\n---\nb",
                mapOf("k123" to "v"), "b",
            )
            list += arrayOf<Any>(
                "key all digits", "---\n007: v\n---\nb",
                mapOf("007" to "v"), "b",
            )
            list += arrayOf<Any>(
                "key single char", "---\nk: v\n---\nb",
                mapOf("k" to "v"), "b",
            )

            // --- 10. value 带冒号（colon in value） ----------------------------
            list += arrayOf<Any>(
                "value has one colon", "---\nkey: a:b\n---\nbody",
                mapOf("key" to "a:b"), "body",
            )
            list += arrayOf<Any>(
                "value has two colons", "---\nkey: a:b:c\n---\nbody",
                mapOf("key" to "a:b:c"), "body",
            )
            list += arrayOf<Any>(
                "value is URL", "---\nurl: https://ex.com/x\n---\nbody",
                mapOf("url" to "https://ex.com/x"), "body",
            )
            list += arrayOf<Any>(
                "value is time", "---\nat: 14:30:00\n---\nbody",
                mapOf("at" to "14:30:00"), "body",
            )

            // --- 11. value 带引号（不剥） --------------------------------------
            list += arrayOf<Any>(
                "value double-quoted", "---\nkey: \"v\"\n---\nb",
                mapOf("key" to "\"v\""), "b",
            )
            list += arrayOf<Any>(
                "value single-quoted", "---\nkey: 'v'\n---\nb",
                mapOf("key" to "'v'"), "b",
            )

            // --- 12. body 保留 / 空行吃掉 --------------------------------------
            list += arrayOf<Any>(
                "body empty after fm", "---\npinned: true\n---\n",
                mapOf("pinned" to "true"), "",
            )
            list += arrayOf<Any>(
                "body after single newline", "---\npinned: true\n---\nbody",
                mapOf("pinned" to "true"), "body",
            )
            list += arrayOf<Any>(
                "body after double newline", "---\npinned: true\n---\n\nbody",
                mapOf("pinned" to "true"), "body",
            )
            list += arrayOf<Any>(
                "body after triple newline", "---\npinned: true\n---\n\n\nbody",
                mapOf("pinned" to "true"), "body",
            )
            list += arrayOf<Any>(
                "body multi-line", "---\npinned: true\n---\nline1\nline2\nline3",
                mapOf("pinned" to "true"), "line1\nline2\nline3",
            )

            // --- 13. frontmatter block 内包含空行（合法：空行被跳过） -----------
            list += arrayOf<Any>(
                "blank line inside block", "---\npinned: true\n\nauthor: a\n---\nb",
                mapOf("pinned" to "true", "author" to "a"), "b",
            )
            list += arrayOf<Any>(
                "leading blank in block", "---\n\npinned: true\n---\nb",
                mapOf("pinned" to "true"), "b",
            )
            list += arrayOf<Any>(
                "trailing blank in block", "---\npinned: true\n\n---\nb",
                mapOf("pinned" to "true"), "b",
            )

            // --- 14. 嵌套 / 多 --- fence — parser 取第一个 close fence ---------
            list += arrayOf<Any>(
                "body contains dashes line", "---\nk: v\n---\nline\n---\nmore",
                mapOf("k" to "v"), "line\n---\nmore",
            )

            // --- 15. 长 frontmatter & 长 value ---------------------------------
            val longValue = "x".repeat(500)
            list += arrayOf<Any>(
                "very long value", "---\nx: $longValue\n---\nb",
                mapOf("x" to longValue), "b",
            )
            val twentyKeys = (1..20).joinToString("\n") { "k$it: v$it" }
            val twentyMap = (1..20).associate { "k$it" to "v$it" }
            list += arrayOf<Any>(
                "twenty keys", "---\n$twentyKeys\n---\nbody",
                twentyMap, "body",
            )

            // --- 16. value 包含特殊字符 ---------------------------------------
            list += arrayOf<Any>(
                "value with comma", "---\ntags: a,b,c\n---\nb",
                mapOf("tags" to "a,b,c"), "b",
            )
            list += arrayOf<Any>(
                "value with pipe", "---\ncols: a|b|c\n---\nb",
                mapOf("cols" to "a|b|c"), "b",
            )
            list += arrayOf<Any>(
                "value with braces", "---\ndata: {a:1}\n---\nb",
                mapOf("data" to "{a:1}"), "b",
            )
            list += arrayOf<Any>(
                "value with brackets", "---\nlist: [1,2,3]\n---\nb",
                mapOf("list" to "[1,2,3]"), "b",
            )
            list += arrayOf<Any>(
                "value with backslash", "---\npath: C\\\\Users\\\\x\n---\nb",
                mapOf("path" to "C\\\\Users\\\\x"), "b",
            )
            list += arrayOf<Any>(
                "value with equals", "---\nexpr: a=b\n---\nb",
                mapOf("expr" to "a=b"), "b",
            )
            list += arrayOf<Any>(
                "value with ampersand", "---\nq: a&b\n---\nb",
                mapOf("q" to "a&b"), "b",
            )
            list += arrayOf<Any>(
                "value negative number", "---\noffset: -3\n---\nb",
                mapOf("offset" to "-3"), "b",
            )
            list += arrayOf<Any>(
                "value float", "---\npi: 3.14\n---\nb",
                mapOf("pi" to "3.14"), "b",
            )

            return list
        }
    }
}
