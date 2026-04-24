package dev.aria.memo.data

import dev.aria.memo.data.MemoRepository.Companion.applyPinFrontMatter
import dev.aria.memo.data.MemoRepository.Companion.stripFrontMatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for p3-polish bug #1: `stripFrontMatter` used to eat any
 * leading `---\n...\n---\n` block, which destroyed content when the user typed
 * a legitimate markdown horizontal rule followed by another `---` further down.
 *
 * The fix: only strip when every non-blank line between the fences looks like
 * `key: value` (simple YAML subset) **and** the block carries a `pinned:` key.
 */
class MemoRepositoryFrontMatterTest {

    @Test
    fun `stripFrontMatter leaves markdown HR blocks alone (content preserved)`() {
        // User typed a horizontal rule on line 1, a heading, another HR, then
        // body. Before the fix this was flattened to just "正文\n".
        val hrContent = "---\n# 一段笔记\n---\n正文\n"
        assertEquals(hrContent, stripFrontMatter(hrContent))
    }

    @Test
    fun `stripFrontMatter strips real pin front matter`() {
        val pinned = "---\npinned: true\n---\n正文\n"
        val stripped = stripFrontMatter(pinned)
        assertEquals("正文\n", stripped)
    }

    @Test
    fun `stripFrontMatter returns content without any front matter unchanged`() {
        val plain = "# 2026-04-21\n\n## 09:00\nbody one\n"
        assertEquals(plain, stripFrontMatter(plain))
    }

    @Test
    fun `stripFrontMatter does not strip yaml-looking block that lacks pinned key`() {
        // Another safety net: a `key: value` block that isn't ours stays put.
        val other = "---\nauthor: alice\ndate: 2026-04-21\n---\n正文\n"
        assertEquals(other, stripFrontMatter(other))
    }

    @Test
    fun `applyPinFrontMatter pinned=true on HR content preserves HR and prepends pin block`() {
        val hrContent = "---\n# 一段笔记\n---\n正文\n"
        val pinned = applyPinFrontMatter(hrContent, pinned = true)
        assertTrue(
            "must start with pin front matter",
            pinned.startsWith("---\npinned: true\n---\n"),
        )
        assertTrue(
            "must preserve original HR + body",
            pinned.contains("---\n# 一段笔记\n---\n正文"),
        )
    }

    @Test
    fun `applyPinFrontMatter pinned=false on HR content is a no-op`() {
        val hrContent = "---\n# 一段笔记\n---\n正文\n"
        assertEquals(hrContent, applyPinFrontMatter(hrContent, pinned = false))
    }
}
