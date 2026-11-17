package com.intramap.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private fun appText(size: Int, height: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = FontFamily.SansSerif, fontWeight = weight,
    fontSize = size.sp, lineHeight = height.sp, letterSpacing = 0.sp
)
val Typography = Typography(
    displayLarge = appText(48,56), displayMedium = appText(40,48), displaySmall = appText(34,42),
    headlineLarge = appText(30,38,FontWeight.SemiBold),
    headlineMedium = appText(26,34,FontWeight.SemiBold),
    headlineSmall = appText(22,30,FontWeight.SemiBold),
    titleLarge = appText(20,28,FontWeight.SemiBold),
    titleMedium = appText(16,24,FontWeight.SemiBold),
    titleSmall = appText(14,20,FontWeight.SemiBold),
    bodyLarge = appText(16,24), bodyMedium = appText(14,22), bodySmall = appText(12,18),
    labelLarge = appText(14,20,FontWeight.Medium),
    labelMedium = appText(12,18,FontWeight.Medium),
    labelSmall = appText(11,16,FontWeight.Medium)
)
