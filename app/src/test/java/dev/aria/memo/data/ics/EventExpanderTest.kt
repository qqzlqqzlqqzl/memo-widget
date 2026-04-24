package dev.aria.memo.data.ics

import dev.aria.memo.data.local.EventEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class EventExpanderTest {

    private fun base(
        startIso: String,
        durationMinutes: Long = 60,
        rrule: String? = null,
    ): EventEntity {
        val start = LocalDateTime.parse(startIso).toInstant(ZoneOffset.UTC).toEpochMilli()
        return EventEntity(
            uid = "t",
            summary = "t",
            startEpochMs = start,
            endEpochMs = start + durationMinutes * 60_000L,
            allDay = false,
            filePath = "events/t.ics",
            githubSha = null,
            localUpdatedAt = 0,
            remoteUpdatedAt = null,
            dirty = false,
            rrule = rrule,
        )
    }

    @Test
    fun `non-recurring event yields a single occurrence inside the window`() {
        val e = base("2026-04-21T10:00:00")
        val occ = EventExpander.expand(e, e.startEpochMs - 1, e.endEpochMs + 1, ZoneOffset.UTC)
        assertEquals(1, occ.size)
        assertEquals(e.startEpochMs, occ[0].startEpochMs)
    }

    @Test
    fun `non-recurring event outside window yields zero occurrences`() {
        val e = base("2026-04-21T10:00:00")
        val later = e.endEpochMs + 1_000_000L
        val occ = EventExpander.expand(e, later, later + 1000, ZoneOffset.UTC)
        assertTrue(occ.isEmpty())
    }

    @Test
    fun `FREQ=WEEKLY expands to one per week across the window`() {
        val e = base("2026-04-21T10:00:00", rrule = "FREQ=WEEKLY")
        val windowEnd = e.startEpochMs + 28L * 86_400_000L // +4 weeks
        val occ = EventExpander.expand(e, e.startEpochMs, windowEnd, ZoneOffset.UTC)
        assertEquals(4, occ.size) // 21st, 28th, May 5, May 12 (window ends at +28 days exclusive, so 4 occurrences)
    }

    @Test
    fun `FREQ=MONTHLY expands monthly across the window`() {
        val e = base("2026-04-21T10:00:00", rrule = "FREQ=MONTHLY")
        val windowEnd = e.startEpochMs + 120L * 86_400_000L // ~4 months
        val occ = EventExpander.expand(e, e.startEpochMs, windowEnd, ZoneOffset.UTC)
        assertTrue("should have at least 3 monthly occurrences, got ${occ.size}", occ.size >= 3)
    }

    @Test
    fun `unknown FREQ degrades to non-recurring`() {
        val e = base("2026-04-21T10:00:00", rrule = "FREQ=YEARLY")
        val windowEnd = e.startEpochMs + 400L * 86_400_000L
        val occ = EventExpander.expand(e, e.startEpochMs, windowEnd, ZoneOffset.UTC)
        assertEquals(1, occ.size)
    }

    @Test
    fun `RRULE round-trips through IcsCodec`() {
        val e = base("2026-04-21T10:00:00", rrule = "FREQ=WEEKLY")
        val decoded = IcsCodec.decode(IcsCodec.encode(e), e.filePath, null, 0L)
        assertEquals("FREQ=WEEKLY", decoded?.rrule)
    }

    @Test
    fun `window that starts after first occurrence still catches later weekly ones`() {
        val e = base("2026-01-01T10:00:00", rrule = "FREQ=WEEKLY")
        val rangeStart = e.startEpochMs + 30L * 86_400_000L // skip first month
        val rangeEnd = e.startEpochMs + 60L * 86_400_000L
        val occ = EventExpander.expand(e, rangeStart, rangeEnd, ZoneOffset.UTC)
        assertTrue("should find at least a few weekly occurrences in that month, got ${occ.size}", occ.size >= 3)
    }

    @Test
    fun `MONTHLY from Jan 31 rolls to Feb 28 then back to Mar 31 not Mar 28`() {
        val e = base("2026-01-31T10:00:00", rrule = "FREQ=MONTHLY")
        val windowEnd = e.startEpochMs + 95L * 86_400_000L // through early May
        val occ = EventExpander.expand(e, e.startEpochMs, windowEnd, ZoneOffset.UTC)
        val days = occ.map {
            LocalDateTime.ofEpochSecond(it.startEpochMs / 1000, 0, ZoneOffset.UTC).dayOfMonth
        }
        // Should be [31, 28, 31, 30] — anchors back to day-of-month 31, not drift to 28.
        assertEquals(listOf(31, 28, 31, 30), days.take(4))
    }

    @Test
    fun `WEEKLY fast-forward skips distant past without walking every week`() {
        // Event from 2020, ask about a window 6 years later.
        val e = base("2020-01-07T09:00:00", rrule = "FREQ=WEEKLY")
        val start = LocalDateTime.parse("2026-04-14T00:00:00").toInstant(ZoneOffset.UTC).toEpochMilli()
        val end = start + 14L * 86_400_000L
        val t0 = System.nanoTime()
        val occ = EventExpander.expand(e, start, end, ZoneOffset.UTC)
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000
        assertTrue("expand should be fast even for old origin events, was ${elapsedMs}ms", elapsedMs < 50)
        assertTrue("should still produce 2 weekly occurrences, got ${occ.size}", occ.size == 2)
    }
}
