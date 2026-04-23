package dev.aria.memo.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val MemoLightColors = lightColorScheme(
    primary = MemoLightPrimary,
    onPrimary = MemoLightOnPrimary,
    primaryContainer = MemoLightPrimaryContainer,
    onPrimaryContainer = MemoLightOnPrimaryContainer,
    secondary = MemoLightSecondary,
    onSecondary = MemoLightOnSecondary,
    secondaryContainer = MemoLightSecondaryContainer,
    onSecondaryContainer = MemoLightOnSecondaryContainer,
    tertiary = MemoLightTertiary,
    onTertiary = MemoLightOnTertiary,
    tertiaryContainer = MemoLightTertiaryContainer,
    onTertiaryContainer = MemoLightOnTertiaryContainer,
    background = MemoLightBackground,
    onBackground = MemoLightOnBackground,
    surface = MemoLightSurface,
    onSurface = MemoLightOnSurface,
    surfaceVariant = MemoLightSurfaceVariant,
    onSurfaceVariant = MemoLightOnSurfaceVariant,
    error = MemoLightError,
    onError = MemoLightOnError,
    errorContainer = MemoLightErrorContainer,
    onErrorContainer = MemoLightOnErrorContainer,
)

private val MemoDarkColors = darkColorScheme(
    primary = MemoDarkPrimary,
    onPrimary = MemoDarkOnPrimary,
    primaryContainer = MemoDarkPrimaryContainer,
    onPrimaryContainer = MemoDarkOnPrimaryContainer,
    secondary = MemoDarkSecondary,
    onSecondary = MemoDarkOnSecondary,
    secondaryContainer = MemoDarkSecondaryContainer,
    onSecondaryContainer = MemoDarkOnSecondaryContainer,
    tertiary = MemoDarkTertiary,
    onTertiary = MemoDarkOnTertiary,
    tertiaryContainer = MemoDarkTertiaryContainer,
    onTertiaryContainer = MemoDarkOnTertiaryContainer,
    background = MemoDarkBackground,
    onBackground = MemoDarkOnBackground,
    surface = MemoDarkSurface,
    onSurface = MemoDarkOnSurface,
    surfaceVariant = MemoDarkSurfaceVariant,
    onSurfaceVariant = MemoDarkOnSurfaceVariant,
    error = MemoDarkError,
    onError = MemoDarkOnError,
    errorContainer = MemoDarkErrorContainer,
    onErrorContainer = MemoDarkOnErrorContainer,
)

@Composable
fun MemoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        darkTheme -> MemoDarkColors
        else -> MemoLightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = MemoTypography,
        content = content,
    )
}
