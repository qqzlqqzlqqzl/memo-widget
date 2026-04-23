package dev.aria.memo.ui.calendar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.aria.memo.data.local.EventEntity
import dev.aria.memo.ui.theme.MemoSpacing
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Create / edit / delete dialog for a single event.
 *
 * Inputs: summary (required) + start time + end time + optional recurrence
 * and reminder. Times render in the device's local zone; the save callback
 * emits UTC epoch milliseconds. Kept as an [AlertDialog] (not a BottomSheet)
 * because the IME interaction behaves better here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventEditDialog(
    initialDate: LocalDate,
    event: EventEntity?,
    onDismiss: () -> Unit,
    onSave: (summary: String, startMs: Long, endMs: Long, rrule: String?, reminderMinutesBefore: Int?) -> Unit,
    onDelete: (() -> Unit)?,
    sessionKey: Int = 0,
) {
    val zone = remember { ZoneId.systemDefault() }
    val now = remember { LocalTime.now().withSecond(0).withNano(0) }
    val baseDate = remember(initialDate, event) {
        event?.let { Instant.ofEpochMilli(it.startEpochMs).atZone(zone).toLocalDate() } ?: initialDate
    }

    // Fixes #22: for new events event?.uid is null, so a plain `rememberSaveable(null)`
    // key would share the saver slot across consecutive "add" sessions and leak state
    // from the previous open. Compose the key with a caller-provided sessionKey that
    // bumps every time the dialog is re-opened.
    val saveKey = "${event?.uid ?: "new"}-$sessionKey"

    var summary by rememberSaveable(saveKey) { mutableStateOf(event?.summary.orEmpty()) }

    // Date can be edited via the DatePicker — store as epoch-day so
    // rememberSaveable handles it without a custom Saver.
    var selectedEpochDay by rememberSaveable(saveKey) { mutableStateOf(baseDate.toEpochDay()) }
    val selectedDate = LocalDate.ofEpochDay(selectedEpochDay)

    val (initialStartHour, initialStartMin) = remember(event) {
        if (event != null) {
            val zdt = Instant.ofEpochMilli(event.startEpochMs).atZone(zone)
            zdt.hour to zdt.minute
        } else now.hour to now.minute
    }
    val (initialEndHour, initialEndMin) = remember(event) {
        if (event != null) {
            val zdt = Instant.ofEpochMilli(event.endEpochMs).atZone(zone)
            zdt.hour to zdt.minute
        } else ((now.hour + 1) % 24) to now.minute
    }

    // null = not showing picker, true = start, false = end
    var editingStart by rememberSaveable(saveKey) { mutableStateOf<Boolean?>(null) }
    var showDatePicker by rememberSaveable(saveKey) { mutableStateOf(false) }
    var startHour by rememberSaveable(saveKey) { mutableStateOf(initialStartHour) }
    var startMin by rememberSaveable(saveKey) { mutableStateOf(initialStartMin) }
    var endHour by rememberSaveable(saveKey) { mutableStateOf(initialEndHour) }
    var endMin by rememberSaveable(saveKey) { mutableStateOf(initialEndMin) }

    // P4: simple RRULE picker — 不重复 / 每周 / 每月 / 自定义.
    val initialRrule = event?.rrule
    var rrule by rememberSaveable(saveKey) { mutableStateOf(initialRrule) }

    // P4.1: reminder (minutes before start). null = no reminder.
    var reminder by rememberSaveable(saveKey) { mutableStateOf(event?.reminderMinutesBefore) }
    val reminderEnabled = reminder != null

    val title = if (event == null) "新建日程" else "编辑日程"

    val startInstant = selectedDate.atTime(startHour, startMin).atZone(zone).toInstant()
    val endInstant = selectedDate.atTime(endHour, endMin).atZone(zone).toInstant()
    val endBeforeStart = endInstant.isBefore(startInstant) || endInstant == startInstant

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        confirmButton = {
            Button(
                onClick = {
                    onSave(summary.trim(), startInstant.toEpochMilli(), endInstant.toEpochMilli(), rrule, reminder)
                },
                enabled = summary.isNotBlank() && !endBeforeStart,
            ) { Text("保存") }
        },
        dismissButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onDelete != null) {
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Filled.Delete, contentDescription = "删除")
                    }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
        title = { Text(title) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(MemoSpacing.md),
                modifier = Modifier.padding(horizontal = MemoSpacing.xs),
            ) {
                OutlinedButton(
                    onClick = { showDatePicker = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.CalendarMonth, contentDescription = null)
                    Spacer(Modifier.height(0.dp))
                    Text(
                        text = " 日期  $selectedDate",
                        modifier = Modifier.padding(start = MemoSpacing.xs),
                    )
                }
                OutlinedTextField(
                    value = summary,
                    onValueChange = { summary = it },
                    label = { Text("标题") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(MemoSpacing.md),
                ) {
                    OutlinedButton(
                        onClick = { editingStart = true },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.AccessTime, contentDescription = null)
                        Spacer(Modifier.height(0.dp))
                        Text(
                            text = " 开始 %02d:%02d".format(startHour, startMin),
                            modifier = Modifier.padding(start = MemoSpacing.xs),
                        )
                    }
                    OutlinedButton(
                        onClick = { editingStart = false },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.AccessTime, contentDescription = null)
                        Spacer(Modifier.height(0.dp))
                        Text(
                            text = " 结束 %02d:%02d".format(endHour, endMin),
                            modifier = Modifier.padding(start = MemoSpacing.xs),
                        )
                    }
                }
                if (endBeforeStart) {
                    Text(
                        text = "结束时间必须晚于开始",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                // P4: recurrence chips — three canned chips plus a "自定义" fallback
                // so a rrule we don't have a UI for (DAILY, YEARLY, COUNT=..., etc.) is
                // still represented and preserved across edit (Fixes #23).
                Text("重复", style = MaterialTheme.typography.labelMedium)
                val isNone = rrule.isNullOrBlank()
                val isWeekly = rrule == "FREQ=WEEKLY"
                val isMonthly = rrule == "FREQ=MONTHLY"
                val isCustom = !isNone && !isWeekly && !isMonthly
                Row(horizontalArrangement = Arrangement.spacedBy(MemoSpacing.sm)) {
                    RrChip(label = "不重复", selected = isNone) { rrule = null }
                    RrChip(label = "每周", selected = isWeekly) { rrule = "FREQ=WEEKLY" }
                    RrChip(label = "每月", selected = isMonthly) { rrule = "FREQ=MONTHLY" }
                    RrChip(label = "自定义", selected = isCustom) { /* preserve rrule */ }
                }
                if (isCustom) {
                    Text(
                        text = rrule.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // P4.1: reminder — Switch to toggle, AssistChip row for choice.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("提醒", style = MaterialTheme.typography.labelMedium)
                    Switch(
                        checked = reminderEnabled,
                        onCheckedChange = { checked ->
                            reminder = if (checked) (reminder ?: 15) else null
                        },
                    )
                }
                if (reminderEnabled) {
                    Row(horizontalArrangement = Arrangement.spacedBy(MemoSpacing.sm)) {
                        ReminderChip(label = "5 分钟前", selected = reminder == 5) { reminder = 5 }
                        ReminderChip(label = "15 分钟前", selected = reminder == 15) { reminder = 15 }
                        ReminderChip(label = "1 小时前", selected = reminder == 60) { reminder = 60 }
                    }
                }
            }
        },
    )

    if (editingStart != null) {
        val pickerState = rememberTimePickerState(
            initialHour = if (editingStart == true) startHour else endHour,
            initialMinute = if (editingStart == true) startMin else endMin,
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { editingStart = null },
            shape = RoundedCornerShape(28.dp),
            confirmButton = {
                TextButton(onClick = {
                    if (editingStart == true) {
                        startHour = pickerState.hour
                        startMin = pickerState.minute
                    } else {
                        endHour = pickerState.hour
                        endMin = pickerState.minute
                    }
                    editingStart = null
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { editingStart = null }) { Text("取消") } },
            title = { Text(if (editingStart == true) "选择开始时间" else "选择结束时间") },
            text = { TimePicker(state = pickerState) },
        )
    }

    if (showDatePicker) {
        val millisForPicker = selectedDate.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()
        val dateState = rememberDatePickerState(initialSelectedDateMillis = millisForPicker)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    dateState.selectedDateMillis?.let { ms ->
                        // DatePicker emits UTC-midnight; convert back via UTC zone
                        // to avoid TZ-offset-day-shift at month boundaries.
                        val picked = Instant.ofEpochMilli(ms).atZone(ZoneId.of("UTC")).toLocalDate()
                        selectedEpochDay = picked.toEpochDay()
                    }
                    showDatePicker = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("取消") }
            },
        ) {
            DatePicker(state = dateState)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RrChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReminderChip(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        FilterChip(selected = true, onClick = onClick, label = { Text(label) })
    } else {
        AssistChip(onClick = onClick, label = { Text(label) })
    }
}
