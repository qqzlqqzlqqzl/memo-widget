package dev.aria.memo.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the FNV-1a 64-bit itemId synthesis (#319 / Perf-1 M4).
 */
class WidgetItemIdTest {

    @Test
    fun `same input produces the same hash`() {
        assertEquals(fnv1a64("hello"), fnv1a64("hello"))
        assertEquals(stableItemId('u', "abc"), stableItemId('u', "abc"))
    }

    @Test
    fun `different inputs almost certainly produce different hashes`() {
        assertNotEquals(fnv1a64(""), fnv1a64("x"))
        assertNotEquals(fnv1a64("hello"), fnv1a64("hellp"))
    }

    @Test
    fun `tag prefix prevents cross-source collisions`() {
        // The same key under a different source tag MUST hash differently
        // — otherwise an event uid that matches a memo composite key
        // would still collide.
        assertNotEquals(stableItemId('u', "key-1"), stableItemId('l', "key-1"))
        assertNotEquals(stableItemId('e', "uid-foo"), stableItemId('m', "uid-foo"))
    }

    @Test
    fun `uniformly distributed keys do not collide at scale`() {
        // 5000 distinct keys must produce 5000 distinct hashes.
        // FNV-1a 64-bit at this scale has roughly P(collision) ≈ 6.8e-13
        // per pair so this is effectively a "no collisions ever" check.
        val ids = HashSet<Long>()
        for (i in 0 until 5000) {
            ids += stableItemId('u', "uid-$i")
        }
        assertEquals(5000, ids.size)
    }

    @Test
    fun `legacy memo composite keys distinguish same-minute different-body`() {
        // Issue #319 motivating case: two legacy entries on the same
        // (date, time) but with different bodies must get different ids,
        // otherwise Glance would recycle one row's view for the other.
        val a = stableItemId('l', "2026-04-28|09:00|first body")
        val b = stableItemId('l', "2026-04-28|09:00|second body")
        assertNotEquals(a, b)
    }

    @Test
    fun `single-note uid is enough on its own`() {
        // Per-row uids are unique across the table, so the same uid in
        // two emissions produces the same itemId — Glance can keep its
        // recycler bindings stable across recompositions.
        assertEquals(
            stableItemId('u', "note-uid-123"),
            stableItemId('u', "note-uid-123"),
        )
    }

    @Test
    fun `range of values uses more than 32 bits`() {
        // Sanity check: hashes must occupy distinct values in the upper
        // half of the 64-bit space, not just collapse into 32 bits like
        // String.hashCode would. We sample varied inputs and check that
        // the high 32 bits are not all zero across the set.
        val varied = listOf(
            "uid-deadbeef",
            "uid-cafebabe",
            "n-2026-04-28-09-00",
            "abc",
            "xyz",
            "f44932c-some-thing-long",
        )
        val highWords = varied.map { (fnv1a64(it) ushr 32).toInt() }.toSet()
        assertTrue(
            "expected non-trivial high-word distribution: $highWords",
            highWords.size >= 4,
        )
        assertTrue(
            "and at least one non-zero high word — otherwise we collapse to 32 bits",
            highWords.any { it != 0 },
        )
    }
}
