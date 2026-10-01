package com.nishu.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

object NishuPalette {
    val Primary = Color(0xFF635BFF)
    val PrimaryLight = Color(0xFFEEEAFE)
    val Secondary = Color(0xFF4F8CFF)
    val Accent = Color(0xFF8B5CF6)
    val Success = Color(0xFF22C55E)
    val Warning = Color(0xFFF59E0B)
    val Danger = Color(0xFFEF4444)
    val CtaStart = Color(0xFF4F8CFF)
    val CtaEnd = Color(0xFF7657FF)
}

val NishuCtaGradient: Brush = Brush.horizontalGradient(listOf(NishuPalette.CtaStart, NishuPalette.CtaEnd))

val LightColors: ColorScheme = lightColorScheme(
    primary = NishuPalette.Primary,
    onPrimary = Color.White,
    primaryContainer = NishuPalette.PrimaryLight,
    onPrimaryContainer = Color(0xFF2B2380),
    secondary = NishuPalette.Secondary,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE3EDFF),
    onSecondaryContainer = Color(0xFF123A80),
    tertiary = NishuPalette.Accent,
    onTertiary = Color.White,
    background = Color(0xFFF7F8FC),
    onBackground = Color(0xFF171827),
    surface = Color.White,
    onSurface = Color(0xFF171827),
    surfaceVariant = Color(0xFFF0F1F8),
    onSurfaceVariant = Color(0xFF6B7085),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFAFAFE),
    surfaceContainer = Color(0xFFF3F4FA),
    surfaceContainerHigh = Color(0xFFEDEEF7),
    outline = Color(0xFFB9BBCB),
    outlineVariant = Color(0xFFE8E9F0),
    error = NishuPalette.Danger,
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
)

val DarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFF8C85FF),
    onPrimary = Color(0xFF16124D),
    primaryContainer = Color(0xFF2A2752),
    onPrimaryContainer = Color(0xFFE2DFFF),
    secondary = Color(0xFF6FA2FF),
    onSecondary = Color(0xFF0B2A5C),
    secondaryContainer = Color(0xFF1E3358),
    onSecondaryContainer = Color(0xFFD6E4FF),
    tertiary = Color(0xFFA78BFA),
    onTertiary = Color(0xFF241352),
    background = Color(0xFF0F1020),
    onBackground = Color(0xFFECEDF7),
    surface = Color(0xFF181A2E),
    onSurface = Color(0xFFECEDF7),
    surfaceVariant = Color(0xFF22243B),
    onSurfaceVariant = Color(0xFFA3A7BD),
    surfaceContainerLowest = Color(0xFF0B0C18),
    surfaceContainerLow = Color(0xFF141628),
    surfaceContainer = Color(0xFF1C1E34),
    surfaceContainerHigh = Color(0xFF22243B),
    outline = Color(0xFF5A5E78),
    outlineVariant = Color(0xFF2C2F48),
    error = Color(0xFFF87171),
    onError = Color(0xFF450A0A),
    errorContainer = Color(0xFF5B1F1F),
    onErrorContainer = Color(0xFFFEE2E2),
)

/** Colors that Material3's scheme has no slot for. */
data class ExtendedColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val ctaGradient: Brush,
)

val LightExtendedColors = ExtendedColors(
    success = NishuPalette.Success,
    onSuccess = Color.White,
    successContainer = Color(0xFFDCFCE7),
    warning = NishuPalette.Warning,
    warningContainer = Color(0xFFFEF3C7),
    ctaGradient = NishuCtaGradient,
)

val DarkExtendedColors = ExtendedColors(
    success = Color(0xFF4ADE80),
    onSuccess = Color(0xFF052E16),
    successContainer = Color(0xFF14532D),
    warning = Color(0xFFFBBF24),
    warningContainer = Color(0xFF4A3410),
    ctaGradient = NishuCtaGradient,
)
