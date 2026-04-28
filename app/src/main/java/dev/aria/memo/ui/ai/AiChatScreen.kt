package dev.aria.memo.ui.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.aria.memo.data.ai.AiContextMode
import dev.aria.memo.ui.components.MemoEmptyState
import dev.aria.memo.ui.theme.MemoShapes
import dev.aria.memo.ui.theme.MemoSpacing

/**
 * AI chat screen. Three layout zones from top to bottom:
 *  - [TopAppBar] with a back arrow and the screen title.
 *  - Context-mode [FilterChip] row (None / Current note / All notes). The
 *    "Current note" chip is only shown when the screen was opened with a
 *    `noteUid` nav arg — legacy day-files don't carry a uid so they can only
 *    participate via the "All notes" bucket.
 *  - Scrolling message transcript plus a pinned composer row at the bottom.
 *
 * When the user hasn't configured a provider yet the entire body is replaced
 * with [MemoEmptyState] nudging them to the Settings tab — matches the same
 * pattern [dev.aria.memo.ui.notelist.NoteListScreen] uses for the "no notes"
 * state so the affordance reads consistently.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiChatScreen(
    viewModel: AiChatViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    val listState = rememberLazyListState()

    // Surface errors via Snackbar so transient network/auth failures don't hide
    // the transcript. We clear through the VM so re-sending doesn't immediately
    // re-fire the banner (error state is one-shot; the user either retries or
    // closes the screen).
    LaunchedEffect(state.error) {
        val msg = state.error
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearError()
        }
    }

    // Bug-2 #169 fix: auto-scroll 仅在用户**已在底部**时触发,否则用户在阅读
    // 历史会被强制拽走。判定:lastVisibleItemIndex 在 lastIndex-1 之内 = 在底部。
    LaunchedEffect(state.messages.size, state.isSending) {
        if (state.messages.isNotEmpty()) {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalSize = listState.layoutInfo.totalItemsCount
            // 用户在底部 (距离最后一项不超过 1) 才自动跟随;在历史阅读则不动。
            if (totalSize == 0 || lastVisible >= totalSize - 2) {
                listState.animateScrollToItem(state.messages.lastIndex)
            }
        }
    }

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            // Fix-7 #5 (UI-A report): AI Chat is a conversation view; the
            // bubble list needs vertical room, not a 140dp hero title. Plain
            // TopAppBar keeps back arrow + screen title on one compact row.
            TopAppBar(
                title = { Text("AI 助手") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { inner ->
        if (!state.isConfigured) {
            Box(modifier = Modifier.fillMaxSize().padding(inner)) {
                MemoEmptyState(
                    icon = Icons.Filled.Settings,
                    title = "未配置 AI",
                    subtitle = "在设置页填入 Provider URL / API Key / Model",
                )
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
        ) {
            ContextModeRow(
                currentMode = state.contextMode,
                hasCurrentNote = state.hasCurrentNote,
                onSelect = viewModel::setContextMode,
            )

            if (state.messages.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    MemoEmptyState(
                        icon = Icons.Filled.Settings,
                        title = "还没有对话",
                        subtitle = "在下方输入你的问题开始聊天",
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(MemoSpacing.sm),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = MemoSpacing.lg),
                ) {
                    items(
                        items = state.messages,
                        key = { msg -> "${msg.timestamp}-${msg.role}-${msg.content.hashCode()}" },
                    ) { msg ->
                        ChatMessageBubble(role = msg.role, content = msg.content)
                    }
                }
            }

            ComposerRow(
                input = state.input,
                isSending = state.isSending,
                onInputChange = viewModel::setInput,
                onSend = viewModel::send,
            )
        }
    }
}

/**
 * Renders the three-chip context selector. The "当前笔记" chip is conditional
 * — see [AiChatViewModel.UiState.hasCurrentNote] — so tab-level entries don't
 * show a no-op third option.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContextModeRow(
    currentMode: AiContextMode,
    hasCurrentNote: Boolean,
    onSelect: (AiContextMode) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MemoSpacing.lg, vertical = MemoSpacing.sm),
        horizontalArrangement = Arrangement.spacedBy(MemoSpacing.sm),
    ) {
        FilterChip(
            selected = currentMode == AiContextMode.NONE,
            onClick = { onSelect(AiContextMode.NONE) },
            label = { Text("无上下文") },
            shape = MemoShapes.button,
        )
        if (hasCurrentNote) {
            FilterChip(
                selected = currentMode == AiContextMode.CURRENT_NOTE,
                onClick = { onSelect(AiContextMode.CURRENT_NOTE) },
                label = { Text("当前笔记") },
                shape = MemoShapes.button,
            )
        }
        FilterChip(
            selected = currentMode == AiContextMode.ALL_NOTES,
            onClick = { onSelect(AiContextMode.ALL_NOTES) },
            label = { Text("全部笔记") },
            shape = MemoShapes.button,
        )
    }
}

/**
 * Bottom composer. Send button swaps to a [CircularProgressIndicator] while
 * [isSending] is true — the VM also refuses double-sends, but visually
 * collapsing the affordance keeps the user from mashing the button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ComposerRow(
    input: String,
    isSending: Boolean,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    Surface(
        tonalElevation = 2.dp,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = MemoSpacing.lg,
                    vertical = MemoSpacing.sm,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                placeholder = { Text("输入你的问题…") },
                enabled = !isSending,
                shape = MemoShapes.button,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = MemoSpacing.sm),
                maxLines = 6,
            )
            if (isSending) {
                Box(
                    modifier = Modifier.size(48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                    )
                }
            } else {
                IconButton(
                    onClick = onSend,
                    enabled = input.isNotBlank(),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "发送",
                        tint = if (input.isNotBlank())
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * A single chat bubble. User turns right-align with primary container; the
 * assistant's replies left-align with surfaceVariant. Max-width caps out at
 * 85% of the row so very long assistant replies still wrap tightly rather
 * than hugging both edges like a paragraph block.
 */
@Composable
private fun ChatMessageBubble(role: String, content: String) {
    val isUser = role == "user"
    val bubbleColor = if (isUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val textColor = if (isUser) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            shape = MemoShapes.card,
            color = bubbleColor,
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Text(
                text = content,
                style = MaterialTheme.typography.bodyMedium,
                color = textColor,
                modifier = Modifier.padding(
                    horizontal = MemoSpacing.lg,
                    vertical = MemoSpacing.md,
                ),
            )
        }
    }
}
