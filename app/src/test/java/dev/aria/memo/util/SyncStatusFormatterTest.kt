package dev.aria.memo.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for [SyncStatusFormatter.formatRelative] — no Compose, no
 * Robolectric. Each branch is locked down because the boundary-time strings
 * are user-facing and survive across releases.
 */
class SyncStatusFormatterTest {

    private val now = 1_750_000_000_000L // arbitrary fixed "now" for determinism

    @Test
    fun `epoch 0 returns null`() {
        assertNull(SyncStatusFormatter.formatRelative(now, 0L))
    }

    @Test
    fun `negative epoch returns null`() {
        // Defensive: DataStore default is 0L but a corrupt value should fall
        // through to "never" rather than producing a far-future timestamp.
        assertNull(SyncStatusFormatter.formatRelative(now, -123L))
    }

    @Test
    fun `delta under 60s shows just-now`() {
        assertEquals("刚刚", SyncStatusFormatter.formatRelative(now, now - 30_000L))
    }

    @Test
    fun `delta of 1 minute shows 1 分钟前`() {
        assertEquals("1 分钟前", SyncStatusFormatter.formatRelative(now, now - 60_000L))
    }

    @Test
    fun `delta of 12 minutes shows 12 分钟前`() {
        assertEquals("12 分钟前", SyncStatusFormatter.formatRelative(now, now - 12 * 60_000L))
    }

    @Test
    fun `delta of 59 minutes still in 分钟前 branch`() {
        assertEquals("59 分钟前", SyncStatusFormatter.formatRelative(now, now - 59 * 60_000L))
    }

    @Test
    fun `delta of 60 minutes flips to 1 小时前`() {
        assertEquals("1 小时前", SyncStatusFormatter.formatRelative(now, now - 60 * 60_000L))
    }

    @Test
    fun `delta of 5 hours shows 5 小时前`() {
        assertEquals("5 小时前", SyncStatusFormatter.formatRelative(now, now - 5 * 60 * 60_000L))
    }

    @Test
    fun `delta of 23 hours still in 小时前 branch`() {
        assertEquals("23 小时前", SyncStatusFormatter.formatRelative(now, now - 23 * 60 * 60_000L))
    }

    @Test
    fun `delta of 24 hours flips to absolute date format`() {
        val out = SyncStatusFormatter.formatRelative(now, now - 24 * 60 * 60_000L)
        // Don't assert exact value — host timezone may shift the rendered
        // date — just verify we left the relative branch and the shape
        // matches yyyy-MM-dd HH:mm.
        assertTrue("not null", out != null)
        assertTrue("not 小时前", !out!!.contains("小时前"))
        assertTrue("not 分钟前", !out.contains("分钟前"))
        assertTrue(
            "matches yyyy-MM-dd HH:mm",
            out.matches(Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}")),
        )
    }

    @Test
    fun `clock skew with future epoch is clamped to just-now`() {
        // System clock can jump backwards (NTP correction, manual change).
        // If epochMs > nowMs the math should not produce "negative minutes
        // ago" — it should fall through to "刚刚".
        assertEquals("刚刚", SyncStatusFormatter.formatRelative(now, now + 5_000L))
    }
}
