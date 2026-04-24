package dev.aria.memo.ui.theme

import androidx.compose.ui.unit.dp

/**
 * App-wide spacing scale. Keeps padding/gap values aligned across screens so
 * redesign tweaks live in one place instead of being sprinkled as magic
 * numbers. Values follow an 8-pt cadence with a 4-pt half-step at the small
 * end for tight row spacing.
 */
object MemoSpacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    val xxxl = 32.dp
}
