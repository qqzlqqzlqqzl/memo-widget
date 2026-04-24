package dev.aria.memo.extB

import dev.aria.memo.data.DatedMemoEntry
import dev.aria.memo.data.local.SingleNoteEntity
import dev.aria.memo.widget.toRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.time.LocalDate
import java.time.LocalTime

/**
 * P8 扩展测试（Agent 8b，Fix-5 精简版）：widget 数据转换业务规则。
 *
 * Red-2 指出：
 *  - 原 `SingleNoteFieldsTest` (70 case) 在测 "data class 字段赋值透传" —— Kotlin 语言特性
 *  - 原 `DatedMemoTest` 4×8×4=80 case 在测同一条赋值逻辑
 *  - 与 extA/FirstLinePreviewMatrixTest 测的是同一个 `firstNonEmptyLineStripped` 算法，70% 重复
 *
 * 精简策略：
 *  - 删 `SingleNoteFieldsTest`（全是字段透传，每个字段 1 case 即可）
 *  - 删 `DatedMemoTest` 大矩阵（改成非参数化 3 case 在 EdgeTest 里）
 *  - 保留 `SingleNoteLabelTest` 的核心 label rule case（markdown 剥离业务，本项目独有）
 *  - 保留 `EdgeTest` 全部 —— 这里的断言都是 widget 业务规则
 */

private fun singleNote(
    uid: String = "uid",
    title: String = "",
    body: String = "",
    date: LocalDate = LocalDate.of(2026, 4, 24),
    time: LocalTime = LocalTime.of(12, 0),
    isPinned: Boolean = false,
): SingleNoteEntity = SingleNoteEntity(
    uid = uid,
    filePath = "notes/$uid.md",
    title = title,
    body = body,
    date = date,
    time = time,
    isPinned = isPinned,
    githubSha = null,
    localUpdatedAt = 0L,
    remoteUpdatedAt = null,
    dirty = false,
)

/**
 * 矩阵（精简版）：SingleNoteEntity.toRow() — title/body 下 label 的核心业务规则。
 *
 * 只保留 2 组：
 *  - 非 blank title → label = title（5 个代表性 title）
 *  - blank title → label = body 首个非空行去 markdown 前缀（15 个代表性 markdown 场景）
 */
@RunWith(Parameterized::class)
class MemoWidgetRowMatrixSingleNoteLabelTest(
    @JvmField val name: String,
    @JvmField val title: String,
    @JvmField val body: String,
    @JvmField val expectedLabel: String,
) {

    @Test
    fun `label matches documented rule`() {
        val row = singleNote(title = title, body = body).toRow()
        assertEquals("title='$title' body='$body'", expectedLabel, row.label)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): List<Array<Any>> = listOf(
            // Rule 1: non-blank title always wins
            arrayOf<Any>("title ascii wins over body", "Meeting", "# Ignored", "Meeting"),
            arrayOf<Any>("title chinese wins over body", "今天", "# body heading", "今天"),
            arrayOf<Any>("title emoji wins", "🚀 launch", "body", "🚀 launch"),
            arrayOf<Any>("title with spaces wins", "with spaces", "body", "with spaces"),
            arrayOf<Any>("title wins even with empty body", "Solo", "", "Solo"),
            // Rule 2: blank title → body first line with markdown stripped
            arrayOf<Any>("blank title + h1", "", "# Heading 1", "Heading 1"),
            arrayOf<Any>("blank title + h2", "", "## Heading 2", "Heading 2"),
            arrayOf<Any>("blank title + h6", "", "###### Deep", "Deep"),
            arrayOf<Any>("blank title + dash bullet", "", "- bullet", "bullet"),
            arrayOf<Any>("blank title + star bullet", "", "* star", "star"),
            arrayOf<Any>("blank title + plus bullet", "", "+ plus", "plus"),
            arrayOf<Any>("blank title + quote", "", "> quote", "quote"),
            arrayOf<Any>("blank title + plain", "", "hello world", "hello world"),
            arrayOf<Any>("blank title + leading blank lines", "", "\n\n\n   real body\n", "real body"),
            arrayOf<Any>("blank title + chinese heading", "", "# 今天想法", "今天想法"),
            arrayOf<Any>("blank title + multiline takes first", "", "first\nsecond", "first"),
            arrayOf<Any>("blank title + chinese first line", "", "笔记标题\n其余内容", "笔记标题"),
            arrayOf<Any>("blank title + emoji first line", "", "😀 emoji first\nnot first", "😀 emoji first"),
            arrayOf<Any>("whitespace-only title falls to body", "   ", "fallback", "fallback"),
            arrayOf<Any>("blank title + empty body yields empty", "", "", ""),
        )
    }
}

