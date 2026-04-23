package dev.aria.memo.ui.help

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.aria.memo.ui.components.MemoEmptyState
import dev.aria.memo.ui.theme.MemoSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * In-app help screen. Reads `assets/user_guide.md` at runtime and pipes it through
 * the minimal markdown-to-compose renderer in this package.
 *
 * The guide source is shipped verbatim so future doc edits don't require a code
 * change — just `cp USER_GUIDE.md app/src/main/assets/user_guide.md` again.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    var content by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Asset IO off the main thread — the file is ~20KB but we still avoid any
    // frame-drop risk on slow devices.
    LaunchedEffect(Unit) {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                ctx.assets.open("user_guide.md").use { stream ->
                    BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).readText()
                }
            }
        }
        result.onSuccess { content = it }
            .onFailure { errorMessage = it.message ?: "无法加载使用说明" }
    }

    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("使用说明书") },
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
    ) { inner ->
        Box(
            modifier = Modifier
                .padding(inner)
                .fillMaxSize(),
            contentAlignment = Alignment.TopCenter,
        ) {
            when {
                errorMessage != null -> MemoEmptyState(
                    icon = Icons.Outlined.Info,
                    title = "无法加载使用说明",
                    subtitle = errorMessage,
                )
                content == null -> CircularProgressIndicator(
                    modifier = Modifier.padding(MemoSpacing.xxxl),
                )
                else -> {
                    // Medium fix: parseBlocks is ~20 KB + regex-heavy; memoize
                    // so tab-switch recompositions don't re-parse the guide.
                    val stable = content!!
                    val rememberedSource = remember(stable) { stable }
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = MemoSpacing.lg, vertical = MemoSpacing.md),
                    ) {
                        RenderMarkdown(
                            source = rememberedSource,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}
