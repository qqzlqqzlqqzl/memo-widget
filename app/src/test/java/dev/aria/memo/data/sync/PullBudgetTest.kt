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
}
