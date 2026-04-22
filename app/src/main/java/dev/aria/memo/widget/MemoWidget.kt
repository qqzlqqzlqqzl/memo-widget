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

/**
 * Glance app-widget entry point for the Memo widget.
 *
 * Contract (AGENT_SPEC.md §4.5):
 *  - Pulls the most recent 3 entries from [dev.aria.memo.data.MemoRepository]
 *    *across every cached day-file*, newest first. Previously this widget
 *    only read today's file, so if the user didn't write today the widget
 *    was blank even when yesterday's file had fresh content.
 *  - "+ New" and entry-row taps launch [dev.aria.memo.EditActivity]
 *    (started via [androidx.glance.action.actionStartActivity] from the
 *    Composable in [MemoWidgetContent] — Agent C owns those Activities).
 *
 * Error handling:
 *  - [ErrorCode.NOT_CONFIGURED] is reflected via [isConfigured]=false so the
 *    UI prompts the user to open the app. Any other error degrades to an
 *    empty entry list; the widget never throws (repo uses [MemoResult]).
 *
 * Freshness:
 *  - Re-reading `recentEntriesAcrossDays()` runs on every Glance update.
 *    Widget refresh is triggered by [MemoWidgetReceiver] (system update
 *    period / explicit `MemoWidget().update(context, id)` calls from Agent C
 *    after save).
 */
class MemoWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Single

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Fetch data OUTSIDE provideContent so the render is a pure function of
        // a snapshot — Glance rebuilds the Composable on every update.
        ServiceLocator.init(context)
        val repository = ServiceLocator.get()

        val result = repository.recentEntriesAcrossDays(limit = 3)
        val isConfigured: Boolean
        val entries: List<DatedMemoEntry>
        when (result) {
            is MemoResult.Ok -> {
                isConfigured = true
                entries = result.value
            }
            is MemoResult.Err -> {
                isConfigured = result.code != ErrorCode.NOT_CONFIGURED
                entries = emptyList()
            }
        }

        provideContent {
            MemoWidgetContent(
                entries = entries,
                isConfigured = isConfigured,
                modifier = GlanceModifier,
            )
        }
    }
}