/**
 * 非参数化：业务边界 case —— widget 独有的 data-source → row 转换规则。
 * 保留全部，因为每条都是 `toRow()` 实际行为的锁定断言。
 */
class MemoWidgetRowMatrixEdgeTest {

    private val d0 = LocalDate.of(2026, 4, 24)
    private val t0 = LocalTime.of(9, 30)

    @Test
    fun `single note with title uses title as label`() {
        val row = singleNote(title = "Meeting notes", body = "anything").toRow()
        assertEquals("Meeting notes", row.label)
    }

    @Test
    fun `single note blank title uses body first line`() {
        val row = singleNote(title = "", body = "First line\nSecond line").toRow()
        assertEquals("First line", row.label)
    }

    @Test
    fun `single note blank title heading one stripped`() {
        assertEquals("Heading", singleNote(title = "", body = "# Heading").toRow().label)
    }

    @Test
    fun `single note blank title dash bullet stripped`() {
        assertEquals("bullet body", singleNote(title = "", body = "- bullet body").toRow().label)
    }

    @Test
    fun `single note blank title quote stripped`() {
        assertEquals("quote body", singleNote(title = "", body = "> quote body").toRow().label)
    }

    @Test
    fun `single note blank title blank body yields empty label`() {
        assertEquals("", singleNote(title = "", body = "").toRow().label)
    }

    @Test
    fun `single note blank title whitespace body yields empty label`() {
        assertEquals("", singleNote(title = "", body = "   \n\t\n   ").toRow().label)
    }

    @Test
    fun `single note blank title skip empty leading lines`() {
        assertEquals("actual", singleNote(title = "", body = "\n\n\nactual").toRow().label)
    }

    @Test
    fun `single note uid reaches row noteUid`() {
        assertEquals("MY_UID", singleNote(uid = "MY_UID").toRow().noteUid)
    }

    @Test
    fun `single note date reaches row date`() {
        val row = singleNote(title = "t", date = LocalDate.of(2020, 2, 29)).toRow()
        assertEquals(LocalDate.of(2020, 2, 29), row.date)
    }

    @Test
    fun `single note time reaches row time`() {
        val row = singleNote(title = "t", time = LocalTime.of(23, 59)).toRow()
        assertEquals(LocalTime.of(23, 59), row.time)
    }

    @Test
    fun `single note pinned does not affect toRow output`() {
        val a = singleNote(uid = "u", title = "t", isPinned = true).toRow()
        val b = singleNote(uid = "u", title = "t", isPinned = false).toRow()
        assertEquals(a, b)
    }

    @Test
    fun `single note uid empty string preserved as empty string not null`() {
        val row = singleNote(uid = "").toRow()
        assertEquals("", row.noteUid)
        assertFalse(row.noteUid == null)
    }

    @Test
    fun `single note very long body and blank title uses first line`() {
        val body = "first\n" + "x".repeat(5000)
        assertEquals("first", singleNote(title = "", body = body).toRow().label)
    }

    @Test
    fun `single note toRow is pure - two calls yield equal rows`() {
        val note = singleNote(uid = "x", title = "ttt")
        assertEquals(note.toRow(), note.toRow())
    }

    // --- DatedMemoEntry.toRow ---

    @Test
    fun `dated memo body becomes label`() {
        assertEquals("my body", DatedMemoEntry(d0, t0, "my body").toRow().label)
    }

    @Test
    fun `dated memo noteUid is null`() {
        assertNull(DatedMemoEntry(d0, t0, "x").toRow().noteUid)
    }

    @Test
    fun `dated memo empty body produces empty label`() {
        assertEquals("", DatedMemoEntry(d0, t0, "").toRow().label)
    }

    @Test
    fun `dated memo multiline body preserved verbatim`() {
        assertEquals("line1\nline2", DatedMemoEntry(d0, t0, "line1\nline2").toRow().label)
    }

