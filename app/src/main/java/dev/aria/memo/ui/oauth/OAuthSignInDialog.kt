package dev.aria.memo.ui.oauth

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.aria.memo.data.oauth.OAuthErrorKind

/**
 * Compose dialog that drives the GitHub OAuth device flow end to end.
 *
 * Four visual phases, all backed by [OAuthSignInViewModel]:
 *  1. spinner while we request the device code
 *  2. big monospace user code + "复制" + "打开浏览器" + "等待授权" spinner
 *  3. success tick + auto-dismiss after [SUCCESS_DISPLAY_MS]
 *  4. error message with a "重试" affordance
 *
 * The dialog owns no state of its own — re-opening it is safe, and dismissing
 * mid-flow calls [OAuthSignInViewModel.reset] so any in-flight poll is cancelled.
 */
private const val SUCCESS_DISPLAY_MS = 1200L

@Composable
fun OAuthSignInDialog(
    viewModel: OAuthSignInViewModel,
    clientId: String,
    onDismiss: () -> Unit,
    onSuccess: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current

    // Start the flow on first composition; if the caller re-enters the dialog
    // (e.g. after clicking 重试 outside this composable), `start` is idempotent
    // because it cancels the prior job.
    LaunchedEffect(clientId) {
        if (state is OAuthSignInState.Idle) {
            viewModel.start(clientId)
        }
    }

    // Auto-dismiss on success so the settings screen reflects the new PAT.
    LaunchedEffect(state) {
        if (state is OAuthSignInState.Completed) {
            kotlinx.coroutines.delay(SUCCESS_DISPLAY_MS)
            onSuccess()
        }
    }

    AlertDialog(
        onDismissRequest = {
            viewModel.reset()
            onDismiss()
        },
        title = { Text("用 GitHub 登录") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                when (val s = state) {
                    is OAuthSignInState.Idle, OAuthSignInState.RequestingCode -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                modifier = Modifier.height(18.dp),
                            )
                            Text("正在向 GitHub 申请设备码…")
                        }
                    }
                    is OAuthSignInState.WaitingForUser -> WaitingForUserContent(s, ctx)
                    is OAuthSignInState.Completed -> CompletedContent(s)
                    is OAuthSignInState.Failed -> FailedContent(s)
                }
            }
        },
        confirmButton = {
            val current = state
            when (current) {
                is OAuthSignInState.Failed -> {
                    TextButton(onClick = { viewModel.start(clientId) }) { Text("重试") }
                }
                is OAuthSignInState.Completed -> {
                    TextButton(onClick = onSuccess) { Text("完成") }
                }
                else -> {
                    // No confirm button during pending states — dismiss cancels.
                }
            }
        },
        dismissButton = {
            TextButton(onClick = {
                viewModel.reset()
                onDismiss()
            }) { Text("关闭") }
        },
    )
}

@Composable
private fun WaitingForUserContent(
    state: OAuthSignInState.WaitingForUser,
    ctx: Context,
) {
    Text(
        text = "在浏览器里打开 ${state.verificationUri}，输入下面的验证码完成登录。",
        style = MaterialTheme.typography.bodyMedium,
    )
    Text(
        text = state.userCode,
        style = MaterialTheme.typography.headlineMedium.copy(
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 32.sp,
            letterSpacing = 4.sp,
        ),
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Fixes #248 (UI-A #29): "打开浏览器" is the primary call to
        // action — the OAuth flow can't progress without it — so it
        // gets the filled Button. "复制" is a side-helper and renders
        // as OutlinedButton for proper M3 hierarchy.
        OutlinedButton(
            onClick = { copyToClipboard(ctx, state.userCode) },
            modifier = Modifier.weight(1f),
        ) { Text("复制") }
        Button(
            onClick = { openBrowser(ctx, state.verificationUri) },
            modifier = Modifier.weight(1f),
        ) { Text("打开浏览器") }
    }
    Spacer(Modifier.height(4.dp))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Fixes #233-style accidental ellipse: size = uniform 16dp.
        CircularProgressIndicator(
            strokeWidth = 2.dp,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = "等待授权中… (每 ${state.intervalSeconds}s 轮询，${state.expiresInSeconds}s 内有效)",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun CompletedContent(state: OAuthSignInState.Completed) {
    Text(
        text = "登录成功 ✓",
        style = MaterialTheme.typography.titleMedium,
    )
    Text(
        text = "已获取访问令牌${if (state.scope.isNotBlank()) "（scope: ${state.scope}）" else ""}，已写入本机设置。",
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun FailedContent(state: OAuthSignInState.Failed) {
    val humanMessage = when (state.kind) {
        OAuthErrorKind.ExpiredToken -> "验证码已过期，请重试。"
        OAuthErrorKind.AccessDenied -> "你在浏览器里取消了授权。"
        OAuthErrorKind.BadClientId -> "Client ID 不正确或已禁用设备流。"
        OAuthErrorKind.BadRequest -> "请求参数异常，请检查 Client ID。"
        OAuthErrorKind.Network -> "网络错误，请检查连接后重试。"
        OAuthErrorKind.Malformed -> "GitHub 返回了非预期的响应。"
        OAuthErrorKind.Unknown -> "未知错误。"
        // These two are internal to the poll loop — should not surface here,
        // but provide a safe fallback so a future refactor doesn't crash.
        OAuthErrorKind.AuthorizationPending, OAuthErrorKind.SlowDown ->
            "正在等待授权，请重试。"
    }
    Text(
        text = humanMessage,
        style = MaterialTheme.typography.titleMedium,
    )
    if (state.message.isNotBlank()) {
        Text(
            text = state.message,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

// ---- side-effect helpers -------------------------------------------------

private fun copyToClipboard(ctx: Context, text: String) {
    val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText("GitHub user code", text))
    Toast.makeText(ctx, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
}

private fun openBrowser(ctx: Context, uri: String) {
    // Medium fix: refuse anything that isn't plain https. GitHub always returns
    // https://github.com/login/device; if a man-in-the-middle or a malformed
    // mock server slipped in http:// / file:// / intent:// we'd otherwise hand
    // it straight to Intent.ACTION_VIEW.
    if (!uri.startsWith("https://", ignoreCase = true)) {
        Toast.makeText(ctx, "GitHub 返回的 URL 不合法，已阻止", Toast.LENGTH_SHORT).show()
        return
    }
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
    }.onFailure {
        Toast.makeText(ctx, "没有可用的浏览器", Toast.LENGTH_SHORT).show()
    }
}
