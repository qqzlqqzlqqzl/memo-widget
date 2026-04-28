package dev.aria.memo.data.ics

import dev.aria.memo.data.local.EventEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IcsCodecTest {

    private fun sample(uid: String = "abc-123", summary: String = "standup"): EventEntity =
        EventEntity(
            uid = uid,
            summary = summary,
            startEpochMs = 1_713_600_000_000L,
            endEpochMs = 1_713_603_600_000L,
            allDay = false,
            filePath = "events/$uid.ics",
            githubSha = null,
            localUpdatedAt = 1_713_600_000_000L,
            remoteUpdatedAt = null,
            dirty = false,
        )

    @Test
    fun `encode then decode round-trips uid and summary`() {
        val original = sample()
        val encoded = IcsCodec.encode(original)
        val decoded = IcsCodec.decode(encoded, original.filePath, null, 0L)
        assertNotNull(decoded)
        assertEquals(original.uid, decoded!!.uid)
        assertEquals(original.summary, decoded.summary)
        assertEquals(original.startEpochMs, decoded.startEpochMs)
        assertEquals(original.endEpochMs, decoded.endEpochMs)
    }

    @Test
    fun `issue 1 — uid containing semicolons survives round trip`() {
        val weird = sample(uid = "foo;bar;baz")
        val decoded = IcsCodec.decode(IcsCodec.encode(weird), weird.filePath, null, 0L)
        assertNotNull(decoded)
        assertEquals("foo;bar;baz", decoded!!.uid)
    }

    @Test
    fun `issue 1 — uid with colon and backslash survives round trip`() {
        val weird = sample(uid = "weird\\uid:1")
        val decoded = IcsCodec.decode(IcsCodec.encode(weird), weird.filePath, null, 0L)
        assertNotNull(decoded)
        assertEquals("weird\\uid:1", decoded!!.uid)
    }

    @Test
    fun `summary with newline survives round trip`() {
        val multiline = sample(summary = "line one\nline two")
        val decoded = IcsCodec.decode(IcsCodec.encode(multiline), multiline.filePath, null, 0L)
        assertNotNull(decoded)
        assertEquals("line one\nline two", decoded!!.summary)
    }

    @Test
    fun `decode tolerates lf-only line endings`() {
        val valid = """
BEGIN:VCALENDAR
VERSION:2.0
BEGIN:VEVENT
UID:test-uid
SUMMARY:lf only
DTSTART:20260421T100000Z
DTEND:20260421T110000Z
END:VEVENT
END:VCALENDAR
""".trimIndent()
        val decoded = IcsCodec.decode(valid, "events/test.ics", null, 0L)
        assertNotNull(decoded)
        assertEquals("test-uid", decoded!!.uid)
        assertEquals("lf only", decoded.summary)
    }

    @Test
    fun `decode returns null when uid is missing`() {
        val noUid = """
BEGIN:VCALENDAR
BEGIN:VEVENT
SUMMARY:missing uid
DTSTART:20260421T100000Z
END:VEVENT
END:VCALENDAR
""".trimIndent()
        assertNull(IcsCodec.decode(noUid, "events/x.ics", null, 0L))
    }

    @Test
    fun `encoded output uses crlf line endings`() {
        val encoded = IcsCodec.encode(sample())
        assertTrue("must contain CRLF line separators", encoded.contains("\r\n"))
    }

    @Test
    fun `issue 28 — long chinese summary folds at 75 octets and unfolds round trip`() {
        // 80 chinese chars × 3 UTF-8 octets each = 240 octets, well past the 75-octet cap.
        val longSummary = "中文提醒一条很长的日程标题用来测试折行行为".repeat(4)
        val event = sample(summary = longSummary)
        val encoded = IcsCodec.encode(event)

        // Fold must actually happen: continuation lines start with `"\r\n "`.
        assertTrue(
            "long SUMMARY must be folded with CRLF+space continuation",
            encoded.contains("\r\n "),
        )
        // No single physical line may exceed 75 octets.
        for (line in encoded.split("\r\n")) {
            assertTrue(
                "physical line exceeds 75 octets: ${line.toByteArray(Charsets.UTF_8).size} bytes",
                line.toByteArray(Charsets.UTF_8).size <= 75,
            )
        }

        val decoded = IcsCodec.decode(encoded, event.filePath, null, 0L)
        assertNotNull(decoded)
        assertEquals(longSummary, decoded!!.summary)
    }

    @Test
    fun `issue 106 — DTSTART with TZID parses without dropping the event`() {
        val tzid = """
BEGIN:VCALENDAR
VERSION:2.0
BEGIN:VEVENT
UID:tz-evt
SUMMARY:meeting
DTSTART;TZID=America/Los_Angeles:20260427T140000
DTEND;TZID=America/Los_Angeles:20260427T150000
END:VEVENT
END:VCALENDAR
""".trimIndent()
        val decoded = IcsCodec.decode(tzid, "events/tz.ics", null, 0L)
        assertNotNull("TZID DTSTART must not silently drop the VEVENT (#106)", decoded)
        assertEquals("tz-evt", decoded!!.uid)
        // 14:00 PT on 2026-04-27 = 21:00 UTC same day = epoch ms
        val expected = java.time.ZonedDateTime
            .of(2026, 4, 27, 14, 0, 0, 0, java.time.ZoneId.of("America/Los_Angeles"))
            .toInstant()
            .toEpochMilli()
        assertEquals(expected, decoded.startEpochMs)
        assertTrue("timed event with HHMMSS must not be marked all-day", !decoded.allDay)
    }

    @Test
    fun `issue 106 — floating DTSTART without Z parses in system default zone`() {
        val floating = """
BEGIN:VCALENDAR
VERSION:2.0
BEGIN:VEVENT
UID:floating-evt
SUMMARY:no-tz timed
DTSTART:20260427T140000
DTEND:20260427T150000
END:VEVENT
END:VCALENDAR
""".trimIndent()
        val decoded = IcsCodec.decode(floating, "events/floating.ics", null, 0L)
        assertNotNull("floating DTSTART must not return null (#106)", decoded)
        // Equivalent computation in the same default zone — start should match.
        val expected = java.time.LocalDateTime
            .of(2026, 4, 27, 14, 0, 0)
            .atZone(java.time.ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        assertEquals(expected, decoded!!.startEpochMs)
    }

    @Test
    fun `issue 106 — VALUE=DATE explicitly marks all-day even with date value`() {
        val allDay = """
BEGIN:VCALENDAR
VERSION:2.0
BEGIN:VEVENT
UID:allday-evt
SUMMARY:holiday
DTSTART;VALUE=DATE:20260427
DTEND;VALUE=DATE:20260428
END:VEVENT
END:VCALENDAR
""".trimIndent()
        val decoded = IcsCodec.decode(allDay, "events/allday.ics", null, 0L)
        assertNotNull(decoded)
        assertTrue("VALUE=DATE must mark allDay=true", decoded!!.allDay)
    }

    @Test
    fun `issue 106 — unknown TZID falls back to UTC for date-only or system zone for timed`() {
        val unknown = """
BEGIN:VCALENDAR
VERSION:2.0
BEGIN:VEVENT
UID:bad-tz
SUMMARY:typo zone
DTSTART;TZID=Mars/Olympus:20260427T140000
END:VEVENT
END:VCALENDAR
""".trimIndent()
        val decoded = IcsCodec.decode(unknown, "events/bad.ics", null, 0L)
        // Should not crash; should still decode by falling back, not return null.
        assertNotNull("unparseable TZID must fall back, not drop the VEVENT", decoded)
        val expected = java.time.LocalDateTime
            .of(2026, 4, 27, 14, 0, 0)
            .atZone(java.time.ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        assertEquals(expected, decoded!!.startEpochMs)
    }

    @Test
    fun `issue 28 — rrule round trips even when value has separator characters`() {
        // Currently produced RRULEs are simple, but the encoder must not corrupt
        // arbitrary RRULE grammar (FREQ=WEEKLY;BYDAY=MO,WE,FR;COUNT=10).
        val recurring = sample().copy(rrule = "FREQ=WEEKLY;BYDAY=MO,WE,FR;COUNT=10")
        val encoded = IcsCodec.encode(recurring)
        val decoded = IcsCodec.decode(encoded, recurring.filePath, null, 0L)
        assertNotNull(decoded)
        assertEquals("FREQ=WEEKLY;BYDAY=MO,WE,FR;COUNT=10", decoded!!.rrule)
    }
}
