package dev.aria.memo.ui.notelist

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.aria.memo.EditActivity
import dev.aria.memo.data.MemoEntry
import dev.aria.memo.data.sync.SyncStatus
import dev.aria.memo.data.sync.SyncStatusBus
import dev.aria.memo.ui.components.MemoCard
import dev.aria.memo.ui.components.MemoEmptyState
import dev.aria.memo.ui.components.MemoSectionHeader
import dev.aria.memo.ui.components.ScrollAwareFab
import dev.aria.memo.ui.theme.MemoSpacing
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteListScreen(
    viewModel: NoteListViewModel,
    onOpenEditor: () -> Unit,
    modifier: Modifier = Modifier,
    // Fixes #71 (P7.0.1): accept an optional noteUid so SingleNoteRow can
    // deep-link into the AI chat with the current note pinned as context.
    // Keyword default = null preserves the tab-level entry (no note selected).
    onOpenAiChat: (noteUid: String?) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Fixes #11: surface sync failures so the user knows when pushes stop landing.
    val syncStatus by SyncStatusBus.status.collectAsStateWithLifecycle()
    // Fixes #30: hold search query in rememberSaveable so it survives a Notes-tab
    // ViewModel reset (which happens when the nav graph rebuilds the Notes entry).
    // The composable becomes the source of truth; VM is pushed to on every change.
    var query by rememberSaveable { mutableStateOf(state.query) }
    LaunchedEffect(Unit) {
        if (query != state.query) viewModel.onQueryChange(query)
    }

    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    val listState = rememberLazyListState()
    // FAB collapses once the list scrolls off the first visible item, so the
    // user sees the full "写一条" label at rest and a compact icon while they
    // scroll through dozens of notes without covering content.
    val fabExpanded by remember {
        derivedStateOf { listState.firstVisibleItemIndex == 0 }
    }

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("笔记") },
                actions = {
                    IconButton(onClick = { onOpenAiChat(null) }) {
                        Icon(Icons.Filled.Psychology, contentDescription = "AI 助手")
                    }
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = "同步")
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            ScrollAwareFab(
                expanded = fabExpanded,
                onClick = onOpenEditor,
                icon = Icons.Filled.Add,
                text = "写一条",
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .padding(inner)
                .fillMaxSize(),
        ) {
            SyncBanner(status = syncStatus, onDismiss = { SyncStatusBus.clearError() })
            NoteListBody(
                state = state,
                query = query,
                onQueryChange = {
                    query = it
                    viewModel.onQueryChange(it)
                },
                onTogglePin = { path, pinned -> viewModel.togglePin(path, pinned) },
                onAskAiForNote = onOpenAiChat,
                listState = listState,
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
                .padding(horizontal = MemoSpacing.lg, vertical = MemoSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "同步失败：${err.message}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) {
                Text("知道了", color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteListBody(
    state: NoteListUiState,
    query: String,
    onQueryChange: (String) -> Unit,
    onTogglePin: (String, Boolean) -> Unit,
    onAskAiForNote: (noteUid: String?) -> Unit,
    listState: androidx.compose.foundation.lazy.LazyListState,
    innerPadding: PaddingValues,
) {
    val context = LocalContext.current
    val openSingleNote: (String) -> Unit = { uid ->
        val intent = Intent(context, EditActivity::class.java)
            .putExtra(EditActivity.EXTRA_NOTE_UID, uid)
        context.startActivity(intent)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .padding(horizontal = MemoSpacing.lg),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            label = { Text("搜索笔记") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
            shape = dev.aria.memo.ui.theme.MemoShapes.button,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = MemoSpacing.sm),
        )

        val pinned = state.pinned
        val unpinned = state.unpinned
        if (pinned.isEmpty() && unpinned.isEmpty()) {
            if (query.isBlank()) {
                MemoEmptyState(
                    icon = Icons.AutoMirrored.Outlined.Notes,
                    title = "还没有笔记",
                    subtitle = "点右下角「写一条」开始记录",
                )
            } else {
                MemoEmptyState(
                    icon = Icons.Outlined.SearchOff,
                    title = "没找到匹配的笔记",
                    subtitle = "试试换个关键词",
                )
            }
        } else {
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(MemoSpacing.md),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (pinned.isNotEmpty()) {
                    item(key = "section-pinned") {
                        MemoSectionHeader(text = "📌 置顶")
                    }
                    renderItems(pinned, onTogglePin, openSingleNote, onAskAiForNote)
                }
                if (unpinned.isNotEmpty()) {
                    if (pinned.isNotEmpty()) {
                        item(key = "section-all") {
                            MemoSectionHeader(text = "全部笔记")
                        }
                    }
                    renderItems(unpinned, onTogglePin, openSingleNote, onAskAiForNote)
                }
                item(key = "footer-spacer") {
                    // Leave breathing room below the last card so the FAB doesn't
                    // hide the final entry while the list is at rest.
                    Spacer(modifier = Modifier.padding(bottom = MemoSpacing.xxxl))
                }
            }
        }
    }
}

/**
 * Emit LazyColumn items for the given [list]. Keeps the when-branch in one
 * place so pinned/unpinned sections can share a single render shape.
 */
private fun LazyListScope.renderItems(
    list: List<NoteListUiItem>,
    onTogglePin: (String, Boolean) -> Unit,
    onOpenSingleNote: (String) -> Unit,
    onAskAiForNote: (noteUid: String?) -> Unit,
) {
    list.forEach { item ->
        when (item) {
            is NoteListUiItem.LegacyDay -> {
                val group = item.group
                item(key = "h-${group.path}") {
                    DayHeader(group, onTogglePin = onTogglePin)
                }
                items(group.entries.size, key = { "${group.path}-$it" }) { idx ->
                    EntryCard(group.entries[idx])
                }
            }
            is NoteListUiItem.SingleNote -> {
                item(key = "single-${item.uid}") {
                    SingleNoteRow(
                        note = item,
                        onTogglePin = onTogglePin,
                        onOpen = onOpenSingleNote,
                        onAskAi = { onAskAiForNote(item.uid) },
                    )
                }
            }
        }
    }
}

private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy 年 MM 月 dd 日 EEEE")
private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val SINGLE_DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM/dd")

@Composable
private fun DayHeader(
    group: DayGroup,
    onTogglePin: (String, Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = MemoSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = group.date.format(DATE_FMT) + if (group.dirty) "  · 待同步" else "",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = { onTogglePin(group.path, !group.pinned) },
        ) {
            Icon(
                imageVector = if (group.pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                contentDescription = if (group.pinned) "取消置顶" else "置顶",
                tint = if (group.pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EntryCard(entry: MemoEntry) {
    // Legacy day-file entry. Primary accent marks "this came from the per-day
    // aggregate" versus SingleNote rows which use tertiary accent.
    MemoCard(accentColor = MaterialTheme.colorScheme.primary) {
        Text(
            text = entry.time.format(TIME_FMT),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = entry.body,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = MemoSpacing.xs),
        )
    }
}

/**
 * Renders a single-note row. Tapping the card opens [EditActivity] with the
 * note's uid; the pin button dispatches through the ViewModel's togglePin —
 * which routes to [dev.aria.memo.data.SingleNoteRepository.togglePin] when
 * the path lives under `notes/`.
 *
 * Tertiary accent distinguishes single-file notes from legacy per-day entries
 * so the user can see at a glance which storage model a row comes from.
 */
@Composable
private fun SingleNoteRow(
    note: NoteListUiItem.SingleNote,
    onTogglePin: (String, Boolean) -> Unit,
    onOpen: (String) -> Unit,
    onAskAi: () -> Unit,
) {
    // Fixes #71 (P7.0.1): long-press menu exposing "问 AI"; the tap opens
    // the note as before. Anchor is the MemoCard, menu offset trails the
    // finger so the user sees both items without moving.
    var menuExpanded by rememberSaveable { mutableStateOf(false) }

    Box {
        MemoCard(
            accentColor = MaterialTheme.colorScheme.tertiary,
            onClick = { onOpen(note.uid) },
            onLongClick = { menuExpanded = true },
        ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${note.date.format(SINGLE_DATE_FMT)} ${note.time.format(TIME_FMT)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                val headline = note.title.ifBlank {
                    note.preview.lineSequence()
                        .map { it.trim() }
                        .firstOrNull { it.isNotEmpty() }
                        .orEmpty()
                }
                if (headline.isNotEmpty()) {
                    Text(
                        text = headline,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 2.dp),
                        maxLines = 1,
                    )
                }
                if (note.preview.isNotEmpty() && note.preview != headline) {
                    Text(
                        text = note.preview,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = MemoSpacing.xs),
                        maxLines = 2,
                    )
                }
            }
            Spacer(modifier = Modifier.width(MemoSpacing.sm))
            IconButton(onClick = { onTogglePin(note.path, !note.pinned) }) {
                Icon(
                    imageVector = if (note.pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                    contentDescription = if (note.pinned) "取消置顶" else "置顶",
                    tint = if (note.pinned) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        }
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
        ) {
            DropdownMenuItem(
                text = { Text("问 AI") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Psychology,
                        contentDescription = null,
                    )
                },
                onClick = {
                    menuExpanded = false
                    onAskAi()
                },
            )
        }
    }
}
