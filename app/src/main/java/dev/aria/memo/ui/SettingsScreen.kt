package dev.aria.memo.ui

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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import dev.aria.memo.ui.theme.MemoTheme
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onOpenEditor: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var patVisible by remember { mutableStateOf(false) }

    // FLAG_SECURE is scoped to the moment the PAT is visible in plaintext.
    // Other tabs (notes list, calendar) remain screen-capture-friendly.
    val ctx = LocalContext.current
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
            innerPadding = innerPadding,
        )
    }
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
) {
    Column(
        modifier = Modifier
            .padding(innerPadding)
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .verticalScroll(rememberScrollState())
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
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
