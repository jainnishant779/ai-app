package com.nishu.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

val LocalNishuColors = staticCompositionLocalOf { LightExtendedColors }

object NishuTheme {
    val extended: ExtendedColors
        @Composable @ReadOnlyComposable get() = LocalNishuColors.current
}

@Composable
fun NishuTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalNishuColors provides if (darkTheme) DarkExtendedColors else LightExtendedColors,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = NishuTypography,
            shapes = NishuShapes,
            content = content,
        )
    }
}
