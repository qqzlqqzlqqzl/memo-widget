package dev.aria.memo.widget

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.components.Scaffold
import androidx.glance.appwidget.components.SquareIconButton
import androidx.glance.appwidget.components.TitleBar
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.layout.wrapContentHeight
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import dev.aria.memo.EditActivity
import dev.aria.memo.MainActivity
import dev.aria.memo.data.MemoEntry
import java.time.format.DateTimeFormatter

/**
 * Glance-composable body of the Memo widget.
 *
 * Layout (fits the canonical 2x2 cell — roughly 180dp square):
 *  ┌─────────────────────────┐
 *  │  Memo        [  + New ] │   ← title row + add button (launches Edit)
 *  ├─────────────────────────┤
 *  │  14:30  今天学了…       │   ← recent entry 1 (tap → Edit)
 *  │  15:12  买菜 / 跑步     │
 *  │  18:05  凉面            │
 *  └─────────────────────────┘
 *
 * State branches (AGENT_SPEC.md §4.5):
 *  1. !isConfigured  → prompt to open the app and configure PAT (launches MainActivity).
 *  2. entries empty  → "今天还没写，点 + 开始" (tapping + still launches Edit).
 *  3. entries shown  → up to 3 rows; each row launches EditActivity on tap.
 *
 * Theming: [GlanceTheme] auto-mirrors the app's Material 3 theme and follows
 * system dark-mode on API 31+. No PAT or secret is ever rendered here
 * (AGENT_SPEC.md §7 rule 5).
 */
@Composable
fun MemoWidgetContent(
    entries: List<MemoEntry>,
    isConfigured: Boolean,
    modifier: GlanceModifier = GlanceModifier,
) {
    GlanceTheme {
        Scaffold(
            backgroundColor = GlanceTheme.colors.background,
            titleBar = { MemoTitleBar(isConfigured = isConfigured) },
            modifier = modifier,
        ) {
            when {
                !isConfigured -> UnconfiguredBody()
                entries.isEmpty() -> EmptyBody()
                else -> EntriesBody(entries)
            }
        }
    }
}

/**
 * Top title bar: "Memo" label with a "+ New" action button on the trailing edge.
 *
 * When the app is unconfigured we re-route the add button to [MainActivity] so
 * the user can't silently start composing against a misconfigured backend.
 */
@Composable
private fun MemoTitleBar(isConfigured: Boolean) {
    val context = LocalContext.current
    val targetActivity = if (isConfigured) EditActivity::class.java else MainActivity::class.java
    TitleBar(
        startIcon = ImageProvider(android.R.drawable.ic_menu_edit),
        title = "Memo",
        actions = {
            SquareIconButton(
                imageProvider = ImageProvider(android.R.drawable.ic_input_add),
                contentDescription = "New memo",
                onClick = actionStartActivity(Intent(context, targetActivity)),
            )
        },
    )
}

/**
 * Unconfigured state: invite the user to open the main app and add a PAT.
 * The whole area is clickable → launches [MainActivity].
 */
@Composable
private fun UnconfiguredBody() {
    val context = LocalContext.current
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .padding(12.dp)
            .clickable(actionStartActivity(Intent(context, MainActivity::class.java))),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "先打开 app 配置 GitHub PAT",
            style = TextStyle(
                color = GlanceTheme.colors.onBackground,
                fontWeight = FontWeight.Medium,
            ),
        )
        Spacer(modifier = GlanceModifier.height(8.dp))
        Text(
            text = "点击打开设置",
            style = TextStyle(
                color = GlanceTheme.colors.primary,
                fontWeight = FontWeight.Medium,
            ),
        )
    }
}

/**
 * Empty state: configured, but today's file has no entries yet.
 * Tapping anywhere jumps into [EditActivity].
 */
@Composable
private fun EmptyBody() {
    val context = LocalContext.current
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .padding(12.dp)
            .clickable(actionStartActivity(Intent(context, EditActivity::class.java))),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "今天还没写，点 + 开始",
            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant),
        )
    }
}

/**
 * Renders the recent entries list. [LazyColumn] keeps the widget well-behaved
 * on resize even though the canonical 2x2 layout shows only 3 rows.
 */
@Composable
private fun EntriesBody(entries: List<MemoEntry>) {
    LazyColumn(
        modifier = GlanceModifier
            .fillMaxSize()
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        items(
            items = entries,
            itemId = { entry -> entry.time.toSecondOfDay().toLong() },
        ) { entry ->
            EntryRow(entry)
        }
    }
}

private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** Single entry row: `HH:mm  body-preview`. Tapping opens [EditActivity]. */
@Composable
private fun EntryRow(entry: MemoEntry) {
    val context = LocalContext.current
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .wrapContentHeight()
            .padding(horizontal = 6.dp, vertical = 6.dp)
            .cornerRadius(8.dp)
            .clickable(actionStartActivity(Intent(context, EditActivity::class.java))),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = entry.time.format(TIME_FMT),
            style = TextStyle(
                color = GlanceTheme.colors.primary,
                fontWeight = FontWeight.Bold,
            ),
            modifier = GlanceModifier.width(48.dp),
        )
        Spacer(modifier = GlanceModifier.width(6.dp))
        Text(
            text = entry.body.firstLinePreview(),
            style = TextStyle(color = GlanceTheme.colors.onBackground),
            maxLines = 1,
            modifier = GlanceModifier.fillMaxWidth(),
        )
    }
}

/**
 * Build a compact, single-line preview of a memo body.
 *
 * Normalizes common markdown list markers ("- ", "* ") and folds up to the
 * first three non-empty lines, separated by " / ". Designed for glance-sized
 * text where body content rarely fits full lines anyway.
 *
 * Examples:
 *  - `"买菜\n跑步 30min"`          → `"买菜 / 跑步 30min"`
 *  - `"- 买菜\n- 跑步 30min"`      → `"买菜 / 跑步 30min"`
 *  - `"今天学了 Glance 的 API"`    → `"今天学了 Glance 的 API"`
 */
private fun String.firstLinePreview(): String {
    val pieces = this.lineSequence()
        .map { line -> line.trim().removePrefix("- ").removePrefix("* ").trim() }
        .filter { it.isNotEmpty() }
        .toList()
    return when (pieces.size) {
        0 -> ""
        1 -> pieces[0]
        else -> pieces.take(3).joinToString(separator = " / ")
    }
}
