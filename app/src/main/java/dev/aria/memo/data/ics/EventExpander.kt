package dev.aria.memo.data.ics

import dev.aria.memo.data.local.EventEntity
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/** One materialised instance of a (possibly recurring) event. */
data class EventOccurrence(
    val event: EventEntity,
    val startEpochMs: Long,
    val endEpochMs: Long,
)

/**
 * Expand a single [EventEntity] into concrete occurrences that fall within
 * `[rangeStartMs, rangeEndMs)`.
 *
 * Subset supported (FREQ only):
 *   - `WEEKLY`  — every 7 days from the event's origin, same clock time
 *   - `MONTHLY` — every calendar month on the **original day-of-month**,
 *     with end-of-month clamping (Jan 31 → Feb 28 → **Mar 31**, not Mar 28).
 * All other RRULE parts are ignored; unknown FREQ degrades to a single shot.
 *
 * Performance: WEEKLY is closed-form (no per-step loop when the range starts
 * far in the future). MONTHLY walks month-by-month within the caller's range
 * — cheap because the caller bounds the range to ~1 year.
 *
 * Bounds: the upstream caller bounds the range window; this function enforces
 * a defence-in-depth cap of [ABSOLUTE_HORIZON_DAYS] from the range start so a
 * runaway caller can't pin the CPU on a pathological input.
 */
object EventExpander {

    private const val ABSOLUTE_HORIZON_DAYS = 400L
    private const val MS_PER_DAY = 86_400_000L

    fun expand(
        event: EventEntity,
        rangeStartMs: Long,
        rangeEndMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<EventOccurrence> {
        val freq = parseFreq(event.rrule)
        val duration = event.endEpochMs - event.startEpochMs
        if (freq == null) {
            return if (event.endEpochMs >= rangeStartMs && event.startEpochMs < rangeEndMs) {
                listOf(EventOccurrence(event, event.startEpochMs, event.endEpochMs))
            } else emptyList()
        }

        val cappedEnd = rangeEndMs.coerceAtMost(rangeStartMs + ABSOLUTE_HORIZON_DAYS * MS_PER_DAY)
        if (event.startEpochMs >= cappedEnd) return emptyList()

        return when (freq) {
            Freq.WEEKLY -> expandWeekly(event, duration, rangeStartMs, cappedEnd)
            Freq.MONTHLY -> expandMonthly(event, duration, rangeStartMs, cappedEnd, zone)
        }
    }

    private fun expandWeekly(
        event: EventEntity,
        duration: Long,
        rangeStartMs: Long,
        rangeEndMs: Long,
    ): List<EventOccurrence> {
        val period = 7L * MS_PER_DAY
        // Fast-forward to the first occurrence whose end lands in the window.
        val minStart = (rangeStartMs - duration).coerceAtLeast(event.startEpochMs)
        val skip = ((minStart - event.startEpochMs).coerceAtLeast(0) + period - 1) / period
        var start = event.startEpochMs + skip * period
        val out = ArrayList<EventOccurrence>()
        while (start < rangeEndMs) {
            val end = start + duration
            if (end >= rangeStartMs) out.add(EventOccurrence(event, start, end))
            start += period
        }
        return out
    }

    private fun expandMonthly(
        event: EventEntity,
        duration: Long,
        rangeStartMs: Long,
        rangeEndMs: Long,
        zone: ZoneId,
    ): List<EventOccurrence> {
        val baseZdt = Instant.ofEpochMilli(event.startEpochMs).atZone(zone)
        val anchorDay = baseZdt.dayOfMonth
        val out = ArrayList<EventOccurrence>()
        var n = 0
        while (true) {
            val targetMonth = baseZdt.toLocalDate().plusMonths(n.toLong()).let { YearMonth.from(it) }
            val clampedDay = anchorDay.coerceAtMost(targetMonth.lengthOfMonth())
            val startZdt = targetMonth.atDay(clampedDay)
                .atTime(baseZdt.toLocalTime())
                .atZone(zone)
            val startMs = startZdt.toInstant().toEpochMilli()
            if (startMs >= rangeEndMs) break
            val endMs = startMs + duration
            if (endMs >= rangeStartMs) out.add(EventOccurrence(event, startMs, endMs))
            n++
        }
        return out
    }

    private enum class Freq { WEEKLY, MONTHLY }

    private fun parseFreq(rrule: String?): Freq? {
        if (rrule.isNullOrBlank()) return null
        val parts = rrule.split(';').mapNotNull {
            val kv = it.split('=', limit = 2)
            if (kv.size == 2) kv[0].trim().uppercase() to kv[1].trim().uppercase() else null
        }.toMap()
        return when (parts["FREQ"]) {
            "WEEKLY" -> Freq.WEEKLY
            "MONTHLY" -> Freq.MONTHLY
            else -> null
        }
    }
}
