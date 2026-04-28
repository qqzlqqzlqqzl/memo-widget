package dev.aria.memo.data

import dev.aria.memo.data.MemoRepository.Companion.applyPinFrontMatter
import dev.aria.memo.data.MemoRepository.Companion.parseEntries
import dev.aria.memo.data.MemoRepository.Companion.stripFrontMatter
import dev.aria.memo.data.notes.FrontMatterCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * Pure JVM tests for the pin front matter round-trip. Exercises the public
 * helpers on [MemoRepository] that produce the YAML block, strip it, and keep
 * the entry parser compatible with and without a leading front matter.
 */
class MemoRepositoryPinTest {

    private val original = "# 2026-04-21\n\n## 09:00\nbody one\n\n## 10:30\nbody two\n"

    @Test
    fun `togglePin true then false restores original content`() {
        val pinned = applyPinFrontMatter(original, pinned = true)
        assertTrue("must start with front matter", pinned.startsWith("---\npinned: true\n---\n"))
        assertTrue("front matter must be exactly 3 YAML lines", pinned.startsWith("---\npinned: true\n---\n"))

        val unpinned = applyPinFrontMatter(pinned, pinned = false)
        assertEquals(original, unpinned)
    }

    @Test
    fun `togglePin true is idempotent`() {
        val once = applyPinFrontMatter(original, pinned = true)
        val twice = applyPinFrontMatter(once, pinned = true)
        assertEquals(once, twice)
    }

    @Test
    fun `togglePin false on content without front matter returns original unchanged`() {
        assertEquals(original, applyPinFrontMatter(original, pinned = false))
    }

    @Test
    fun `pin marker round-trips through FrontMatterCodec parse`() {
        // Issue #105 (Data-1 R6): the read- and write-side YAML parsers used
        // to disagree (the read side stripped quotes, the codec was strict).
        // Now both go through FrontMatterCodec.parse, so a value we wrote
        // ourselves must come back identically.
        val pinned = applyPinFrontMatter(original, pinned = true)
        val pinnedFm = FrontMatterCodec.parse(pinned).frontMatter["pinned"]
        assertEquals("true", pinnedFm)
        val originalFm = FrontMatterCodec.parse(original).frontMatter["pinned"]
        assertEquals(null, originalFm)
    }

    @Test
    fun `quoted pin value is treated as user YAML, not our pin marker`() {
        // Issue #105: with the lenient reader gone, `pinned: "true"` (quoted)
        // is no longer mistaken for our marker — it's preserved as user-
        // authored YAML, exactly matching what FrontMatterCodec.applyPin
        // does on the write side.
        val withQuotes = "---\npinned: \"true\"\nauthor: alice\n---\n\nbody\n"
        val parsed = FrontMatterCodec.parse(withQuotes).frontMatter
        // The codec preserves the literal value (quotes included).
        assertEquals("\"true\"", parsed["pinned"])
        assertEquals("alice", parsed["author"])
        // applyPin(false) must not touch a non-strict-bool block — that's
        // user-authored YAML.
        val unchanged = applyPinFrontMatter(withQuotes, pinned = false)
        assertEquals(withQuotes, unchanged)
    }

    @Test
    fun `stripFrontMatter is a no-op when no front matter`() {
        assertEquals(original, stripFrontMatter(original))
    }

    @Test
    fun `stripFrontMatter tolerates crlf line endings`() {
        val withCrlf = "---\r\npinned: true\r\n---\r\n\r\n# 2026-04-21\r\n\r\n## 09:00\r\nhello\r\n"
        val stripped = stripFrontMatter(withCrlf)
        assertFalse("must not retain the fence", stripped.contains("---"))
        assertTrue("must keep body after front matter", stripped.contains("hello"))
    }

    @Test
    fun `parseEntries ignores front matter and returns the same entries`() {
        val date = LocalDate.of(2026, 4, 21)
        val without = parseEntries(original, date)
        val pinned = applyPinFrontMatter(original, pinned = true)
        val withFm = parseEntries(pinned, date)
        assertEquals(without, withFm)
        // Sanity check the actual parsed content.
        assertEquals(2, withFm.size)
        assertEquals(LocalTime.of(10, 30), withFm[0].time)
        assertEquals(LocalTime.of(9, 0), withFm[1].time)
    }
}
