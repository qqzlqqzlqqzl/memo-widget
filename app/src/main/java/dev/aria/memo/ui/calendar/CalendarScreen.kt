package dev.aria.memo.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kizitonwose.calendar.compose.HorizontalCalendar
import com.kizitonwose.calendar.compose.rememberCalendarState
import com.kizitonwose.calendar.core.CalendarDay
import com.kizitonwose.calendar.core.DayPosition
import com.kizitonwose.calendar.core.daysOfWeek
import dev.aria.memo.data.local.EventEntity
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

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("日历") },
                actions = {
                    IconButton(onClick = {
                        scope.launch {
                            calendarState.animateScrollToMonth(calendarState.firstVisibleMonth.yearMonth.minusMonths(1))
                        }
                    }) { Icon(Icons.Filled.ChevronLeft, contentDescription = "上一月") }
                    Text(
                        text = calendarState.firstVisibleMonth.yearMonth.format(DateTimeFormatter.ofPattern("yyyy 年 M 月")),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    IconButton(onClick = {
                        scope.launch {
                            calendarState.animateScrollToMonth(calendarState.firstVisibleMonth.yearMonth.plusMonths(1))
                        }
                    }) { Icon(Icons.Filled.ChevronRight, contentDescription = "下一月") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAddDialog = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("加日程") },
            )
        },
    ) { inner ->
        Column(Modifier.padding(inner).fillMaxSize()) {
            WeekHeader(daysOfWeek)
            HorizontalCalendar(
                state = calendarState,
                dayContent = { day ->
                    DayCell(
                        day = day,
                        selected = day.date == state.selected,
                        marked = day.date in state.markedDates,
                        onClick = { viewModel.selectDate(day.date) },
                    )
                },
            )
            HorizontalDivider()
            DaySheet(
                date = state.selected,
                summary = state.daySummary,
                onEventClick = { editingEvent = it },
            )
        }
    }

    if (showAddDialog) {
        EventEditDialog(
            initialDate = state.selected,
            event = null,
            onDismiss = { showAddDialog = false },
            onSave = { summary, startMs, endMs ->
                viewModel.createEvent(summary, startMs, endMs) { showAddDialog = false }
            },
            onDelete = null,
        )
    }
    editingEvent?.let { ev ->
        EventEditDialog(
            initialDate = state.selected,
            event = ev,
            onDismiss = { editingEvent = null },
            onSave = { summary, startMs, endMs ->
                viewModel.updateEvent(ev.uid, summary, startMs, endMs) { editingEvent = null }
            },
            onDelete = {
                viewModel.deleteEvent(ev.uid) { editingEvent = null }
            },
        )
    }
}

@Composable
private fun WeekHeader(daysOfWeek: List<DayOfWeek>) {
    Row(Modifier.fillMaxWidth()) {
        daysOfWeek.forEach { d ->
            Text(
                text = d.getDisplayName(TextStyle.SHORT, Locale.SIMPLIFIED_CHINESE),
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun DayCell(
    day: CalendarDay,
    selected: Boolean,
    marked: Boolean,
    onClick: () -> Unit,
) {
    val inMonth = day.position == DayPosition.MonthDate
    val bgColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    val fgColor = when {
        selected -> MaterialTheme.colorScheme.onPrimaryContainer
        !inMonth -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        else -> MaterialTheme.colorScheme.onSurface
    }
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .padding(2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .clickable(enabled = inMonth) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = day.date.dayOfMonth.toString(),
                color = fgColor,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (marked && inMonth) {
                Spacer(Modifier.height(2.dp))
                Box(
                    modifier = Modifier
                        .size(4.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
    }
}

@Composable
private fun DaySheet(
    date: LocalDate,
    summary: DaySummary,
    onEventClick: (EventEntity) -> Unit,
) {
    val title = date.format(DateTimeFormatter.ofPattern("yyyy 年 M 月 d 日 EEEE"))
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
            )
        }
        if (summary.events.isEmpty() && summary.memos.isEmpty()) {
            item {
                Text(
                    text = "这一天还没有记录",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }
        }
        if (summary.events.isNotEmpty()) {
            item { SectionHeader("日程") }
            items(summary.events.size, key = { "e-${summary.events[it].uid}" }) { idx ->
                EventRow(summary.events[idx], onClick = { onEventClick(summary.events[idx]) })
            }
        }
        if (summary.memos.isNotEmpty()) {
            item { SectionHeader("备忘") }
            items(summary.memos.size, key = { "m-${summary.memos[it].time}" }) { idx ->
                val m = summary.memos[idx]
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            m.time.format(DateTimeFormatter.ofPattern("HH:mm")),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(m.body, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(88.dp)) } // space for FAB
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun EventRow(event: EventEntity, onClick: () -> Unit) {
    val zone = remember { ZoneId.systemDefault() }
    val start = remember(event) { Instant.ofEpochMilli(event.startEpochMs).atZone(zone) }
    val end = remember(event) { Instant.ofEpochMilli(event.endEpochMs).atZone(zone) }
    val timeFmt = remember { DateTimeFormatter.ofPattern("HH:mm") }
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                text = "${start.format(timeFmt)} – ${end.format(timeFmt)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                text = event.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            if (event.dirty) {
                Text(
                    text = "待同步",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}
