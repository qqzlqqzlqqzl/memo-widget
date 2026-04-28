package dev.aria.memo.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.aria.memo.ui.theme.MemoSpacing
import dev.aria.memo.ui.theme.MemoTheme

/**
 * Persistent "offline + queued writes" banner.
 *
 * Fixes #158 (Bug-2 用户故事 8): the only feedback for a failed push was
 * a transient Snackbar, so users couldn't tell whether their notes had
 * actually shipped to GitHub. This banner stays pinned above the list
 * for as long as the platform reports no internet, and tells the user
 * how many rows are waiting locally.
 *
 * Renders nothing when [isOnline] is true, which lets callers pass the
 * banner unconditionally — same shape as [SyncBanner].
 */
@Composable
fun OfflineBanner(
    isOnline: Boolean,
    dirtyCount: Int,
    modifier: Modifier = Modifier,
) {
    if (isOnline) return
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
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
                imageVector = Icons.Filled.CloudOff,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = if (dirtyCount > 0) {
                    "离线中 · $dirtyCount 条待同步"
                } else {
                    "离线中 · 笔记将在恢复联网后自动上传"
                },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Preview(showBackground = true, name = "OfflineBanner · with queue")
@Composable
private fun OfflineBannerWithQueuePreview() {
    MemoTheme {
        OfflineBanner(isOnline = false, dirtyCount = 3)
    }
}

@Preview(showBackground = true, name = "OfflineBanner · empty queue")
@Composable
private fun OfflineBannerEmptyQueuePreview() {
    MemoTheme {
        OfflineBanner(isOnline = false, dirtyCount = 0)
    }
}
