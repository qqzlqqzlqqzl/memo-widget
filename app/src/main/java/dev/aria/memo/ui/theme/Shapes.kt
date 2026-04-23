package dev.aria.memo.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * Shared corner shapes for cards and buttons. Material 3 Expressive pushes
 * rounder corners; we land on 16 dp for cards (feels modern without losing
 * density on list items) and 12 dp for buttons (hit target stays crisp).
 */
object MemoShapes {
    val card = RoundedCornerShape(16.dp)
    val button = RoundedCornerShape(12.dp)
}
