package dev.aria.memo.extA

import dev.aria.memo.data.local.SingleNoteEntity
import dev.aria.memo.widget.toRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.time.LocalDate
import java.time.LocalTime

/**
 * P8 Agent 8a — `SingleNoteEntity.toRow().label` 预览算法覆盖。
 *
 * `MemoWidgetContent.firstLinePreview` 是 private 无法直接触碰。`SingleNoteEntity`
 * 在 widget 层通过 internal `toRow()` → `firstNonEmptyLineStripped()` 生成
 * label（title 为空才走 body 预览），这是用户在桌面 widget 上实际看到的预览。
 *
 * 算法（`firstNonEmptyLineStripped`，见 MemoWidget.kt 第 127 行）：
 *  1. lineSequence 拆分 → 每行 trim → 找第一个非空
 *  2. 若没有：返回 ""
 *  3. 有：去掉前缀 `#+\s*`，再去掉前缀 `[>\-*+]\s*`，最后 trim
 *
 * 本测试不重复 Agent 8 的 `MemoRowPreviewExtTest`（那是测 `firstLinePreview`
 * 3-line join 算法），专注 `firstNonEmptyLineStripped` 的边界：markdown 列表、
 * 引用、代码块、emoji、空行、全空格、tab 前导、CRLF。
 *
 * 注意：`toRow()` 是 internal 可见性，同 module 的 test 可以访问。
 */
private fun makeEntity(title: String, body: String): SingleNoteEntity =
    SingleNoteEntity(
        uid = "uid-${title.hashCode().toString(16)}-${body.hashCode().toString(16)}",
        filePath = "notes/test.md",
        title = title,
        body = body,
        date = LocalDate.of(2026, 4, 24),
        time = LocalTime.of(10, 30),
        isPinned = false,
        githubSha = null,
        localUpdatedAt = 0L,
        remoteUpdatedAt = null,
        dirty = false,
        tombstoned = false,
    )

