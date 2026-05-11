package dev.aria.memo.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.core.net.toUri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.aria.memo.data.PreferencesStore
import dev.aria.memo.data.ServiceLocator
import dev.aria.memo.data.sync.SyncScheduler
import dev.aria.memo.data.oauth.GitHubOAuthClient
import dev.aria.memo.notify.NotificationPermissionBus
import dev.aria.memo.notify.QuickAddNotificationManager
import dev.aria.memo.ui.components.MemoCard
import dev.aria.memo.ui.components.MemoSectionHeader
import dev.aria.memo.ui.components.PatStatusCard
import dev.aria.memo.ui.oauth.OAuthSignInDialog
import dev.aria.memo.ui.oauth.OAuthSignInState
import dev.aria.memo.ui.oauth.OAuthSignInViewModel
import dev.aria.memo.ui.theme.MemoSpacing
import dev.aria.memo.ui.theme.MemoTheme
import dev.aria.memo.ui.theme.MemoThemeColors
import dev.aria.memo.util.CrashLogger
import dev.aria.memo.util.LogExporter
import dev.aria.memo.util.SyncStatusFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onOpenEditor: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenHelp: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var patVisible by rememberSaveable { mutableStateOf(false) }
    var aiKeyVisible by rememberSaveable { mutableStateOf(false) }
    // Review-W #3: gate the destructive switch-account flow behind a confirm
    // dialog so a misclick can't silently empty the user's pending notes.
    var showSwitchAccountDialog by rememberSaveable { mutableStateOf(false) }
    // Fixes #24: subscribe to the permission bus so denied state surfaces a guidance card.
    val notificationDenied by NotificationPermissionBus.denied.collectAsStateWithLifecycle()

    // FLAG_SECURE is scoped to the moment the PAT is visible in plaintext.
    // Other tabs (notes list, calendar) remain screen-capture-friendly.
    val ctx = LocalContext.current

    // Quick-add status-bar toggle — lives in PreferencesStore, independent of
    // the GitHub-config-focused SettingsStore.
    val preferencesStore = remember(ctx) { PreferencesStore(ctx.applicationContext) }
    val quickAddEnabled by preferencesStore.quickAddEnabled
        .collectAsStateWithLifecycle(initialValue = false)
    val themeMode by preferencesStore.themeMode
        .collectAsStateWithLifecycle(initialValue = "auto")
    val lastPullEpochMs by preferencesStore.lastPullEpochMs
        .collectAsStateWithLifecycle(initialValue = 0L)
    val lastPushEpochMs by preferencesStore.lastPushEpochMs
        .collectAsStateWithLifecycle(initialValue = 0L)

    // OAuth device-flow scaffolding. Kept local so the `ui/oauth/` package
    // doesn't need any of the SettingsScreen state.
    val oauthClient = remember { GitHubOAuthClient(ServiceLocator.httpClient()) }
    val oauthViewModel = remember {
        OAuthSignInViewModel(oauthClient, ServiceLocator.settingsStore)
    }
    // Severe fix: the VM is held in a plain `remember`, not a ViewModelStore, so
    // leaving and re-entering the settings tab would otherwise leave a polling
    // job alive on the old instance. Cancel it when the composable leaves.
    androidx.compose.runtime.DisposableEffect(oauthViewModel) {
        onDispose { oauthViewModel.reset() }
    }
    var showClientIdDialog by rememberSaveable { mutableStateOf(false) }
    var showOAuthDialog by rememberSaveable { mutableStateOf(false) }
    var pendingClientId by rememberSaveable { mutableStateOf("") }
    var clientIdDraft by rememberSaveable { mutableStateOf("") }
    // Sec-1 M2 fix (#99): SettingsScreen 全局 FLAG_SECURE — PAT/apiKey 即使不
    // toggle 明文,设置页本身就含 redacted 但能反推的输入提示 + repo 名 + provider
    // URL 等敏感配置。截图/任务卡片不应保留这些。整页 always-on 取代之前
    // toggle-based 局部覆盖,简化 + 防漏。
    androidx.compose.runtime.DisposableEffect(Unit) {
        val activity = ctx as? android.app.Activity
        val window = activity?.window
        window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) }
    }

    // Fix-X1: focus + scroll plumbing for the PAT field. The TextField hooks
    // both modifiers; the LaunchedEffect on `highlightPatRequest` plays the
    // pulse whenever the VM bumps the counter (post-OAuth-failure, post-fix
    // navigation, etc.).
    val patFocusRequester = remember { FocusRequester() }
    val patBringIntoView = remember { BringIntoViewRequester() }
    LaunchedEffect(state.highlightPatRequest) {
        if (state.highlightPatRequest > 0L) {
            // bringIntoView first so the field is on-screen before we steal
            // focus — focusing an off-screen element doesn't auto-scroll on
            // older Compose, leaving the keyboard up over a hidden target.
            runCatching { patBringIntoView.bringIntoView() }
            runCatching { patFocusRequester.requestFocus() }
        }
    }

    // Fix-X1: watch the OAuth dialog's state. When it lands in `Failed`, drop
    // the user back at the PAT input with the highlight pulse + a snackbar
    // hinting that the existing stored PAT may be the real culprit (expired
    // or scope mismatch).
    val oauthState by oauthViewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(oauthState) {
        if (oauthState is OAuthSignInState.Failed && showOAuthDialog) {
            showOAuthDialog = false
            viewModel.requestPatHighlight()
            scope.launch {
                snackbarHostState.showSnackbar(
                    "GitHub 登录失败: 上次的登录信息可能过期了, 请检查设置",
                )
            }
        }
    }

    // Fix-X1: when the screen first lands on already-configured config, fire a
    // background verification once so the StatusCard can swap the neutral
    // "已配置" hint for an authoritative ✓ / ⚠️.
    LaunchedEffect(state.loaded, state.isConfigured, state.patStatus) {
        if (state.loaded && state.isConfigured && state.patStatus is PatStatus.Unknown) {
            viewModel.testConnection()
        }
    }

    // Surface saved / error events as Snackbars then consume them.
    LaunchedEffect(state.lastSavedAt) {
        if (state.lastSavedAt != null) {
            scope.launch { snackbarHostState.showSnackbar("已保存 ✓") }
            viewModel.consumeSavedEvent()
        }
    }
    LaunchedEffect(state.errorMessage) {
        val msg = state.errorMessage
        if (msg != null) {
            scope.launch { snackbarHostState.showSnackbar(msg) }
            viewModel.consumeError()
        }
    }
    // Review-W #3 fix: dedicated success snackbar for the "切换账号" path so
    // the user gets explicit confirmation that the local sync queue was wiped
    // (this is the whole point of the safety dialog — silently swallowing the
    // outcome would defeat it).
    LaunchedEffect(state.accountSwitchedAt) {
        if (state.accountSwitchedAt != null) {
            scope.launch {
                snackbarHostState.showSnackbar("已切换账号，本地未同步备忘录已清除 ✓")
            }
            viewModel.consumeAccountSwitchedEvent()
        }
    }
    // AI "测试连接" outcomes piggy-back on the same snackbar host. Two distinct
    // strings so the user can tell which test ran (if more are added later).
    LaunchedEffect(state.aiTestResult) {
        val outcome = state.aiTestResult
        if (outcome != null) {
            val text = when (outcome) {
                is AiTestOutcome.Success -> "AI 连接成功 ✓"
                is AiTestOutcome.Failure -> "AI 连接失败：${outcome.message}"
            }
            scope.launch { snackbarHostState.showSnackbar(text) }
            viewModel.consumeAiTestResult()
        }
    }

    // Clicking "用 GitHub 登录" either asks for a client id (first run) or
    // jumps straight into the device-flow dialog (saved client id).
    val onOAuthClick: () -> Unit = {
        scope.launch {
            val saved = preferencesStore.githubClientId.first()
            if (saved.isBlank()) {
                clientIdDraft = ""
                showClientIdDialog = true
            } else {
                pendingClientId = saved
                showOAuthDialog = true
            }
        }
    }

    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            // Fix-7 #5 (UI-A report): Settings is a dense form page — the
            // 140dp LargeTopAppBar hero title was wasting vertical space
            // above the first field. Switched to the standard `TopAppBar`
            // which keeps the title crisp without pushing content down.
            TopAppBar(
                title = { Text("设置") },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        SettingsContent(
            state = state,
            patVisible = patVisible,
            onTogglePatVisibility = { patVisible = !patVisible },
            aiKeyVisible = aiKeyVisible,
            onToggleAiKeyVisibility = { aiKeyVisible = !aiKeyVisible },
            onPatChange = viewModel::onPatChange,
            onOwnerChange = viewModel::onOwnerChange,
            onRepoChange = viewModel::onRepoChange,
            onBranchChange = viewModel::onBranchChange,
            onSave = viewModel::save,
            onSwitchAccount = { showSwitchAccountDialog = true },
            onTestConnection = viewModel::testConnection,
            patFocusRequester = patFocusRequester,
            patBringIntoView = patBringIntoView,
            onAiProviderUrlChange = viewModel::onAiProviderUrlChange,
            onAiModelChange = viewModel::onAiModelChange,
            onAiApiKeyChange = viewModel::onAiApiKeyChange,
            onSaveAi = viewModel::saveAiConfig,
            onTestAi = viewModel::testAiConnection,
            onOpenEditor = onOpenEditor,
            onOpenHelp = onOpenHelp,
            onOAuthSignIn = onOAuthClick,
            innerPadding = innerPadding,
            notificationDenied = notificationDenied,
            onOpenNotificationSettings = { openAppNotificationSettings(ctx) },
            quickAddEnabled = quickAddEnabled,
            onQuickAddToggle = { requested ->
                scope.launch {
                    preferencesStore.setQuickAddEnabled(requested)
                    if (requested) {
                        QuickAddNotificationManager.show(ctx)
                    } else {
                        QuickAddNotificationManager.hide(ctx)
                    }
                }
            },
            themeMode = themeMode,
            onThemeModeChange = { mode ->
                scope.launch { preferencesStore.setThemeMode(mode) }
            },
            lastPullEpochMs = lastPullEpochMs,
            lastPushEpochMs = lastPushEpochMs,
            onSyncNow = {
                SyncScheduler.enqueuePullNow(ctx)
                SyncScheduler.enqueuePush(ctx)
                scope.launch {
                    snackbarHostState.showSnackbar("已请求立即同步")
                }
            },
            onExportLogs = {
                scope.launch {
                    try {
                        val (file, crashCount) = withContext(Dispatchers.IO) {
                            val f = LogExporter.captureToFile(ctx)
                            val n = dev.aria.memo.util.CrashLogger.crashDir(ctx)
                                .listFiles()?.size ?: 0
                            f to n
                        }
                        ctx.startActivity(LogExporter.shareIntent(ctx, file))
                        // Surface the crash count so the user knows the export
                        // is "interesting" (has historical crashes worth sharing)
                        // vs just-in-case (no crashes recorded).
                        if (crashCount > 0) {
                            snackbarHostState.showSnackbar(
                                "日志已生成，附带 $crashCount 条历史崩溃"
                            )
                        }
                    } catch (e: ActivityNotFoundException) {
                        snackbarHostState.showSnackbar("没找到能接收文件的应用")
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        // 不把 Java 类名 / 异常 message 透出给用户，会包含
                        // 类似 java.io.IOException 这样的技术栈词汇。日志已落
                        // adb logcat / crash dir 用于排查。
                        android.util.Log.w("SettingsScreen", "log export failed", e)
                        snackbarHostState.showSnackbar("导出日志没成功，请稍后再试")
                    }
                }
            },
        )
    }

    if (showClientIdDialog) {
        AlertDialog(
            onDismissRequest = { showClientIdDialog = false },
            title = { Text("填入 GitHub OAuth Client ID") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(MemoSpacing.sm)) {
                    Text(
                        text = "先去 GitHub Settings → Developer settings → OAuth Apps 注册一个应用，" +
                            "把它的 Client ID 填到这里（它是公开标识，不是 Secret，可以明文保存）。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = clientIdDraft,
                        onValueChange = { clientIdDraft = it },
                        label = { Text("Client ID") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = clientIdDraft.isNotBlank(),
                    onClick = {
                        val trimmed = clientIdDraft.trim()
                        scope.launch {
                            preferencesStore.setGithubClientId(trimmed)
                            pendingClientId = trimmed
                            showClientIdDialog = false
                            showOAuthDialog = true
                        }
                    },
                ) { Text("继续") }
            },
            dismissButton = {
                TextButton(onClick = { showClientIdDialog = false }) { Text("取消") }
            },
        )
    }

    if (showOAuthDialog && pendingClientId.isNotBlank()) {
        OAuthSignInDialog(
            viewModel = oauthViewModel,
            clientId = pendingClientId,
            onDismiss = { showOAuthDialog = false },
            onSuccess = {
                showOAuthDialog = false
                // Severe fix: `onPatChange` only updates UI state and marks it as
                // user-edited; if the user navigates away without pressing "保存",
                // the next save() would rewrite the persisted token with whatever
                // stale value happened to be in state. Calling reload() pulls the
                // freshly-persisted token (and owner/repo/branch) from
                // SettingsStore, giving the UI an authoritative snapshot.
                viewModel.reload()
                scope.launch { snackbarHostState.showSnackbar("已登录 GitHub，令牌已保存 ✓") }
            },
        )
    }

    // Review-W #3 fix: confirmation dialog for the "切换账号" flow. The
    // wording is deliberately blunt — we are about to *erase* every unsynced
    // local edit so the new account's repo doesn't inherit notes typed under
    // the previous identity. The destructive `Confirm` button uses the error
    // palette so it doesn't blur into the normal "继续" cadence.
    if (showSwitchAccountDialog) {
        AlertDialog(
            onDismissRequest = { showSwitchAccountDialog = false },
            title = { Text("切换 GitHub 账号？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(MemoSpacing.sm)) {
                    Text(
                        text = "切换账号会清除当前所有「本地已写但还没同步到 GitHub」" +
                            "的备忘录修改。这一步是为了避免上一个账号的草稿被推到新账号的 repo。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "如果只是想更新过期的登录信息、保留本地未同步内容, 请按 取消 然后用「保存」。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSwitchAccountDialog = false
                        viewModel.switchAccount()
                    },
                ) {
                    Text(
                        text = "确认切换并清除草稿",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showSwitchAccountDialog = false }) { Text("取消") }
            },
        )
    }
}

