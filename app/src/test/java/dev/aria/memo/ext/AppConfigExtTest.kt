package dev.aria.memo.ext

import dev.aria.memo.data.AppConfig
import dev.aria.memo.data.DatedMemoEntry
import dev.aria.memo.data.ErrorCode
import dev.aria.memo.data.MemoEntry
import dev.aria.memo.data.MemoResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.time.LocalDate
import java.time.LocalTime

/**
 * P8 扩展测试（Agent 8，Fix-5 精简版）：AppConfig / MemoResult / MemoEntry /
 * DatedMemoEntry / ErrorCode 业务契约。
 *
 * Red-1 指出：
 *  - AppConfigExtFilePathForTest 第 113-123 行生成 40 个 `{yyyy}-{MM}-{dd}` case，
 *    但 expected 用同一个 `"%02d".format` 再算一次 → 测 stdlib 一致性
 *  - MemoEntryExtEqualityTest 51 个生成 case 在测 data class equals (编译器)
 *  - ErrorCodeExtTest 每个 code × 9 test 在测 enum 语言特性
 *
 * 精简：
 *  - AppConfigExtConfiguredTest：保留（真值表业务）
 *  - AppConfigExtFilePathForTest：只保留手写 22 个 case，删 gen 部分
 *  - MemoEntryExtEqualityTest：全删（data class 语言特性）
 *  - ErrorCodeExtTest：全删，合并 3 条 smoke 到 MemoResultExtSmokeTest
 *  - MemoResultExtSmokeTest：保留全部（Result 分支、Err 字段是业务）
 */
@RunWith(Parameterized::class)
class AppConfigExtConfiguredTest(
    @Suppress("unused") private val name: String,
    private val pat: String,
    private val owner: String,
    private val repo: String,
    private val expected: Boolean,
) {

    @Test
    fun `isConfigured matches expectation`() {
        val cfg = AppConfig(pat = pat, owner = owner, repo = repo)
        assertEquals(expected, cfg.isConfigured)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): List<Array<Any>> {
            val list = mutableListOf<Array<Any>>()
            val blanks = listOf("", " ", "   ", "\t")
            val validPat = listOf("ghp_abc", "token")
            val validOwner = listOf("alice", "bob")
            val validRepo = listOf("notes", "memo")
            // fully configured (8 combos instead of 27)
            for (p in validPat) for (o in validOwner) for (r in validRepo) {
                list += arrayOf<Any>("ok $o/$r", p, o, r, true)
            }
            // individually blank — one per blank flavour per field
            for (b in blanks) {
                list += arrayOf<Any>("blank pat: $b", b, "o", "r", false)
                list += arrayOf<Any>("blank owner: $b", "p", b, "r", false)
                list += arrayOf<Any>("blank repo: $b", "p", "o", b, false)
            }
            return list
        }
    }
}

/**
 * filePathFor rendering — 22 hand-written cases（原 gen 部分 40 个测 stdlib
 * 一致性已删）。这些 case 锁定 `{yyyy}-{MM}-{dd}` template token 的替换规则。
 */
@RunWith(Parameterized::class)
class AppConfigExtFilePathForTest(
    @Suppress("unused") private val name: String,
    private val template: String,
    private val date: LocalDate,
    private val expected: String,
) {

    @Test
    fun `filePathFor rendering`() {
        val cfg = AppConfig(pat = "p", owner = "o", repo = "r", pathTemplate = template)
        assertEquals(expected, cfg.filePathFor(date))
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): List<Array<Any>> {
            val d1 = LocalDate.of(2026, 4, 24)
            val d2 = LocalDate.of(2024, 1, 1)
            val d3 = LocalDate.of(2023, 12, 31)
            val d4 = LocalDate.of(2026, 9, 5)
            return listOf(
                arrayOf<Any>("default template", "{yyyy}-{MM}-{dd}.md", d1, "2026-04-24.md"),
                arrayOf<Any>("default template j1", "{yyyy}-{MM}-{dd}.md", d2, "2024-01-01.md"),
                arrayOf<Any>("default template d31", "{yyyy}-{MM}-{dd}.md", d3, "2023-12-31.md"),
                arrayOf<Any>("default template sep5", "{yyyy}-{MM}-{dd}.md", d4, "2026-09-05.md"),
                arrayOf<Any>("nested dir", "notes/{yyyy}/{MM}/{dd}.md", d1, "notes/2026/04/24.md"),
                arrayOf<Any>("no year", "{MM}-{dd}.md", d1, "04-24.md"),
                arrayOf<Any>("no slash", "{yyyy}{MM}{dd}.md", d1, "20260424.md"),
                arrayOf<Any>("no md suffix", "{yyyy}-{MM}-{dd}", d1, "2026-04-24"),
                arrayOf<Any>("prefix dir", "journals/{yyyy}-{MM}-{dd}.md", d1, "journals/2026-04-24.md"),
                arrayOf<Any>("suffix dir", "{yyyy}-{MM}-{dd}/index.md", d1, "2026-04-24/index.md"),
                arrayOf<Any>("pure text (no tokens)", "hello.md", d1, "hello.md"),
                arrayOf<Any>("duplicate yyyy token", "{yyyy}-{yyyy}.md", d1, "2026-2026.md"),
                arrayOf<Any>("MM only", "{MM}", d1, "04"),
                arrayOf<Any>("dd only", "{dd}", d1, "24"),
                arrayOf<Any>("yyyy only", "{yyyy}", d1, "2026"),
                arrayOf<Any>("leap day", "{yyyy}-{MM}-{dd}.md", LocalDate.of(2024, 2, 29), "2024-02-29.md"),
                arrayOf<Any>("single digit month padded", "{yyyy}-{MM}", LocalDate.of(2026, 3, 1), "2026-03"),
                arrayOf<Any>("single digit day padded", "{MM}-{dd}", LocalDate.of(2026, 3, 9), "03-09"),
                arrayOf<Any>("only literal", "README.md", d1, "README.md"),
                arrayOf<Any>("template empty", "", d1, ""),
                arrayOf<Any>("template multiple tokens", "{yyyy}/{yyyy}/{MM}/{dd}", d1, "2026/2026/04/24"),
                arrayOf<Any>("token then literal", "{yyyy}-note.md", d1, "2026-note.md"),
            )
        }
    }
}

