package dev.aria.memo.ext

import dev.aria.memo.data.sync.PullBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P8 扩展测试（Agent 8，Fix-5 精简版）：[PullBudget] 的业务 smoke 断言。
 *
 * Red-1 指出：原 parameterized 部分 ~1,700 cases 在枚举 `cap=0..30, n=0..cap+2`，
 * 每条只验一条规则，与 extB/PullBudgetMatrixTest 大量重叠。
 *
 * 精简：删除两个 parameterized class，保留 smoke 作为最简业务规则锁（DEFAULT_CAP=150,
 * remaining 单调递减, exhausted 闭合语义）。extB 里有更完整的 Edge coverage。
 */
class PullBudgetExtSmokeTest {

    @Test
    fun `default cap is 150`() {
        assertEquals(150, PullBudget().remaining)
    }

    @Test
    fun `default cap not exhausted immediately`() {
        assertFalse(PullBudget().exhausted())
    }

    @Test
    fun `cap one consume once succeeds then fails`() {
        val b = PullBudget(1)
        assertTrue(b.consume())
        assertFalse(b.consume())
    }

    @Test
    fun `cap three consume three succeeds`() {
        val b = PullBudget(3)
        repeat(3) { assertTrue(b.consume()) }
        assertFalse(b.consume())
    }

    @Test
    fun `consume after exhausted returns false`() {
        val b = PullBudget(1)
        b.consume()
        repeat(10) { assertFalse(b.consume()) }
    }

    @Test
    fun `cap 150 default exhausts exactly at 150`() {
        val b = PullBudget()
        repeat(150) { assertTrue(b.consume()) }
        assertTrue(b.exhausted())
        assertFalse(b.consume())
    }

    @Test
    fun `multiple budgets are independent`() {
        val a = PullBudget(2)
        val b = PullBudget(2)
        a.consume()
        a.consume()
        assertTrue(a.exhausted())
        assertFalse(b.exhausted())
    }

    @Test
    fun `remaining monotonically decreases`() {
        val b = PullBudget(10)
        var prev = b.remaining
        repeat(10) {
            b.consume()
            assertTrue(b.remaining <= prev)
            prev = b.remaining
        }
    }

    @Test
    fun `cap zero exhausted immediately`() {
        val b = PullBudget(0)
        assertTrue(b.exhausted())
        assertEquals(0, b.remaining)
    }

    @Test
    fun `single consume decreases remaining by one`() {
        val b = PullBudget(5)
        assertEquals(5, b.remaining)
        b.consume()
        assertEquals(4, b.remaining)
    }

    @Test
    fun `budget sequence cap 3 returns three trues then falses`() {
        val b = PullBudget(3)
        val results = List(5) { b.consume() }
        assertEquals(listOf(true, true, true, false, false), results)
    }

    @Test
    fun `DEFAULT_CAP constant is 150`() {
        assertEquals(150, PullBudget.DEFAULT_CAP)
    }

    @Test
    fun `constructor default cap equals DEFAULT_CAP`() {
        assertEquals(PullBudget.DEFAULT_CAP, PullBudget().remaining)
    }
}
