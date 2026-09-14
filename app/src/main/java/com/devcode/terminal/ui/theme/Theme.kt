package com.devcode.terminal.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val DarkColorScheme = darkColorScheme(
    primary        = Color(0xFF7DD3FC),  // sky-300 ish
    secondary      = Color(0xFF38BDF8),  // sky-400
    tertiary       = Color(0xFF22D3EE),  // cyan-400
    onPrimary      = Color(0xFF0C1924),
    onSecondary    = Color(0xFF0F172A),
    onTertiary     = Color(0xFF08101B),
    background     = Color(0xFF0B0F14),  // near-black
    surface        = Color(0xFF11161D),  // dark surface
    surfaceVariant = Color(0xFF1A2030),  // slightly lifted
    onBackground   = Color(0xFFE2E8F0),  // slate-200
    onSurface      = Color(0xFFE2E8F0),
    onSurfaceVariant = Color(0xFF94A3B8), // slate-400
    error          = Color(0xFFFB7185),  // rose-400
    onError        = Color(0xFF180205),
)

private val LightColorScheme = lightColorScheme(
    primary        = Color(0xFF0284C7),
    secondary      = Color(0xFF0369A1),
    tertiary       = Color(0xFF0C4A6E),
    onPrimary      = Color(0xFFE0F2FE),
    onSecondary    = Color(0xFFE0F2FE),
    onTertiary     = Color(0xFFE0F2FE),
    background     = Color(0xFFF0F9FF),
    surface        = Color(0xFFE0F2FE),
    surfaceVariant = Color(0xFFBAE6FD),
    onBackground   = Color(0xFF0B1120),
    onSurface      = Color(0xFF0B1120),
    onSurfaceVariant = Color(0xFF374151),
    error          = Color(0xFFBE123C),
    onError        = Color(0xFFFDF2F2),
)

private val Typography = Typography(
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize   = 14.sp,
        lineHeight = 20.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize   = 12.sp,
        lineHeight = 16.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize   = 11.sp,
        lineHeight = 14.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize   = 10.sp,
        letterSpacing = 0.5.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize   = 14.sp,
        lineHeight = 20.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize   = 12.sp,
        lineHeight = 16.sp,
    ),
)

@Composable
fun DevCodeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography  = Typography,
        content     = content,
    )
}
