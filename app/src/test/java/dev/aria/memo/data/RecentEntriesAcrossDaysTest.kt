package dev.aria.memo.data

import dev.aria.memo.data.MemoRepository.Companion.mergeRecentAcrossDays
import dev.aria.memo.data.local.NoteFileEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * Pure JVM tests for the cross-day "recent memos" feed used by the home-screen
 * widget. Bug being fixed: `MemoWidget` used to call [MemoRepository.recentEntries]
 * which only reads today's `YYYY-MM-DD.md`, so if the user didn't write today
 * the widget was blank even when yesterday's file had fresh entries. The new
 * [MemoRepository.recentEntriesAcrossDays] walks every cached day-file and
 * returns the newest N entries by (date, time); this test pins the companion
 * helper that does the actual merge so the widget always shows something as
 * long as Room has any notes.
 *
 * We exercise the public companion helper [mergeRecentAcrossDays] directly so
 * the suite stays on pure JVM — no Android Context, SettingsStore, Room or
 * Robolectric required. The suspend wrapper in [MemoRepository] is a thin
 * pass-through: it guards on [AppConfig.isConfigured] then delegates here.
 */
class RecentEntriesAcrossDaysTest {

    private fun note(
        date: LocalDate,
        content: String,
    ): NoteFileEntity = NoteFileEntity(
        path = "${date}.md",
        date = date,
        content = content,
        githubSha = null,
        localUpdatedAt = 0L,
        remoteUpdatedAt = null,
        dirty = false,
    )

    /**
     * Build a day-file with the same skeleton [MemoRepository.buildNewContent]
     * produces — `# date\n\n## HH:MM\nbody\n\n## HH:MM\nbody\n...` — so the
     * parser exercised here matches production content shape exactly.
     */
    private fun dayFile(date: LocalDate, vararg entries: Pair<LocalTime, String>): NoteFileEntity {
        val sb = StringBuilder()
        sb.append("# $date\n")
        for ((time, body) in entries) {
            val hhmm = "%02d:%02d".format(time.hour, time.minute)
            sb.append("\n## $hhmm\n")
            sb.append(body)
            if (!body.endsWith("\n")) sb.append("\n")
        }
        return note(date, sb.toString())
    }

    @Test
    fun `empty Room returns empty list`() {
        val result = mergeRecentAcrossDays(files = emptyList(), limit = 3)
        assertEquals(emptyList<DatedMemoEntry>(), result)
    }

    @Test
    fun `limit of zero returns empty list even with data`() {
        val today = LocalDate.of(2026, 4, 21)
        val files = listOf(
            dayFile(today, LocalTime.of(9, 0) to "hello"),
        )
        assertTrue(mergeRecentAcrossDays(files, limit = 0).isEmpty())
    }

    @Test
    fun `only today's notes present returns today's entries newest first`() {
        val today = LocalDate.of(2026, 4, 21)
        val files = listOf(
            dayFile(
                today,
                LocalTime.of(9, 0) to "morning",
                LocalTime.of(14, 30) to "afternoon",
                LocalTime.of(22, 0) to "night",
            ),
        )
        val result = mergeRecentAcrossDays(files, limit = 3)
        assertEquals(3, result.size)
        // parseEntries returns per-day newest-time-first, so the 22:00 entry
        // tops the list even though it is last inside the file.
        assertEquals(LocalTime.of(22, 0), result[0].time)
        assertEquals("night", result[0].body)
        assertEquals(LocalTime.of(14, 30), result[1].time)
        assertEquals("afternoon", result[1].body)
        assertEquals(LocalTime.of(9, 0), result[2].time)
        assertEquals("morning", result[2].body)
        // All rows are tagged with the owning file's date.
        assertTrue(result.all { it.date == today })
    }

    @Test
    fun `three days of notes with limit 3 returns the newest entries across days`() {
        val today = LocalDate.of(2026, 4, 21)
        val yesterday = today.minusDays(1)
        val dayBefore = today.minusDays(2)
        val files = listOf(
            dayFile(
                today,
                LocalTime.of(10, 0) to "today-a",
            ),
            dayFile(
                yesterday,
                LocalTime.of(23, 30) to "yesterday-late",
                LocalTime.of(8, 0) to "yesterday-early",
            ),
            dayFile(
                dayBefore,
                LocalTime.of(12, 0) to "two-days-ago",
            ),
        )
        val result = mergeRecentAcrossDays(files, limit = 3)
        assertEquals(3, result.size)
        // Order: today's one entry, then yesterday's two (late first).
        assertEquals(today, result[0].date)
        assertEquals(LocalTime.of(10, 0), result[0].time)
        assertEquals("today-a", result[0].body)

        assertEquals(yesterday, result[1].date)
        assertEquals(LocalTime.of(23, 30), result[1].time)
        assertEquals("yesterday-late", result[1].body)

        assertEquals(yesterday, result[2].date)
        assertEquals(LocalTime.of(8, 0), result[2].time)
        assertEquals("yesterday-early", result[2].body)
    }

