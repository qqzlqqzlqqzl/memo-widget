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
}
