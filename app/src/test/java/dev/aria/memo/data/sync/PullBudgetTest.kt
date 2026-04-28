package dev.aria.memo.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PullBudgetTest {

    @Test
    fun `consume returns true until cap is reached`() {
        val budget = PullBudget(cap = 3)
        assertTrue(budget.consume())
        assertTrue(budget.consume())
        assertTrue(budget.consume())
        assertFalse(budget.consume())
    }

    @Test
    fun `remaining decrements with each consume`() {
        val budget = PullBudget(cap = 3)
        assertEquals(3, budget.remaining)
        budget.consume()
        assertEquals(2, budget.remaining)
        budget.consume()
        assertEquals(1, budget.remaining)
        budget.consume()
        assertEquals(0, budget.remaining)
    }

    @Test
    fun `remaining never goes below zero`() {
        val budget = PullBudget(cap = 1)
        budget.consume()
        budget.consume()
        assertEquals(0, budget.remaining)
    }

    @Test
    fun `exhausted becomes true after cap consumes`() {
        val budget = PullBudget(cap = 2)
        assertFalse(budget.exhausted())
        budget.consume()
        assertFalse(budget.exhausted())
        budget.consume()
        assertTrue(budget.exhausted())
    }

    @Test
    fun `cap=0 exhausted immediately and consume returns false`() {
        val budget = PullBudget(cap = 0)
        assertTrue(budget.exhausted())
        assertFalse(budget.consume())
        assertEquals(0, budget.remaining)
    }

    @Test
    fun `default cap is 150`() {
        val budget = PullBudget()
        assertEquals(150, budget.remaining)
    }

    @Test
    fun `tightenFromHeader lowers the cap to match advertised remaining`() {
        // Issue #314: GitHub responses carry X-RateLimit-Remaining; an
        // unauthenticated client at 8/60 should see the budget shrink to
        // 8 even though we constructed it with the default 150.
        val budget = PullBudget(cap = 150)
        budget.consume() // used = 1
        budget.tightenFromHeader("8")
        // capRef = used (1) + advertised (8) = 9; remaining = 9 - 1 = 8.
        assertEquals(8, budget.remaining)
    }

    @Test
    fun `tightenFromHeader never raises the cap`() {
        val budget = PullBudget(cap = 50)
        budget.tightenFromHeader("9999")
        assertEquals(50, budget.remaining)
    }

    @Test
    fun `tightenFromHeader ignores malformed values`() {
        val budget = PullBudget(cap = 50)
        budget.tightenFromHeader(null)
        budget.tightenFromHeader("")
        budget.tightenFromHeader("not-a-number")
        budget.tightenFromHeader("-3")
        assertEquals(50, budget.remaining)
    }

    @Test
    fun `consume is atomic under parallel callers`() {
        // Issue #314: simulate the future async path by hammering consume
        // from many threads. The total successful consumes must equal the
        // cap exactly — never less (lost increments) and never more
        // (over-budget API calls).
        val cap = 1000
        val parallelism = 16
        val budget = PullBudget(cap = cap)
        val successes = java.util.concurrent.atomic.AtomicInteger(0)
        val attempts = cap * 4
        val perThread = attempts / parallelism
        val threads = List(parallelism) {
            Thread {
                repeat(perThread) {
                    if (budget.consume()) successes.incrementAndGet()
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(cap, successes.get())
        assertTrue(budget.exhausted())
    }
}
