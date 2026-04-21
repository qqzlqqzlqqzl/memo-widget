package dev.aria.memo.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import dev.aria.memo.ui.oauth.OAuthSignInDialog
import dev.aria.memo.ui.oauth.OAuthSignInViewModel
import dev.aria.memo.ui.theme.MemoTheme
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
    androidx.compose.runtime.DisposableEffect(patVisible) {
        val activity = ctx as? android.app.Activity
        val window = activity?.window
        if (patVisible) {
            window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        }
        onDispose { window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) }
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

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(title = { Text("Memo Widget · 设置") })
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        SettingsContent(
            state = state,
            patVisible = patVisible,
            onTogglePatVisibility = { patVisible = !patVisible },
            onPatChange = viewModel::onPatChange,
            onOwnerChange = viewModel::onOwnerChange,
            onRepoChange = viewModel::onRepoChange,
            onBranchChange = viewModel::onBranchChange,
            onSave = viewModel::save,
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
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
    onOpenHelp: () -> Unit = {},
    onOAuthSignIn: () -> Unit = {},
    notificationDenied: Boolean = false,
    onOpenNotificationSettings: () -> Unit = {},
    quickAddEnabled: Boolean = false,
    onQuickAddToggle: (Boolean) -> Unit = {},
) {
    Column(
        modifier = Modifier
            .padding(innerPadding)
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .verticalScroll(rememberScrollState())
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (notificationDenied) {
            NotificationPermissionCard(onOpenSettings = onOpenNotificationSettings)
        }
        QuickAddToggleCard(
            enabled = quickAddEnabled,
            onToggle = onQuickAddToggle,
        )
        StatusCard(state = state)

        OutlinedTextField(
            value = state.pat,
            onValueChange = onPatChange,
            label = { Text("GitHub PAT") },
            placeholder = { Text("ghp_ 或 github_pat_...") },
            singleLine = true,
            visualTransformation = if (patVisible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                IconButton(onClick = onTogglePatVisibility) {
                    Icon(
                        imageVector = if (patVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (patVisible) "隐藏 PAT" else "显示 PAT",
                    )
                }
            },
            supportingText = { Text("仅本机存储，不会上传到任何其它地方") },
            modifier = Modifier.fillMaxWidth(),
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

        Spacer(Modifier.height(4.dp))

        Button(
            onClick = onSave,
            enabled = !state.isSaving && state.loaded,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.isSaving) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.height(18.dp),
                )
            } else {
                Text("保存")
            }
        }

        FilledTonalButton(
            onClick = onOpenEditor,
            enabled = state.isConfigured,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.Edit, contentDescription = null)
            Spacer(Modifier.height(0.dp))
            Text("  立即写一条", modifier = Modifier.padding(start = 4.dp))
        }

        HelpEntryCard(onOpenHelp = onOpenHelp)
    }
}

@Composable
private fun HelpEntryCard(onOpenHelp: () -> Unit) {
    // User feedback called out missing in-app docs — this card opens the bundled
    // user_guide.md in HelpScreen without leaving the app.
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ),
        onClick = onOpenHelp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.MenuBook,
                contentDescription = null,
            )
            Text(
                text = "  📖 查看使用说明书",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Composable
private fun StatusCard(state: SettingsUiState) {
    val configured = state.isConfigured
    val colors = if (configured) {
        CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    } else {
        CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
    Card(colors = colors, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = if (configured) "当前配置已就绪 ✓" else "还缺：${state.missingFields.joinToString("、")}",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "备注会追加到 ${state.owner.ifBlank { "<owner>" }}/${state.repo.ifBlank { "<repo>" }} 的 ${state.branch.ifBlank { "main" }} 分支",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun QuickAddToggleCard(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.padding(end = 12.dp)) {
                Text(
                    text = "常驻通知栏快速入口",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "在通知栏常驻一条低优先级通知，点一下直接打开写备忘。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun NotificationPermissionCard(onOpenSettings: () -> Unit) {
    // Fixes #24: user has denied POST_NOTIFICATIONS — reminders won't fire.
    // Use a warm amber palette so it reads as "heads up", not "error".
    val amber = androidx.compose.ui.graphics.Color(0xFFFFF4CC)
    val onAmber = androidx.compose.ui.graphics.Color(0xFF5A4A00)
    Card(
        colors = CardDefaults.cardColors(containerColor = amber, contentColor = onAmber),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "通知权限未开启，日程提醒不会响",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "在系统设置里允许通知后，已排期的提醒就能按时响起。",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = onOpenSettings) { Text("去系统设置") }
        }
    }
}

@Preview(showBackground = true, name = "Settings · empty")
@Composable
private fun SettingsContentEmptyPreview() {
    MemoTheme {
        Scaffold(
            topBar = {
                androidx.compose.material3.TopAppBar(title = { Text("Memo Widget · 设置") })
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

@Preview(showBackground = true, name = "Settings · filled")
@Composable
private fun SettingsContentFilledPreview() {
    MemoTheme {
        Scaffold(
            topBar = {
                androidx.compose.material3.TopAppBar(title = { Text("Memo Widget · 设置") })
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
