package dev.aria.memo.data.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for the Obsidian-style single-note filename slugger.
 *
 * The slug is what lives between the `HHMM-` timestamp and the `.md` suffix
 * in `notes/YYYY-MM-DD-HHMM-<slug>.md`, so it MUST be filename-safe on any
 * reasonable filesystem, non-empty, and readable enough for the user to
 * locate the file by eye in a file browser.
 */
class NoteSluggerTest {

    @Test
    fun `empty body falls back to default slug`() {
        assertEquals("note", NoteSlugger.slugOf(""))
        assertEquals("note", NoteSlugger.slugOf("   \n\n\t  \n"))
    }

    @Test
    fun `chinese title is preserved verbatim`() {
        assertEquals("早晨想法", NoteSlugger.slugOf("早晨想法\n\n今天要..."))
    }

    @Test
    fun `leading markdown heading syntax is stripped`() {
        assertEquals("会议记录", NoteSlugger.slugOf("# 会议记录\n\n正文"))
        assertEquals("标题二", NoteSlugger.slugOf("## 标题二"))
        // Internal whitespace is collapsed to '-' so the slug is a single
        // shell-friendly token; this is intentional, not a bug.
        assertEquals("bullet-item", NoteSlugger.slugOf("- bullet item\n- next"))
        assertEquals("quoted", NoteSlugger.slugOf("> quoted"))
        assertEquals("numbered", NoteSlugger.slugOf("1. numbered"))
    }

    @Test
    fun `path separators are replaced with spaces and collapsed`() {
        // Slash and backslash must NOT survive — they'd break the GitHub path.
        val slash = NoteSlugger.slugOf("foo/bar/baz")
        assertFalse("no slash allowed", slash.contains('/'))
        assertFalse("no backslash allowed", slash.contains('\\'))
        assertEquals("foo-bar-baz", slash)

        val back = NoteSlugger.slugOf("""a\b\c""")
        assertEquals("a-b-c", back)
    }

    @Test
    fun `windows-reserved characters are stripped`() {
        val dirty = """what:is?<this>"mess"|even*?"""
        val slug = NoteSlugger.slugOf(dirty)
        // None of the reserved chars survive.
        for (ch in listOf(':', '?', '*', '"', '<', '>', '|')) {
            assertFalse("must not contain '$ch' in $slug", slug.contains(ch))
        }
        assertTrue("slug must still carry readable letters", slug.startsWith("what"))
    }

    @Test
    fun `long title is truncated to the max length`() {
        val long = "a".repeat(100)
        val slug = NoteSlugger.slugOf(long)
        assertEquals(NoteSlugger.MAX_SLUG_CHARS, slug.length)
    }

    @Test
    fun `long chinese title is truncated by codepoint count`() {
        // 40 chars × 3 UTF-8 octets = 120 octets, well past any byte cap.
        val long = "中".repeat(40)
        val slug = NoteSlugger.slugOf(long)
        assertEquals(NoteSlugger.MAX_SLUG_CHARS, slug.length)
        // Every char must be the expected Chinese char, so truncation
        // didn't accidentally split a multi-byte character.
        assertTrue("truncation split a codepoint", slug.all { it == '中' })
    }

    @Test
    fun `emoji title is preserved without breaking surrogate pairs`() {
        // 😀 is U+1F600 — two UTF-16 code units, one codepoint. If we naively
        // truncated by .length we'd produce an invalid surrogate half.
        val body = "😀 happy day"
        val slug = NoteSlugger.slugOf(body)
        // Must start with the full emoji codepoint.
        assertTrue("emoji must survive", slug.startsWith("😀"))
        // Must not end on a lonely high surrogate.
        if (slug.isNotEmpty()) {
            val last = slug.last()
            assertFalse(
                "trailing high surrogate indicates a split codepoint",
                last.isHighSurrogate(),
            )
        }
    }

    @Test
    fun `whitespace-only first line falls through to next non-blank line`() {
        val body = "   \n\n  \n实际标题\n其余正文"
        assertEquals("实际标题", NoteSlugger.slugOf(body))
    }

    @Test
    fun `two distinct inputs should produce distinct slugs`() {
        // Sanity: slugger must not collapse different titles into the same
        // value under its normal operation (full user title well under the
        // truncation limit).
        val a = NoteSlugger.slugOf("早晨想法")
        val b = NoteSlugger.slugOf("下午会议记录")
        assertNotEquals(a, b)
    }
}
