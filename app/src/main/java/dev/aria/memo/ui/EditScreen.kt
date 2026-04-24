package dev.aria.memo.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.HorizontalRule
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Title
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.aria.memo.ui.edit.ReadModeNote
import dev.aria.memo.ui.theme.MemoSpacing
import dev.aria.memo.ui.theme.MemoTheme
import kotlinx.coroutines.launch

/**
 * Visual mode for [EditScreen]. Read mode renders the body as a checkbox-aware
 * list (`- [ ] foo` → live Checkbox); edit mode is the original TextField plus
 * a Markdown formatting toolbar. Starts in [Edit] for the existing
 * widget-launched write flow; flipping to [Read] via the FilterChip is how
 * users interact with the checkboxes.
 */
private enum class ViewMode { Read, Edit }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditScreen(
    viewModel: EditViewModel,
    onSaved: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    // Fix-6 (Bug-1 C4): expose a delete hook that the overflow menu drives
    // when the editor is showing an existing single-note. EditActivity wires
    // this to [EditViewModel.delete] + finish() so the user can wipe a note
    // from the editor without having to go back to the list. null = new-note
    // mode (no existing row to delete → overflow menu omits the item).
    onDelete: (() -> Unit)? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Body flows through the VM so checklist toggles and TextField edits share
    // a single source of truth. The TextField still uses its own
    // rememberSaveable for cursor/selection, but is seeded from vmBody on load
    // and pushes edits back via viewModel.setBody.
    val vmBody by viewModel.body.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var editorValue by rememberSaveable(stateSaver = TextFieldValueSaver) {
        mutableStateOf(TextFieldValue(vmBody))
    }
    var mode by rememberSaveable { mutableStateOf(ViewMode.Edit) }

    // Fix-6 (Bug-2 / H1): track the body we first showed the user so we can
    // detect unsaved edits on back-press. Captured once, then compared against
    // the live `editorValue.text` — any divergence means the user has typed
    // something we haven't persisted yet. `initialBody` hydrates the moment
    // vmBody transitions from "" to a non-empty value (EditActivity primes the
    // VM asynchronously).
    var initialBody by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(vmBody) {
        if (initialBody == null) initialBody = vmBody
    }
    // Fix-6 (Bug-2 / H1): confirm dialog state for the "discard unsaved?" flow.
    var showDiscardDialog by rememberSaveable { mutableStateOf(false) }
    // Fix-6 (Bug-1 C4): EditActivity overflow menu state + delete-confirm state.
    var overflowExpanded by rememberSaveable { mutableStateOf(false) }
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }

    // When the VM pushes a body update (e.g. after a checklist toggle in read
    // mode), reflect it into the editor's saveable state so switching back to
    // Edit mode shows the latest text. We guard with equality to avoid
    // clobbering in-flight edits.
    LaunchedEffect(vmBody) {
        if (editorValue.text != vmBody) {
            editorValue = TextFieldValue(vmBody, TextRange(vmBody.length))
        }
    }

    // React to terminal save states.
    LaunchedEffect(state) {
        when (val s = state) {
            is SaveState.Success -> {
                // Clear the editor text immediately so the user can *see* the
                // save landed — an empty field is a stronger "done" signal
                // than leaving the text sitting there. ViewModel's
                // duplicate-save window is the authoritative guard against
                // double-tap.
                editorValue = TextFieldValue("")
                viewModel.setBody("")
                viewModel.reset()
                onSaved()
            }
            is SaveState.Error -> {
                scope.launch { snackbarHostState.showSnackbar(s.message) }
                viewModel.reset()
            }
            else -> Unit
        }
    }

    val isSaving = state is SaveState.Saving
    val canSave = editorValue.text.isNotBlank() && !isSaving

    // Fix-6 (Bug-2 / H1): unified "are there unsaved edits?" check. We compare
    // against the first non-null initial body (trimmed both sides so stray
    // whitespace doesn't fire a false positive on a pristine open). If no
    // initial body has been captured yet, treat an empty editor as "nothing to
    // lose" — this keeps the back-press for a new-note mode that the user
    // hasn't typed in behaving like before (straight finish).
    val hasUnsavedChanges: Boolean = !isSaving &&
        editorValue.text.trim() != (initialBody ?: "").trim()

    // Fix-6 (Bug-2 / H1): intercept the system back gesture so we can prompt
    // before discarding a typed-but-unsaved draft. When there's nothing to
    // lose we fall through to the default [onBack] — no dialog churn.
    BackHandler(enabled = hasUnsavedChanges) {
        showDiscardDialog = true
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("写点什么") },
                    navigationIcon = {
                        IconButton(onClick = {
                            // Honor the same "unsaved check" for the back arrow
                            // so finger and gesture both get the prompt.
                            if (hasUnsavedChanges) {
                                showDiscardDialog = true
                            } else {
                                onBack()
                            }
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = { viewModel.save(editorValue.text) },
                            enabled = canSave,
                        ) {
                            Icon(Icons.Filled.Save, contentDescription = "保存")
                        }
                        // Fix-6 (Bug-1 C4): overflow menu with "删除" item,
                        // gated on the caller having wired [onDelete]. For
                        // new-note mode (no existing uid) we still render the
                        // overflow icon off — keeps the top bar compact.
                        if (onDelete != null) {
                            IconButton(onClick = { overflowExpanded = true }) {
                                Icon(
                                    imageVector = Icons.Filled.MoreVert,
                                    contentDescription = "更多操作",
                                )
                            }
                            DropdownMenu(
                                expanded = overflowExpanded,
                                onDismissRequest = { overflowExpanded = false },
                            ) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = "删除",
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Filled.Delete,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    },
                                    onClick = {
                                        overflowExpanded = false
                                        showDeleteDialog = true
                                    },
                                )
                            }
                        }
                    },
                )
                // Thin progress bar replaces the previous full-screen scrim;
                // gives visible feedback while the repo talks to GitHub
                // without hiding the content.
                if (isSaving) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        when (mode) {
            ViewMode.Edit -> EditBody(
                value = editorValue,
                onValueChange = {
                    editorValue = it
                    viewModel.setBody(it.text)
                },
                isSaving = isSaving,
                mode = mode,
                onModeChange = { next ->
                    if (next == ViewMode.Read) {
                        viewModel.setBody(editorValue.text)
                    } else {
                        editorValue = TextFieldValue(vmBody, TextRange(vmBody.length))
                    }
                    mode = next
                },
                innerPadding = innerPadding,
            )
            ViewMode.Read -> ReadBody(
                body = vmBody,
                onToggle = { idx, rawLine, newChecked ->
                    viewModel.toggleChecklist(idx, rawLine, newChecked)
                },
                mode = mode,
                onModeChange = { next ->
                    if (next == ViewMode.Edit) {
                        editorValue = TextFieldValue(vmBody, TextRange(vmBody.length))
                    }
                    mode = next
                },
                innerPadding = innerPadding,
            )
        }
    }

    // Fix-6 (Bug-2 / H1): "您有未保存的改动" confirmation. Three outcomes:
    //  - 保存 → fire the usual save path; Success-effect will call onSaved().
    //  - 放弃 → leave without writing; reset() clears any transient state.
    //  - 取消 → close the dialog, stay on the editor.
    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text("是否保存更改？") },
            text = { Text("当前改动还没有保存,放弃将无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    showDiscardDialog = false
                    if (canSave) {
                        viewModel.save(editorValue.text)
                    } else {
                        // Empty body can't be saved, but the user tapped 保存
                        // with nothing to write — treat it as a benign discard
                        // so they aren't stuck in a dialog loop.
                        onBack()
                    }
                }) {
                    Text("保存")
                }
            },
            dismissButton = {
                androidx.compose.foundation.layout.Row {
                    TextButton(onClick = {
                        showDiscardDialog = false
                        onBack()
                    }) {
                        Text("放弃", color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = { showDiscardDialog = false }) {
                        Text("取消")
                    }
                }
            },
        )
    }

    // Fix-6 (Bug-1 C4): delete-confirm AlertDialog for the overflow menu.
    if (showDeleteDialog && onDelete != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("确定删除？") },
            text = { Text("这条笔记将被删除,该操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    onDelete()
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("取消")
                }
            },
        )
    }
}

