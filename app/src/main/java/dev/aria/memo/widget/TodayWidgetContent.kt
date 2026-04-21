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
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import dev.aria.memo.EditActivity
import dev.aria.memo.MainActivity
import dev.aria.memo.data.MemoEntry
import dev.aria.memo.data.local.EventEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private sealed interface TodayRow {
    data class EventRow(val event: EventEntity) : TodayRow
    data class MemoRow(val memo: MemoEntry) : TodayRow
}

/**
 * Renders a 4xN stack:
 *  ┌─────────────────────────────────────┐
 *  │ Today 2026-04-21                [+] │
 *  ├─────────────────────────────────────┤
 *  │ ● 09:00–10:00   晨会                │
 *  │ ● 14:30          今天学了 Glance 的…│
 *  │ ● 15:12          买菜 / 跑步 30min  │
 *  └─────────────────────────────────────┘
 */
@Composable
fun TodayWidgetContent(
    isConfigured: Boolean,
    date: LocalDate,
    events: List<EventEntity>,
    memos: List<MemoEntry>,
    modifier: GlanceModifier = GlanceModifier,
) {
    GlanceTheme {
        Scaffold(
            backgroundColor = GlanceTheme.colors.background,
            titleBar = { TodayTitleBar(date = date, isConfigured = isConfigured) },
            modifier = modifier,
        ) {
            when {
                !isConfigured -> UnconfiguredState()
                events.isEmpty() && memos.isEmpty() -> EmptyToday()
                else -> TodayList(events, memos)
            }
        }
    }
}

@Composable
private fun TodayTitleBar(date: LocalDate, isConfigured: Boolean) {
    val context = LocalContext.current
    TitleBar(
        startIcon = ImageProvider(android.R.drawable.ic_menu_my_calendar),
        title = "今日 ${date.format(DateTimeFormatter.ofPattern("M/d"))}",
        actions = {
            SquareIconButton(
                imageProvider = ImageProvider(android.R.drawable.ic_input_add),
                contentDescription = "新建备忘",
                onClick = actionStartActivity(
                    Intent(context, if (isConfigured) EditActivity::class.java else MainActivity::class.java)
                ),
            )
        },
    )
}

@Composable
private fun UnconfiguredState() {
    val context = LocalContext.current
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .padding(12.dp)
            .clickable(actionStartActivity(Intent(context, MainActivity::class.java))),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "先去设置配置 GitHub",
            style = TextStyle(color = GlanceTheme.colors.onBackground, fontWeight = FontWeight.Medium),
        )
    }
}

@Composable
private fun EmptyToday() {
    val context = LocalContext.current
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .padding(12.dp)
            .clickable(actionStartActivity(Intent(context, MainActivity::class.java))),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "今天没有日程和备忘，点 + 开始",
            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant),
        )
    }
}

@Composable
private fun TodayList(events: List<EventEntity>, memos: List<MemoEntry>) {
    val rows = buildList<TodayRow> {
        events.forEach { add(TodayRow.EventRow(it)) }
        memos.forEach { add(TodayRow.MemoRow(it)) }
    }
    LazyColumn(
        modifier = GlanceModifier
            .fillMaxSize()
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        items(
            items = rows,
            itemId = { row ->
                when (row) {
                    is TodayRow.EventRow -> row.event.uid.hashCode().toLong()
                    is TodayRow.MemoRow -> row.memo.time.toSecondOfDay().toLong() + 1_000_000L
                }
            },
        ) { row ->
            when (row) {
                is TodayRow.EventRow -> EventLine(row.event)
                is TodayRow.MemoRow -> MemoLine(row.memo)
            }
        }
    }
}

private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

@Composable
private fun EventLine(event: EventEntity) {
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    val start = Instant.ofEpochMilli(event.startEpochMs).atZone(zone)
    val end = Instant.ofEpochMilli(event.endEpochMs).atZone(zone)
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 6.dp)
            .cornerRadius(8.dp)
            .clickable(actionStartActivity(Intent(context, MainActivity::class.java))),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${start.format(TIME_FMT)}–${end.format(TIME_FMT)}",
            style = TextStyle(color = GlanceTheme.colors.primary, fontWeight = FontWeight.Bold),
            modifier = GlanceModifier.width(92.dp),
        )
        Spacer(modifier = GlanceModifier.width(6.dp))
        Text(
            text = event.summary,
            style = TextStyle(color = GlanceTheme.colors.onBackground),
            maxLines = 1,
            modifier = GlanceModifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun MemoLine(memo: MemoEntry) {
    val context = LocalContext.current
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 6.dp)
            .cornerRadius(8.dp)
            .clickable(actionStartActivity(Intent(context, EditActivity::class.java))),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = memo.time.format(TIME_FMT),
            style = TextStyle(color = GlanceTheme.colors.secondary, fontWeight = FontWeight.Bold),
            modifier = GlanceModifier.width(52.dp),
        )
        Spacer(modifier = GlanceModifier.width(6.dp))
        Text(
            text = memo.body.firstLinePreview(),
            style = TextStyle(color = GlanceTheme.colors.onBackground),
            maxLines = 1,
            modifier = GlanceModifier.fillMaxWidth(),
        )
    }
}

private fun String.firstLinePreview(): String {
    val pieces = this.lineSequence()
        .map { l -> l.trim().removePrefix("- ").removePrefix("* ").trim() }
        .filter { it.isNotEmpty() }
        .toList()
    return when (pieces.size) {
        0 -> ""
        1 -> pieces[0]
        else -> pieces.take(3).joinToString(separator = " / ")
    }
}
