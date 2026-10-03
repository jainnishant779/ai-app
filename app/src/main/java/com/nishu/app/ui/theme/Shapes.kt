package com.nishu.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

val NishuShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp), // 18-24 dp for cards
    large = RoundedCornerShape(22.dp),  // 16-24 dp for buttons
    extraLarge = RoundedCornerShape(32.dp), // Pill shapes
)
