package dev.aria.memo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.aria.memo.ui.theme.MemoTheme
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditScreen(
    viewModel: EditViewModel,
    onSaved: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var body by rememberSaveable { mutableStateOf("") }

    // React to terminal save states.
    LaunchedEffect(state) {
        when (val s = state) {
            is SaveState.Success -> {
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

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("写点什么") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        EditBody(
            body = body,
            onBodyChange = { body = it },
            isSaving = state is SaveState.Saving,
            onSave = { viewModel.save(body) },
            innerPadding = innerPadding,
        )
    }
}

@Composable
private fun EditBody(
    body: String,
    onBodyChange: (String) -> Unit,
    isSaving: Boolean,
    onSave: () -> Unit,
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
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                value = body,
                onValueChange = onBodyChange,
                label = { Text("正文（Markdown 友好）") },
                minLines = 8,
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                onClick = onSave,
                enabled = body.isNotBlank() && !isSaving,
                contentPadding = ButtonDefaults.ContentPadding,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            ) {
                Text(
                    text = if (isSaving) "保存中..." else "保存",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }

        if (isSaving) {
            // Full-screen scrim + centered spinner while the repository talks
            // to GitHub. Prevents accidental re-taps / edits mid-save.
            Surface(
                color = Color.Black.copy(alpha = 0.25f),
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

@Preview(showBackground = true, name = "Edit · empty")
@Composable
private fun EditBodyEmptyPreview() {
    MemoTheme {
        Scaffold(
            topBar = {
                androidx.compose.material3.TopAppBar(title = { Text("写点什么") })
            },
        ) { inner ->
            EditBody(
                body = "",
                onBodyChange = {},
                isSaving = false,
                onSave = {},
                innerPadding = inner,
            )
        }
    }
}

@Preview(showBackground = true, name = "Edit · typing")
@Composable
private fun EditBodyTypingPreview() {
    MemoTheme {
        Scaffold(
            topBar = {
                androidx.compose.material3.TopAppBar(title = { Text("写点什么") })
            },
        ) { inner ->
            EditBody(
                body = "今天学了 Glance widget。\n- 买菜\n- 跑步 30min",
                onBodyChange = {},
                isSaving = false,
                onSave = {},
                innerPadding = inner,
            )
        }
    }
}

@Preview(showBackground = true, name = "Edit · saving")
@Composable
private fun EditBodySavingPreview() {
    MemoTheme {
        Scaffold(
            topBar = {
                androidx.compose.material3.TopAppBar(title = { Text("写点什么") })
            },
        ) { inner ->
            EditBody(
                body = "保存中的示例内容",
                onBodyChange = {},
                isSaving = true,
                onSave = {},
                innerPadding = inner,
            )
        }
    }
}
