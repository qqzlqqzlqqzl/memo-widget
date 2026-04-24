package dev.aria.memo.ui.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChecklistLineParserTest {

    @Test
    fun `plain unchecked box parses to indent 0, false, text`() {
        val result = parseChecklistLine("- [ ] foo")
        assertEquals(ChecklistLine(indent = 0, checked = false, text = "foo"), result)
    }

    @Test
    fun `lowercase x checked box parses to checked true`() {
        val result = parseChecklistLine("- [x] bar")
        assertEquals(ChecklistLine(indent = 0, checked = true, text = "bar"), result)
    }

    @Test
    fun `indented uppercase X checked box parses with indent and checked`() {
        val result = parseChecklistLine("  - [X] baz")
        assertEquals(ChecklistLine(indent = 2, checked = true, text = "baz"), result)
    }

    @Test
    fun `bullet without brackets returns null`() {
        assertNull(parseChecklistLine("- not a todo"))
    }

    @Test
    fun `indented plain text returns null`() {
        assertNull(parseChecklistLine("  abc"))
    }

    @Test
    fun `empty line returns null`() {
        assertNull(parseChecklistLine(""))
    }

    @Test
    fun `time header returns null`() {
        assertNull(parseChecklistLine("## 14:30"))
    }

    @Test
    fun `trailing whitespace in text is preserved verbatim`() {
        // We don't trim — the renderer just displays whatever is there, and the
        // round-trip from toggle must not silently munge user whitespace.
        val result = parseChecklistLine("- [ ] with trailing space ")
        assertEquals(
            ChecklistLine(indent = 0, checked = false, text = "with trailing space "),
            result,
        )
    }
}
