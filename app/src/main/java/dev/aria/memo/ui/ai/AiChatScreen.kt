package dev.aria.memo.ui.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Psychology
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
                // 之前文案 "去「设置」填好 Provider URL / API Key / 模型名"
                // 用术语轰炸用户。改成给具体例子 (OpenAI/DeepSeek/Ollama),
                // 让没接触过的人至少有个抓手，知道这玩意儿是干嘛的。
                MemoEmptyState(
                    icon = Icons.Outlined.Psychology,
                    title = "AI 还没接通",
                    subtitle = "去「设置」连一个 AI 服务（OpenAI、DeepSeek、本地 Ollama 都可以）",
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
                // 空对话态: 之前只是 "在下方输入框开始问吧" 一句空话, 用户对着
                // 输入框想不出问什么。给 3-4 个 quick prompt 可点, 既是引导
                // 也是示范——按一下就发出去, 立刻看到 AI 怎么用。 hasCurrentNote
                // 时多给一个针对当前笔记的 prompt; 否则只给通用的。
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = MemoSpacing.lg),
                    verticalArrangement = Arrangement.Center,
                ) {
                    MemoEmptyState(
                        icon = Icons.AutoMirrored.Outlined.Chat,
                        title = "还没有对话",
                        subtitle = "试试下面这些, 或者直接在下方输入框开问",
                    )
                    Spacer(modifier = Modifier.size(MemoSpacing.md))
                    QuickPromptList(
                        hasCurrentNote = state.hasCurrentNote,
                        onPick = { prompt ->
                            viewModel.setInput(prompt)
                            viewModel.send()
                        },
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
                    itemsIndexed(
                        items = state.messages,
                        key = { idx, msg -> "$idx-${msg.timestamp}" },
                    ) { _, msg ->
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
 * Quick-prompt suggestions shown on the empty conversation state. Tapping one
 * pipes the text through [AiChatViewModel.setInput] + [AiChatViewModel.send]
 * so the user gets an immediate "this is what AI 助手 looks like" demo
 * instead of staring at a blank composer with no idea what to type.
 *
 * The "总结这条笔记" prompt is only surfaced when the screen was opened with
 * a noteUid (hasCurrentNote=true) — without a focal note it has no referent.
 */
@Composable
private fun QuickPromptList(
    hasCurrentNote: Boolean,
    onPick: (String) -> Unit,
) {
    val prompts = buildList {
        if (hasCurrentNote) add("帮我总结这条笔记的要点")
        add("我最近写了什么？")
        add("把今天的待办整理一下")
        add("用我的笔记给我写一段周报")
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(MemoSpacing.sm),
    ) {
        prompts.forEach { prompt ->
            Surface(
                onClick = { onPick(prompt) },
                shape = MemoShapes.card,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = prompt,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(
                        horizontal = MemoSpacing.lg,
                        vertical = MemoSpacing.md,
                    ),
                )
            }
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
    // Fixes #227 (UI-A #19): the previous hard-coded 320dp cap meant
    // a 400dp+ tablet kept the bubble at phone width, leaving the
    // transcript hugging the leading edge of the screen. Cap at 85%
    // of the available row width so phone bubbles stay tight (≈320dp
    // on a 360dp-wide phone) and tablet bubbles grow with the device.
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier = Modifier.fillMaxWidth(),
    ) {
        val cap = maxWidth * 0.85f
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        ) {
            Surface(
                shape = MemoShapes.card,
                color = bubbleColor,
                modifier = Modifier.widthIn(max = cap),
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
}