/**
 * MemoResult sealed class + AppConfig defaults 业务 smoke。
 */
class MemoResultExtSmokeTest {

    @Test
    fun `Ok value accessible`() {
        assertEquals(42, MemoResult.Ok(42).value)
    }

    @Test
    fun `Err code accessible`() {
        val e = MemoResult.Err(ErrorCode.UNAUTHORIZED, "401")
        assertEquals(ErrorCode.UNAUTHORIZED, e.code)
    }

    @Test
    fun `Err message accessible`() {
        val e = MemoResult.Err(ErrorCode.NETWORK, "oops")
        assertEquals("oops", e.message)
    }

    @Test
    fun `Ok equals clone`() {
        assertEquals(MemoResult.Ok(1), MemoResult.Ok(1))
    }

    @Test
    fun `Err equals clone`() {
        assertEquals(
            MemoResult.Err(ErrorCode.NETWORK, "x"),
            MemoResult.Err(ErrorCode.NETWORK, "x"),
        )
    }

    @Test
    fun `Ok not equal to Err`() {
        assertNotEquals(MemoResult.Ok(1), MemoResult.Err(ErrorCode.UNKNOWN, "x"))
    }

    @Test
    fun `Err with different message not equal`() {
        val a = MemoResult.Err(ErrorCode.NETWORK, "x")
        val b = MemoResult.Err(ErrorCode.NETWORK, "y")
        assertNotEquals(a, b)
    }

    @Test
    fun `ErrorCode values all six`() {
        // production error handling switches on these 6 — adding/removing breaks
        // the `when` branches across MemoRepository / SyncStatus / widget.
        assertEquals(6, ErrorCode.values().size)
    }

    @Test
    fun `ErrorCode contains all six expected names`() {
        val names = ErrorCode.values().map { it.name }.toSet()
        assertTrue(names.containsAll(setOf(
            "NOT_CONFIGURED", "UNAUTHORIZED", "NOT_FOUND", "CONFLICT", "NETWORK", "UNKNOWN"
        )))
    }

    @Test
    fun `AppConfig default branch main`() {
        assertEquals("main", AppConfig("p", "o", "r").branch)
    }

    @Test
    fun `AppConfig default template yyyy-MM-dd md`() {
        assertEquals("{yyyy}-{MM}-{dd}.md", AppConfig("p", "o", "r").pathTemplate)
    }

    @Test
    fun `AppConfig toString non-null`() {
        assertNotNull(AppConfig("p", "o", "r").toString())
    }

    @Test
    fun `AppConfig copy changes branch preserves others`() {
        val c = AppConfig("p", "o", "r", branch = "dev")
        assertEquals("dev", c.branch)
        assertEquals("p", c.pat)
        assertEquals("o", c.owner)
        assertEquals("r", c.repo)
    }

    @Test
    fun `DatedMemoEntry and MemoEntry are distinct types`() {
        val date = LocalDate.of(2026, 4, 24)
        val time = LocalTime.of(9, 0)
        val m = MemoEntry(date, time, "b")
        val d = DatedMemoEntry(date, time, "b")
        assertFalse((m as Any) == (d as Any))
    }

    @Test
    fun `DatedMemoEntry fields preserved`() {
        val d = LocalDate.of(2026, 4, 24)
        val t = LocalTime.of(9, 0)
        val e = DatedMemoEntry(d, t, "x")
        assertEquals(d, e.date)
        assertEquals(t, e.time)
        assertEquals("x", e.body)
    }

    @Test
    fun `MemoEntry fields preserved`() {
        val d = LocalDate.of(2026, 4, 24)
        val t = LocalTime.of(9, 0)
        val e = MemoEntry(d, t, "x")
        assertEquals(d, e.date)
        assertEquals(t, e.time)
        assertEquals("x", e.body)
    }
}
