package dev.aria.memo.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kizitonwose.calendar.compose.HorizontalCalendar
import com.kizitonwose.calendar.compose.rememberCalendarState
import com.kizitonwose.calendar.core.CalendarDay
import com.kizitonwose.calendar.core.DayPosition
import com.kizitonwose.calendar.core.daysOfWeek
import dev.aria.memo.data.ics.EventOccurrence
import dev.aria.memo.data.local.EventEntity
import dev.aria.memo.ui.components.MemoCard
import dev.aria.memo.ui.components.MemoEmptyState
import dev.aria.memo.ui.components.MemoSectionHeader
import dev.aria.memo.ui.components.ScrollAwareFab
import dev.aria.memo.ui.theme.MemoSpacing
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    viewModel: CalendarViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showAddDialog by remember { mutableStateOf(false) }
    var editingEvent by remember { mutableStateOf<EventEntity?>(null) }
    // Fixes #22: bump per open so EventEditDialog's rememberSaveable keys don't collide
    // across back-to-back "new event" sessions (event?.uid is null in that case).
    var dialogEpoch by remember { mutableStateOf(0) }
    val onEventClick: (EventOccurrence) -> Unit = { occ ->
        editingEvent = occ.event
        dialogEpoch += 1
    }

    val currentMonth = remember { YearMonth.now() }
    val startMonth = remember { currentMonth.minusMonths(24) }
    val endMonth = remember { currentMonth.plusMonths(24) }
    val daysOfWeek = remember { daysOfWeek(firstDayOfWeek = DayOfWeek.MONDAY) }
    val calendarState = rememberCalendarState(
        startMonth = startMonth,
        endMonth = endMonth,
        firstVisibleMonth = currentMonth,
        firstDayOfWeek = DayOfWeek.MONDAY,
    )
    val scope = rememberCoroutineScope()
    // Fixes #8: cancel a pending scroll before launching a new one so quick
    // chevron taps don't stack month-scroll animations on top of each other.
    var scrollJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    // enterAlwaysScrollBehavior lets the LargeTopAppBar collapse on scroll, so
    // the day sheet gets more vertical room as the user reads through events.
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

    // Fixes #45 (P6.1): tie ScrollAwareFab's expanded state to the top-bar
    // collapse fraction. When the user scrolls and the bar starts to collapse,
    // the FAB follows by collapsing to icon-only, giving more room to the day
    // sheet. Threshold 0.5f picks the midpoint so the transition feels paired.
    val fabExpanded by remember {
        derivedStateOf { scrollBehavior.state.collapsedFraction < 0.5f }
    }

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = {
                    Text(
                        text = calendarState.firstVisibleMonth.yearMonth
                            .format(DateTimeFormatter.ofPattern("yyyy 年 M 月")),
                    )
                },
                actions = {
                    IconButton(onClick = {
                        scrollJob?.cancel()
                        scrollJob = scope.launch {
                            calendarState.animateScrollToMonth(
                                calendarState.firstVisibleMonth.yearMonth.minusMonths(1),
                            )
                        }
                    }) { Icon(Icons.Filled.ChevronLeft, contentDescription = "上一月") }
                    IconButton(onClick = {
                        scrollJob?.cancel()
                        scrollJob = scope.launch {
                            calendarState.animateScrollToMonth(
                                calendarState.firstVisibleMonth.yearMonth.plusMonths(1),
                            )
                        }
                    }) { Icon(Icons.Filled.ChevronRight, contentDescription = "下一月") }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            ScrollAwareFab(
                expanded = fabExpanded,
                onClick = {
                    dialogEpoch += 1
                    showAddDialog = true
                },
                icon = Icons.Filled.Add,
                text = "加日程",
            )
        },
    ) { inner ->
        Column(
            Modifier
                .padding(inner)
                .fillMaxSize(),
        ) {
            WeekHeader(daysOfWeek)
            HorizontalCalendar(
                state = calendarState,
                dayContent = { day ->
                    DayCell(
                        day = day,
                        selected = day.date == state.selected,
                        marked = day.date in state.markedDates,
                        isToday = day.date == LocalDate.now(),
                        onClick = { viewModel.selectDate(day.date) },
                    )
                },
            )
            HorizontalDivider()
            DaySheet(
                date = state.selected,
                summary = state.daySummary,
                onEventClick = onEventClick,
                padding = PaddingValues(horizontal = MemoSpacing.lg),
            )
        }
    }

    if (showAddDialog) {
        EventEditDialog(
            initialDate = state.selected,
            event = null,
            onDismiss = { showAddDialog = false },
            onSave = { summary, startMs, endMs, rrule, reminder ->
                viewModel.createEvent(summary, startMs, endMs, rrule, reminder) { showAddDialog = false }
            },
            onDelete = null,
            sessionKey = dialogEpoch,
        )
    }
    editingEvent?.let { ev ->
        EventEditDialog(
            initialDate = state.selected,
            event = ev,
            onDismiss = { editingEvent = null },
            onSave = { summary, startMs, endMs, rrule, reminder ->
                viewModel.updateEvent(ev.uid, summary, startMs, endMs, rrule, reminder) { editingEvent = null }
            },
            onDelete = {
                viewModel.deleteEvent(ev.uid) { editingEvent = null }
            },
            sessionKey = dialogEpoch,
        )
    }
}