private fun openAppNotificationSettings(ctx: android.content.Context) {
    // Android 8+ deep-link to the app's notification channels page. Fixes #24.
    val intent = Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
        putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    ctx.startActivity(intent)
}

@Composable
private fun SettingsContent(
    state: SettingsUiState,
    patVisible: Boolean,
    onTogglePatVisibility: () -> Unit,
    onPatChange: (String) -> Unit,
    onOwnerChange: (String) -> Unit,
    onRepoChange: (String) -> Unit,
    onBranchChange: (String) -> Unit,
    onSave: () -> Unit,
    onOpenEditor: () -> Unit,
    innerPadding: PaddingValues,
    aiKeyVisible: Boolean = false,
    onToggleAiKeyVisibility: () -> Unit = {},
    onAiProviderUrlChange: (String) -> Unit = {},
    onAiModelChange: (String) -> Unit = {},
    onAiApiKeyChange: (String) -> Unit = {},
    onSaveAi: () -> Unit = {},
    onTestAi: () -> Unit = {},
    onOpenHelp: () -> Unit = {},
    onOAuthSignIn: () -> Unit = {},
    onSwitchAccount: () -> Unit = {},
    onTestConnection: () -> Unit = {},
    patFocusRequester: FocusRequester? = null,
    patBringIntoView: BringIntoViewRequester? = null,
    notificationDenied: Boolean = false,
    onOpenNotificationSettings: () -> Unit = {},
    quickAddEnabled: Boolean = false,
    onQuickAddToggle: (Boolean) -> Unit = {},
    themeMode: String = "auto",
    onThemeModeChange: (String) -> Unit = {},
    onExportLogs: () -> Unit = {},
    lastPullEpochMs: Long = 0L,
    lastPushEpochMs: Long = 0L,
    onSyncNow: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val ioScope = rememberCoroutineScope()
    // Bumped after a clear so the LaunchedEffect below re-reads the directory
    // and the indicator card disappears.
    var crashRefreshKey by remember { mutableIntStateOf(0) }
    var crashSummary by remember { mutableStateOf(CrashLogger.CrashSummary(0, null)) }
    LaunchedEffect(crashRefreshKey) {
        crashSummary = withContext(Dispatchers.IO) { CrashLogger.summary(ctx) }
    }
    Column(
        modifier = Modifier
            .padding(innerPadding)
            .padding(horizontal = MemoSpacing.xl, vertical = MemoSpacing.md)
            .verticalScroll(rememberScrollState())
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MemoSpacing.md),
    ) {
        // Settings 页 1121 行扁平堆 14 张卡, 用户找不到重点。重排+加 section
        // header 后, 顶部是"出问题需要立刻处理的"alert, 然后按"GitHub 账号 → AI
        // 助手 → 应用偏好 → 帮助与诊断"分组, 让用户能扫到自己要的。
        //
        // 没用 Card 容器套整个 section, 因为里面已经是 MemoCard 嵌套, 双层卡
        // 视觉太重; section header 是 labelLarge primary 色文字, 上下间距由
        // MemoSectionHeader 自己处理。

        // ── 顶部 Alerts (条件渲染, 没问题就不出现) ──
        if (notificationDenied) {
            NotificationPermissionCard(onOpenSettings = onOpenNotificationSettings)
        }
        if (crashSummary.count > 0) {
            CrashIndicatorCard(
                summary = crashSummary,
                onExport = onExportLogs,
                onClear = {
                    ioScope.launch {
                        withContext(Dispatchers.IO) { CrashLogger.clearAll(ctx) }
                        crashRefreshKey++
                    }
                },
            )
        }

        // ── GitHub 账号 (主功能, 用户进设置页的主要原因) ──
        MemoSectionHeader(text = "GitHub 账号")
        // Fix-X1: replaces the legacy StatusCard. The new card surfaces the
        // PAT *liveness* state machine (Unknown / Verifying / Valid / Invalid
        // / CheckFailed) instead of just "fields non-blank?", and exposes
        // "重新验证" + "用 GitHub 重新登录" actions inline so users have a
        // visible recovery path when the token gets revoked.
        PatStatusCard(
            state = state,
            onTestConnection = onTestConnection,
            onReauth = onOAuthSignIn,
        )

        // Fix-X1: chain the focus + bringIntoView modifiers so an external
        // `requestPatHighlight()` lands here. We default to a no-op `Modifier`
        // when the requesters are absent (Preview composables) so the
        // signature stays back-compat with the existing previews.
        val patFieldModifier = Modifier.fillMaxWidth().let { base ->
            val withFocus = if (patFocusRequester != null) base.focusRequester(patFocusRequester) else base
            if (patBringIntoView != null) withFocus.bringIntoViewRequester(patBringIntoView) else withFocus
        }
        OutlinedTextField(
            value = state.pat,
            onValueChange = onPatChange,
            label = { Text("GitHub PAT") },
            placeholder = { Text("ghp_ 或 github_pat_...") },
            singleLine = true,
            visualTransformation = if (patVisible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            isError = state.patError != null,
            trailingIcon = {
                IconButton(onClick = onTogglePatVisibility) {
                    Icon(
                        imageVector = if (patVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (patVisible) "隐藏 PAT" else "显示 PAT",
                    )
                }
            },
            supportingText = {
                Text(state.patError ?: "仅本机存储，不会上传到任何其它地方")
            },
            modifier = patFieldModifier,
        )

        // 加一个"如何获取 PAT"的小按钮直接跳到 GitHub 官方创建页, 之前用户
        // 看到 "ghp_ 或 github_pat_..." 完全不知道这是从哪里来的, 只能自己
        // Google 一通。https://github.com/settings/tokens 是 PAT 管理页 (登录
        // 后能看到 New token 按钮)。
        TextButton(
            onClick = {
                runCatching {
                    val intent = Intent(
                        Intent.ACTION_VIEW,
                        "https://github.com/settings/tokens".toUri(),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                }.onFailure {
                    // 没装浏览器之类极少数情况, 静默吞掉, 不弹错误打扰用户
                }
            },
            modifier = Modifier.align(Alignment.End),
        ) {
            Text("PAT 是什么 / 怎么获取？")
        }

        OutlinedButton(
            onClick = onOAuthSignIn,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("用 GitHub 扫码登录")
        }

        // 字段标签从原英文 GitHub 术语改成"中文 (English)" 双语:
        //   Owner（用户名或组织）→ 用户名（Owner）
        //   Repo                → 仓库名（Repo）
        //   Branch              → 分支（Branch）
        // 中文当主标签让普通用户一眼能看懂; 英文括号保留, 用户去 GitHub 网站
        // 找对应字段时仍能对上号 (URL 路径里的 owner/repo/branch 字眼)。
        OutlinedTextField(
            value = state.owner,
            onValueChange = onOwnerChange,
            label = { Text("用户名（Owner）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = state.repo,
            onValueChange = onRepoChange,
            label = { Text("仓库名（Repo）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = state.branch,
            onValueChange = onBranchChange,
            label = { Text("分支（Branch）") },
            placeholder = { Text("main") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(MemoSpacing.xs))

        Button(
            onClick = onSave,
            enabled = !state.isSaving && !state.isSwitchingAccount && state.loaded,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.isSaving) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp),
                )
            } else {
                Text("保存")
            }
        }

        // Review-W #3 fix: dedicated "切换账号" affordance.
        //
        // Disabled until the user has actually filled in a config (no point
        // switching to nothing) and during the in-flight switch / save (avoid
        // double submission). Uses an OutlinedButton so it visually reads as
        // "secondary, destructive" rather than competing with 保存.
        OutlinedButton(
            onClick = onSwitchAccount,
            enabled = !state.isSaving &&
                !state.isSwitchingAccount &&
                state.loaded &&
                state.isConfigured,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.isSwitchingAccount) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp),
                )
            } else {
                Text("切换 GitHub 账号")
            }
        }

        FilledTonalButton(
            onClick = onOpenEditor,
            enabled = state.isConfigured,
            modifier = Modifier.fillMaxWidth(),
        ) {
            // Fixes #246 (UI-A #16): use ButtonDefaults.IconSpacing
            // (8dp, the M3 standard) instead of the full-width spacer
            // and double-space-prefix hack the original layout used.
            Icon(
                imageVector = Icons.Filled.Edit,
                contentDescription = null,
                modifier = Modifier.size(androidx.compose.material3.ButtonDefaults.IconSize),
            )
            Spacer(Modifier.width(androidx.compose.material3.ButtonDefaults.IconSpacing))
            Text("立即写一条")
        }

        // SyncStatusCard 移到 GitHub 账号块的尾部 — 之前它出现在最顶部,
        // 但"上次上传 / 上次检查"只在配好账号后才有意义, 跟 PatStatusCard
        // 放一起更连贯。
        if (state.isConfigured) {
            SyncStatusCard(
                lastPushEpochMs = lastPushEpochMs,
                lastPullEpochMs = lastPullEpochMs,
                onSyncNow = onSyncNow,
            )
        }

        // ── AI 助手 ──
        MemoSectionHeader(text = "AI 助手")
        AiConfigSection(
            state = state,
            keyVisible = aiKeyVisible,
            onToggleKeyVisibility = onToggleAiKeyVisibility,
            onProviderUrlChange = onAiProviderUrlChange,
            onModelChange = onAiModelChange,
            onApiKeyChange = onAiApiKeyChange,
            onSave = onSaveAi,
            onTest = onTestAi,
        )

        // ── 应用偏好 (低频, 一次设置完基本不动) ──
        MemoSectionHeader(text = "应用偏好")
        QuickAddToggleCard(
            enabled = quickAddEnabled,
            onToggle = onQuickAddToggle,
        )
        ThemeChooserCard(
            mode = themeMode,
            onChange = onThemeModeChange,
        )

        // ── 帮助与诊断 ──
        MemoSectionHeader(text = "帮助与诊断")
        LogExportCard(onExport = onExportLogs)

        HelpEntryCard(onOpenHelp = onOpenHelp)
    }
}

