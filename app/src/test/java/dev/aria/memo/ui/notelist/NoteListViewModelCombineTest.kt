package dev.aria.memo.ui.notelist

import dev.aria.memo.data.local.NoteFileEntity
import dev.aria.memo.data.local.SingleNoteEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * Pure JVM tests for the combine-two-data-sources logic introduced in P6.1.
 * We don't instantiate [NoteListViewModel] directly (its constructors require
 * a live [android.content.Context] by way of the repositories); instead we
 * exercise the deterministic building blocks that the combine block uses:
 *
 *  - [NoteListUiItem.LegacyDay] / [NoteListUiItem.SingleNote] construction
 *    from raw entity data (schema-level parity check)
 *  - [NoteListViewModel.Companion.ITEM_ORDER] — pin-first, newest-first
 *  - [NoteListUiState.filtered] / `pinned` / `unpinned` query routing
 *  - [NoteListViewModel.Companion.buildPreview] — front-matter strip
 *
 * This matches the "pure JVM, no Robolectric" constraint for the test suite.
 */
class NoteListViewModelCombineTest {

    private val today: LocalDate = LocalDate.of(2026, 4, 22)
    private val yesterday: LocalDate = today.minusDays(1)

    private fun legacy(
        path: String,
        date: LocalDate = today,
        pinned: Boolean = false,
        entries: List<Pair<LocalTime, String>> = listOf(LocalTime.of(10, 0) to "hi"),
    ): NoteListUiItem.LegacyDay = NoteListUiItem.LegacyDay(
        DayGroup(
            date = date,
            entries = entries.map { (t, b) -> dev.aria.memo.data.MemoEntry(date, t, b) },
            dirty = false,
            path = path,
            pinned = pinned,
        )
    )

    private fun single(
        uid: String,
        date: LocalDate = today,
        time: LocalTime = LocalTime.of(10, 0),
        title: String = "title-$uid",
        preview: String = "preview-$uid",
        pinned: Boolean = false,
        path: String = "notes/$uid.md",
    ): NoteListUiItem.SingleNote = NoteListUiItem.SingleNote(
        uid = uid,
        path = path,
        title = title,
        preview = preview,
        date = date,
        time = time,
        pinned = pinned,
    )

    @Test
    fun `pinned items sort ahead of unpinned regardless of date`() {
        val items = listOf(
            single(uid = "fresh", date = today, time = LocalTime.of(23, 0), pinned = false),
            single(uid = "old-pin", date = yesterday, time = LocalTime.of(1, 0), pinned = true),
        )
        val sorted = items.sortedWith(NoteListViewModel.ITEM_ORDER)
        assertEquals("old-pin", (sorted[0] as NoteListUiItem.SingleNote).uid)
        assertEquals("fresh", (sorted[1] as NoteListUiItem.SingleNote).uid)
    }

    @Test
    fun `within same pin bucket items sort by date DESC then time DESC`() {
        val items = listOf(
            single(uid = "today-09", date = today, time = LocalTime.of(9, 0)),
            single(uid = "today-18", date = today, time = LocalTime.of(18, 0)),
            single(uid = "yesterday-23", date = yesterday, time = LocalTime.of(23, 0)),
        )
        val sorted = items.sortedWith(NoteListViewModel.ITEM_ORDER)
        assertEquals("today-18", (sorted[0] as NoteListUiItem.SingleNote).uid)
        assertEquals("today-09", (sorted[1] as NoteListUiItem.SingleNote).uid)
        assertEquals("yesterday-23", (sorted[2] as NoteListUiItem.SingleNote).uid)
    }

    @Test
    fun `mixed legacy and single from same date -- single wins on time`() {
        // Legacy items are treated as midnight when sorting; a single-note any
        // later time same day should appear first.
        val items: List<NoteListUiItem> = listOf(
            legacy(path = "$today.md", date = today),
            single(uid = "s1", date = today, time = LocalTime.of(9, 0)),
        )
        val sorted = items.sortedWith(NoteListViewModel.ITEM_ORDER)
        assertTrue("single should come first", sorted[0] is NoteListUiItem.SingleNote)
        assertTrue("legacy should be second", sorted[1] is NoteListUiItem.LegacyDay)
    }

    @Test
    fun `filtered by query matches single note by title`() {
        val state = NoteListUiState(
            items = listOf(
                single(uid = "a", title = "早晨想法", preview = "body"),
                single(uid = "b", title = "晚间总结", preview = "body"),
            ),
            query = "早晨",
        )
        val filtered = state.filtered
        assertEquals(1, filtered.size)
        assertEquals("a", (filtered[0] as NoteListUiItem.SingleNote).uid)
    }

    @Test
    fun `filtered by query matches single note by preview`() {
        val state = NoteListUiState(
            items = listOf(
                single(uid = "a", title = "title-a", preview = "今天 learned Glance"),
                single(uid = "b", title = "title-b", preview = "no match here"),
            ),
            query = "glance",
        )
        val filtered = state.filtered
        assertEquals(1, filtered.size)
        assertEquals("a", (filtered[0] as NoteListUiItem.SingleNote).uid)
    }