/** Saver for [TextFieldValue] so rotation/dark-mode flips don't wipe selection. */
private val TextFieldValueSaver: Saver<TextFieldValue, Any> = Saver(
    save = { listOf(it.text, it.selection.start, it.selection.end) },
    restore = {
        @Suppress("UNCHECKED_CAST")
        val raw = it as List<Any>
        TextFieldValue(
            text = raw[0] as String,
            selection = TextRange(raw[1] as Int, raw[2] as Int),
        )
    },
)

@Composable
private fun EditBody(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    isSaving: Boolean,
    mode: ViewMode,
    onModeChange: (ViewMode) -> Unit,
    innerPadding: PaddingValues,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = MemoSpacing.xl, vertical = MemoSpacing.md),
            verticalArrangement = Arrangement.spacedBy(MemoSpacing.md),
        ) {
            ModeChips(mode = mode, onModeChange = onModeChange)

            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = { Text("正文（Markdown 友好）") },
                minLines = 8,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false),
            )

            WordCountFooter(body = value.text)

            MarkdownToolbar(
                value = value,
                onValueChange = onValueChange,
            )
        }

        if (isSaving) {
            // Tap-absorbing scrim while a save is in flight so the user can't
            // accidentally fire a second tap. Spinner is centered; the top-bar
            // LinearProgressIndicator already signals progress.
            // Fix-7 #1 (UI-A report): was `Color.Black.copy(alpha = 0.18f)`,
            // which all but disappears in dark mode. `colorScheme.scrim` is
            // the M3 semantic slot; 0.32 alpha gives enough darken on light
            // *and* enough contrast against a dark surface.
            Surface(
                color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f),
                modifier = Modifier.fillMaxSize(),
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(48.dp))
                }
            }
        }
    }
}

