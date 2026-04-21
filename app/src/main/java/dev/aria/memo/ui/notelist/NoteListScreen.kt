package dev.aria.memo.ui.notelist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.aria.memo.data.MemoEntry
import dev.aria.memo.data.sync.SyncStatus
import dev.aria.memo.data.sync.SyncStatusBus
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteListScreen(
    viewModel: NoteListViewModel,
    onOpenEditor: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Fixes #11: surface sync failures so the user knows when pushes stop landing.
    val syncStatus by SyncStatusBus.status.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("笔记") },
                actions = {
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = "同步")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onOpenEditor,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("写一条") },
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier.padding(inner).fillMaxSize(),
        ) {
            SyncBanner(status = syncStatus, onDismiss = { SyncStatusBus.clearError() })
            NoteListBody(
                state = state,
                onQueryChange = viewModel::onQueryChange,
                innerPadding = PaddingValues(0.dp),
            )
        }
    }
}

@Composable
private fun SyncBanner(status: SyncStatus, onDismiss: () -> Unit) {
    val err = status as? SyncStatus.Error ?: return
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "同步失败：${err.message}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("知道了", color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}

@Composable
private fun NoteListBody(
    state: NoteListUiState,
    onQueryChange: (String) -> Unit,
    innerPadding: PaddingValues,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .padding(horizontal = 16.dp),
    ) {
        OutlinedTextField(
            value = state.query,
            onValueChange = onQueryChange,
            label = { Text("搜索笔记") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
        )

        val groups = state.filtered
        if (groups.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (state.query.isBlank()) "还没有笔记，点右下角写一条" else "没找到匹配的笔记",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                groups.forEach { group ->
                    item(key = "h-${group.date}") { DayHeader(group) }
                    items(group.entries.size, key = { "${group.date}-$it" }) { idx ->
                        EntryCard(group.entries[idx])
                    }
                }
            }
        }
    }
}

private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy 年 MM 月 dd 日 EEEE")
private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

@Composable
private fun DayHeader(group: DayGroup) {
    Text(
        text = group.date.format(DATE_FMT) + if (group.dirty) "  · 待同步" else "",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

@Composable
private fun EntryCard(entry: MemoEntry) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = entry.time.format(TIME_FMT),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = entry.body,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
