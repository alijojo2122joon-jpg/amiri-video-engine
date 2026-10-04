package com.amiri.videoengine.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object AmiriColors {
    val Background = Color(0xFF0A0A0D)
    val Surface = Color(0xFF141419)
    val SurfaceHigh = Color(0xFF1C1C23)
    val Outline = Color(0xFF2A2A33)
    /** Sky blue, matching the Amiri Video Engine logo background. */
    val Accent = Color(0xFF87CEEB)
    val AccentDim = Color(0xFF3F7F9C)
    val Text = Color(0xFFF2F0EB)
    val TextDim = Color(0xFF9A98A0)
    val Danger = Color(0xFFE5675C)
    val Ok = Color(0xFF6CC58C)
}

private val scheme = darkColorScheme(
    primary = AmiriColors.Accent,
    onPrimary = Color(0xFF1A1205),
    secondary = AmiriColors.Accent,
    background = AmiriColors.Background,
    onBackground = AmiriColors.Text,
    surface = AmiriColors.Surface,
    onSurface = AmiriColors.Text,
    surfaceVariant = AmiriColors.SurfaceHigh,
    onSurfaceVariant = AmiriColors.TextDim,
    outline = AmiriColors.Outline,
    error = AmiriColors.Danger,
)

private val typography = Typography(
    headlineSmall = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 4.sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.sp),
    labelSmall = TextStyle(fontSize = 11.sp, letterSpacing = 1.sp),
)

@Composable
fun AmiriTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
