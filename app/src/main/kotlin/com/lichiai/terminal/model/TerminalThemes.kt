package com.lichiai.terminal.model

object TerminalThemes {
    val LICHI_DARK = TerminalTheme(
        id = "lichi_dark",
        name = "Lichi Dark (Default)",
        backgroundHex = 0xFF0D1117,
        foregroundHex = 0xFFE6EDF3,
        cursorHex = 0xFF8B5CF6,
        selectionHex = 0xFF3B82F6,
        ansiBlack = 0xFF161B22,
        ansiRed = 0xFFF85149,
        ansiGreen = 0xFF3FB950,
        ansiYellow = 0xFFD29922,
        ansiBlue = 0xFF58A6FF,
        ansiMagenta = 0xFFBC8CFF,
        ansiCyan = 0xFF39C5CF,
        ansiWhite = 0xFFB1BAC4,
        ansiBrightBlack = 0xFF6E7681,
        ansiBrightRed = 0xFFFF7B72,
        ansiBrightGreen = 0xFF56D364,
        ansiBrightYellow = 0xFFE3B341,
        ansiBrightBlue = 0xFF79C0FF,
        ansiBrightMagenta = 0xFFD2A8FF,
        ansiBrightCyan = 0xFF56D4DD,
        ansiBrightWhite = 0xFFF0F6FC
    )

    val LICHI_LIGHT = TerminalTheme(
        id = "lichi_light",
        name = "Lichi Light",
        backgroundHex = 0xFFF8FAFC,
        foregroundHex = 0xFF1E293B,
        cursorHex = 0xFF6366F1,
        selectionHex = 0xFFC7D2FE,
        ansiBlack = 0xFF0F172A,
        ansiRed = 0xFFDC2626,
        ansiGreen = 0xFF16A34A,
        ansiYellow = 0xFFCA8A04,
        ansiBlue = 0xFF2563EB,
        ansiMagenta = 0xFF9333EA,
        ansiCyan = 0xFF0891B2,
        ansiWhite = 0xFF64748B,
        ansiBrightBlack = 0xFF475569,
        ansiBrightRed = 0xFFEF4444,
        ansiBrightGreen = 0xFF22C55E,
        ansiBrightYellow = 0xFFEAB308,
        ansiBrightBlue = 0xFF3B82F6,
        ansiBrightMagenta = 0xFFA855F7,
        ansiBrightCyan = 0xFF06B6D4,
        ansiBrightWhite = 0xFF020617
    )

    val DRACULA = TerminalTheme(
        id = "dracula",
        name = "Dracula",
        backgroundHex = 0xFF282A36,
        foregroundHex = 0xFFF8F8F2,
        cursorHex = 0xFFFF79C6,
        selectionHex = 0xFF44475A,
        ansiBlack = 0xFF21222C,
        ansiRed = 0xFFFF5555,
        ansiGreen = 0xFF50FA7B,
        ansiYellow = 0xFFF1FA8C,
        ansiBlue = 0xFFBD93F9,
        ansiMagenta = 0xFFFF79C6,
        ansiCyan = 0xFF8BE9FD,
        ansiWhite = 0xFFF8F8F2,
        ansiBrightBlack = 0xFF6272A4,
        ansiBrightRed = 0xFFFF6E6E,
        ansiBrightGreen = 0xFF69FF94,
        ansiBrightYellow = 0xFFFFFFA5,
        ansiBrightBlue = 0xFFD6ACFF,
        ansiBrightMagenta = 0xFFFF92DF,
        ansiBrightCyan = 0xFFA4FFFF,
        ansiBrightWhite = 0xFFFFFFFF
    )

    val SOLARIZED_DARK = TerminalTheme(
        id = "solarized_dark",
        name = "Solarized Dark",
        backgroundHex = 0xFF002B36,
        foregroundHex = 0xFF839496,
        cursorHex = 0xFF2AA198,
        selectionHex = 0xFF073642,
        ansiBlack = 0xFF073642,
        ansiRed = 0xFFDC322F,
        ansiGreen = 0xFF859900,
        ansiYellow = 0xFFB58900,
        ansiBlue = 0xFF268BD2,
        ansiMagenta = 0xFFD33682,
        ansiCyan = 0xFF2AA198,
        ansiWhite = 0xFFEEE8D5,
        ansiBrightBlack = 0xFF586E75,
        ansiBrightRed = 0xFFCB4B16,
        ansiBrightGreen = 0xFF586E75,
        ansiBrightYellow = 0xFF657B83,
        ansiBrightBlue = 0xFF839496,
        ansiBrightMagenta = 0xFF6C71C4,
        ansiBrightCyan = 0xFF93A1A1,
        ansiBrightWhite = 0xFFFDF6E3
    )

    val MONOCHROME = TerminalTheme(
        id = "monochrome",
        name = "Monochrome Matrix",
        backgroundHex = 0xFF050505,
        foregroundHex = 0xFF22C55E,
        cursorHex = 0xFF4ADE80,
        selectionHex = 0xFF14532D,
        ansiBlack = 0xFF000000,
        ansiRed = 0xFF15803D,
        ansiGreen = 0xFF22C55E,
        ansiYellow = 0xFF86EFAC,
        ansiBlue = 0xFF16A34A,
        ansiMagenta = 0xFF4ADE80,
        ansiCyan = 0xFF22C55E,
        ansiWhite = 0xFFBBF7D0,
        ansiBrightBlack = 0xFF14532D,
        ansiBrightRed = 0xFF16A34A,
        ansiBrightGreen = 0xFF4ADE80,
        ansiBrightYellow = 0xFF86EFAC,
        ansiBrightBlue = 0xFF22C55E,
        ansiBrightMagenta = 0xFF86EFAC,
        ansiBrightCyan = 0xFF4ADE80,
        ansiBrightWhite = 0xFFDCFCE7
    )

    val HIGH_CONTRAST = TerminalTheme(
        id = "high_contrast",
        name = "High Contrast",
        backgroundHex = 0xFF000000,
        foregroundHex = 0xFFFFFFFF,
        cursorHex = 0xFFFFFF00,
        selectionHex = 0xFF003366,
        ansiBlack = 0xFF000000,
        ansiRed = 0xFFFF0000,
        ansiGreen = 0xFF00FF00,
        ansiYellow = 0xFFFFFF00,
        ansiBlue = 0xFF0066FF,
        ansiMagenta = 0xFFFF00FF,
        ansiCyan = 0xFF00FFFF,
        ansiWhite = 0xFFFFFFFF,
        ansiBrightBlack = 0xFF808080,
        ansiBrightRed = 0xFFFF4040,
        ansiBrightGreen = 0xFF40FF40,
        ansiBrightYellow = 0xFFFFFF40,
        ansiBrightBlue = 0xFF4080FF,
        ansiBrightMagenta = 0xFFFF40FF,
        ansiBrightCyan = 0xFF40FFFF,
        ansiBrightWhite = 0xFFFFFFFF
    )

    val ALL_PRESETS = listOf(
        LICHI_DARK,
        LICHI_LIGHT,
        DRACULA,
        SOLARIZED_DARK,
        MONOCHROME,
        HIGH_CONTRAST
    )

    fun getById(id: String): TerminalTheme {
        return ALL_PRESETS.firstOrNull { it.id == id } ?: LICHI_DARK
    }

    fun getEffectiveTheme(id: String, isDarkTheme: Boolean): TerminalTheme {
        if (id == "lichi_dark" || id == "lichi_light" || id == "default" || id == "auto") {
            return if (isDarkTheme) LICHI_DARK else LICHI_LIGHT
        }
        return getById(id)
    }
}
