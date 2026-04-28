package dev.aria.memo.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.aria.memo.ui.theme.MemoSpacing
import dev.aria.memo.ui.theme.MemoTheme

/**
 * Centered empty-state illustration for list/detail screens.
 *
 * The icon lives inside a filled circle using the tertiary container colour so
 * it reads as a gentle "nothing here yet" rather than an error. Optional
 * [subtitle] adds a one-line hint underneath — keep it short; multi-line
 * copy should live in a dedicated onboarding screen instead.
 */
@Composable
fun MemoEmptyState(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(MemoSpacing.md),
            modifier = Modifier.padding(MemoSpacing.xxl),
        ) {
            // Fixes #237 (UI-A #24): tertiaryContainer landed as a
            // blue-purple disk on top of our green-leaning palette,
            // reading as "look, an alert!" rather than "nothing here
            // yet". Switched to secondaryContainer to keep the empty
            // state in the app's own colour family. Disk + icon
            // shrunk to the M3-recommended empty-state sizes (56dp /
            // 28dp).
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.size(56.dp),
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Preview(showBackground = true, name = "Empty · with subtitle")
@Composable
private fun MemoEmptyStatePreview() {
    MemoTheme {
        MemoEmptyState(
            icon = Icons.Outlined.EventAvailable,
            title = "今日无事件",
            subtitle = "点右下角添加新事件",
        )
    }
}

@Preview(showBackground = true, name = "Empty · title only")
@Composable
private fun MemoEmptyStateTitleOnlyPreview() {
    MemoTheme {
        MemoEmptyState(
            icon = Icons.Outlined.EventAvailable,
            title = "还没有笔记",
        )
    }
}
