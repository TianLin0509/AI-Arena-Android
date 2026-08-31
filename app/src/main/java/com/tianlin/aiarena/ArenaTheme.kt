package com.tianlin.aiarena

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val ArenaLightColors = lightColorScheme(
    primary = Color(0xFF1D6078),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE7F2F5),
    onPrimaryContainer = Color(0xFF173C4B),
    secondary = Color(0xFF71558B),
    background = Color(0xFFF6F7F9),
    onBackground = Color(0xFF17232D),
    surface = Color.White,
    onSurface = Color(0xFF17232D),
    surfaceVariant = Color(0xFFEFF3F5),
    onSurfaceVariant = Color(0xFF5B6B77),
    outline = Color(0xFFDDE4E8),
    error = Color(0xFFB33A3A),
)

private val ArenaTypography = Typography(
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 24.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 21.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
    ),
)

@Composable
fun ArenaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ArenaLightColors,
        typography = ArenaTypography,
        content = content,
    )
}
