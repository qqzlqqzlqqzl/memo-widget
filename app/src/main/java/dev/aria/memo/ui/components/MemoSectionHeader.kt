package dev.aria.memo.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import dev.aria.memo.ui.theme.MemoSpacing
import dev.aria.memo.ui.theme.MemoTheme

/**
 * Section label used inside lazy lists — consistent type/colour so the section
 * break reads the same across Calendar, Settings and Tag screens. Optional
 * [trailing] slot (count chip, action button, etc.) aligns to the right.
 */
@Composable
fun MemoSectionHeader(
    text: String,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                top = MemoSpacing.md,
                bottom = MemoSpacing.xs,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        if (trailing != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MemoSpacing.sm),
                content = trailing,
            )
        }
    }
}

@Preview(showBackground = true, name = "SectionHeader · basic")
@Composable
private fun MemoSectionHeaderPreview() {
    MemoTheme {
        MemoSectionHeader(text = "日程")
    }
}

@Preview(showBackground = true, name = "SectionHeader · with trailing")
@Composable
private fun MemoSectionHeaderTrailingPreview() {
    MemoTheme {
        MemoSectionHeader(
            text = "备忘",
            trailing = { Text("3 条", style = MaterialTheme.typography.labelMedium) },
        )
    }
}
