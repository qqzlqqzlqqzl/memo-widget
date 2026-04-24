package dev.aria.memo.ui.theme

import androidx.compose.ui.graphics.Color

// Light palette
val MemoLightPrimary = Color(0xFF3F6B3F)
val MemoLightOnPrimary = Color(0xFFFFFFFF)
val MemoLightPrimaryContainer = Color(0xFFC1EFBB)
val MemoLightOnPrimaryContainer = Color(0xFF002106)

val MemoLightSecondary = Color(0xFF52634F)
val MemoLightOnSecondary = Color(0xFFFFFFFF)
val MemoLightSecondaryContainer = Color(0xFFD5E8CF)
val MemoLightOnSecondaryContainer = Color(0xFF111F0F)

// Tertiary — blue-violet accent for calendar events / secondary emphasis
val MemoLightTertiary = Color(0xFF5B6CC9)
val MemoLightOnTertiary = Color(0xFFFFFFFF)
val MemoLightTertiaryContainer = Color(0xFFDDE1FF)
val MemoLightOnTertiaryContainer = Color(0xFF121755)

val MemoLightBackground = Color(0xFFFBFDF7)
val MemoLightOnBackground = Color(0xFF1A1C19)
val MemoLightSurface = Color(0xFFFBFDF7)
val MemoLightOnSurface = Color(0xFF1A1C19)
val MemoLightSurfaceVariant = Color(0xFFDEE5D8)
val MemoLightOnSurfaceVariant = Color(0xFF424940)

val MemoLightError = Color(0xFFBA1A1A)
val MemoLightOnError = Color(0xFFFFFFFF)
val MemoLightErrorContainer = Color(0xFFFFDAD6)
val MemoLightOnErrorContainer = Color(0xFF410002)

// Dark palette
val MemoDarkPrimary = Color(0xFFA6D3A0)
val MemoDarkOnPrimary = Color(0xFF103912)
val MemoDarkPrimaryContainer = Color(0xFF275228)
val MemoDarkOnPrimaryContainer = Color(0xFFC1EFBB)

val MemoDarkSecondary = Color(0xFFB9CCB4)
val MemoDarkOnSecondary = Color(0xFF253423)
val MemoDarkSecondaryContainer = Color(0xFF3B4B38)
val MemoDarkOnSecondaryContainer = Color(0xFFD5E8CF)

val MemoDarkTertiary = Color(0xFFBBC4F4)
val MemoDarkOnTertiary = Color(0xFF232E6E)
val MemoDarkTertiaryContainer = Color(0xFF3A4583)
val MemoDarkOnTertiaryContainer = Color(0xFFDDE1FF)

val MemoDarkBackground = Color(0xFF1A1C19)
val MemoDarkOnBackground = Color(0xFFE2E3DD)
val MemoDarkSurface = Color(0xFF1A1C19)
val MemoDarkOnSurface = Color(0xFFE2E3DD)
val MemoDarkSurfaceVariant = Color(0xFF424940)
val MemoDarkOnSurfaceVariant = Color(0xFFC2C9BD)

val MemoDarkError = Color(0xFFFFB4AB)
val MemoDarkOnError = Color(0xFF690005)
val MemoDarkErrorContainer = Color(0xFF93000A)
val MemoDarkOnErrorContainer = Color(0xFFFFDAD6)

// ─────────────────────────────────────────────────────────────────────────
// Named semantic extensions — referenced from ui/**/*.kt instead of
// hardcoded `Color(0xFF...)` so dark-mode / dynamic-color themes can
// respond. Exposed as `MemoThemeColors.<name>` via `@Composable` accessors
// that reach into `MaterialTheme.colorScheme` wherever possible. A few
// flavors (notification "warning" amber) stay fixed because the M3 default
// scheme has no semantic slot for them.
// ─────────────────────────────────────────────────────────────────────────

/**
 * Warm amber used for "heads-up / notifications off" cards. Darkens slightly
 * in light mode and brightens in dark mode so the accent bar reads on both.
 */
val MemoLightWarning = Color(0xFFB8860B)
val MemoDarkWarning = Color(0xFFE6B24A)

/**
 * Inline-code background tint (markdown). Uses low-alpha onSurface-ish grey
 * so it works against either surface tonal palette.
 */
val MemoLightInlineCodeBg = Color(0x22808080)
val MemoDarkInlineCodeBg = Color(0x33B0B0B0)