@Composable
private fun WeekHeader(daysOfWeek: List<DayOfWeek>) {
    Row(Modifier.fillMaxWidth().padding(horizontal = MemoSpacing.xs)) {
        daysOfWeek.forEach { d ->
            Text(
                text = d.getDisplayName(TextStyle.SHORT, Locale.SIMPLIFIED_CHINESE),
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Spacer(Modifier.height(MemoSpacing.xs))
}

@Composable
private fun DayCell(
    day: CalendarDay,
    selected: Boolean,
    marked: Boolean,
    isToday: Boolean,
    onClick: () -> Unit,
) {
    val inMonth = day.position == DayPosition.MonthDate
    val bgColor = when {
        selected -> MaterialTheme.colorScheme.primary
        isToday && inMonth -> MaterialTheme.colorScheme.primaryContainer
        else -> Color.Transparent
    }
    val fgColor = when {
        selected -> MaterialTheme.colorScheme.onPrimary
        isToday && inMonth -> MaterialTheme.colorScheme.onPrimaryContainer
        !inMonth -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        else -> MaterialTheme.colorScheme.onSurface
    }
    val markerTint = MaterialTheme.colorScheme.tertiary
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .padding(MemoSpacing.xs)
            // Fix-7 #8 (UI-A report): previously today/selected cells were
            // CircleShape while normal days were 12dp rounded rects, so each
            // row mixed two different shapes. Matching Google Calendar +
            // Samsung Calendar, we now render every cell as a circle —
            // keeps the calendar grid visually consistent.
            .clip(CircleShape)
            .background(bgColor)
            .clickable(enabled = inMonth) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = day.date.dayOfMonth.toString(),
                color = fgColor,
                fontWeight = if (selected || isToday) FontWeight.SemiBold else FontWeight.Normal,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (marked && inMonth) {
                Spacer(Modifier.height(2.dp))
                Box(
                    modifier = Modifier
                        .size(4.dp)
                        .clip(CircleShape)
                        .background(if (selected) MaterialTheme.colorScheme.onPrimary else markerTint),
                )
            }
        }
    }
}

@Composable
private fun DaySheet(
    date: LocalDate,
    summary: DaySummary,
    onEventClick: (EventOccurrence) -> Unit,
    padding: PaddingValues,
) {
    val title = date.format(DateTimeFormatter.ofPattern("yyyy 年 M 月 d 日 EEEE"))
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(MemoSpacing.sm),
    ) {
        item {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = MemoSpacing.md, bottom = MemoSpacing.xs),
            )
        }
        if (summary.events.isEmpty() && summary.memos.isEmpty()) {
            item {
                // Contain the empty state to a sensible height — the
                // MemoEmptyState wants fillMaxSize, so we wrap it in a
                // fixed-height box.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                ) {
                    MemoEmptyState(
                        icon = Icons.Outlined.EventAvailable,
                        title = "今日无事件",
                        subtitle = "点右下角添加新事件",
                    )
                }
            }
        }
        if (summary.events.isNotEmpty()) {
            item { MemoSectionHeader(text = "日程") }
            items(summary.events.size, key = { idx ->
                val o = summary.events[idx]
                "e-${o.event.uid}-${o.startEpochMs}"
            }) { idx ->
                EventRow(summary.events[idx], onClick = { onEventClick(summary.events[idx]) })
            }
        }
        if (summary.memos.isNotEmpty()) {
            item { MemoSectionHeader(text = "备忘") }
            // Bug-1 M9 fix (#129): 同一分钟两条 memo 共享 time key,LazyColumn key
            // 碰撞会让 Compose 错乱重用 cell。改 idx 与 time 联合 (idx 全局唯一)。
            items(summary.memos.size, key = { idx -> "m-$idx-${summary.memos[idx].time}" }) { idx ->
                val m = summary.memos[idx]
                MemoCard(accentColor = MaterialTheme.colorScheme.primary) {
                    Text(
                        m.time.format(DateTimeFormatter.ofPattern("HH:mm")),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(m.body, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        item { Spacer(Modifier.height(88.dp)) } // space for FAB
    }
}

@Composable
private fun EventRow(occ: EventOccurrence, onClick: () -> Unit) {
    val zone = remember { ZoneId.systemDefault() }
    val start = remember(occ) { Instant.ofEpochMilli(occ.startEpochMs).atZone(zone) }
    val end = remember(occ) { Instant.ofEpochMilli(occ.endEpochMs).atZone(zone) }
    val timeFmt = remember { DateTimeFormatter.ofPattern("HH:mm") }
    val isRecurring = !occ.event.rrule.isNullOrBlank()
    MemoCard(
        accentColor = MaterialTheme.colorScheme.tertiary,
        onClick = onClick,
    ) {
        // Fix-7 #9 (UI-A report): recurring mark was a "🔁" emoji appended
        // to the time string; now an `Icons.Filled.Repeat` tinted tertiary
        // sits inline next to the time so the indicator stays in the same
        // Material iconography as the rest of the app.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${start.format(timeFmt)} – ${end.format(timeFmt)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.tertiary,
            )
            if (isRecurring) {
                Spacer(Modifier.width(MemoSpacing.xs))
                Icon(
                    imageVector = Icons.Filled.Repeat,
                    contentDescription = "重复事件",
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        Text(
            text = occ.event.summary,
            style = MaterialTheme.typography.titleMedium,
        )
        if (occ.event.dirty) {
            Text(
                text = "待同步",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
