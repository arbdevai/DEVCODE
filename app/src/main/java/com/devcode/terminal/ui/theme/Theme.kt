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

// 2026 Modern Developer Dark Theme (Obsidian & Electric Sky)
private val DarkColorScheme = darkColorScheme(
    primary          = Color(0xFF38BDF8),  // Sky-400
    onPrimary        = Color(0xFF082F49),  // Deep contrast on sky
    primaryContainer = Color(0xFF0C4A6E),  // Sky-900
    onPrimaryContainer = Color(0xFFE0F2FE),
    secondary        = Color(0xFF818CF8),  // Indigo-400
    onSecondary      = Color(0xFF1E1B4B),
    secondaryContainer = Color(0xFF312E81),
    onSecondaryContainer = Color(0xFFE0E7FF),
    tertiary         = Color(0xFF2DD4BF),  // Teal-400
    onTertiary       = Color(0xFF042F2E),
    background       = Color(0xFF090D12),  // Deep Obsidian
    onBackground     = Color(0xFFF1F5F9),  // Slate-100
    surface          = Color(0xFF111722),  // Elevated dark surface
    onSurface        = Color(0xFFF1F5F9),
    surfaceVariant   = Color(0xFF1E293B),  // Slate-800 card border / chip
    onSurfaceVariant = Color(0xFF94A3B8),  // Slate-400 secondary text
    outline          = Color(0xFF334155),  // Slate-700 subtle border
    error            = Color(0xFFF43F5E),  // Rose-500
    onError          = Color(0xFFFFFFFF),
    errorContainer   = Color(0xFF4C0519),
    onErrorContainer = Color(0xFFFFE4E6),
)

// 2026 Modern Clean Light Theme (Crisp Porcelain & Deep Ocean)
private val LightColorScheme = lightColorScheme(
    primary          = Color(0xFF0284C7),  // Sky-600
    onPrimary        = Color(0xFFFFFFFF),  // High-contrast white
    primaryContainer = Color(0xFFE0F2FE),
    onPrimaryContainer = Color(0xFF0369A1),
    secondary        = Color(0xFF4F46E5),  // Indigo-600
    onSecondary      = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEEF2FF),
    onSecondaryContainer = Color(0xFF3730A3),
    tertiary         = Color(0xFF0D9488),  // Teal-600
    onTertiary       = Color(0xFFFFFFFF),
    background       = Color(0xFFF8FAFC),  // Crisp Slate-50
    onBackground     = Color(0xFF0F172A),  // Slate-900
    surface          = Color(0xFFFFFFFF),  // White card surface
    onSurface        = Color(0xFF0F172A),
    surfaceVariant   = Color(0xFFF1F5F9),  // Slate-100
    onSurfaceVariant = Color(0xFF475569),  // Slate-600
    outline          = Color(0xFFE2E8F0),  // Slate-200 border
    error            = Color(0xFFE11D48),  // Rose-600
    onError          = Color(0xFFFFFFFF),
    errorContainer   = Color(0xFFFFE4E6),
    onErrorContainer = Color(0xFF9F1239),
)

private val AppTypography = Typography(
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize   = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.5).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize   = 17.sp,
        lineHeight = 22.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize   = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = (-0.2).sp,
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
        fontSize   = 13.sp,
        lineHeight = 18.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize   = 14.sp,
        lineHeight = 20.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize   = 13.sp,
        lineHeight = 18.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize   = 11.sp,
        lineHeight = 15.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize   = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.2.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize   = 11.sp,
        lineHeight = 14.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize   = 10.sp,
        lineHeight = 13.sp,
        letterSpacing = 0.4.sp,
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
        typography  = AppTypography,
        content     = content,
    )
}
