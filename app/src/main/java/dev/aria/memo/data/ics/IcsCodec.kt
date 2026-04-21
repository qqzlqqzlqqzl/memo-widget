package dev.aria.memo.data.ics

import dev.aria.memo.data.local.EventEntity
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Minimal iCalendar (RFC 5545) codec — supports exactly the fields we need
 * for the P2 schedule feature: VCALENDAR wrapper, one VEVENT with UID,
 * DTSTAMP, SUMMARY, DTSTART, DTEND. No RRULE, no VTIMEZONE — everything is
 * serialized as UTC basic format (`YYYYMMDDTHHMMSSZ`). Good enough to be
 * read by Google Calendar / Apple Calendar / khal.
 *
 * Encode output uses CRLF line endings per RFC 5545. Decode tolerates both.
 */
object IcsCodec {

    private const val PRODID = "-//memo-widget//EN"
    private val UTC_FMT: DateTimeFormatter = DateTimeFormatter
        .ofPattern("yyyyMMdd'T'HHmmss'Z'")
        .withZone(ZoneOffset.UTC)

    fun encode(event: EventEntity): String {
        val sb = StringBuilder()
        sb.append("BEGIN:VCALENDAR\r\n")
        sb.append("VERSION:2.0\r\n")
        sb.append("PRODID:$PRODID\r\n")
        sb.append("BEGIN:VEVENT\r\n")
        // Fixes #1: escape UID so `;` / `:` / `\` / `\n` in the UID survive a round trip.
        sb.append("UID:").append(escapeText(event.uid)).append("\r\n")
        sb.append("DTSTAMP:").append(UTC_FMT.format(Instant.ofEpochMilli(event.localUpdatedAt))).append("\r\n")
        sb.append("SUMMARY:").append(escapeText(event.summary)).append("\r\n")
        sb.append("DTSTART:").append(UTC_FMT.format(Instant.ofEpochMilli(event.startEpochMs))).append("\r\n")
        sb.append("DTEND:").append(UTC_FMT.format(Instant.ofEpochMilli(event.endEpochMs))).append("\r\n")
        if (!event.rrule.isNullOrBlank()) {
            sb.append("RRULE:").append(event.rrule).append("\r\n")
        }
        sb.append("END:VEVENT\r\n")
        sb.append("END:VCALENDAR\r\n")
        return sb.toString()
    }

    /** Parse one VEVENT block. Returns null if mandatory fields are missing. */
    fun decode(text: String, filePath: String, githubSha: String?, nowMs: Long): EventEntity? {
        val unfolded = unfoldLines(text)
        val fields = HashMap<String, String>()
        var inEvent = false
        for (line in unfolded.lineSequence()) {
            val trimmed = line.trim()
            when {
                trimmed == "BEGIN:VEVENT" -> inEvent = true
                trimmed == "END:VEVENT" -> inEvent = false
                inEvent -> {
                    val idx = trimmed.indexOf(':')
                    if (idx <= 0) continue
                    // Strip any params after ; (e.g. "DTSTART;TZID=...")
                    val keyRaw = trimmed.substring(0, idx)
                    val key = keyRaw.substringBefore(';').uppercase()
                    val value = trimmed.substring(idx + 1)
                    fields[key] = value
                }
            }
        }
        // Fixes #1: UIDs written by us are escaped on encode, so reverse that here.
        val uid = fields["UID"]?.let(::unescapeText) ?: return null
        val summary = unescapeText(fields["SUMMARY"].orEmpty())
        val start = parseIcsInstant(fields["DTSTART"]) ?: return null
        val end = parseIcsInstant(fields["DTEND"]) ?: start
        return EventEntity(
            uid = uid,
            summary = summary,
            startEpochMs = start,
            endEpochMs = end,
            allDay = fields["DTSTART"]?.let { !it.contains('T') } ?: false,
            filePath = filePath,
            githubSha = githubSha,
            localUpdatedAt = parseIcsInstant(fields["DTSTAMP"]) ?: nowMs,
            remoteUpdatedAt = nowMs,
            dirty = false,
            rrule = fields["RRULE"]?.takeIf { it.isNotBlank() },
        )
    }

    // --- helpers -----------------------------------------------------------

    /**
     * RFC 5545 line folding: any line beginning with a space/tab is a
     * continuation of the previous one. We reverse that before parsing.
     */
    private fun unfoldLines(text: String): String {
        val out = StringBuilder(text.length)
        val lines = text.split("\r\n", "\n")
        for (line in lines) {
            if (line.startsWith(" ") || line.startsWith("\t")) {
                // Strip trailing newline already added to out, append without the leading whitespace.
                if (out.isNotEmpty() && out.last() == '\n') out.setLength(out.length - 1)
                out.append(line.substring(1))
                out.append('\n')
            } else {
                out.append(line)
                out.append('\n')
            }
        }
        return out.toString()
    }

    private fun parseIcsInstant(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val cleaned = raw.trim()
        return try {
            when {
                cleaned.endsWith("Z") -> Instant.from(UTC_FMT.parse(cleaned)).toEpochMilli()
                cleaned.length == 8 -> { // date-only YYYYMMDD → midnight UTC
                    val y = cleaned.substring(0, 4).toInt()
                    val m = cleaned.substring(4, 6).toInt()
                    val d = cleaned.substring(6, 8).toInt()
                    java.time.LocalDate.of(y, m, d).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                }
                else -> null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun escapeText(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\n", "\\n")
        .replace(",", "\\,")
        .replace(";", "\\;")

    /**
     * Single-pass unescaper. `replace`-chained unescaping is not round-trip
     * safe: an escaped backslash can collide with a subsequent escape pass.
     * We walk char-by-char and map `\x` sequences once.
     */
    private fun unescapeText(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    'n', 'N' -> out.append('\n')
                    ',' -> out.append(',')
                    ';' -> out.append(';')
                    '\\' -> out.append('\\')
                    else -> { out.append(c); out.append(n) }
                }
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }
}
