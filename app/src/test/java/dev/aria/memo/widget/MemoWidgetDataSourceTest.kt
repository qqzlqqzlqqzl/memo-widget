package dev.aria.memo.widget

import dev.aria.memo.data.DatedMemoEntry
import dev.aria.memo.data.ErrorCode
import dev.aria.memo.data.MemoResult
import dev.aria.memo.data.local.SingleNoteEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * Pure JVM tests for the P6.1 widget data source union.
 *
 * The widget first reads [dev.aria.memo.data.SingleNoteRepository.observeRecent]
 * and, only when that comes back empty, falls back to the legacy cross-day
 * feed [dev.aria.memo.data.MemoRepository.recentEntriesAcrossDays]. We exercise
 * the deterministic translator functions ([SingleNoteEntity.toRow] /
 * [DatedMemoEntry.toRow]) and simulate the decision block from
 * [MemoWidget.provideGlance] with a small stand-in so Robolectric / Room can
 * stay out of the test path.
 */
class MemoWidgetDataSourceTest {

    private val today: LocalDate = LocalDate.of(2026, 4, 22)

    private fun single(
        uid: String,
        title: String = "title-$uid",
        body: String = "body-$uid",
        date: LocalDate = today,
        time: LocalTime = LocalTime.of(9, 15),
    ) = SingleNoteEntity(
        uid = uid,
        filePath = "notes/$uid.md",
        title = title,
        body = body,
        date = date,
        time = time,
        isPinned = false,
        githubSha = null,
        localUpdatedAt = 0L,
        remoteUpdatedAt = null,
        dirty = false,
    )

    private fun legacyDated(body: String, date: LocalDate = today, time: LocalTime = LocalTime.of(18, 0)) =
        DatedMemoEntry(date = date, time = time, body = body)

    /**
     * Pure version of the decision block in [MemoWidget.provideGlance]. Kept
     * here so the test asserts the actual "single wins, legacy falls back,
     * empty surfaces empty" behaviour the production code implements, without
     * needing to spin up a live Room/Glance environment.
     */
    private fun decideRows(
        singleNotes: List<SingleNoteEntity>,
        legacyResult: MemoResult<List<DatedMemoEntry>>,
        settingsConfigured: Boolean,
    ): Pair<Boolean /* isConfigured */, List<MemoWidgetRow>> {
        val effectiveSingle = if (settingsConfigured) singleNotes else emptyList()
        if (effectiveSingle.isNotEmpty()) {
            return true to effectiveSingle.map { it.toRow() }
        }
        return when (legacyResult) {
            is MemoResult.Ok -> true to legacyResult.value.map { it.toRow() }
            is MemoResult.Err ->
                (legacyResult.code != ErrorCode.NOT_CONFIGURED) to emptyList()
        }
    }

    @Test
    fun `single notes present - widget renders single rows and does not hit legacy`() {
        val rows = decideRows(
            singleNotes = listOf(single(uid = "a", title = "alpha")),
            legacyResult = MemoResult.Err(ErrorCode.UNKNOWN, "must not be used"),
            settingsConfigured = true,
        )
        assertEquals(true, rows.first)
        assertEquals(1, rows.second.size)
        val row = rows.second[0]
        assertEquals("alpha", row.label)
        assertNotNull("single rows must carry the uid for deep-linking", row.noteUid)
        assertEquals("a", row.noteUid)
    }

    @Test
    fun `no single notes - widget falls back to the legacy cross-day feed`() {
        val rows = decideRows(
            singleNotes = emptyList(),
            legacyResult = MemoResult.Ok(
                listOf(
                    legacyDated("买菜 / 跑步", date = today, time = LocalTime.of(18, 0)),
                    legacyDated("凉面", date = today.minusDays(1), time = LocalTime.of(20, 0)),
                )
            ),
            settingsConfigured = true,
        )
        assertEquals(true, rows.first)
        assertEquals(2, rows.second.size)
        // Legacy rows must NOT carry a uid (null → EditActivity opens blank).
        assertTrue(rows.second.all { it.noteUid == null })
        assertEquals("买菜 / 跑步", rows.second[0].label)
    }

    @Test
    fun `both empty - widget surfaces the empty body`() {
        val rows = decideRows(
            singleNotes = emptyList(),
            legacyResult = MemoResult.Ok(emptyList()),
            settingsConfigured = true,
        )
        assertEquals(true, rows.first)
        assertTrue("empty rows → empty body renders", rows.second.isEmpty())
    }

    @Test
    fun `legacy result is NOT_CONFIGURED - widget marks isConfigured false`() {
        val rows = decideRows(
            singleNotes = emptyList(),
            legacyResult = MemoResult.Err(ErrorCode.NOT_CONFIGURED, "no PAT"),
            settingsConfigured = false,
        )
        assertEquals(false, rows.first)
        assertTrue(rows.second.isEmpty())
    }

    @Test
    fun `legacy result is a transient error - widget still claims isConfigured true`() {
        val rows = decideRows(
            singleNotes = emptyList(),
            legacyResult = MemoResult.Err(ErrorCode.NETWORK, "offline"),
            settingsConfigured = true,
        )
        assertEquals(true, rows.first)
        assertTrue(rows.second.isEmpty())
    }

    @Test
    fun `single note with blank title uses stripped first body line as label`() {
        val row = single(uid = "b", title = "", body = "# 早晨想法\n\n正文").toRow()
        assertEquals("早晨想法", row.label)
        assertEquals("b", row.noteUid)
    }

    @Test
    fun `single note with blank title and empty body produces empty label`() {
        val row = single(uid = "c", title = "", body = "   \n\n").toRow()
        assertEquals("", row.label)
    }

    @Test
    fun `single note with title preserves that title verbatim`() {
        val row = single(uid = "d", title = "会议记录", body = "body").toRow()
        assertEquals("会议记录", row.label)
    }

    @Test
    fun `DatedMemoEntry toRow preserves date time and body`() {
        val e = DatedMemoEntry(
            date = LocalDate.of(2026, 4, 20),
            time = LocalTime.of(15, 12),
            body = "买菜",
        )
        val row = e.toRow()
        assertEquals(LocalDate.of(2026, 4, 20), row.date)
        assertEquals(LocalTime.of(15, 12), row.time)
        assertEquals("买菜", row.label)
        assertNull(row.noteUid)
    }

    @Test
    fun `not-configured settings masks any single notes present`() {
        // If the app is mid-configuration (PAT cleared but Room still has
        // cached content from a previous session), the widget must report
        // isConfigured=true via the legacy path OR unconfigured when the
        // legacy call itself returns NOT_CONFIGURED — but should NOT
        // accidentally render stale single-notes against the wrong repo.
        val rows = decideRows(
            singleNotes = listOf(single(uid = "leak", title = "stale")),
            legacyResult = MemoResult.Err(ErrorCode.NOT_CONFIGURED, "no PAT"),
            settingsConfigured = false,
        )
        assertEquals(false, rows.first)
        assertTrue(rows.second.isEmpty())
    }
}
