package com.mnbt.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.mnbt.app.data.local.ThemeMode

/**
 * 把语义色板翻译成 M3 ColorScheme。容器色全部拍平到 surface/background：
 * 组件自带的 tonal 分层会和我们「1px 边分层、不用阴影」的规则打架。
 */
private fun AppPalette.toColorScheme(dark: Boolean): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = surface,
        onPrimaryContainer = textPrimary,
        inversePrimary = primary,
        secondary = textSecondary,
        onSecondary = surface,
        secondaryContainer = background,
        onSecondaryContainer = textPrimary,
        tertiary = textSecondary,
        onTertiary = surface,
        tertiaryContainer = background,
        onTertiaryContainer = textPrimary,
        background = background,
        onBackground = textPrimary,
        surface = surface,
        onSurface = textPrimary,
        surfaceVariant = background,
        onSurfaceVariant = textSecondary,
        surfaceDim = background,
        surfaceBright = surface,
        surfaceContainerLowest = surface,
        surfaceContainerLow = surface,
        surfaceContainer = surface,
        surfaceContainerHigh = background,
        surfaceContainerHighest = background,
        inverseSurface = textPrimary,
        inverseOnSurface = surface,
        error = error,
        onError = onPrimary,
        errorContainer = surface,
        onErrorContainer = error,
        outline = outline,
        outlineVariant = outline,
        surfaceTint = primary,
    )
}

@Composable
fun MNBTappTheme(
    themeMode: ThemeMode = ThemeMode.FOLLOW_SYSTEM,
    content: @Composable () -> Unit,
) {
    // 刻意不开 dynamicColor（Material You）：§8.1 的色板是定死的，
    // 跟随壁纸取色会让全部页面对照不了验收标准 C5。
    val dark = when (themeMode) {
        ThemeMode.FOLLOW_SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val palette = if (dark) DarkPalette else LightPalette

    CompositionLocalProvider(LocalAppPalette provides palette) {
        MaterialTheme(
            colorScheme = palette.toColorScheme(dark),
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}
