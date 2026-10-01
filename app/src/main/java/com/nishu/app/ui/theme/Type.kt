package com.nishu.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.nishu.app.R

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun inter(weight: FontWeight) = Font(
    resId = R.font.inter_variable,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

val InterFamily = FontFamily(
    inter(FontWeight.Normal),
    inter(FontWeight.Medium),
    inter(FontWeight.SemiBold),
    inter(FontWeight.Bold),
)

private fun style(size: Int, weight: FontWeight, lineHeight: Int, letterSpacing: Double = 0.0) = TextStyle(
    fontFamily = InterFamily,
    fontSize = size.sp,
    fontWeight = weight,
    lineHeight = lineHeight.sp,
    letterSpacing = letterSpacing.sp,
)

val NishuTypography = Typography(
    displaySmall = style(40, FontWeight.SemiBold, 48, -0.5),
    headlineSmall = style(24, FontWeight.SemiBold, 30, -0.2),
    titleLarge = style(22, FontWeight.SemiBold, 28, -0.2),
    titleMedium = style(16, FontWeight.Medium, 22),
    titleSmall = style(14, FontWeight.Medium, 20),
    bodyLarge = style(16, FontWeight.Normal, 24),
    bodyMedium = style(14, FontWeight.Normal, 20),
    bodySmall = style(12, FontWeight.Normal, 16),
    labelLarge = style(14, FontWeight.Medium, 20),
    labelMedium = style(12, FontWeight.Medium, 16),
    labelSmall = style(11, FontWeight.Normal, 14),
)