@Composable
private fun AiConfigSection(
    state: SettingsUiState,
    keyVisible: Boolean,
    onToggleKeyVisibility: () -> Unit,
    onProviderUrlChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onSave: () -> Unit,
    onTest: () -> Unit,
) {
    // Block is a cohesive group — card gives it a visual boundary so the GitHub
    // fields above and the help card below don't blur together. Accent tint
    // reuses the tertiary role so it reads as "secondary feature", matching the
    // help card's styling conventions.
    MemoCard(accentColor = MaterialTheme.colorScheme.tertiary) {
        Column(verticalArrangement = Arrangement.spacedBy(MemoSpacing.sm)) {
            Text(
                text = "AI 配置",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.tertiary,
            )
            Text(
                // OpenAI-compatible endpoint 是技术黑话, 改成普通用户能 parse
                // 的描述; 加密存储/本机也少术语化。
                text = "支持 OpenAI、DeepSeek、Azure、本地 Ollama 等。密钥只存在这台手机。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // 字段标签从纯英文 (Provider URL / Model / API Key) 改成中文,
            // placeholder 仍保留具体英文例子让用户能对照原服务的文档。
            OutlinedTextField(
                value = state.aiProviderUrl,
                onValueChange = onProviderUrlChange,
                label = { Text("服务地址") },
                placeholder = { Text("https://api.openai.com/v1/chat/completions") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                isError = state.aiProviderUrlError != null,
                supportingText = state.aiProviderUrlError?.let { msg -> { Text(msg) } },
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = state.aiModel,
                onValueChange = onModelChange,
                label = { Text("模型") },
                placeholder = { Text("gpt-4o-mini / deepseek-chat / llama3") },
                singleLine = true,
                isError = state.aiModelError != null,
                supportingText = state.aiModelError?.let { msg -> { Text(msg) } },
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = state.aiApiKey,
                onValueChange = onApiKeyChange,
                label = { Text("密钥") },
                placeholder = { Text("sk-…") },
                singleLine = true,
                visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                isError = state.aiApiKeyError != null,
                trailingIcon = {
                    IconButton(onClick = onToggleKeyVisibility) {
                        Icon(
                            imageVector = if (keyVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (keyVisible) "隐藏密钥" else "显示密钥",
                        )
                    }
                },
                supportingText = state.aiApiKeyError?.let { msg -> { Text(msg) } },
                modifier = Modifier.fillMaxWidth(),
            )

            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MemoSpacing.sm),
            ) {
                Button(
                    onClick = onSave,
                    enabled = !state.isSavingAi && state.loaded,
                    modifier = Modifier.weight(1f),
                ) {
                    if (state.isSavingAi) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp),
                        )
                    } else {
                        Text("保存 AI 配置")
                    }
                }
                OutlinedButton(
                    onClick = onTest,
                    enabled = !state.isTestingAi && state.isAiConfigured,
                    modifier = Modifier.weight(1f),
                ) {
                    if (state.isTestingAi) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp),
                        )
                    } else {
                        Text("测试连接")
                    }
                }
            }
        }
    }
}

