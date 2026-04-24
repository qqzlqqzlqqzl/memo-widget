package dev.aria.memo.ui.help

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit coverage for the parser half of [MarkdownRenderer]. We only test
 * [parseBlocks] and the inline helpers — the Compose rendering layer is not
 * unit-testable without androidx.compose.ui.test + a device, which is out of
 * scope for `testDebugUnitTest`.
 */
class MarkdownRendererTest {

    @Test
    fun `heading levels map 1-3 and clamp above 3`() {
        val blocks = parseBlocks("# one\n## two\n### three\n#### four")
        assertEquals(4, blocks.size)
        assertEquals(MdBlock.Heading(1, "one"), blocks[0])
        assertEquals(MdBlock.Heading(2, "two"), blocks[1])
        assertEquals(MdBlock.Heading(3, "three"), blocks[2])
        assertEquals(MdBlock.Heading(3, "four"), blocks[3])
    }

    @Test
    fun `horizontal rule becomes its own block`() {
        val blocks = parseBlocks("before\n\n---\n\nafter")
        // paragraph / HR / paragraph
        assertEquals(3, blocks.size)
        assertTrue(blocks[0] is MdBlock.Paragraph)
        assertEquals(MdBlock.HorizontalRule, blocks[1])
        assertTrue(blocks[2] is MdBlock.Paragraph)
    }

    @Test
    fun `fenced code block captures lang and preserves body`() {
        val source = """
            ```kotlin
            fun foo() = 1
            val x = 2
            ```
        """.trimIndent()
        val blocks = parseBlocks(source)
        assertEquals(1, blocks.size)
        val code = blocks[0] as MdBlock.CodeBlock
        assertEquals("kotlin", code.lang)
        assertEquals("fun foo() = 1\nval x = 2", code.content)
    }

    @Test
    fun `mermaid fenced block is preserved with lang mermaid`() {
        val source = """
            ```mermaid
            flowchart LR
              A --> B
            ```
        """.trimIndent()
        val blocks = parseBlocks(source)
        val code = blocks.single() as MdBlock.CodeBlock
        assertEquals("mermaid", code.lang)
        assertTrue(code.content.contains("flowchart LR"))
    }

    @Test
    fun `blockquote collapses contiguous gt prefixed lines`() {
        val blocks = parseBlocks("> one\n> two\n> three")
        val quote = blocks.single() as MdBlock.BlockQuote
        assertEquals("one\ntwo\nthree", quote.text)
    }

    @Test
    fun `table with separator row drops the separator and keeps header+rows`() {
        val source = """
            | a | b |
            |---|---|
            | 1 | 2 |
            | 3 | 4 |
        """.trimIndent()
        val blocks = parseBlocks(source)
        val table = blocks.single() as MdBlock.Table
        assertEquals(3, table.rows.size)
        assertEquals(listOf("a", "b"), table.rows[0])
        assertEquals(listOf("1", "2"), table.rows[1])
        assertEquals(listOf("3", "4"), table.rows[2])
    }

    @Test
    fun `standalone image line becomes an Image block`() {
        val blocks = parseBlocks("![caption](screenshots/foo.png)")
        val img = blocks.single() as MdBlock.Image
        assertEquals("caption", img.alt)
        assertEquals("screenshots/foo.png", img.path)
    }

    @Test
    fun `bullet list gathers dash and star items`() {
        val blocks = parseBlocks("- first\n- second\n* third")
        val list = blocks.single() as MdBlock.BulletList
        assertEquals(listOf("- first", "- second", "* third"), list.items)
    }

    @Test
    fun `inline bold and link both annotate correctly`() {
        val annotated = buildInlineAnnotatedString("see **bold** and [GitHub](https://github.com)")
        val text = annotated.text
        // Bold markers are stripped and link text is included verbatim.
        assertEquals("see bold and GitHub", text)
        val urls = annotated.getStringAnnotations("URL", 0, text.length)
        assertEquals(1, urls.size)
        assertEquals("https://github.com", urls[0].item)
    }

    @Test
    fun `inline code renders monospace text without backticks`() {
        val annotated = buildInlineAnnotatedString("run `foo()` now")
        assertEquals("run foo() now", annotated.text)
    }

    @Test
    fun `blank lines terminate paragraph and quote`() {
        val source = "para1\n\n> quote1\n\npara2"
        val blocks = parseBlocks(source)
        assertEquals(3, blocks.size)
        assertTrue(blocks[0] is MdBlock.Paragraph)
        assertTrue(blocks[1] is MdBlock.BlockQuote)
        assertTrue(blocks[2] is MdBlock.Paragraph)
    }
}
