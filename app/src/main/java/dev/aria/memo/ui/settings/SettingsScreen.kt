package dev.aria.memo.ui.settings

import android.content.Intent
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import dev.aria.memo.data.oauth.GitHubOAuthClient
import dev.aria.memo.notify.NotificationPermissionBus
import dev.aria.memo.notify.QuickAddNotificationManager
import dev.aria.memo.ui.components.MemoCard
import dev.aria.memo.ui.components.PatStatusCard
import dev.aria.memo.ui.oauth.OAuthSignInDialog
import dev.aria.memo.ui.oauth.OAuthSignInState
import dev.aria.memo.ui.oauth.OAuthSignInViewModel
import dev.aria.memo.ui.theme.MemoSpacing
import dev.aria.memo.ui.theme.MemoTheme
import dev.aria.memo.ui.theme.MemoThemeColors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onOpenEditor: () -> Unit,
    onOpenHelp: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var patVisible by remember { mutableStateOf(false) }
    var aiKeyVisible by remember { mutableStateOf(false) }
    // Review-W #3: gate the destructive switch-account flow behind a confirm
    // dialog so a misclick can't silently empty the user's pending notes.
    var showSwitchAccountDialog by remember { mutableStateOf(false) }
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
    var showClientIdDialog by remember { mutableStateOf(false) }
    var showOAuthDialog by remember { mutableStateOf(false) }
    var pendingClientId by remember { mutableStateOf("") }
    var clientIdDraft by remember { mutableStateOf("") }
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
                    "GitHub 登录失败：PAT 可能已过期或权限不足，请检查设置",
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
                title = { Text("Memo Widget · 设置") },
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
                        text = "如果只是想更新过期的 PAT、保留本地未同步内容，请按 取消 然后用「保存」。",
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
) {
    Column(
        modifier = Modifier
            .padding(innerPadding)
            .padding(horizontal = MemoSpacing.xl, vertical = MemoSpacing.md)
            .verticalScroll(rememberScrollState())
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MemoSpacing.md),
    ) {
        if (notificationDenied) {
            NotificationPermissionCard(onOpenSettings = onOpenNotificationSettings)
        }
        QuickAddToggleCard(
            enabled = quickAddEnabled,
            onToggle = onQuickAddToggle,
        )
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

        OutlinedButton(
            onClick = onOAuthSignIn,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("用 GitHub 登录（Device Flow）")
        }

        OutlinedTextField(
            value = state.owner,
            onValueChange = onOwnerChange,
            label = { Text("Owner（用户名或组织）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = state.repo,
            onValueChange = onRepoChange,
            label = { Text("Repo") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = state.branch,
            onValueChange = onBranchChange,
            label = { Text("Branch") },
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
                text = "支持 OpenAI / DeepSeek / Azure / ollama 等 OpenAI-compatible endpoint。" +
                    "密钥仅保存在本机加密存储中。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = state.aiProviderUrl,
                onValueChange = onProviderUrlChange,
                label = { Text("Provider URL") },
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
                label = { Text("Model") },
                placeholder = { Text("gpt-4o-mini / deepseek-chat / llama3") },
                singleLine = true,
                isError = state.aiModelError != null,
                supportingText = state.aiModelError?.let { msg -> { Text(msg) } },
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = state.aiApiKey,
                onValueChange = onApiKeyChange,
                label = { Text("API Key") },
                placeholder = { Text("sk-…") },
                singleLine = true,
                visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                isError = state.aiApiKeyError != null,
                trailingIcon = {
                    IconButton(onClick = onToggleKeyVisibility) {
                        Icon(
                            imageVector = if (keyVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (keyVisible) "隐藏 API Key" else "显示 API Key",
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
