package dev.aria.memo.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import dev.aria.memo.data.DatedMemoEntry
import dev.aria.memo.data.ErrorCode
import dev.aria.memo.data.MemoResult
import dev.aria.memo.data.ServiceLocator
import dev.aria.memo.data.local.SingleNoteEntity
import dev.aria.memo.util.MarkdownPreview
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Glance app-widget entry point for the Memo widget.
 *
 * P6.1 contract (widget-driven):
 *  - Primary feed: [dev.aria.memo.data.SingleNoteRepository.observeRecent], the
 *    newest 20 Obsidian-style single-note files. Each row can deep-link into
 *    [dev.aria.memo.EditActivity] with the note's uid.
 *  - Fallback: when the single-note table is empty we reuse the legacy cross-
 *    day merge from [dev.aria.memo.data.MemoRepository.recentEntriesAcrossDays]
 *    so existing users keep seeing content while their history is still in the
 *    day-file format.
 *  - When BOTH sources are empty we render the "empty + add" body.
 *
 * P8 更新：
 *  - limit 从 3 提升到 20。UI 侧 LazyColumn 天然可滚动，用户把 widget resize 到
 *    更大的格子（3x3 / 4x4）就能看到更多条，2x2 时仍然看前几条。
 *  - 这是"源码常量"而不是 runtime config —— widget 里没地方开偏好设置，
 *    20 条也足够覆盖"最近一周的笔记"这种常见用例。
 *
 * Error handling:
 *  - [ErrorCode.NOT_CONFIGURED] is reflected via `isConfigured=false` so the
 *    UI prompts the user to open the app. Any other error degrades to an empty
 *    entry list; the widget never throws (repos use [MemoResult]).
 *
 * Fixes #59 (P6.1): ⚠️ **Test / prod parity warning** — `MemoWidgetDataSourceTest`
 * exercises a `decideRows(...)` stand-in that mirrors the single-note-vs-legacy
 * branching below. If you change the selection logic here (priority, limits,
 * empty-state decision) you MUST update `decideRows` in that test file in
 * lock-step — the tests can go green while production drifts silently.
 */
class MemoWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Single

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Fetch data OUTSIDE provideContent so the render is a pure function of
        // a snapshot — Glance rebuilds the Composable on every update.
        ServiceLocator.init(context)
        val repository = ServiceLocator.get()
        val singleNoteRepo = ServiceLocator.singleNoteRepo
        val settings = ServiceLocator.settingsStore.current()

        // Fast path: new single-note feed.
        // P8: limit 20（见类 KDoc）。
        //
        // Perf-fix C3: 给 Room observe + fallback 都套 withTimeoutOrNull。
        // 老用户（100+ 天笔记文件）fallback 极端路径会整表 parseEntries +
        // 正则解析，Room 冷路径 + Keystore 冷启叠起来可能贴近 AppWidget 20s
        // ANR 阈值。3s timeout 会让 widget 降级成"空态"而不是 ANR / 黑屏。
        val singleNotes: List<SingleNoteEntity> = withTimeoutOrNull(3_000) {
            if (!settings.isConfigured) emptyList()
            else singleNoteRepo.observeRecent(limit = 20).first()
        } ?: emptyList()

        val rows: List<MemoWidgetRow>
        val isConfigured: Boolean

        if (singleNotes.isNotEmpty()) {
            isConfigured = true
            rows = singleNotes.map { it.toRow() }
        } else {
            // Fallback to the legacy cross-day feed. P8: 同步提升到 20。
            val legacy = withTimeoutOrNull(3_000) {
                repository.recentEntriesAcrossDays(limit = 20)
            }
            when (legacy) {
                is MemoResult.Ok -> {
                    isConfigured = true
                    rows = legacy.value.map { it.toRow() }
                }
                is MemoResult.Err -> {
                    isConfigured = legacy.code != ErrorCode.NOT_CONFIGURED
                    rows = emptyList()
                }
                null -> {
                    // Timeout 时假设已配置（避免错误引导用户重开 app），
                    // 只渲染空态 —— 下一次 widget tick 会再试一次。
                    isConfigured = true
                    rows = emptyList()
                }
            }
        }

        provideContent {
            MemoWidgetContent(
                rows = rows,
                isConfigured = isConfigured,
                modifier = GlanceModifier,
            )
        }
    }
}

/**
 * Widget-layer row description. Unifies the two data sources ([SingleNoteEntity]
 * and [DatedMemoEntry]) into one shape the Composable can render, and carries
 * the single-note uid so a row tap can deep-link into EditActivity when present.
 */
data class MemoWidgetRow(
    val date: java.time.LocalDate,
    val time: java.time.LocalTime,
    /** Human-readable line shown after the `MM/DD HH:mm` prefix. */
    val label: String,
    /** Single-note UID if this row came from the new feed, else null. */
    val noteUid: String?,
)

internal fun SingleNoteEntity.toRow(): MemoWidgetRow = MemoWidgetRow(
    date = date,
    time = time,
    label = title.ifBlank { MarkdownPreview.firstNonEmptyLineStripped(body) },
    noteUid = uid,
)

internal fun DatedMemoEntry.toRow(): MemoWidgetRow = MemoWidgetRow(
    date = date,
    time = time,
    label = body,
    noteUid = null,
)
