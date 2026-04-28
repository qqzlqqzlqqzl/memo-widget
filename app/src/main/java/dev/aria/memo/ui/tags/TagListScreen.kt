package dev.aria.memo.ui.tags

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.aria.memo.data.tag.TagMatch
import dev.aria.memo.data.tag.TagNode
import dev.aria.memo.ui.components.MemoCard
import dev.aria.memo.ui.components.MemoEmptyState
import dev.aria.memo.ui.theme.MemoSpacing
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagListScreen(
    viewModel: TagListViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // A tag is selected by its full path (e.g. "work/meeting"). Null = tree view.
    var selectedPath by rememberSaveable { mutableStateOf<String?>(null) }
    // Which internal branches are expanded — keyed by full path. Promoted to
    // `rememberSaveable` (Fixes #167) so the user's expansion choices survive
    // tab swaps and process death; the previous mutableStateListOf was wiped
    // every time the tab tore down. listSaver flattens the Set to a
    // Bundle-safe List<String> on save.
    var expanded by rememberSaveable(
        stateSaver = listSaver<Set<String>, String>(
            save = { it.toList() },
            restore = { it.toSet() },
        ),
    ) { mutableStateOf<Set<String>>(emptySet()) }

    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            // Fixes #235 (UI-A #23): the tree view (selectedPath ==
            // null) is a top-level tab entry and earns the 140dp
            // hero LargeTopAppBar; the per-tag detail view is a
            // sub-page and uses a compact TopAppBar so the bar
            // doesn't dominate before the user can read any entries.
            if (selectedPath == null) {
                LargeTopAppBar(
                    title = { Text(text = "标签") },
                    scrollBehavior = scrollBehavior,
                )
            } else {
                TopAppBar(
                    title = { Text(text = "#$selectedPath") },
                    navigationIcon = {
                        IconButton(onClick = { selectedPath = null }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回标签树",
                            )
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
            }
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .padding(inner)
                .fillMaxSize()
                .padding(horizontal = MemoSpacing.lg),
        ) {
            val selectedNode = selectedPath?.let { findNode(state.root, it) }
            if (selectedNode != null) {
                TagEntriesList(node = selectedNode)
            } else if (state.root.children.isEmpty()) {
                MemoEmptyState(
                    icon = Icons.Outlined.Tag,
                    title = "还没有标签",
                    subtitle = "在笔记正文里写 #work 或 #work/meeting 试试",
                )
            } else {
                TagTree(
                    root = state.root,
                    expanded = expanded,
                    onToggleExpand = { path ->
                        expanded = if (path in expanded) expanded - path else expanded + path
                    },
                    onSelectTag = { path -> selectedPath = path },
                )
            }
        }
    }
}

// --- tree ------------------------------------------------------------------

@Composable
private fun TagTree(
    root: TagNode,
    expanded: Set<String>,
    onToggleExpand: (String) -> Unit,
    onSelectTag: (String) -> Unit,
) {
    val flattened = remember(root, expanded) {
        flatten(root, expanded)
    }
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(flattened, key = { it.path }) { row ->
            TagRow(
                row = row,
                onToggleExpand = onToggleExpand,
                onSelectTag = onSelectTag,
            )
        }
    }
}

private data class FlatRow(
    val path: String,
    val name: String,
    val depth: Int,
    val hasChildren: Boolean,
    val isExpanded: Boolean,
    val entryCount: Int,
)

/** Pre-order flatten so the tree renders top-down, parents above their children. */
private fun flatten(root: TagNode, expanded: Set<String>): List<FlatRow> {
    val out = mutableListOf<FlatRow>()
    fun walk(node: TagNode, prefix: String, depth: Int) {
        for (child in node.children) {
            val path = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
            val isOpen = expanded.contains(path)
            out += FlatRow(
                path = path,
                name = child.name,
                depth = depth,
                hasChildren = child.children.isNotEmpty(),
                isExpanded = isOpen,
                entryCount = totalEntryCount(child),
            )
            if (isOpen) walk(child, path, depth + 1)
        }
    }
    walk(root, prefix = "", depth = 0)
    return out
}

/** All matches in this subtree — used for the badge count next to the row. */
private fun totalEntryCount(node: TagNode): Int {
    var n = node.entries.size
    for (c in node.children) n += totalEntryCount(c)
    return n
}

private fun findNode(root: TagNode, path: String): TagNode? {
    val segments = path.split('/').filter { it.isNotEmpty() }
    var cur: TagNode = root
    for (seg in segments) {
        cur = cur.children.firstOrNull { it.name == seg } ?: return null
    }
    return cur
}

@Composable
private fun TagRow(
    row: FlatRow,
    onToggleExpand: (String) -> Unit,
    onSelectTag: (String) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelectTag(row.path) }
            .padding(
                start = (row.depth * 20).dp + MemoSpacing.sm,
                end = MemoSpacing.sm,
                top = MemoSpacing.sm + 2.dp,
                bottom = MemoSpacing.sm + 2.dp,
            ),
    ) {
        if (row.hasChildren) {
            Icon(
                imageVector = if (row.isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (row.isExpanded) "折叠" else "展开",
                modifier = Modifier
                    .clickable { onToggleExpand(row.path) }
                    .padding(MemoSpacing.xs),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Label,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(MemoSpacing.xs),
            )
        }
        Spacer(Modifier.width(MemoSpacing.xs + 2.dp))
        Text(
            text = "#${row.name}",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (row.entryCount > 0) {
            // Fixes #244 (UI-A #15): wrap the count in a pill (Surface +
            // CircleShape + secondaryContainer) so the number reads as a
            // deliberate badge rather than a stray label hanging off the
            // right edge of the row.
            androidx.compose.material3.Surface(
                shape = androidx.compose.foundation.shape.CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Text(
                    text = row.entryCount.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(
                        horizontal = MemoSpacing.sm,
                        vertical = 2.dp,
                    ),
                )
            }
        }
    }
}

// --- entries ---------------------------------------------------------------

private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

@Composable
private fun TagEntriesList(
    node: TagNode,
) {
    // All matches in this subtree — parent nodes show their own plus all child matches.
    val entries = remember(node) { collectEntries(node) }

    if (entries.isEmpty()) {
        MemoEmptyState(
            icon = Icons.Outlined.Tag,
            title = "这个标签下还没有笔记",
        )
    } else {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(MemoSpacing.sm),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(entries.size, key = { "${entries[it].date}-${entries[it].time}-$it" }) { idx ->
                EntryCard(entries[idx])
            }
        }
    }
}

private fun collectEntries(node: TagNode): List<TagMatch> {
    val out = mutableListOf<TagMatch>()
    fun walk(n: TagNode) {
        out += n.entries
        for (c in n.children) walk(c)
    }
    walk(node)
    return out.sortedWith(
        compareByDescending<TagMatch> { it.date }.thenByDescending { it.time }
    )
}

@Composable
private fun EntryCard(match: TagMatch) {
    MemoCard(accentColor = MaterialTheme.colorScheme.primary) {
        Text(
            text = "${match.date.format(DATE_FMT)} · ${match.time.format(TIME_FMT)}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = match.body,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = MemoSpacing.xs),
        )
    }
}
