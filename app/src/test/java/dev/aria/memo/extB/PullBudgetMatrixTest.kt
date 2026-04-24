package dev.aria.memo.extB

import dev.aria.memo.data.sync.PullBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * P8 扩展测试（Agent 8b，Fix-5 精简版）：[PullBudget] 业务契约。
 *
 * Red-2 指出：原文件 ~1,200 case，大部分在做 `cap=0..15 × consumes=0..cap+3`
 * 笛卡尔积，反复验证同一条 invariant（"前 min(cap,n) 个 true 然后 false"）。
 *
 * 精简规则：
 *  - 删除 `StableStateTest`（测"连读 remaining 3 次相等"—— 这是 val 属性特性，不是业务）
 *  - 删除 `IndependenceTest`（"两个实例互不影响" 1 个 case 足够）
 *  - 保留 `SequenceTest` 但收缩到 10 个代表性 cap×calls
 *  - 保留 `EdgeTest` 全部 —— 这是 PullBudget 真正的业务边界测试
 *
 * 总 case 数 ≈ 30（原文件 ~1,200）。
 */

/**
 * 矩阵 1（收缩版）：cap × consumeSequenceLen 的 10 个代表性组合，断言
 * `consume()` 返回值序列形状为 "前 min(cap, n) 个 true，然后 false"。
 *
 * 为什么不删整个 parameterized：这一条业务 invariant 值得留 5-10 个代表性 case
 * 当 guard，删光了就没人发现 `consume()` 被改成 "永远返回 true"。
 */
@RunWith(Parameterized::class)
class PullBudgetMatrixSequenceTest(
    @JvmField val name: String,
    @JvmField val cap: Int,
    @JvmField val calls: Int,
) {

    @Test
    fun `consume sequence has exactly min(cap, calls) trues`() {
        val budget = PullBudget(cap = cap)
        val results = List(calls) { budget.consume() }
        val expectedTrueCount = cap.coerceAtLeast(0).coerceAtMost(calls)
        assertEquals(expectedTrueCount, results.count { it })
    }

    @Test
    fun `no true comes after a false in the sequence`() {
        val budget = PullBudget(cap = cap)
        val results = List(calls) { budget.consume() }
        var sawFalse = false
        for (r in results) {
            if (!r) sawFalse = true
            if (sawFalse) assertFalse(r)
        }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): List<Array<Any>> = listOf(
            // cap=0 edge
            arrayOf<Any>("cap=0 calls=0", 0, 0),
            arrayOf<Any>("cap=0 calls=5", 0, 5),
            // cap=1 smallest non-zero
            arrayOf<Any>("cap=1 calls=3", 1, 3),
            // production default cap=150
            arrayOf<Any>("cap=150 calls=149", 150, 149),
            arrayOf<Any>("cap=150 calls=150", 150, 150),
            arrayOf<Any>("cap=150 calls=151", 150, 151),
            // medium sparse samples
            arrayOf<Any>("cap=5 calls=10", 5, 10),
            arrayOf<Any>("cap=50 calls=100", 50, 100),
            // large cap, never exhausts
            arrayOf<Any>("cap=1000 calls=200", 1_000, 200),
            // calls=0 edge: never consumes, all zero trues
            arrayOf<Any>("cap=5 calls=0", 5, 0),
        )
    }
}

/**
 * 非参数化业务边界：PullBudget 的真正业务契约 —— 保留全部。
 * 这些是 PullWorker 的速率限制能不能正确起作用的关键。
 */
class PullBudgetMatrixEdgeTest {

    @Test
    fun `cap zero remaining zero`() {
        assertEquals(0, PullBudget(0).remaining)
    }

    @Test
    fun `cap zero exhausted immediately`() {
        assertTrue(PullBudget(0).exhausted())
    }

    @Test
    fun `cap zero consume always false`() {
        val b = PullBudget(0)
        repeat(50) { assertFalse(b.consume()) }
    }

    @Test
    fun `cap negative one treated as zero for remaining`() {
        assertEquals(0, PullBudget(-1).remaining)
    }

    @Test
    fun `cap negative one exhausted`() {
        assertTrue(PullBudget(-1).exhausted())
    }

    @Test
    fun `cap negative one consume returns false`() {
        assertFalse(PullBudget(-1).consume())
    }

    @Test
    fun `cap negative large remaining still zero`() {
        assertEquals(0, PullBudget(-100_000).remaining)
    }

    @Test
    fun `production cap 150 exhausts at exactly 150 consumes`() {
        val b = PullBudget()
        repeat(150) { assertTrue("$it", b.consume()) }
        assertTrue(b.exhausted())
        assertFalse(b.consume())
    }

    @Test
    fun `production cap 150 at 149 consumes not exhausted`() {
        val b = PullBudget()
        repeat(149) { b.consume() }
        assertFalse(b.exhausted())
        assertEquals(1, b.remaining)
    }

    @Test
    fun `production cap 150 at 151 consumes exhausted`() {
        val b = PullBudget()
        repeat(151) { b.consume() }
        assertTrue(b.exhausted())
        assertEquals(0, b.remaining)
    }

    @Test
    fun `production cap 150 at 50 consumes remaining 100`() {
        val b = PullBudget()
        repeat(50) { b.consume() }
        assertEquals(100, b.remaining)
    }

    @Test
    fun `production cap 150 at 0 consumes remaining 150`() {
        assertEquals(150, PullBudget().remaining)
    }

    @Test
    fun `production cap 150 exhausted false initially`() {
        assertFalse(PullBudget().exhausted())
    }

    @Test
    fun `production cap 150 sequence consume exhaust at 150`() {
        val b = PullBudget()
        val seq = (0 until 152).map { b.consume() }
        assertEquals(150, seq.count { it })
        assertFalse(seq[150])
        assertFalse(seq[151])
    }

    @Test
    fun `default cap companion value is 150`() {
        assertEquals(150, PullBudget.DEFAULT_CAP)
    }

    @Test
    fun `cap extremely large stays non-exhausted after typical use`() {
        val b = PullBudget(Int.MAX_VALUE / 4)
        repeat(100_000) { b.consume() }
        assertFalse(b.exhausted())
    }

    @Test
    fun `remaining decreases by exactly one per successful consume`() {
        val b = PullBudget(100)
        var prev = b.remaining
        for (i in 1..100) {
            val ok = b.consume()
            assertTrue(ok)
            val now = b.remaining
            assertEquals("i=$i", prev - 1, now)
            prev = now
        }
    }

    @Test
    fun `remaining unchanged after a failed consume`() {
        val b = PullBudget(2)
        b.consume()
        b.consume()
        val before = b.remaining
        b.consume()
        assertEquals(before, b.remaining)
    }

    @Test
    fun `remaining never negative even after a thousand over-consumes`() {
        val b = PullBudget(3)
        repeat(1_000) { b.consume() }
        assertTrue(b.remaining >= 0)
    }

    @Test
    fun `two instances are independent`() {
        val a = PullBudget(5)
        val b = PullBudget(5)
        repeat(5) { a.consume() }
        assertTrue(a.exhausted())
        assertFalse(b.exhausted())
        assertEquals(5, b.remaining)
    }

    @Test
    fun `remaining equals zero iff exhausted after normal cap`() {
        val b = PullBudget(3)
        repeat(3) { b.consume() }
        assertEquals(0, b.remaining)
        assertTrue(b.exhausted())
    }
}
