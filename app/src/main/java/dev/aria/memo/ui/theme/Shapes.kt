package dev.aria.memo.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Shared corner shapes. Material 3 Expressive specifies a five-step shape
 * scale — we mirror it so chips / text fields / cards / dialogs / sheets
 * each have a dedicated level instead of everything falling back to the
 * same two values.
 *
 * Legacy aliases `card` (16dp) and `button` (12dp) stay to avoid a flood
 * of call-site churn; they now just point at the new scale.
 */
object MemoShapes {
    val extraSmall = RoundedCornerShape(4.dp)
    val small = RoundedCornerShape(8.dp)
    val medium = RoundedCornerShape(12.dp)
    val large = RoundedCornerShape(16.dp)
    val extraLarge = RoundedCornerShape(28.dp)

    // Back-compat aliases.
    val card = large
    val button = medium
}

/**
 * Feeds the same scale into `MaterialTheme(shapes = ...)`. With this wired
 * up, `AlertDialog`, `MenuDefaults`, `Card`, `Button` etc. pick up the
 * expressive scale automatically instead of the default M3 values, so
 * every surface in the app shares one shape language.
 */
val MemoMaterialShapes: Shapes = Shapes(
    extraSmall = MemoShapes.extraSmall,
    small = MemoShapes.small,
    medium = MemoShapes.medium,
    large = MemoShapes.large,
    extraLarge = MemoShapes.extraLarge,
)
