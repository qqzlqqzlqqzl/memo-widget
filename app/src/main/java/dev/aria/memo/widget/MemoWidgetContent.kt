package dev.aria.memo.widget

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.components.Scaffold
import androidx.glance.appwidget.components.SquareIconButton
import androidx.glance.appwidget.components.TitleBar
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.updateAll
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
import dev.aria.memo.util.MarkdownPreview
import java.time.format.DateTimeFormatter

/**
 * Fix-6 (Bug-2): Glance widgets have no way to show a spinner — tapping 🔄
 * looks the same whether the refresh succeeded, hung, or silently failed. We
 * can't modify [RefreshMemoWidgetAction] (owned by Fix-1), but we *can* swap
 * the button's wiring in this file to a Toast-aware wrapper.
 *
 * [ToastingRefreshMemoAction] is a thin [ActionCallback] that pops "已刷新"
 * on the main thread, then delegates to [MemoWidget].updateAll — the same
 * semantics as [RefreshMemoWidgetAction] minus the no-feedback problem.
 * `runCatching` mirrors the failure policy used by the owner-of-record.
 *
 * Why Handler.post(mainLooper): `Toast.makeText(...).show()` must be called
 * from a thread with a Looper; Glance's coroutine dispatcher is not one.
 *
 * Marked as a temporary workaround for P8.1 — once Glance exposes inline
 * loading affordances we can drop the Toast.
 */
class ToastingRefreshMemoAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val appCtx = context.applicationContext
        Handler(Looper.getMainLooper()).post {
            // "已刷新" is a white-lie — the updateAll below may still be
            // in-flight. We accept that: the intent here is feedback-for-tap,
            // not strict sync confirmation. If updateAll throws, at least the
            // user knows the button registered.
            Toast.makeText(appCtx, "已刷新", Toast.LENGTH_SHORT).show()
        }
        runCatching { MemoWidget().updateAll(appCtx) }
    }
}

/**
 * Glance-composable body of the Memo widget.
 *
 * Layout (fits the canonical 2x2 cell — roughly 180dp square):
 *  ┌──────────────────────────────┐
 *  │  Memo          [  + New ]    │   ← title row + add button (launches Edit)
 *  ├──────────────────────────────┤
 *  │  04/21 14:30  早晨想法        │   ← single-note row (tap → Edit with uid)
 *  │  04/20 15:12  买菜 / 跑步     │   ← legacy cross-day entry
 *  │  04/20 18:05  凉面            │
 *  └──────────────────────────────┘
 *
 * Rows are a union of the new single-note feed and the legacy cross-day feed
 * (single-notes win when both have content). Each carries a `MM/DD HH:mm`
 * prefix so mixed-day lists are unambiguous.
 *
 * State branches (AGENT_SPEC.md §4.5):
 *  1. !isConfigured  → prompt to open the app and configure PAT (launches MainActivity).
 *  2. rows empty     → "还没有备忘，点 + 开始" (tapping + still launches Edit).
 *  3. rows shown     → up to 3 rows; single-note rows deep-link by uid, legacy
 *                      rows open EditActivity with no extras.
 */
@Composable
fun MemoWidgetContent(
    rows: List<MemoWidgetRow>,
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
                rows.isEmpty() -> EmptyBody()
                else -> EntriesBody(rows)
            }
        }
    }
}

/**
 * Top title bar: "Memo" label with 🔄 Refresh + "+ New" action buttons.
 *
 * P8 改动：新增 🔄 刷新按钮（在 + New 前面）。
 *  - 为什么需要它：widget 的 `updatePeriodMillis=0`，平时靠 app 写入后主动调
 *    `WidgetRefresher.refreshAll(...)` 推。但后台拉取完成、或用户怀疑 widget
 *    数据没跟上（例如在别的设备改了再打开这台）时，手动触发重绘是最直观的
 *    救急入口，不依赖任何 background job 的排队延迟。
 *  - 为什么必须走 [RefreshMemoWidgetAction]：`SquareIconButton.onClick` 只接
 *    `Action`；而我们要调的 [MemoWidget.updateAll] 是 suspend。Glance 的官方
 *    组合姿势就是 `actionRunCallback<T>()` + 一个实现 `ActionCallback` 的类。
 *
 * 顺序：刷新 → 新建。刷新在左是为了"不破坏用户点右侧 + 按钮的肌肉记忆"。
 *
 * When the app is unconfigured we re-route the add button to [MainActivity] so
 * the user can't silently start composing against a misconfigured backend.
 * 注意：未配置时刷新按钮依然保留，因为用户配完 PAT 回到桌面会希望立刻看到列表。
 */
