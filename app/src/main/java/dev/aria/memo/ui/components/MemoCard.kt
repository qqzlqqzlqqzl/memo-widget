package dev.aria.memo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.aria.memo.ui.theme.MemoShapes
import dev.aria.memo.ui.theme.MemoSpacing
import dev.aria.memo.ui.theme.MemoTheme

/**
 * Reusable surface card with an optional left accent bar.
 *
 * When [accentColor] is provided, a 4 dp vertical stripe hugs the card's left
 * edge — used to color-code content (primary for notes, tertiary for events)
 * without tinting the whole card. Click and long-click are optional; if both
 * are null the card is non-interactive. Content sits inside a [ColumnScope]
 * so callers can stack rows/text without an extra wrapper.
 */
@Composable
fun MemoCard(
    modifier: Modifier = Modifier,
    accentColor: Color? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val cardModifier = when {
        onClick != null || onLongClick != null -> modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { onClick?.invoke() },
                onLongClick = onLongClick,
            )
        else -> modifier.fillMaxWidth()
    }

    Card(
        shape = MemoShapes.card,
        // Fix-7 #2 (UI-A report): bumped from `surfaceContainerLow` + 0dp to
        // `surfaceContainer` + 1dp tonal elevation so cards sit visibly above
        // the Scaffold surface on both light (subtle shadow) and dark
        // (tonal-elevation color shift) themes, instead of bleeding into the
        // background as one flat plane.
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = cardModifier,
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            if (accentColor != null) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .fillMaxHeight()
                        .background(accentColor),
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(MemoSpacing.lg),
                content = content,
            )
        }
    }
}

@Preview(showBackground = true, name = "Card · with accent")
@Composable
private fun MemoCardAccentPreview() {
    MemoTheme {
        MemoCard(
            accentColor = MaterialTheme.colorScheme.tertiary,
            onClick = {},
        ) {
            Text("10:00 – 11:00", style = MaterialTheme.typography.labelMedium)
            Text("团队周会", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Preview(showBackground = true, name = "Card · plain")
@Composable
private fun MemoCardPlainPreview() {
    MemoTheme {
        MemoCard {
            Text("无 accent 条的卡片", style = MaterialTheme.typography.titleMedium)
            Text("正文内容", style = MaterialTheme.typography.bodyMedium)
        }
    }
}
