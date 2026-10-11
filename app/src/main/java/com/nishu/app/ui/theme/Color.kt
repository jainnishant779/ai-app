package com.nishu.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

object NishuPalette {
    // Primary brand colors from nishu_iot_ai.html
    val Primary = Color(0xFF1D6047) // Forest Green (--green: #1d6047)
    val PrimaryLight = Color(0xFFD8F3DF) // Mint Container (--mint: #d8f3df)
    val Secondary = Color(0xFF2C805C) // Vibrant Green (--green2: #2c805c)
    val Accent = Color(0xFFD8F3DF) // Mint Accent

    // Functional & category accents
    val Lavender = Color(0xFFEAE5FF)
    val SoftBlue = Color(0xFFE5EEFF)
    val Peach = Color(0xFFFFF0DF)
    val Mint = Color(0xFFD8F3DF)

    // Neutrals matching nishu_iot_ai.html
    val WarmIvory = Color(0xFFF2F6F3) // Background (--bg: #f2f6f3)
    val SurfaceWarm = Color(0xFFFFFFFF) // Card Surface (--card: #fff)
    val TextMain = Color(0xFF14251B) // Ink (--ink: #14251b)
    val TextSecondary = Color(0xFF718177) // Muted (--muted: #718177)
    val Divider = Color(0xFFDFE9E2) // Line (--line: #dfe9e2)

    // Feedback
    val Success = Color(0xFF20C981) // Pulsing active dot #20c981
    val Warning = Color(0xFFD97706)
    val Danger = Color(0xFFEF3157) // Recording Red / Stop (--red: #ef3157)

    // Recording theme
    val RecordingBgDark = Color(0xFF06140D)
    val RecordingBgLight = Color(0xFF174D36)

    // Button gradient (Deep Forest to Emerald Sage)
    val CtaStart = Color(0xFF1D6047)
    val CtaEnd = Color(0xFF2C805C)
}

val NishuCtaGradient: Brush = Brush.horizontalGradient(listOf(NishuPalette.CtaStart, NishuPalette.CtaEnd))

val LightColors: ColorScheme = lightColorScheme(
    primary = NishuPalette.Primary,
    onPrimary = Color.White,
    primaryContainer = NishuPalette.PrimaryLight,
    onPrimaryContainer = NishuPalette.Primary,
    secondary = NishuPalette.Secondary,
    onSecondary = Color.White,
    secondaryContainer = NishuPalette.Lavender,
    onSecondaryContainer = Color(0xFF382F7E),
    tertiary = NishuPalette.Secondary,
    onTertiary = Color.White,
    background = NishuPalette.WarmIvory,
    onBackground = NishuPalette.TextMain,
    surface = NishuPalette.SurfaceWarm,
    onSurface = NishuPalette.TextMain,
    surfaceVariant = Color(0xFFF0EFE7),
    onSurfaceVariant = NishuPalette.TextSecondary,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFAF9F3),
    surfaceContainer = NishuPalette.SurfaceWarm,
    surfaceContainerHigh = Color(0xFFF2F0E8),
    outline = NishuPalette.Secondary,
    outlineVariant = NishuPalette.Divider,
    error = NishuPalette.Danger,
    onError = Color.White,
    errorContainer = Color(0xFFFFEBEB),
    onErrorContainer = Color(0xFF7F1D1D),
)

val DarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFF6CC1A2),
    onPrimary = Color(0xFF09291E),
    primaryContainer = Color(0xFF133F31),
    onPrimaryContainer = Color(0xFFD1F2E4),
    secondary = Color(0xFF9ABAA5),
    onSecondary = Color(0xFF172D20),
    secondaryContainer = Color(0xFF2E2D48),
    onSecondaryContainer = Color(0xFFE2DEFD),
    tertiary = Color(0xFF86CCA9),
    onTertiary = Color(0xFF0F3223),
    background = Color(0xFF111916),
    onBackground = Color(0xFFF5F3EC),
    surface = Color(0xFF18221E),
    onSurface = Color(0xFFF5F3EC),
    surfaceVariant = Color(0xFF222E29),
    onSurfaceVariant = Color(0xFFA1AEA6),
    surfaceContainerLowest = Color(0xFF0B120F),
    surfaceContainerLow = Color(0xFF141D1A),
    surfaceContainer = Color(0xFF1C2723),
    surfaceContainerHigh = Color(0xFF24312C),
    outline = Color(0xFF4A5C53),
    outlineVariant = Color(0xFF2B3A33),
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
    val lavender: Color,
    val softBlue: Color,
    val peach: Color,
    val mint: Color,
    val divider: Color,
    val ctaGradient: Brush,
)

val LightExtendedColors = ExtendedColors(
    success = NishuPalette.Primary,
    onSuccess = Color.White,
    successContainer = NishuPalette.Mint,
    warning = NishuPalette.Warning,
    warningContainer = NishuPalette.Peach,
    lavender = NishuPalette.Lavender,
    softBlue = NishuPalette.SoftBlue,
    peach = NishuPalette.Peach,
    mint = NishuPalette.Mint,
    divider = NishuPalette.Divider,
    ctaGradient = NishuCtaGradient,
)

val DarkExtendedColors = ExtendedColors(
    success = Color(0xFF57B894),
    onSuccess = Color(0xFF062B1D),
    successContainer = Color(0xFF123D2E),
    warning = Color(0xFFFBBF24),
    warningContainer = Color(0xFF4A3410),
    lavender = Color(0xFF352F54),
    softBlue = Color(0xFF243354),
    peach = Color(0xFF4D361F),
    mint = Color(0xFF163E30),
    divider = Color(0xFF2B3A33),
    ctaGradient = Brush.horizontalGradient(listOf(Color(0xFF1F5B49), Color(0xFF27745E))),
)
