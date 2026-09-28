package com.lichiai.terminal.emulator

import androidx.compose.ui.graphics.Color
import com.lichiai.terminal.model.TerminalTheme

data class AnsiStyle(
    val fgColorIndex: Int = -1, // -1 means default theme fg, 0-15 standard/bright ANSI, or 16-255 / RGB
    val bgColorIndex: Int = -1, // -1 means default theme bg
    val customFgRgb: Long? = null,
    val customBgRgb: Long? = null,
    val bold: Boolean = false,
    val dim: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val inverse: Boolean = false,
    val hidden: Boolean = false
) {
    fun resolveFgColor(theme: TerminalTheme): Color {
        if (inverse) return resolveBgColorDirect(theme)
        return resolveFgColorDirect(theme)
    }

    fun resolveBgColor(theme: TerminalTheme): Color {
        if (inverse) return resolveFgColorDirect(theme)
        return resolveBgColorDirect(theme)
    }

    private fun resolveFgColorDirect(theme: TerminalTheme): Color {
        if (customFgRgb != null) return Color(customFgRgb)
        return when (fgColorIndex) {
            0 -> Color(theme.ansiBlack)
            1 -> Color(theme.ansiRed)
            2 -> Color(theme.ansiGreen)
            3 -> Color(theme.ansiYellow)
            4 -> Color(theme.ansiBlue)
            5 -> Color(theme.ansiMagenta)
            6 -> Color(theme.ansiCyan)
            7 -> Color(theme.ansiWhite)
            8 -> Color(theme.ansiBrightBlack)
            9 -> Color(theme.ansiBrightRed)
            10 -> Color(theme.ansiBrightGreen)
            11 -> Color(theme.ansiBrightYellow)
            12 -> Color(theme.ansiBrightBlue)
            13 -> Color(theme.ansiBrightMagenta)
            14 -> Color(theme.ansiBrightCyan)
            15 -> Color(theme.ansiBrightWhite)
            else -> if (dim) Color(theme.foregroundHex).copy(alpha = 0.5f) else Color(theme.foregroundHex)
        }
    }

    private fun resolveBgColorDirect(theme: TerminalTheme): Color {
        if (customBgRgb != null) return Color(customBgRgb)
        return when (bgColorIndex) {
            0 -> Color(theme.ansiBlack)
            1 -> Color(theme.ansiRed)
            2 -> Color(theme.ansiGreen)
            3 -> Color(theme.ansiYellow)
            4 -> Color(theme.ansiBlue)
            5 -> Color(theme.ansiMagenta)
            6 -> Color(theme.ansiCyan)
            7 -> Color(theme.ansiWhite)
            8 -> Color(theme.ansiBrightBlack)
            9 -> Color(theme.ansiBrightRed)
            10 -> Color(theme.ansiBrightGreen)
            11 -> Color(theme.ansiBrightYellow)
            12 -> Color(theme.ansiBrightBlue)
            13 -> Color(theme.ansiBrightMagenta)
            14 -> Color(theme.ansiBrightCyan)
            15 -> Color(theme.ansiBrightWhite)
            else -> Color(theme.backgroundHex)
        }
    }

    companion object {
        val DEFAULT = AnsiStyle()
    }
}

data class TerminalCell(
    val char: Char = ' ',
    val style: AnsiStyle = AnsiStyle.DEFAULT
)

data class TerminalLine(
    val cells: MutableList<TerminalCell> = mutableListOf()
) {
    fun toPlainText(): String {
        return buildString {
            for (cell in cells) {
                append(cell.char)
            }
        }.trimEnd()
    }
}
