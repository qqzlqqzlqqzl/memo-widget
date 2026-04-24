package dev.aria.memo.ui.components

import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * FAB that collapses to icon-only while the underlying list is scrolling down
 * and expands back out when the list is idle or at rest. Consumers feed in the
 * `expanded` flag; a [androidx.compose.foundation.lazy.LazyListState] driven
 * derivedStateOf typically works well:
 *
 * ```
 * val expanded by remember { derivedStateOf { listState.firstVisibleItemScrollOffset == 0 } }
 * ```
 *
 * Wraps the Material 3 [ExtendedFloatingActionButton] which handles the
 * expand/collapse animation internally.
 */
@Composable
fun ScrollAwareFab(
    expanded: Boolean,
    onClick: () -> Unit,
    icon: ImageVector,
    text: String,
    modifier: Modifier = Modifier,
) {
    ExtendedFloatingActionButton(
        onClick = onClick,
        expanded = expanded,
        icon = { Icon(imageVector = icon, contentDescription = null) },
        text = { Text(text) },
        modifier = modifier,
    )
}
