package dev.aria.memo.data.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrontMatterCodecTest {

    // --- parse ---------------------------------------------------------------

    @Test
    fun `parse empty body returns empty map and empty body`() {
        val result = FrontMatterCodec.parse("")
        assertEquals(emptyMap<String, String>(), result.frontMatter)
        assertEquals("", result.body)
    }

    @Test
    fun `parse body without front matter returns empty map and original body`() {
        val body = "# 2026-04-22\n\n## 09:00\nhello\n"
        val result = FrontMatterCodec.parse(body)
        assertEquals(emptyMap<String, String>(), result.frontMatter)
        assertEquals(body, result.body)
    }

    @Test
    fun `parse standard front matter block extracts key-value pairs`() {
        val full = "---\nkey: value\n---\nbody content"
        val result = FrontMatterCodec.parse(full)
        assertEquals(mapOf("key" to "value"), result.frontMatter)
        assertEquals("body content", result.body)
    }

    @Test
    fun `parse pinned front matter block`() {
        val full = "---\npinned: true\n---\n\nbody"
        val result = FrontMatterCodec.parse(full)
        assertEquals(mapOf("pinned" to "true"), result.frontMatter)
        assertEquals("body", result.body)
    }

    @Test
    fun `parse HR-looking block with heading returns empty map`() {
        val hr = "---\n# heading\n---\nbody"
        val result = FrontMatterCodec.parse(hr)
        assertEquals(emptyMap<String, String>(), result.frontMatter)
        assertEquals(hr, result.body)
    }

    // --- strip ---------------------------------------------------------------

    @Test
    fun `strip is no-op when no front matter`() {
        val plain = "# 2026-04-21\n\n## 09:00\nbody\n"
        assertEquals(plain, FrontMatterCodec.strip(plain))
    }

    @Test
    fun `strip removes pin front matter`() {
        val pinned = "---\npinned: true\n---\n正文\n"
        assertEquals("正文\n", FrontMatterCodec.strip(pinned))
    }

    @Test
    fun `strip leaves HR block alone`() {
        val hr = "---\n# 笔记\n---\n正文\n"
        assertEquals(hr, FrontMatterCodec.strip(hr))
    }

    @Test
    fun `strip leaves yaml block without pinned key alone`() {
        val other = "---\nauthor: alice\ndate: 2026-04-21\n---\n正文\n"
        assertEquals(other, FrontMatterCodec.strip(other))
    }

    @Test
    fun `strip tolerates CRLF`() {
        val crlf = "---\r\npinned: true\r\n---\r\n\r\nhello\r\n"
        val stripped = FrontMatterCodec.strip(crlf)
        assertFalse(stripped.contains("---"))
        assertTrue(stripped.contains("hello"))
    }

    // --- applyPin ------------------------------------------------------------

    @Test
    fun `applyPin true on plain body prepends front matter`() {
        val body = "# 2026-04-22\n\nbody\n"
        val result = FrontMatterCodec.applyPin(body, pinned = true)
        assertTrue(result.startsWith("---\npinned: true\n---\n"))
        assertTrue(result.contains("body"))
    }

    @Test
    fun `applyPin true is idempotent`() {
        val body = "# 2026-04-22\n\nbody\n"
        val once = FrontMatterCodec.applyPin(body, pinned = true)
        val twice = FrontMatterCodec.applyPin(once, pinned = true)
        assertEquals(once, twice)
    }

    @Test
    fun `applyPin false removes existing pin front matter`() {
        val pinned = "---\npinned: true\n---\n\nbody\n"
        assertEquals("body\n", FrontMatterCodec.applyPin(pinned, pinned = false))
    }

    @Test
    fun `applyPin false on plain body is no-op`() {
        val body = "# 2026-04-22\n\nbody\n"
        assertEquals(body, FrontMatterCodec.applyPin(body, pinned = false))
    }

    @Test
    fun `applyPin true then false round-trips`() {
        val original = "# 2026-04-21\n\n## 09:00\nbody one\n\n## 10:30\nbody two\n"
        val pinned = FrontMatterCodec.applyPin(original, pinned = true)
        val unpinned = FrontMatterCodec.applyPin(pinned, pinned = false)
        assertEquals(original, unpinned)
    }

    @Test
    fun `applyPin true on HR content preserves HR content`() {
        val hr = "---\n# 笔记\n---\n正文\n"
        val pinned = FrontMatterCodec.applyPin(hr, pinned = true)
        assertTrue(pinned.startsWith("---\npinned: true\n---\n"))
        assertTrue(pinned.contains("---\n# 笔记\n---\n正文"))
    }

    @Test
    fun `applyPin false on HR content is no-op`() {
        val hr = "---\n# 笔记\n---\n正文\n"
        assertEquals(hr, FrontMatterCodec.applyPin(hr, pinned = false))
    }

    // --- looksLikePinOnlyFrontMatter ----------------------------------------

    @Test
    fun `looksLikePinOnly true for single pinned true line`() {
        assertTrue(FrontMatterCodec.looksLikePinOnlyFrontMatter(listOf("pinned: true")))
    }

    @Test
    fun `looksLikePinOnly true for single pinned false line`() {
        assertTrue(FrontMatterCodec.looksLikePinOnlyFrontMatter(listOf("pinned: false")))
    }

    @Test
    fun `looksLikePinOnly false when other keys present`() {
        assertFalse(FrontMatterCodec.looksLikePinOnlyFrontMatter(listOf("author: alice", "pinned: true")))
    }

    @Test
    fun `looksLikePinOnly false when pinned value is quoted true`() {
        // P6 semantic: `"true"` with quotes is user-authored YAML, not our flag.
        assertFalse(FrontMatterCodec.looksLikePinOnlyFrontMatter(listOf("pinned: \"true\"")))
    }

    @Test
    fun `looksLikePinOnly false when pinned value is 1`() {
        assertFalse(FrontMatterCodec.looksLikePinOnlyFrontMatter(listOf("pinned: 1")))
    }

    @Test
    fun `looksLikePinOnly false when pinned value is yes`() {
        assertFalse(FrontMatterCodec.looksLikePinOnlyFrontMatter(listOf("pinned: yes")))
    }

    @Test
    fun `looksLikePinOnly false when empty list`() {
        assertFalse(FrontMatterCodec.looksLikePinOnlyFrontMatter(emptyList()))
    }

    @Test
    fun `looksLikePinOnly ignores blank lines`() {
        assertTrue(FrontMatterCodec.looksLikePinOnlyFrontMatter(listOf("", "pinned: true", "")))
    }

    // --- P6.1 boundary regressions (fixes #41) -------------------------------

    @Test
    fun `parse with nested triple dash inside user YAML stops at first close fence`() {
        // 用户写了一个 YAML 值含 `---`，parse 碰到第一个 `\n---` 就收尾。
        // 这里行 `hint: use --- as` 含内嵌 `---`，但不是行头所以安全。
        // 第二行 `---\n---` 则是首个 close fence——我们应该只吃到这。
        val nested = "---\nhint: use --- as separator\n---\nbody after"
        val result = FrontMatterCodec.parse(nested)
        assertEquals(mapOf("hint" to "use --- as separator"), result.frontMatter)
        assertEquals("body after", result.body)
    }

    @Test
    fun `parse with no closing fence returns empty map and original body`() {
        val openOnly = "---\npinned: true\nthis block never closes\nmore body"
        val result = FrontMatterCodec.parse(openOnly)
        assertEquals(emptyMap<String, String>(), result.frontMatter)
        assertEquals(openOnly, result.body)
    }

    @Test
    fun `strip with no closing fence leaves body untouched`() {
        val openOnly = "---\npinned: true\nno closer here"
        assertEquals(openOnly, FrontMatterCodec.strip(openOnly))
    }

    @Test
    fun `applyPin false on no-closing-fence body is no-op`() {
        val openOnly = "---\npinned: true\nno closer here"
        assertEquals(openOnly, FrontMatterCodec.applyPin(openOnly, pinned = false))
    }

    @Test
    fun `parse accepts non-ASCII key (Kotlin isLetterOrDigit is Unicode-aware)`() {
        // 锁定当前行为：Kotlin Char.isLetterOrDigit() 是 Unicode-aware 的，
        // 中文字符也属于 letter，所以中文键可以被正确解析。
        // 如果将来想限制 ASCII-only（防用户写中文出意料之外的 YAML），需
        // 同步更新这个 test + FrontMatterCodec 里的 key 校验。
        val chineseKey = "---\n标签: foo\n---\nbody"
        val result = FrontMatterCodec.parse(chineseKey)
        assertEquals(mapOf("标签" to "foo"), result.frontMatter)
        assertEquals("body", result.body)
    }

    @Test
    fun `strip with mixed non-ASCII key plus strict pinned removes the block`() {
        // 既有中文键（`作者`）又有 `pinned: true`，当前语义：整块被当 pin block
        // 剥离（`作者` 也跟着消失）。这是 P6 简化策略——pin-only 和 "pin + 其他"
        // 都吃；用户若要保留非 pin 的中文键，不要同时写 pinned。
        val mixed = "---\n作者: alice\npinned: true\n---\n正文"
        assertEquals("正文", FrontMatterCodec.strip(mixed))
    }
}
