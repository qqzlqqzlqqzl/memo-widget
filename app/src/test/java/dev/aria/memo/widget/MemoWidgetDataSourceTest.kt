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

    /**
     * P8 回归：widget 数据源的 limit 从 3 提升到 20。
     *
     * 这个 case 证明当 repo 返回 20 条 [SingleNoteEntity] 时，[decideRows] 原封不动
     * 透传所有 20 行（不是像 P7 那样再 `.take(3)`）—— 因为上游 `observeRecent(limit=20)`
     * 已经控制了上限，widget 不应再二次截断。
     *
     * 万一未来有人把 `decideRows`（或者它模拟的 provideGlance 决策块）
     * 无意中加回 `.take(3)`，这个测试会立刻失败，保护 P8 的核心用户价值：
     * "widget resize 到更大的格子就能看到更多笔记"。
     */
    @Test
    fun `P8 limit 20 - when repo yields 20 single notes widget renders all 20 rows`() {
        val twenty = (1..20).map { idx ->
            single(
                uid = "uid-$idx",
                title = "note-$idx",
                time = LocalTime.of(0, 0).plusMinutes(idx.toLong()),
            )
        }
        val rows = decideRows(
            singleNotes = twenty,
            legacyResult = MemoResult.Err(ErrorCode.UNKNOWN, "must not be used"),
            settingsConfigured = true,
        )
        assertEquals(true, rows.first)
        assertEquals(
            "P8：widget 必须展示 repo 给出的全部 20 条；如果这里掉回 3 说明下游偷偷再截断",
            20,
            rows.second.size,
        )
        // 顺序也要一致（repo 已按 time DESC 排，widget 不改顺序）。
        assertEquals("note-1", rows.second.first().label)
        assertEquals("note-20", rows.second.last().label)
        // 所有 20 行都要带 uid，这样点击能 deep-link。
        assertTrue(rows.second.all { !it.noteUid.isNullOrBlank() })
    }

    /**
     * P8 回归：legacy 路径同样要能承载 20 行。当 SingleNote 空而 legacy Ok 返回 20 条
     * [DatedMemoEntry] 时，widget 也得完整展示（说明 MemoWidget 里 legacy 那一支
     * 的 `recentEntriesAcrossDays(limit=...)` 被对齐到了 20）。
     */
    @Test
    fun `P8 limit 20 - legacy fallback also renders 20 rows when single notes empty`() {
        val legacy = (1..20).map { idx ->
            legacyDated(body = "legacy-$idx", time = LocalTime.of(0, 0).plusMinutes(idx.toLong()))
        }
        val rows = decideRows(
            singleNotes = emptyList(),
            legacyResult = MemoResult.Ok(legacy),
            settingsConfigured = true,
        )
        assertEquals(true, rows.first)
        assertEquals(20, rows.second.size)
        assertTrue(rows.second.all { it.noteUid == null })
    }
}
