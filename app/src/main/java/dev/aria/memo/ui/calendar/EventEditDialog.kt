package dev.aria.memo.ui.calendar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.aria.memo.data.local.EventEntity
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Create / edit / delete dialog for a single event.
 *
 * Inputs: summary (required) + start time + end time — date is inherited from
 * [initialDate] (or the event's existing start). Times render in the device's
 * local zone; the save callback emits UTC epoch milliseconds.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventEditDialog(
    initialDate: LocalDate,
    event: EventEntity?,
    onDismiss: () -> Unit,
    onSave: (summary: String, startMs: Long, endMs: Long) -> Unit,
    onDelete: (() -> Unit)?,
) {
    val zone = remember { ZoneId.systemDefault() }
    val now = remember { LocalTime.now().withSecond(0).withNano(0) }
    val baseDate = remember(initialDate, event) {
        event?.let { Instant.ofEpochMilli(it.startEpochMs).atZone(zone).toLocalDate() } ?: initialDate
    }

    var summary by rememberSaveable(event?.uid) { mutableStateOf(event?.summary.orEmpty()) }

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

    // Fixes #4: rememberSaveable so a config change (rotate/theme) doesn't blow away
    // the picker the user is actively using.
    var editingStart by rememberSaveable { mutableStateOf<Boolean?>(null) } // null = not showing picker, true = start, false = end
    var startHour by rememberSaveable(event?.uid) { mutableStateOf(initialStartHour) }
    var startMin by rememberSaveable(event?.uid) { mutableStateOf(initialStartMin) }
    var endHour by rememberSaveable(event?.uid) { mutableStateOf(initialEndHour) }
    var endMin by rememberSaveable(event?.uid) { mutableStateOf(initialEndMin) }

    val title = if (event == null) "新建日程" else "编辑日程"

    val startInstant = baseDate.atTime(startHour, startMin).atZone(zone).toInstant()
    val endInstant = baseDate.atTime(endHour, endMin).atZone(zone).toInstant()
    val endBeforeStart = endInstant.isBefore(startInstant) || endInstant == startInstant

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(
                onClick = {
                    onSave(summary.trim(), startInstant.toEpochMilli(), endInstant.toEpochMilli())
                },
                enabled = summary.isNotBlank() && !endBeforeStart,
            ) { Text("保存") }
        },
        dismissButton = {
            Row {
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
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("日期：${baseDate}")
                OutlinedTextField(
                    value = summary,
                    onValueChange = { summary = it },
                    label = { Text("标题") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(onClick = { editingStart = true }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.AccessTime, contentDescription = null)
                        Spacer(Modifier.height(0.dp))
                        Text(
                            text = " 开始 %02d:%02d".format(startHour, startMin),
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                    OutlinedButton(onClick = { editingStart = false }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.AccessTime, contentDescription = null)
                        Spacer(Modifier.height(0.dp))
                        Text(
                            text = " 结束 %02d:%02d".format(endHour, endMin),
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
                if (endBeforeStart) {
                    androidx.compose.material3.Text(
                        text = "结束时间必须晚于开始",
                        color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    )
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
}
