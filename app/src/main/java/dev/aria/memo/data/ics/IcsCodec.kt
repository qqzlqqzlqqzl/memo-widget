package dev.aria.memo.data.ics

import dev.aria.memo.data.local.EventEntity
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
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

    /**
     * Vendor X-property name we use to round-trip
     * [EventEntity.reminderMinutesBefore] (#308 fix). Hard-coded
     * upper-case to match our decoder's case-folding lookup.
     */
    private const val X_REMINDER_KEY = "X-MEMO-REMINDER-MINUTES"
    private val UTC_FMT: DateTimeFormatter = DateTimeFormatter
        .ofPattern("yyyyMMdd'T'HHmmss'Z'")
        .withZone(ZoneOffset.UTC)
    private val LOCAL_FMT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

    /**
     * Parsed content line: the value after `:` plus any `;param=value` pairs
     * from the property name (RFC 5545 §3.2). Captured separately so we can
     * read TZID / VALUE without losing them to the colon split.
     *
     * Fixes #106 (Bug-1 H7): the old decode dropped the part of the property
     * name after `;`, so `DTSTART;TZID=America/Los_Angeles:20260427T140000`
     * lost the TZID, fed `20260427T140000` to a UTC-only parser, returned
     * null, and silently dropped the entire VEVENT.
     */
    private data class FieldEntry(
        val value: String,
        val params: Map<String, String> = emptyMap(),
    )

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
        // Fixes #308 (Data-1 R17): persist the user's reminder choice
        // as an iCalendar X-property so it survives a round-trip to
        // GitHub. RFC 5545 §3.8.8.2 lets vendors stash app-specific
        // values under any `X-…` key — third-party calendar apps will
        // see this as an unknown property and ignore it harmlessly,
        // while our [decode] reads it back into [reminderMinutesBefore].
        // Local-only previously meant "device A's reminder vanishes
        // when device B's PullWorker syncs the canonical .ics".
        if (event.reminderMinutesBefore != null) {
            appendLine(sb, "$X_REMINDER_KEY:${event.reminderMinutesBefore}")
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
        val fields = HashMap<String, FieldEntry>()
        var inEvent = false
        for (line in unfolded.lineSequence()) {
            val trimmed = line.trim()
            when {
                trimmed == "BEGIN:VEVENT" -> inEvent = true
                trimmed == "END:VEVENT" -> inEvent = false
                inEvent -> {
                    val idx = trimmed.indexOf(':')
                    if (idx <= 0) continue
                    val keyRaw = trimmed.substring(0, idx)
                    val key = keyRaw.substringBefore(';').uppercase()
                    val params = parseParams(keyRaw)
                    val value = trimmed.substring(idx + 1)
                    fields[key] = FieldEntry(value, params)
                }
            }
        }
        // Fixes #1: UIDs written by us are escaped on encode, so reverse that here.
        val uid = fields["UID"]?.value?.let(::unescapeText) ?: return null
        val summary = unescapeText(fields["SUMMARY"]?.value.orEmpty())
        val dtstart = fields["DTSTART"] ?: return null
        val start = parseIcsInstant(dtstart.value, dtstart.params["TZID"]) ?: return null
        val dtend = fields["DTEND"]
        val end = dtend?.let { parseIcsInstant(it.value, it.params["TZID"]) } ?: start
        // VALUE=DATE marks an all-day event explicitly; otherwise infer from the
        // basic-format length (`YYYYMMDD` has no `T`).
        val allDay = dtstart.params["VALUE"]?.equals("DATE", ignoreCase = true) == true ||
            !dtstart.value.contains('T')
        // Fixes #308: round-trip the reminder via the vendor X-property
        // we wrote in [encode]. Anything unparseable falls back to null
        // so corrupt files don't crash the decode.
        val reminder = fields[X_REMINDER_KEY]?.value?.trim()?.toIntOrNull()
        return EventEntity(
            uid = uid,
            summary = summary,
            startEpochMs = start,
            endEpochMs = end,
            allDay = allDay,
            filePath = filePath,
            githubSha = githubSha,
            localUpdatedAt = fields["DTSTAMP"]?.let { parseIcsInstant(it.value, it.params["TZID"]) } ?: nowMs,
            remoteUpdatedAt = nowMs,
            dirty = false,
            rrule = fields["RRULE"]?.value?.takeIf { it.isNotBlank() },
            reminderMinutesBefore = reminder,
        )
    }

    /**
     * Pull the `;PARAM=value` pairs off a property name like
     * `DTSTART;TZID=America/Los_Angeles;VALUE=DATE-TIME`. Param names are
     * upper-cased so callers can lookup case-insensitively. Quoted values
     * (RFC 5545 §3.2: `DQUOTE`-wrapped) have their wrapping quotes stripped.
     */
    private fun parseParams(keyRaw: String): Map<String, String> {
        if (!keyRaw.contains(';')) return emptyMap()
        val parts = keyRaw.split(';')
        if (parts.size <= 1) return emptyMap()
        val out = HashMap<String, String>(parts.size - 1)
        for (i in 1 until parts.size) {
            val eq = parts[i].indexOf('=')
            if (eq <= 0) continue
            val k = parts[i].substring(0, eq).uppercase()
            val v = parts[i].substring(eq + 1).removeSurrounding("\"")
            out[k] = v
        }
        return out
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

    /**
     * Parse a DTSTART/DTEND/DTSTAMP value. Three RFC 5545 forms are accepted:
     *  - UTC basic: `YYYYMMDDTHHMMSSZ` — interpreted as UTC regardless of [tzid].
     *  - Floating local / TZID-anchored: `YYYYMMDDTHHMMSS` — interpreted in
     *    [tzid] when present, otherwise [ZoneId.systemDefault] (RFC 5545 §3.3.5
     *    "form #1: floating" → recipient's local zone).
     *  - Date-only: `YYYYMMDD` — midnight in [tzid] when present, otherwise UTC.
     *
     * Fixes #106 (Bug-1 H7): previously a TZID DTSTART or floating DTSTART hit
     * the `else` branch and made the whole VEVENT decode return null.
     */
    private fun parseIcsInstant(raw: String?, tzid: String? = null): Long? {
        if (raw.isNullOrBlank()) return null
        val cleaned = raw.trim()
        return try {
            when {
                cleaned.endsWith("Z") -> Instant.from(UTC_FMT.parse(cleaned)).toEpochMilli()
                cleaned.length == 8 -> {
                    val y = cleaned.substring(0, 4).toInt()
                    val m = cleaned.substring(4, 6).toInt()
                    val d = cleaned.substring(6, 8).toInt()
                    val zone = resolveZone(tzid) ?: ZoneOffset.UTC
                    LocalDate.of(y, m, d).atStartOfDay(zone).toInstant().toEpochMilli()
                }
                cleaned.length == 15 && cleaned[8] == 'T' -> {
                    val ldt = LocalDateTime.parse(cleaned, LOCAL_FMT)
                    val zone = resolveZone(tzid) ?: ZoneId.systemDefault()
                    ldt.atZone(zone).toInstant().toEpochMilli()
                }
                else -> null
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** Resolve a TZID parameter to a ZoneId; null when absent or unparseable. */
    private fun resolveZone(tzid: String?): ZoneId? {
        if (tzid.isNullOrBlank()) return null
        return runCatching { ZoneId.of(tzid) }.getOrNull()
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
