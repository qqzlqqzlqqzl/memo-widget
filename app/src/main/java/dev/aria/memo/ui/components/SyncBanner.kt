package dev.aria.memo.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import dev.aria.memo.BuildConfig
import dev.aria.memo.data.ErrorCode
import dev.aria.memo.data.sync.SyncStatus
import dev.aria.memo.ui.theme.MemoSpacing
import dev.aria.memo.ui.theme.MemoTheme

/**
 * Top-of-list banner that surfaces a transient sync error and gives the user
 * a clear next action.
 *
 * The text is mapped per [ErrorCode] (was inlined in NoteListScreen with the
 * same logic; pulled out so other screens can reuse it and so it has a unit
 * surface for Compose UI tests). For the two errors the user can act on —
 * `UNAUTHORIZED` (PAT expired / scope wrong) and `NOT_CONFIGURED` (never
 * filled in PAT yet) — the banner shows a "去设置" shortcut next to "知道了".
 * Other codes (`NETWORK` / `CONFLICT` / `NOT_FOUND` / `UNKNOWN`) only get
 * dismiss because there's nothing actionable from here.
 *
 * In debug builds, the raw [SyncStatus.Error.message] is appended in
 * parentheses so a developer adb-pulling logcat can correlate the friendly
 * text with the underlying repo error. Release builds hide it.
 */
@Composable
fun SyncBanner(
    status: SyncStatus,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val err = status as? SyncStatus.Error ?: return
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MemoSpacing.lg, vertical = MemoSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MemoSpacing.sm),
        ) {
            Icon(
                imageVector = Icons.Filled.SyncProblem,
                contentDescription = null,
            )
            // Banner 文案改成大白话、跟 onboarding/widget 的"连仓库"措辞一致;
            // PAT/认证/并发冲突/远程文件 这些纯术语换成"GitHub 拒绝了 / 自动处理了 /
            // 找不到 / 还没连仓库"。每条都说清"问题在哪、用户是不是要做什么"。
            val friendlyText = when (err.code) {
                ErrorCode.UNAUTHORIZED -> "GitHub 拒绝了, 多半是登录信息过期或权限不够"
                ErrorCode.NETWORK -> "网络不太行, 稍后会自动重试"
                ErrorCode.CONFLICT -> "和云端有冲突, 已经自动处理了"
                ErrorCode.NOT_FOUND -> "远端找不到这个文件"
                ErrorCode.NOT_CONFIGURED -> "还没连仓库, 去设置里连一个吧"
                ErrorCode.UNKNOWN -> "同步出了点问题"
            } + err.message.takeIf { BuildConfig.DEBUG }?.let { "（$it）" }.orEmpty()
            Text(
                text = friendlyText,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            if (err.code == ErrorCode.UNAUTHORIZED || err.code == ErrorCode.NOT_CONFIGURED) {
                TextButton(onClick = onOpenSettings) {
                    Text("去设置", color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
            TextButton(onClick = onDismiss) {
                Text("知道了", color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}

@Preview(showBackground = true, name = "SyncBanner · auth error")
@Composable
private fun SyncBannerAuthPreview() {
    MemoTheme {
        SyncBanner(
            status = SyncStatus.Error(ErrorCode.UNAUTHORIZED, "GitHub 拒绝访问：PAT 无效"),
            onDismiss = {},
            onOpenSettings = {},
        )
    }
}

@Preview(showBackground = true, name = "SyncBanner · network error")
@Composable
private fun SyncBannerNetworkPreview() {
    MemoTheme {
        SyncBanner(
            status = SyncStatus.Error(ErrorCode.NETWORK, "请求超时"),
            onDismiss = {},
            onOpenSettings = {},
        )
    }
}