/**
 * Sync status — answers two distinct user questions:
 *  - "我刚改的笔记上传到 GitHub 了吗" → push timestamp (PushWorker.success
 *    with at least one row clean'd).
 *  - "其他设备的改动来了吗" → pull timestamp (PullWorker.success with no
 *    transient errors).
 *
 * Both timestamps live in [PreferencesStore] so they survive process
 * restart. 0L (never recorded) renders as "尚未上传" / "尚未检查".
 */
@Composable
private fun SyncStatusCard(
    lastPushEpochMs: Long,
    lastPullEpochMs: Long,
    onSyncNow: () -> Unit,
) {
    val nowMs = System.currentTimeMillis()
    val pushRel = SyncStatusFormatter.formatRelative(nowMs, lastPushEpochMs) ?: "尚未上传"
    val pullRel = SyncStatusFormatter.formatRelative(nowMs, lastPullEpochMs) ?: "尚未检查"
    MemoCard {
        Column(verticalArrangement = Arrangement.spacedBy(MemoSpacing.sm)) {
            Text(
                text = "同步状态",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "已上传到 GitHub：$pushRel",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "已检查 GitHub 更新：$pullRel  ·  默认 30 分钟自动检查",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Manual trigger — kicks both PullWorker (catch other-device
            // edits) and PushWorker (verify local edits landed) immediately.
            // KEEP policy collapses repeated taps so spamming the button
            // can't pile up workers; the SyncBanner reflects the result.
            FilledTonalButton(onClick = onSyncNow) {
                Text("立即同步")
            }
        }
    }
}

/**
 * Crash indicator — only appears when [CrashLogger] has persisted crash
 * files. Shows count + most-recent timestamp with two actions: export logs
 * (forwards to LogExportCard's same path) and clear, kept independent so
 * the user can decide whether to triage now or wipe and move on. Splitting
 * into two buttons also avoids a race between the async log capture (which
 * reads the crash files) and a hypothetical "export+clear" combined op.
 */
@Composable
private fun CrashIndicatorCard(
    summary: CrashLogger.CrashSummary,
    onExport: () -> Unit,
    onClear: () -> Unit,
) {
    val warningAccent = MemoThemeColors.warning
    val timeText = summary.latestEpochMs?.let { ms ->
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
            .format(java.util.Date(ms))
    } ?: "—"
    MemoCard(accentColor = warningAccent) {
        Column(verticalArrangement = Arrangement.spacedBy(MemoSpacing.sm)) {
            Text(
                text = "检测到 ${summary.count} 条崩溃记录",
                style = MaterialTheme.typography.titleMedium,
                color = warningAccent,
            )
            Text(
                text = "最近一次：$timeText  ·  导出后可发开发者排查；不再需要时可清空。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MemoSpacing.sm),
            ) {
                FilledTonalButton(
                    onClick = onExport,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("导出日志")
                }
                OutlinedButton(
                    onClick = onClear,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("清空记录")
                }
            }
        }
    }
}

/**
 * Log export card — taps generate a timestamped .txt of the current process's
 * recent logcat into cacheDir/logs/ then opens a share sheet so the user can
 * forward it to the dev for triage. Implementation lives in [LogExporter];
 * the card just renders the button + explanatory copy.
 */
@Composable
private fun LogExportCard(onExport: () -> Unit) {
    MemoCard {
        Column(verticalArrangement = Arrangement.spacedBy(MemoSpacing.sm)) {
            Text(
                text = "日志导出",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "遇到问题时点这里：会把最近运行日志生成 .txt，方便发给开发者排查。" +
                    "日志只包含本应用的运行记录，不含密钥。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FilledTonalButton(onClick = onExport) {
                Text("导出最近日志")
            }
        }
    }
}

@Composable
private fun HelpEntryCard(onOpenHelp: () -> Unit) {
    // User feedback called out missing in-app docs — this card opens the bundled
    // user_guide.md in HelpScreen without leaving the app.
    MemoCard(
        accentColor = MaterialTheme.colorScheme.tertiary,
        onClick = onOpenHelp,
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.MenuBook,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
            )
            Text(
                text = "查看使用说明书",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = MemoSpacing.sm),
            )
        }
    }
}

