package dev.aria.memo.ext

import dev.aria.memo.widget.MemoWidgetRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * P8 扩展测试（Agent 8，Fix-5 精简版）：[MemoWidgetRow] data class 基础契约 smoke。
 *
 * Red-1 指出：原文件 140 + 225 个 parameterized case 在测 Kotlin 编译器生成的
 * `copy/equals/hashCode`。这些不保护 production 任何业务逻辑。
 *
 * 只保留 smoke case（~15 条）作为 sanity check：`MemoWidgetRow` 是被 widget
 * 产品路径直接用到的 data class，基础结构被意外改破必须立刻红。
 */
class MemoWidgetRowExtSmokeTest {

    private val date = LocalDate.of(2026, 4, 24)
    private val time = LocalTime.of(9, 30)

    @Test
    fun `constructor round-trips all four fields`() {
        val r = MemoWidgetRow(date, time, "label", "uid")
        assertEquals(date, r.date)
        assertEquals(time, r.time)
        assertEquals("label", r.label)
        assertEquals("uid", r.noteUid)
    }

    @Test
    fun `noteUid nullable`() {
        val r = MemoWidgetRow(date, time, "label", null)
        assertNull(r.noteUid)
    }

    @Test
    fun `row with empty label allowed`() {
        val r = MemoWidgetRow(date, time, "", "uid")
        assertEquals("", r.label)
    }

    @Test
    fun `row with very long label preserved`() {
        val r = MemoWidgetRow(date, time, "x".repeat(5000), "uid")
        assertEquals(5000, r.label.length)
    }

    @Test
    fun `row with unicode label preserved`() {
        val r = MemoWidgetRow(date, time, "笔记内容", "uid")
        assertEquals("笔记内容", r.label)
    }

    @Test
    fun `row with emoji label preserved`() {
        val r = MemoWidgetRow(date, time, "😀📝", "uid")
        assertEquals("😀📝", r.label)
    }

    @Test
    fun `row equals clone`() {
        val a = MemoWidgetRow(date, time, "x", "u")
        val b = MemoWidgetRow(date, time, "x", "u")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `row not equal when label differs`() {
        val a = MemoWidgetRow(date, time, "x", "u")
        val b = MemoWidgetRow(date, time, "y", "u")
        assertNotEquals(a, b)
    }

    @Test
    fun `row not equal when noteUid differs (one null one non-null)`() {
        val a = MemoWidgetRow(date, time, "x", "u")
        val b = MemoWidgetRow(date, time, "x", null)
        assertNotEquals(a, b)
    }

    @Test
    fun `row toString contains label`() {
        val r = MemoWidgetRow(date, time, "hello-label", "uid-1")
        assertTrue(r.toString().contains("hello-label"))
    }

    @Test
    fun `row copy with one field changed preserves others`() {
        val r = MemoWidgetRow(date, time, "a", "uid")
        val r2 = r.copy(label = "b")
        assertEquals(date, r2.date)
        assertEquals(time, r2.time)
        assertEquals("b", r2.label)
        assertEquals("uid", r2.noteUid)
    }

    @Test
    fun `destructure yields all four fields in declaration order`() {
        val r = MemoWidgetRow(date, time, "lbl", "uid-1")
        val (d, t, l, u) = r
        assertEquals(date, d)
        assertEquals(time, t)
        assertEquals("lbl", l)
        assertEquals("uid-1", u)
    }
}