@Composable
private fun ReadBody(
    body: String,
    onToggle: (Int, String, Boolean) -> Unit,
    mode: ViewMode,
    onModeChange: (ViewMode) -> Unit,
    innerPadding: PaddingValues,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(MemoSpacing.sm),
        ) {
            Box(modifier = Modifier.padding(horizontal = MemoSpacing.xl, vertical = MemoSpacing.md)) {
                ModeChips(mode = mode, onModeChange = onModeChange)
            }
            if (body.isBlank()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "还没有内容。切到编辑模式写点东西吧。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                ReadModeNote(
                    body = body,
                    onToggle = onToggle,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeChips(
    mode: ViewMode,
    onModeChange: (ViewMode) -> Unit,
) {
    androidx.compose.foundation.layout.Row(
        horizontalArrangement = Arrangement.spacedBy(MemoSpacing.sm),
    ) {
        FilterChip(
            selected = mode == ViewMode.Edit,
            onClick = { onModeChange(ViewMode.Edit) },
            label = { Text("编辑") },
        )
        FilterChip(
            selected = mode == ViewMode.Read,
            onClick = { onModeChange(ViewMode.Read) },
            label = { Text("预览") },
        )
    }
}

@Composable
private fun WordCountFooter(body: String) {
    // Fixes #55 (P6.1): cache line count across recompositions that don't
    // change body; also use codePointCount for user-visible "字符数" so
    // surrogate-pair emoji count as 1 instead of 2 JVM chars. Renamed
    // "字数" to "字符数" to avoid the Chinese 字数 / 字符数 ambiguity
    // (字数 can mean words or characters depending on context).
    val charCount = remember(body) {
        if (body.isEmpty()) 0 else body.codePointCount(0, body.length)
    }
    val lineCount = remember(body) {
        if (body.isEmpty()) 0 else body.lineSequence().count()
    }
    Text(
        text = "字符数：$charCount  ·  行数：$lineCount",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Bottom toolbar with Markdown formatting shortcuts. Each button either wraps
 * the current selection in the target syntax or inserts the syntax at the
 * cursor and places the caret inside the wrapper so the user can keep typing.
 */
@Composable
private fun MarkdownToolbar(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(MemoSpacing.xs),
        modifier = Modifier.fillMaxWidth(),
    ) {
        item { ToolbarIconButton(Icons.Filled.FormatBold, "加粗") { onValueChange(applyWrap(value, "**", "**")) } }
        item { ToolbarIconButton(Icons.Filled.FormatItalic, "斜体") { onValueChange(applyWrap(value, "*", "*")) } }
        item { ToolbarIconButton(Icons.Filled.Title, "标题") { onValueChange(applyHeadingCycle(value)) } }
        item { ToolbarIconButton(Icons.AutoMirrored.Filled.FormatListBulleted, "无序列表") { onValueChange(applyLinePrefix(value, "- ")) } }
        item { ToolbarIconButton(Icons.Filled.FormatListNumbered, "有序列表") { onValueChange(applyLinePrefix(value, "1. ")) } }
        item { ToolbarIconButton(Icons.Filled.CheckBox, "复选框") { onValueChange(applyLinePrefix(value, "- [ ] ")) } }
        item { ToolbarIconButton(Icons.Filled.Link, "链接") { onValueChange(applyLink(value)) } }
        item { ToolbarIconButton(Icons.Filled.Code, "代码块") { onValueChange(applyWrap(value, "```\n", "\n```")) } }
        item { ToolbarIconButton(Icons.Filled.FormatQuote, "引用") { onValueChange(applyLinePrefix(value, "> ")) } }
        item { ToolbarIconButton(Icons.Filled.HorizontalRule, "横线") { onValueChange(applyBlockInsert(value, "\n---\n")) } }
    }
}

@Composable
private fun ToolbarIconButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(imageVector = icon, contentDescription = description)
    }
}

// --- Markdown toolbar helpers ---------------------------------------------

/** Wrap the current selection with [prefix]/[suffix]; empty selection places
 *  the caret between the two markers. */
private fun applyWrap(value: TextFieldValue, prefix: String, suffix: String): TextFieldValue {
    val sel = value.selection
    val text = value.text
    val start = sel.start.coerceIn(0, text.length)
    val end = sel.end.coerceIn(0, text.length)
    val before = text.substring(0, start)
    val selected = text.substring(start, end)
    val after = text.substring(end)
    val newText = before + prefix + selected + suffix + after
    val caretStart = start + prefix.length
    val caretEnd = caretStart + selected.length
    return value.copy(text = newText, selection = TextRange(caretStart, caretEnd))
}

/** Cycle through H1/H2/H3 prefixes at the start of the selection's first line. */
private fun applyHeadingCycle(value: TextFieldValue): TextFieldValue {
    val text = value.text
    val sel = value.selection
    val start = sel.start.coerceIn(0, text.length)
    val lineStart = text.lastIndexOf('\n', start - 1).let { if (it == -1) 0 else it + 1 }
    val lineEnd = text.indexOf('\n', start).let { if (it == -1) text.length else it }
    val line = text.substring(lineStart, lineEnd)
    val cleaned = line.trimStart('#', ' ')
    val currentHashes = line.takeWhile { it == '#' }.length
    val nextPrefix = when {
        currentHashes == 0 -> "# "
        currentHashes == 1 -> "## "
        currentHashes == 2 -> "### "
        else -> ""
    }
    val newLine = nextPrefix + cleaned
    val newText = text.substring(0, lineStart) + newLine + text.substring(lineEnd)
    val delta = newLine.length - line.length
    val newSelection = TextRange((start + delta).coerceAtLeast(lineStart))
    return value.copy(text = newText, selection = newSelection)
}

/** Prefix each selected line (or just the caret's line) with [prefix]. */
private fun applyLinePrefix(value: TextFieldValue, prefix: String): TextFieldValue {
    val text = value.text
    val sel = value.selection
    val selStart = sel.start.coerceIn(0, text.length)
    val selEnd = sel.end.coerceIn(0, text.length)
    val lineStart = text.lastIndexOf('\n', selStart - 1).let { if (it == -1) 0 else it + 1 }
    val lineEnd = text.indexOf('\n', selEnd).let { if (it == -1) text.length else it }
    val block = text.substring(lineStart, lineEnd)
    val newBlock = block.split("\n").joinToString("\n") { prefix + it }
    val newText = text.substring(0, lineStart) + newBlock + text.substring(lineEnd)
    val caret = selStart + prefix.length
    return value.copy(text = newText, selection = TextRange(caret))
}

/** `[]()` with the caret positioned inside the label brackets. */
private fun applyLink(value: TextFieldValue): TextFieldValue {
    val sel = value.selection
    val text = value.text
    val start = sel.start.coerceIn(0, text.length)
    val end = sel.end.coerceIn(0, text.length)
    val selected = text.substring(start, end)
    val snippet = "[$selected]()"
    val newText = text.substring(0, start) + snippet + text.substring(end)
    val caret = start + 1 + selected.length // inside the empty `()` if no selection, or after the label
    val finalCaret = if (selected.isEmpty()) start + 1 else caret
    return value.copy(text = newText, selection = TextRange(finalCaret))
}

/** Drop a standalone block (e.g. horizontal rule) at the current line break. */
private fun applyBlockInsert(value: TextFieldValue, snippet: String): TextFieldValue {
    val text = value.text
    val sel = value.selection
    val pos = sel.end.coerceIn(0, text.length)
    val newText = text.substring(0, pos) + snippet + text.substring(pos)
    return value.copy(text = newText, selection = TextRange(pos + snippet.length))
}

// --- Previews --------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true, name = "Edit · empty")
@Composable
private fun EditBodyEmptyPreview() {
    MemoTheme {
        Scaffold(
            topBar = { TopAppBar(title = { Text("写点什么") }) },
        ) { inner ->
            EditBody(
                value = TextFieldValue(""),
                onValueChange = {},
                isSaving = false,
                mode = ViewMode.Edit,
                onModeChange = {},
                innerPadding = inner,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true, name = "Edit · typing")
@Composable
private fun EditBodyTypingPreview() {
    MemoTheme {
        Scaffold(
            topBar = { TopAppBar(title = { Text("写点什么") }) },
        ) { inner ->
            EditBody(
                value = TextFieldValue("今天学了 Glance widget。\n- 买菜\n- 跑步 30min"),
                onValueChange = {},
                isSaving = false,
                mode = ViewMode.Edit,
                onModeChange = {},
                innerPadding = inner,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true, name = "Edit · saving")
@Composable
private fun EditBodySavingPreview() {
    MemoTheme {
        Scaffold(
            topBar = { TopAppBar(title = { Text("写点什么") }) },
        ) { inner ->
            EditBody(
                value = TextFieldValue("保存中的示例内容"),
                onValueChange = {},
                isSaving = true,
                mode = ViewMode.Edit,
                onModeChange = {},
                innerPadding = inner,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true, name = "Read · with todos")
@Composable
private fun ReadBodyTodosPreview() {
    MemoTheme {
        Scaffold(
            topBar = { TopAppBar(title = { Text("写点什么") }) },
        ) { inner ->
            ReadBody(
                body = "## 14:30\n- [ ] 买菜\n- [ ] 跑步\n- [x] 写周报",
                onToggle = { _, _, _ -> },
                mode = ViewMode.Read,
                onModeChange = {},
                innerPadding = inner,
            )
        }
    }
}