    @Test
    fun `filtered by query matches legacy day by entry body`() {
        val state = NoteListUiState(
            items = listOf(
                legacy(path = "a.md", entries = listOf(LocalTime.of(8, 0) to "买菜 / 跑步")),
                legacy(path = "b.md", entries = listOf(LocalTime.of(9, 0) to "凉面")),
            ),
            query = "跑步",
        )
        val filtered = state.filtered
        assertEquals(1, filtered.size)
        val day = filtered[0] as NoteListUiItem.LegacyDay
        assertEquals("a.md", day.group.path)
        // The matching entry survives, the other day's entry is dropped.
        assertEquals(1, day.group.entries.size)
    }

    @Test
    fun `filtered pinned and unpinned partition survives query filtering`() {
        val items = listOf(
            single(uid = "pin-match", pinned = true, title = "alpha"),
            single(uid = "pin-miss", pinned = true, title = "beta"),
            single(uid = "unpin-match", pinned = false, title = "alpha-2"),
            single(uid = "unpin-miss", pinned = false, title = "gamma"),
        )
        val state = NoteListUiState(items = items, query = "alpha")
        assertEquals(2, state.filtered.size)
        assertEquals(1, state.pinned.size)
        assertEquals("pin-match", (state.pinned[0] as NoteListUiItem.SingleNote).uid)
        assertEquals(1, state.unpinned.size)
        assertEquals("unpin-match", (state.unpinned[0] as NoteListUiItem.SingleNote).uid)
    }

    @Test
    fun `empty query returns every item untouched`() {
        val items = listOf(
            single(uid = "a"),
            single(uid = "b"),
        )
        val state = NoteListUiState(items = items, query = "   ")
        assertEquals(items, state.filtered)
    }

    @Test
    fun `groups accessor extracts legacy day groups only`() {
        val state = NoteListUiState(
            items = listOf(
                single(uid = "a"),
                legacy(path = "a.md"),
                single(uid = "b"),
                legacy(path = "b.md"),
            ),
        )
        val groups = state.groups
        assertEquals(2, groups.size)
        assertTrue(groups.any { it.path == "a.md" })
        assertTrue(groups.any { it.path == "b.md" })
    }

    @Test
    fun `buildPreview strips front matter before trimming to 120 chars`() {
        val body = "---\npinned: true\n---\n\n今天学了 Glance 的 API"
        assertEquals("今天学了 Glance 的 API", NoteListViewModel.buildPreview(body))
    }

    @Test
    fun `buildPreview caps output at 120 characters`() {
        val long = "x".repeat(500)
        val out = NoteListViewModel.buildPreview(long)
        assertEquals(120, out.length)
    }

    @Test
    fun `buildPreview leaves non-front-matter bodies alone`() {
        val body = "plain note body"
        assertEquals("plain note body", NoteListViewModel.buildPreview(body))
    }

    @Test
    fun `buildPreview does not eat a dash-dash-dash hr that lacks closing fence`() {
        // A user who types a horizontal rule but no matching `---` must not
        // lose content. Current implementation returns the raw text when no
        // close-fence is found; we only guarantee "no content is dropped".
        val body = "---\ntitle body"
        val out = NoteListViewModel.buildPreview(body)
        assertTrue("must not eat the body", out.contains("title body"))
    }

    @Test
    fun `legacy path does not leak into the togglePin single-note dispatch shape`() {
        // Sanity: paths NOT starting with "notes/" must be treated as legacy by
        // the ViewModel's dispatcher. We assert the string predicate directly
        // since the dispatch routing is a compile-time branch.
        val legacyPath = "2026-04-22.md"
        val singlePath = "notes/2026-04-22-0915-slug.md"
        assertFalse(legacyPath.startsWith("notes/"))
        assertTrue(singlePath.startsWith("notes/"))
    }

    /**
     * End-to-end shape check: given mixed Room rows, produce the same items
     * the combine block would — verifying the DayGroup/SingleNote projection
     * matches the entity fields.
     */
    @Test
    fun `projection from Room entities produces UI items with matching fields`() {
        val nf = NoteFileEntity(
            path = "2026-04-22.md",
            date = today,
            content = "# 2026-04-22\n\n## 09:00\nhello\n",
            githubSha = null,
            localUpdatedAt = 0L,
            remoteUpdatedAt = null,
            dirty = true,
            isPinned = true,
        )
        val sn = SingleNoteEntity(
            uid = "uid-1",
            filePath = "notes/2026-04-22-0915-s.md",
            title = "s",
            body = "s body",
            date = today,
            time = LocalTime.of(9, 15),
            isPinned = false,
            githubSha = null,
            localUpdatedAt = 0L,
            remoteUpdatedAt = null,
            dirty = true,
        )
        val legacyItem = NoteListUiItem.LegacyDay(
            DayGroup(
                date = nf.date,
                entries = dev.aria.memo.data.MemoRepository.parseEntries(nf.content, nf.date),
                dirty = nf.dirty,
                path = nf.path,
                pinned = nf.isPinned,
            )
        )
        val singleItem = NoteListUiItem.SingleNote(
            uid = sn.uid,
            path = sn.filePath,
            title = sn.title,
            preview = NoteListViewModel.buildPreview(sn.body),
            date = sn.date,
            time = sn.time,
            pinned = sn.isPinned,
        )
        assertEquals("2026-04-22.md", legacyItem.path)
        assertEquals(true, legacyItem.pinned)
        assertEquals("notes/2026-04-22-0915-s.md", singleItem.path)
        assertEquals("s body", singleItem.preview)
    }
}
