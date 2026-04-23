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
import kotlinx.coroutines.flow.first

/**
 * Glance app-widget entry point for the Memo widget.
 *
 * P6.1 contract (widget-driven):
 *  - Primary feed: [dev.aria.memo.data.SingleNoteRepository.observeRecent], the
 *    newest 3 Obsidian-style single-note files. Each row can deep-link into
 *    [dev.aria.memo.EditActivity] with the note's uid.
 *  - Fallback: when the single-note table is empty we reuse the legacy cross-
 *    day merge from [dev.aria.memo.data.MemoRepository.recentEntriesAcrossDays]
 *    so existing users keep seeing content while their history is still in the
 *    day-file format.
 *  - When BOTH sources are empty we render the "empty + add" body.
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
        val singleNotes: List<SingleNoteEntity> =
            if (!settings.isConfigured) emptyList()
            else singleNoteRepo.observeRecent(limit = 3).first()

        val rows: List<MemoWidgetRow>
        val isConfigured: Boolean

        if (singleNotes.isNotEmpty()) {
            isConfigured = true
            rows = singleNotes.map { it.toRow() }
        } else {
            // Fallback to the legacy cross-day feed.
            val legacy = repository.recentEntriesAcrossDays(limit = 3)
            when (legacy) {
                is MemoResult.Ok -> {
                    isConfigured = true
                    rows = legacy.value.map { it.toRow() }
                }
                is MemoResult.Err -> {
                    isConfigured = legacy.code != ErrorCode.NOT_CONFIGURED
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
    label = title.ifBlank { body.firstNonEmptyLineStripped() },
    noteUid = uid,
)

internal fun DatedMemoEntry.toRow(): MemoWidgetRow = MemoWidgetRow(
    date = date,
    time = time,
    label = body,
    noteUid = null,
)

/**
 * Return the first non-empty line of [this] with leading markdown markers
 * removed so widget preview matches what the user "reads" as a title.
 */
private fun String.firstNonEmptyLineStripped(): String {
    val first = this.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotEmpty() }
        ?: return ""
    var t = first
    t = t.replace(Regex("^#+\\s*"), "")
    t = t.replace(Regex("^[>\\-*+]\\s*"), "")
    return t.trim()
}
