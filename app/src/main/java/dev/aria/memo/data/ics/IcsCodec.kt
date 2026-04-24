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
        appendLine(sb, "BEGIN:VCALENDAR")
        appendLine(sb, "VERSION:2.0")
        appendLine(sb, "PRODID:$PRODID")
        appendLine(sb, "BEGIN:VEVENT")
        // Fixes #1: escape UID so `;` / `:` / `\` / `\n` in the UID survive a round trip.
        appendLine(sb, "UID:${escapeText(event.uid)}")
        appendLine(sb, "DTSTAMP:${UTC_FMT.format(Instant.ofEpochMilli(event.localUpdatedAt))}")
        // Fixes #28: apply RFC 5545 line folding to SUMMARY (long UTF-8 values must be folded).
        appendLine(sb, "SUMMARY:${escapeText(event.summary)}")
        appendLine(sb, "DTSTART:${UTC_FMT.format(Instant.ofEpochMilli(event.startEpochMs))}")
        appendLine(sb, "DTEND:${UTC_FMT.format(Instant.ofEpochMilli(event.endEpochMs))}")
        if (!event.rrule.isNullOrBlank()) {
            // Fixes #28: currently produced RRULEs (FREQ=WEEKLY / FREQ=MONTHLY) have no special
            // characters, but escape anyway to guard against future RRULE values containing `,`/`;`/`\`.
            appendLine(sb, "RRULE:${encodeRRuleValue(event.rrule)}")
        }
        appendLine(sb, "END:VEVENT")
        appendLine(sb, "END:VCALENDAR")
        return sb.toString()
    }

    /** Fold [line] per RFC 5545 (§3.1, max 75 octets) and append with CRLF. */
    private fun appendLine(sb: StringBuilder, line: String) {
        sb.append(foldLine(line))
        sb.append("\r\n")
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
     * RFC 5545 recur-rule-part values are a list of `key=value;key=value` — colons,
     * backslashes, newlines should never appear, but we still strip them defensively
     * so hand-edited rules can't break a VEVENT. No escaping of `;` / `,` here — those
     * are syntactic separators inside the RRULE grammar itself.
     */
    private fun encodeRRuleValue(s: String): String = s
        .replace("\\", "")
        .replace("\r", "")
        .replace("\n", "")

    /**
     * RFC 5545 §3.1 "Content Lines": fold any line longer than 75 octets (UTF-8).
     * Continuations start with a single space. The boundary must not split a UTF-8
     * code point, so we back off to the previous byte that is not a continuation
     * byte (top two bits `10xxxxxx`).
     */
    private fun foldLine(line: String): String {
        val bytes = line.toByteArray(Charsets.UTF_8)
        if (bytes.size <= MAX_LINE_OCTETS) return line
        val out = StringBuilder(bytes.size + bytes.size / MAX_LINE_OCTETS + 2)
        var i = 0
        var first = true
        while (i < bytes.size) {
            // First segment gets the full 75-octet budget; continuation lines lose
            // one octet to the leading SP, so we cap them at 74.
            val budget = if (first) MAX_LINE_OCTETS else MAX_LINE_OCTETS - 1
            var end = minOf(i + budget, bytes.size)
            if (end < bytes.size) {
                // Walk back off any UTF-8 continuation bytes so we cut on a code-point boundary.
                while (end > i && (bytes[end].toInt() and 0xC0) == 0x80) end--
                if (end == i) end = minOf(i + budget, bytes.size) // degenerate, give up
            }
            val chunk = String(bytes, i, end - i, Charsets.UTF_8)
            if (!first) {
                out.append("\r\n ")
            }
            out.append(chunk)
            first = false
            i = end
        }
        return out.toString()
    }

    private const val MAX_LINE_OCTETS = 75

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