@RunWith(Parameterized::class)
class FirstLinePreviewMatrixTest(
    @JvmField val name: String,
    @JvmField val title: String,
    @JvmField val body: String,
    @JvmField val expectedLabel: String,
) {

    @Test
    fun `toRow label matches expectation`() {
        val row = makeEntity(title, body).toRow()
        assertEquals("label for $name", expectedLabel, row.label)
    }

    @Test
    fun `toRow label never null`() {
        val row = makeEntity(title, body).toRow()
        assertNotNull(row.label)
    }

    @Test
    fun `toRow label no internal LF`() {
        val row = makeEntity(title, body).toRow()
        // Single-line preview — should never contain an LF character (the
        // first-non-empty-line algorithm takes exactly one line).
        assertTrue(
            "label for $name must not contain LF, got=${row.label}",
            !row.label.contains('\n'),
        )
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): Collection<Array<Any>> {
            val list = mutableListOf<Array<Any>>()

            // --- 1. title 非空 → label == title（不看 body） -----------------
            list += arrayOf<Any>("title set non-blank", "My Title", "body irrelevant", "My Title")
            list += arrayOf<Any>("title chinese", "今天", "任何 body", "今天")
            list += arrayOf<Any>("title emoji", "😀 mood", "body", "😀 mood")
            list += arrayOf<Any>("title with hash", "# not-a-heading", "body", "# not-a-heading")
            list += arrayOf<Any>("title single char", "a", "body", "a")
            list += arrayOf<Any>("title long", "x".repeat(200), "body", "x".repeat(200))
            list += arrayOf<Any>("title with dash", "- looks-like-bullet", "body", "- looks-like-bullet")
            list += arrayOf<Any>("title with spaces", "two words", "body", "two words")
            list += arrayOf<Any>("title with colon", "k: v", "body", "k: v")
            list += arrayOf<Any>("title with tab", "tab\there", "body", "tab\there")
            list += arrayOf<Any>("title with bullet prefix", "* bullet text", "body", "* bullet text")
            list += arrayOf<Any>("title numeric", "12345", "body", "12345")

            // --- 2. title=blank → label = body.firstNonEmptyLineStripped() ---
            list += arrayOf<Any>("blank title empty body", "", "", "")
            list += arrayOf<Any>("blank title spaces body", "", "   ", "")
            list += arrayOf<Any>("blank title tab body", "", "\t\t", "")
            list += arrayOf<Any>("blank title only newlines", "", "\n\n\n", "")
            list += arrayOf<Any>("blank title mixed whitespace", "", " \t \n \t ", "")

            // --- 3. plain body content -----------------------------------------
            list += arrayOf<Any>("plain one word", "", "hello", "hello")
            list += arrayOf<Any>("plain two words", "", "hello world", "hello world")
            list += arrayOf<Any>("plain chinese", "", "今天很累", "今天很累")
            list += arrayOf<Any>("plain emoji", "", "😀", "😀")
            list += arrayOf<Any>("plain with trailing LF", "", "hello\n", "hello")
            list += arrayOf<Any>("plain with leading LF", "", "\nhello", "hello")
            list += arrayOf<Any>("plain with surrounding LFs", "", "\n\nhello\n\n", "hello")

            // --- 4. multi-line body takes first non-empty -----------------------
            list += arrayOf<Any>("two lines", "", "line1\nline2", "line1")
            list += arrayOf<Any>("three lines", "", "a\nb\nc", "a")
            list += arrayOf<Any>("blank first then content", "", "\nsecond", "second")
            list += arrayOf<Any>("blank whitespace then content", "", "   \ncontent", "content")
            list += arrayOf<Any>("tab blank then content", "", "\t\ncontent", "content")

            // --- 5. Markdown heading prefix # + space stripped -------------------
            list += arrayOf<Any>("md heading h1", "", "# Heading", "Heading")
            list += arrayOf<Any>("md heading h2", "", "## Heading", "Heading")
            list += arrayOf<Any>("md heading h3", "", "### Heading", "Heading")
            list += arrayOf<Any>("md heading h6", "", "###### Heading", "Heading")
            list += arrayOf<Any>("md heading no space", "", "#Heading", "Heading")
            list += arrayOf<Any>("md heading with multi space", "", "#    Heading", "Heading")
            list += arrayOf<Any>("md heading chinese", "", "# 标题", "标题")
            list += arrayOf<Any>("md heading then body", "", "# Title\nbody", "Title")

            // --- 6. Markdown list markers [>\-*+] + space stripped ---------------
            list += arrayOf<Any>("md list dash", "", "- item", "item")
            list += arrayOf<Any>("md list star", "", "* item", "item")
            list += arrayOf<Any>("md list plus", "", "+ item", "item")
            list += arrayOf<Any>("md list gt quote", "", "> quote", "quote")
            list += arrayOf<Any>("md list dash chinese", "", "- 今天", "今天")
            list += arrayOf<Any>("md list dash emoji", "", "- 😀", "😀")
            list += arrayOf<Any>("md list dash no space", "", "-item", "item")
            list += arrayOf<Any>("md list star no space", "", "*item", "item")
            list += arrayOf<Any>("md list plus no space", "", "+item", "item")
            list += arrayOf<Any>("md list gt no space", "", ">quote", "quote")

            // --- 7. Heading with list marker后缀（heading prefix 先吃，然后 list） -
            list += arrayOf<Any>("heading then dash", "", "# - item", "item")
            list += arrayOf<Any>("heading then star", "", "## * item", "item")
            list += arrayOf<Any>("heading then gt", "", "### > quote", "quote")

            // --- 8. Special: spaces around content ------------------------------
            list += arrayOf<Any>("surround spaces", "", "   hello   ", "hello")
            list += arrayOf<Any>("tab surrounded", "", "\thello\t", "hello")
            list += arrayOf<Any>("mixed ws surrounded", "", "   \thello\t   ", "hello")

            // --- 9. Body without alpha — preserved -------------------------------
            list += arrayOf<Any>("only number", "", "42", "42")
            list += arrayOf<Any>("only punctuation", "", "!!!", "!!!")
            list += arrayOf<Any>("url", "", "https://example.com", "https://example.com")
            list += arrayOf<Any>("path", "", "/a/b/c", "/a/b/c")
            list += arrayOf<Any>("json-ish", "", "{\"k\":\"v\"}", "{\"k\":\"v\"}")

            // --- 10. CRLF — lineSequence splits on \n, trim removes trailing \r ---
            list += arrayOf<Any>("CRLF two lines", "", "line1\r\nline2", "line1")
            list += arrayOf<Any>("CRLF blank first", "", "\r\nline1", "line1")
            list += arrayOf<Any>("CRLF heading", "", "# Heading\r\nbody", "Heading")

            // --- 11. Edge: markdown marker alone (no content after prefix) -------
            list += arrayOf<Any>("dash alone", "", "- ", "")
            list += arrayOf<Any>("star alone", "", "* ", "")
            list += arrayOf<Any>("plus alone", "", "+ ", "")
            list += arrayOf<Any>("gt alone", "", "> ", "")
            list += arrayOf<Any>("hash alone", "", "# ", "")
            // regex `^[>\-*+]\s*` with \s* matches zero whitespace → the single
            // char is eaten entirely.
            list += arrayOf<Any>("dash no space alone", "", "-", "")
            list += arrayOf<Any>("star no space alone", "", "*", "")
            // `#` gets eaten by heading regex `^#+\s*` → empty → no list regex match.
            list += arrayOf<Any>("hash no space alone", "", "#", "")

            // --- 12. Multi-level md nesting: first level stripped ---------------
            list += arrayOf<Any>("nested list", "", "- outer\n  - inner", "outer")
            list += arrayOf<Any>("nested quote", "", "> > nested", "> nested")

            // --- 13. Regex behavior: heading followed by non-space ---------------
            list += arrayOf<Any>("hash then letter", "", "#letter", "letter")
            list += arrayOf<Any>("double hash no space", "", "##letter", "letter")

            // --- 14. Generated filler ------------------------------------------
            for (i in 1..15) {
                val b = "content line $i"
                list += arrayOf<Any>("gen plain $i", "", b, b)
            }
            for (i in 1..10) {
                val b = "- gen bullet $i\nother"
                list += arrayOf<Any>("gen bullet $i", "", b, "gen bullet $i")
            }
            for (i in 1..10) {
                val t = "gen title $i"
                list += arrayOf<Any>("gen title-set $i", t, "irrelevant body $i", t)
            }

            return list
        }
    }
}

