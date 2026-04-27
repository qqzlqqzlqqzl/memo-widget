package dev.aria.memo.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import dev.aria.memo.data.ErrorCode
import dev.aria.memo.data.sync.SyncStatus
import dev.aria.memo.ui.theme.MemoSpacing
import dev.aria.memo.ui.theme.MemoTheme

/**
 * Top-of-list banner that surfaces a transient sync error.
 *
 * Fix-X1: when the error is an auth failure (`UNAUTHORIZED`), a "修复" button
 * appears next to "知道了". Pressing it invokes [onFix], which the host screen
 * is expected to wire to a Settings-tab navigation + a "highlight PAT" pulse
 * (`SettingsViewModel.requestPatHighlight()`) — giving the user a single-tap
 * path from "GitHub 拒绝访问" to the PAT input.
 *
 * For non-auth errors (network, conflict, …), only the dismiss button shows;
 * the user has nothing actionable to fix from this banner.
 *
 * Note: `NoteListScreen.kt` currently inlines an older copy of this banner
 * (without the 修复 button). Wiring this version in is a one-line import swap
 * but is left for a follow-up that owns NoteListScreen.kt — Fix-X1 stays
 * scoped to the file ownership listed in the task brief.
 */
@Composable
fun SyncBanner(
    status: SyncStatus,
    onDismiss: () -> Unit,
    onFix: () -> Unit,
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
            horizontalArrangement = Arrangement.spacedBy(MemoSpacing.xs),
        ) {
            Text(
                text = "同步失败：${err.message}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            if (err.code == ErrorCode.UNAUTHORIZED) {
                TextButton(onClick = onFix) {
                    Text(
                        text = "修复",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
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
            onFix = {},
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
            onFix = {},
        )
    }
}