@Composable
private fun StatusCard(state: SettingsUiState) {
    val configured = state.isConfigured
    val accent = if (configured) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.error
    }
    MemoCard(accentColor = accent) {
        Text(
            text = if (configured) "当前配置已就绪 ✓" else "还缺：${state.missingFields.joinToString("、")}",
            style = MaterialTheme.typography.titleMedium,
            color = accent,
        )
        Text(
            text = "备注会追加到 ${state.owner.ifBlank { "<owner>" }}/${state.repo.ifBlank { "<repo>" }} 的 ${state.branch.ifBlank { "main" }} 分支",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/**
 * Theme picker — three FilterChips inside a MemoCard. Mode strings
 * `auto` / `light` / `dark` mirror what `PreferencesStore.themeMode`
 * persists; MemoThemeWithMode reads the same value at AppNav root and
 * flips the palette without an Activity restart.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ThemeChooserCard(
    mode: String,
    onChange: (String) -> Unit,
) {
    MemoCard {
        Column(verticalArrangement = Arrangement.spacedBy(MemoSpacing.sm)) {
            Text(
                text = "外观主题",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "选「跟随系统」会随系统暗色模式切换；选「亮」/「暗」固定一种。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MemoSpacing.xs),
            ) {
                listOf("auto" to "跟随系统", "light" to "亮", "dark" to "暗").forEach { (value, label) ->
                    androidx.compose.material3.FilterChip(
                        selected = mode == value,
                        onClick = { onChange(value) },
                        label = { Text(label) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickAddToggleCard(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    MemoCard {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.padding(end = MemoSpacing.md).weight(1f)) {
                Text(
                    text = "常驻通知栏快速入口",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "在通知栏常驻一条低优先级通知，点一下直接打开写备忘。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun NotificationPermissionCard(onOpenSettings: () -> Unit) {
    // Fixes #24: user has denied POST_NOTIFICATIONS — reminders won't fire.
    // Warm amber accent so it reads as "heads up", not "error".
    // Fix-7 #1 (UI-A report): was hardcoded `Color(0xFFB8860B)`; lifted to
    // theme so dark mode brightens the amber (`MemoDarkWarning`) instead of
    // reusing the dim light-mode value against a dark surface.
    val amberAccent = MemoThemeColors.warning
    MemoCard(accentColor = amberAccent) {
        Column(verticalArrangement = Arrangement.spacedBy(MemoSpacing.sm)) {
            Text(
                text = "通知权限未开启，日程提醒不会响",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "在系统设置里允许通知后，已排期的提醒就能按时响起。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onOpenSettings) { Text("去系统设置") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true, name = "Settings · empty")
@Composable
private fun SettingsContentEmptyPreview() {
    MemoTheme {
        Scaffold(
            topBar = {
                TopAppBar(title = { Text("Memo Widget · 设置") })
            },
        ) { inner ->
            SettingsContent(
                state = SettingsUiState(loaded = true),
                patVisible = false,
                onTogglePatVisibility = {},
                onPatChange = {},
                onOwnerChange = {},
                onRepoChange = {},
                onBranchChange = {},
                onSave = {},
                onOpenEditor = {},
                innerPadding = inner,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true, name = "Settings · filled")
@Composable
private fun SettingsContentFilledPreview() {
    MemoTheme {
        Scaffold(
            topBar = {
                TopAppBar(title = { Text("Memo Widget · 设置") })
            },
        ) { inner ->
            SettingsContent(
                state = SettingsUiState(
                    pat = "ghp_preview_token_abc",
                    owner = "qqzlqqzlqqzl",
                    repo = "memos",
                    branch = "main",
                    loaded = true,
                ),
                patVisible = false,
                onTogglePatVisibility = {},
                onPatChange = {},
                onOwnerChange = {},
                onRepoChange = {},
                onBranchChange = {},
                onSave = {},
                onOpenEditor = {},
                innerPadding = inner,
            )
        }
    }
}