/**
 * Non-parameterized invariants on label derivation.
 */
class FirstLinePreviewSmokeTest {

    @Test
    fun `empty title and empty body yields empty label`() {
        val r = makeEntity("", "").toRow()
        assertEquals("", r.label)
    }

    @Test
    fun `title wins over body when title non-blank`() {
        val r = makeEntity("Hi", "xxx body xxx").toRow()
        assertEquals("Hi", r.label)
    }

    @Test
    fun `title blank falls through to body`() {
        val r = makeEntity("", "note body").toRow()
        assertEquals("note body", r.label)
    }

    @Test
    fun `title whitespace-only falls through to body`() {
        val r = makeEntity("   ", "note body").toRow()
        assertEquals("note body", r.label)
    }

    @Test
    fun `label never contains internal LF`() {
        val r = makeEntity("", "line1\nline2\nline3").toRow()
        assertTrue(!r.label.contains('\n'))
    }

    @Test
    fun `label for bullet body equals stripped first item`() {
        val r = makeEntity("", "- milk").toRow()
        assertEquals("milk", r.label)
    }

    @Test
    fun `label for quote body equals stripped quote`() {
        val r = makeEntity("", "> quote").toRow()
        assertEquals("quote", r.label)
    }

    @Test
    fun `label for heading body equals heading text`() {
        val r = makeEntity("", "# Hello").toRow()
        assertEquals("Hello", r.label)
    }

    @Test
    fun `label preserves unicode`() {
        val r = makeEntity("", "- 今天").toRow()
        assertEquals("今天", r.label)
    }

    @Test
    fun `label preserves emoji`() {
        val r = makeEntity("", "- 😀").toRow()
        assertEquals("😀", r.label)
    }

    @Test
    fun `label from CRLF body`() {
        val r = makeEntity("", "first\r\nsecond").toRow()
        assertEquals("first", r.label)
    }

    @Test
    fun `label trims surrounding whitespace`() {
        val r = makeEntity("", "   hello   ").toRow()
        assertEquals("hello", r.label)
    }

    @Test
    fun `label is non-null for typical input`() {
        assertNotNull(makeEntity("", "hi").toRow().label)
    }

    @Test
    fun `toRow preserves noteUid from entity uid`() {
        val e = makeEntity("", "body")
        assertEquals(e.uid, e.toRow().noteUid)
    }

    @Test
    fun `toRow preserves date from entity`() {
        val e = makeEntity("", "body")
        assertEquals(e.date, e.toRow().date)
    }

    @Test
    fun `toRow preserves time from entity`() {
        val e = makeEntity("", "body")
        assertEquals(e.time, e.toRow().time)
    }
}
