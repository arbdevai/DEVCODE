package com.devcode.terminal.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import java.util.regex.Pattern

/**
 * High-performance ANSI escape sequence parser for Android Jetpack Compose.
 * Translates terminal SGR color & format codes into rich [AnnotatedString] styles.
 */
object AnsiParser {

    private val SGR_PATTERN = Pattern.compile("\u001B\\[([0-9;]*)m")
    private val STRIP_PATTERN = Pattern.compile("\u001B\\[[?0-9;]*[a-zA-Z]|\r")

    // Modern 2026 Developer Terminal Palette
    private val COLOR_BLACK         = Color(0xFF0F172A)
    private val COLOR_RED           = Color(0xFFF43F5E) // Rose-500
    private val COLOR_GREEN         = Color(0xFF10B981) // Emerald-500
    private val COLOR_YELLOW        = Color(0xFFFBBF24) // Amber-400
    private val COLOR_BLUE          = Color(0xFF38BDF8) // Sky-400
    private val COLOR_MAGENTA       = Color(0xFFA855F7) // Purple-500
    private val COLOR_CYAN          = Color(0xFF2DD4BF) // Teal-400
    private val COLOR_WHITE         = Color(0xFFE2E8F0) // Slate-200

    private val COLOR_BRIGHT_BLACK  = Color(0xFF64748B) // Slate-500
    private val COLOR_BRIGHT_RED    = Color(0xFFFB7185) // Rose-400
    private val COLOR_BRIGHT_GREEN  = Color(0xFF34D399) // Emerald-400
    private val COLOR_BRIGHT_YELLOW = Color(0xFFFDE047) // Yellow-300
    private val COLOR_BRIGHT_BLUE   = Color(0xFF60A5FA) // Blue-400
    private val COLOR_BRIGHT_MAGENTA= Color(0xFFC084FC) // Purple-400
    private val COLOR_BRIGHT_CYAN   = Color(0xFF5EEAD4) // Teal-300
    private val COLOR_BRIGHT_WHITE  = Color(0xFFFFFFFF)

    fun parse(rawText: String): AnnotatedString {
        if (!rawText.contains('\u001B')) {
            // Fast path for text without ANSI codes
            return AnnotatedString(rawText.replace("\r", ""))
        }

        val matcher = SGR_PATTERN.matcher(rawText)
        val builder = AnnotatedString.Builder()
        var lastEnd = 0

        var currentFgColor: Color? = null
        var isBold = false
        var isUnderline = false

        fun currentStyle(): SpanStyle {
            return SpanStyle(
                color = currentFgColor ?: Color.Unspecified,
                fontWeight = if (isBold) FontWeight.Bold else FontWeight.Normal,
                textDecoration = if (isUnderline) TextDecoration.Underline else TextDecoration.None
            )
        }

        while (matcher.find()) {
            val textSegment = rawText.substring(lastEnd, matcher.start())
            if (textSegment.isNotEmpty()) {
                val cleanText = STRIP_PATTERN.matcher(textSegment).replaceAll("")
                if (cleanText.isNotEmpty()) {
                    val start = builder.length
                    builder.append(cleanText)
                    builder.addStyle(currentStyle(), start, builder.length)
                }
            }

            val codesStr = matcher.group(1) ?: ""
            val codes = if (codesStr.isEmpty()) listOf(0) else codesStr.split(';').mapNotNull { it.toIntOrNull() }

            var i = 0
            while (i < codes.size) {
                when (val code = codes[i]) {
                    0 -> { // Reset all
                        currentFgColor = null
                        isBold = false
                        isUnderline = false
                    }
                    1 -> isBold = true
                    22 -> isBold = false // Normal intensity
                    4 -> isUnderline = true
                    24 -> isUnderline = false
                    39 -> currentFgColor = null // Default FG
                    in 30..37 -> currentFgColor = getStandardColor(code - 30, bright = false)
                    in 90..97 -> currentFgColor = getStandardColor(code - 90, bright = true)
                    38 -> { // 256 colors or RGB
                        if (i + 2 < codes.size && codes[i + 1] == 5) {
                            currentFgColor = get256Color(codes[i + 2])
                            i += 2
                        } else if (i + 4 < codes.size && codes[i + 1] == 2) {
                            currentFgColor = Color(codes[i + 2], codes[i + 3], codes[i + 4])
                            i += 4
                        }
                    }
                }
                i++
            }

            lastEnd = matcher.end()
        }

        if (lastEnd < rawText.length) {
            val trailingText = STRIP_PATTERN.matcher(rawText.substring(lastEnd)).replaceAll("")
            if (trailingText.isNotEmpty()) {
                val start = builder.length
                builder.append(trailingText)
                builder.addStyle(currentStyle(), start, builder.length)
            }
        }

        return builder.toAnnotatedString()
    }

    private fun getStandardColor(index: Int, bright: Boolean): Color {
        return if (!bright) {
            when (index) {
                0 -> COLOR_BLACK
                1 -> COLOR_RED
                2 -> COLOR_GREEN
                3 -> COLOR_YELLOW
                4 -> COLOR_BLUE
                5 -> COLOR_MAGENTA
                6 -> COLOR_CYAN
                else -> COLOR_WHITE
            }
        } else {
            when (index) {
                0 -> COLOR_BRIGHT_BLACK
                1 -> COLOR_BRIGHT_RED
                2 -> COLOR_BRIGHT_GREEN
                3 -> COLOR_BRIGHT_YELLOW
                4 -> COLOR_BRIGHT_BLUE
                5 -> COLOR_BRIGHT_MAGENTA
                6 -> COLOR_BRIGHT_CYAN
                else -> COLOR_BRIGHT_WHITE
            }
        }
    }

    private fun get256Color(colorIndex: Int): Color {
        return when (colorIndex) {
            in 0..7 -> getStandardColor(colorIndex, bright = false)
            in 8..15 -> getStandardColor(colorIndex - 8, bright = true)
            in 16..231 -> {
                val idx = colorIndex - 16
                val r = (idx / 36) * 51
                val g = ((idx % 36) / 6) * 51
                val b = (idx % 6) * 51
                Color(r, g, b)
            }
            in 232..255 -> {
                val gray = (colorIndex - 232) * 10 + 8
                Color(gray, gray, gray)
            }
            else -> COLOR_WHITE
        }
    }
}
