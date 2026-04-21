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