    @Test
    fun `only yesterday has content - today missing - widget still shows yesterday`() {
        // This is the regression scenario from the bug report: the user didn't
        // write today, so a today-only query would return []. The cross-day
        // query must still surface yesterday's entries.
        val yesterday = LocalDate.of(2026, 4, 20)
        val files = listOf(
            dayFile(
                yesterday,
                LocalTime.of(8, 0) to "yesterday-morning",
                LocalTime.of(18, 0) to "yesterday-evening",
            ),
        )
        val result = mergeRecentAcrossDays(files, limit = 3)
        assertEquals(2, result.size)
        assertEquals(LocalTime.of(18, 0), result[0].time)
        assertEquals("yesterday-evening", result[0].body)
        assertEquals(LocalTime.of(8, 0), result[1].time)
        assertEquals("yesterday-morning", result[1].body)
        assertTrue("every row must carry yesterday's date", result.all { it.date == yesterday })
    }

    @Test
    fun `limit smaller than total entries clips to newest`() {
        val today = LocalDate.of(2026, 4, 21)
        val yesterday = today.minusDays(1)
        val files = listOf(
            dayFile(
                today,
                LocalTime.of(23, 0) to "today-late",
                LocalTime.of(8, 0) to "today-early",
            ),
            dayFile(
                yesterday,
                LocalTime.of(22, 0) to "yesterday-any",
            ),
        )
        val result = mergeRecentAcrossDays(files, limit = 2)
        assertEquals(2, result.size)
        // Both entries must be today's — yesterday.22:00 is strictly older
        // than today.08:00 because date dominates the ordering.
        assertEquals(today, result[0].date)
        assertEquals(today, result[1].date)
        assertEquals(LocalTime.of(23, 0), result[0].time)
        assertEquals(LocalTime.of(8, 0), result[1].time)
    }

    @Test
    fun `same minute multiple entries within a single day have stable ordering`() {
        // Two entries at 14:30 inside the same file. The parser's sorted-by-
        // time step is not stable across repeated runs of the regex ordering
        // if we sort with a non-stable comparator, so pin the order here.
        val today = LocalDate.of(2026, 4, 21)
        // parseEntries uses `sortedByDescending` on `LocalTime`, a stable sort
        // in Kotlin's implementation. For same-minute items the parse order
        // is preserved: the first `## 14:30` block in the file wins index 0.
        val file = dayFile(
            today,
            LocalTime.of(14, 30) to "first-same-minute",
            LocalTime.of(14, 30) to "second-same-minute",
            LocalTime.of(9, 0) to "morning",
        )
        val resultA = mergeRecentAcrossDays(listOf(file), limit = 3)
        val resultB = mergeRecentAcrossDays(listOf(file), limit = 3)
        // Determinism across repeated calls with the same input.
        assertEquals(resultA, resultB)
        // First 14:30 entry appears before the second.
        assertEquals("first-same-minute", resultA[0].body)
        assertEquals("second-same-minute", resultA[1].body)
        assertEquals("morning", resultA[2].body)
    }

    @Test
    fun `cross-day same-time entries order by date descending`() {
        val today = LocalDate.of(2026, 4, 21)
        val yesterday = today.minusDays(1)
        val files = listOf(
            dayFile(today, LocalTime.of(14, 30) to "today-1430"),
            dayFile(yesterday, LocalTime.of(14, 30) to "yesterday-1430"),
        )
        val result = mergeRecentAcrossDays(files, limit = 2)
        assertEquals(2, result.size)
        assertEquals(today, result[0].date)
        assertEquals("today-1430", result[0].body)
        assertEquals(yesterday, result[1].date)
        assertEquals("yesterday-1430", result[1].body)
    }

    @Test
    fun `files out of date order in the input still sort newest first`() {
        // Simulate what [NoteDao.observeAll] with a quirky ORDER BY or a
        // custom test double might produce — the helper must not trust the
        // incoming order.
        val today = LocalDate.of(2026, 4, 21)
        val yesterday = today.minusDays(1)
        val files = listOf(
            // Yesterday first, today second — wrong order on purpose.
            dayFile(yesterday, LocalTime.of(9, 0) to "y"),
            dayFile(today, LocalTime.of(9, 0) to "t"),
        )
        val result = mergeRecentAcrossDays(files, limit = 2)
        assertEquals(today, result[0].date)
        assertEquals("t", result[0].body)
        assertEquals(yesterday, result[1].date)
        assertEquals("y", result[1].body)
    }

    @Test
    fun `file with no parseable entries contributes nothing`() {
        // A placeholder file written by some future feature or a partially-
        // pulled remote revision might have a header but no `## HH:MM` blocks.
        val today = LocalDate.of(2026, 4, 21)
        val yesterday = today.minusDays(1)
        val files = listOf(
            note(today, "# $today\n\n(no entries yet)\n"),
            dayFile(yesterday, LocalTime.of(20, 0) to "from-yesterday"),
        )
        val result = mergeRecentAcrossDays(files, limit = 3)
        assertEquals(1, result.size)
        assertEquals(yesterday, result[0].date)
        assertEquals("from-yesterday", result[0].body)
    }

    @Test
    fun `pinned front matter is stripped and does not leak into entry body`() {
        // Guards against the regression where the pin `---\npinned: true\n---`
        // prefix could sneak in ahead of the `## HH:MM` header; parseEntries
        // strips it, and the widget should never see YAML in a body.
        val today = LocalDate.of(2026, 4, 21)
        val pinned = "---\npinned: true\n---\n\n# $today\n\n## 09:00\nhello from pinned day\n"
        val files = listOf(note(today, pinned))
        val result = mergeRecentAcrossDays(files, limit = 3)
        assertEquals(1, result.size)
        assertEquals("hello from pinned day", result[0].body)
        // Body must not contain the YAML fence itself.
        assertTrue("body must not contain fence", !result[0].body.contains("---"))
    }
}