    @Test
    fun `dated memo chinese body preserved`() {
        assertEquals("今天下午开会", DatedMemoEntry(d0, t0, "今天下午开会").toRow().label)
    }

    @Test
    fun `dated memo date passes through`() {
        val row = DatedMemoEntry(LocalDate.of(2020, 2, 29), t0, "x").toRow()
        assertEquals(LocalDate.of(2020, 2, 29), row.date)
    }

    @Test
    fun `dated memo time passes through`() {
        val row = DatedMemoEntry(d0, LocalTime.of(23, 59), "x").toRow()
        assertEquals(LocalTime.of(23, 59), row.time)
    }

    @Test
    fun `dated memo whitespace-only body produces whitespace label verbatim`() {
        // DatedMemoEntry is the legacy path and does NOT strip — body is passed as-is.
        assertEquals("   ", DatedMemoEntry(d0, t0, "   ").toRow().label)
    }

    @Test
    fun `dated memo never has noteUid regardless of body content`() {
        for (body in listOf("", "x", "# heading", "- bullet", "笔记")) {
            assertNull(DatedMemoEntry(d0, t0, body).toRow().noteUid)
        }
    }

    @Test
    fun `dated memo toRow is pure - two calls yield equal rows`() {
        val e = DatedMemoEntry(d0, t0, "body")
        assertEquals(e.toRow(), e.toRow())
    }

    // --- Row equality contract (kept minimal because it's a data class) ---

    @Test
    fun `two single-note rows with same content are equal`() {
        val a = singleNote(uid = "u", title = "t").toRow()
        val b = singleNote(uid = "u", title = "t").toRow()
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `two single-note rows with different uid are unequal`() {
        val a = singleNote(uid = "u1", title = "t").toRow()
        val b = singleNote(uid = "u2", title = "t").toRow()
        assertNotEquals(a, b)
    }

    @Test
    fun `dated memo row with uid=null vs single-note row with uid are unequal`() {
        val a = DatedMemoEntry(d0, t0, "hello").toRow()
        val b = singleNote(uid = "U", title = "hello", date = d0, time = t0).toRow()
        assertNotEquals(a, b)
    }

    @Test
    fun `dated memo row toString non-null smoke`() {
        assertNotNull(DatedMemoEntry(d0, t0, "body").toRow().toString())
    }

    @Test
    fun `single note blank title strips one heading marker only`() {
        // 策略：先 heading regex `^#+\s*` 然后 bullet regex `^[>\-*+]\s*`。两个 regex
        // 各跑一次，所以 "# > nested" 先剥 "# " 得 "> nested"，再剥 "> " 得 "nested"。
        val row = singleNote(title = "", body = "# > nested").toRow()
        assertEquals("nested", row.label)
    }

    @Test
    fun `single note blank title strip bullet then quote does bullet first`() {
        // For "- > nested": heading regex does nothing, bullet regex strips "- " → "> nested".
        // 第二个 regex 不再跑 —— 只是 two sequential replaces on the same string.
        // 实际行为：先 heading regex (无变化)，后 bullet regex 剥 "- "，结果 "> nested".
        // 注意：bullet regex 也能剥 ">"，但作用在已经是 "> nested" 的字符串上会再剥一次
        // 变 "nested"。这里行为取决于实现。
        // MemoWidget.kt 的 firstNonEmptyLineStripped 是两次 replace 调用，第二次作用在第一次结果上。
        // 第一次 replace(Regex("^#+\\s*"), ""): "- > nested" → "- > nested" (不是以 # 开头)
        // 第二次 replace(Regex("^[>\\-*+]\\s*"), ""): "- > nested" → "> nested" (剥了 "- ")
        // 返回 "> nested".trim() → "> nested"
        val row = singleNote(title = "", body = "- > nested").toRow()
        assertEquals("> nested", row.label)
    }

    @Test
    fun `row is a data class - destructuring yields all four fields`() {
        val row = singleNote(uid = "uid-1", title = "t").toRow()
        val (date, time, label, noteUid) = row
        assertEquals(row.date, date)
        assertEquals(row.time, time)
        assertEquals("t", label)
        assertEquals("uid-1", noteUid)
        assertTrue(true)
    }
}
