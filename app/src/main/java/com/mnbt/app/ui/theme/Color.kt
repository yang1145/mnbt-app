package com.mnbt.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * PRD §8.1 的语义色层。组件只允许从这里取色，禁止写死色值——
 * 这是深色模式不返工的前提。
 */
@Immutable
data class AppPalette(
    val primary: Color,
    val onPrimary: Color,
    val background: Color,
    val surface: Color,
    val outline: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textPlaceholder: Color,
    val success: Color,
    val warning: Color,
    val error: Color,
)

val LightPalette = AppPalette(
    primary = Color(0xFF0B57D0),
    onPrimary = Color(0xFFFFFFFF),
    background = Color(0xFFF5F6F8),
    surface = Color(0xFFFFFFFF),
    outline = Color(0xFFE7E7E7),
    textPrimary = Color(0xFF181818),
    textSecondary = Color(0xFF595959),
    textPlaceholder = Color(0xFF8C8C8C),
    success = Color(0xFF2BA471),
    warning = Color(0xFFE37318),
    error = Color(0xFFD54941),
)

val DarkPalette = AppPalette(
    primary = Color(0xFFA8C7FA),
    onPrimary = Color(0xFF062E6F),
    background = Color(0xFF111318),
    surface = Color(0xFF1E2025),
    outline = Color(0xFF3A3D42),
    textPrimary = Color(0xFFE6E6E6),
    textSecondary = Color(0xFFA0A4AB),
    textPlaceholder = Color(0xFF6F7379),
    success = Color(0xFF4CC38A),
    warning = Color(0xFFF0954A),
    error = Color(0xFFF2726B),
)

val LocalAppPalette = staticCompositionLocalOf { LightPalette }