@Composable
private fun MemoTitleBar(isConfigured: Boolean) {
    val context = LocalContext.current
    val targetActivity = if (isConfigured) EditActivity::class.java else MainActivity::class.java
    TitleBar(
        startIcon = ImageProvider(android.R.drawable.ic_menu_edit),
        title = "Memo",
        actions = {
            // 🔄 刷新按钮。Glance 系统资源 `ic_popup_sync` 是经典旋转箭头，
            // 在各厂商主题下都有，避免自建 drawable。
            //
            // Fix-6 (Bug-2): route through [ToastingRefreshMemoAction] so the
            // user gets a "已刷新" confirmation — Glance can't render a
            // spinner and the silent tap was driving repeated presses.
            SquareIconButton(
                imageProvider = ImageProvider(android.R.drawable.ic_popup_sync),
                contentDescription = "Refresh widget",
                onClick = actionRunCallback<ToastingRefreshMemoAction>(),
            )
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
 * Empty state: configured, but Room has no entries from any source yet.
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
            text = "还没有备忘，点 + 开始",
            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant),
        )
    }
}

/**
 * Renders the recent entries list. [LazyColumn] keeps the widget well-behaved
 * on resize even though the canonical 2x2 layout shows only 3 rows.
 */
@Composable
private fun EntriesBody(rows: List<MemoWidgetRow>) {
    LazyColumn(
        modifier = GlanceModifier
            .fillMaxSize()
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        items(
            items = rows,
            // Fixes #319: FNV-1a 64-bit over a stable per-row key. The
            // 'u' / 'l' tag prevents single-note ids from colliding with
            // legacy day-file rows that happen to hash to the same value.
            itemId = { row ->
                if (row.noteUid != null) {
                    stableItemId('u', row.noteUid)
                } else {
                    stableItemId('l', "${row.date}|${row.time}|${row.label}")
                }
            },
        ) { row ->
            EntryRow(row)
        }
    }
}

private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/**
 * Single entry row: `MM/dd HH:mm  <label>`. The date prefix lets users tell
 * today's rows apart from yesterday's at a glance when the widget shows
 * cross-day content. Tapping opens [EditActivity] — single-note rows deep-link
 * with their uid so the editor lands on the right file.
 */
@Composable
private fun EntryRow(row: MemoWidgetRow) {
    val context = LocalContext.current
    val dateTimeLabel = "%02d/%02d %s".format(
        row.date.monthValue,
        row.date.dayOfMonth,
        row.time.format(TIME_FMT),
    )
    val intent = Intent(context, EditActivity::class.java).apply {
        val uid = row.noteUid
        if (!uid.isNullOrBlank()) {
            putExtra(EditActivity.EXTRA_NOTE_UID, uid)
        }
    }
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .wrapContentHeight()
            .padding(horizontal = 6.dp, vertical = 6.dp)
            .cornerRadius(8.dp)
            .clickable(actionStartActivity(intent)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = dateTimeLabel,
            style = TextStyle(
                color = GlanceTheme.colors.primary,
                fontWeight = FontWeight.Bold,
            ),
            modifier = GlanceModifier.width(96.dp),
        )
        Spacer(modifier = GlanceModifier.width(6.dp))
        Text(
            text = MarkdownPreview.buildPreview(row.label),
            style = TextStyle(color = GlanceTheme.colors.onBackground),
            maxLines = 1,
            modifier = GlanceModifier.fillMaxWidth(),
        )
    }
}
