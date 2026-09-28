package com.lichiai.terminal.emulator

/**
 * Robust ANSI / VT100 / VT220 / xterm stream parser.
 * Handles SGR attributes, 8/16/256/24-bit colors, cursor movement, clear commands,
 * and alternate screen buffer switches without dropping characters or freezing.
 */
class AnsiParser(private val buffer: TerminalScreenBuffer) {

    private enum class State {
        GROUND,
        ESCAPE,
        CSI,
        OSC
    }

    private var state = State.GROUND
    private val csiParams = ArrayList<Int>()
    private val csiBuffer = StringBuilder()
    private var isPrivateSequence = false

    fun process(text: String) {
        for (i in text.indices) {
            processChar(text[i])
        }
    }

    fun processBytes(bytes: ByteArray, length: Int) {
        val str = String(bytes, 0, length, Charsets.UTF_8)
        process(str)
    }

    private fun processChar(ch: Char) {
        when (state) {
            State.GROUND -> {
                if (ch == '\u001B') { // ESC
                    state = State.ESCAPE
                } else {
                    buffer.writeChar(ch)
                }
            }
            State.ESCAPE -> {
                when (ch) {
                    '[' -> {
                        state = State.CSI
                        csiParams.clear()
                        csiBuffer.clear()
                        isPrivateSequence = false
                    }
                    ']' -> {
                        state = State.OSC
                    }
                    'c' -> { // Reset terminal
                        buffer.currentStyle = AnsiStyle.DEFAULT
                        buffer.clearScreen(2)
                        state = State.GROUND
                    }
                    else -> {
                        // Unhandled 2-character escape sequence
                        state = State.GROUND
                    }
                }
            }
            State.CSI -> {
                when {
                    ch == '?' -> {
                        isPrivateSequence = true
                    }
                    ch in '0'..'9' || ch == ';' -> {
                        csiBuffer.append(ch)
                    }
                    ch in '@'..'~' -> { // Final CSI command character
                        parseCsiParams()
                        executeCsiCommand(ch)
                        state = State.GROUND
                    }
                    ch == '\u001B' -> {
                        state = State.ESCAPE
                    }
                    else -> {
                        // Intermediate character, ignore
                    }
                }
            }
            State.OSC -> {
                // Operating System Command (e.g. set title) ends on BEL (0x07) or ST (ESC \)
                if (ch == '\u0007' || ch == '\u001B') {
                    state = State.GROUND
                }
            }
        }
    }

    private fun parseCsiParams() {
        csiParams.clear()
        if (csiBuffer.isEmpty()) return
        val parts = csiBuffer.split(';')
        for (part in parts) {
            val num = part.toIntOrNull() ?: 0
            csiParams.add(num)
        }
    }

    private fun executeCsiCommand(cmd: Char) {
        when (cmd) {
            'm' -> handleSgr()
            'A' -> { // Cursor Up (CUU)
                val count = csiParams.firstOrNull()?.coerceAtLeast(1) ?: 1
                buffer.moveCursor(0, -count)
            }
            'B' -> { // Cursor Down (CUD)
                val count = csiParams.firstOrNull()?.coerceAtLeast(1) ?: 1
                buffer.moveCursor(0, count)
            }
            'C' -> { // Cursor Forward (CUF)
                val count = csiParams.firstOrNull()?.coerceAtLeast(1) ?: 1
                buffer.moveCursor(count, 0)
            }
            'D' -> { // Cursor Backward (CUB)
                val count = csiParams.firstOrNull()?.coerceAtLeast(1) ?: 1
                buffer.moveCursor(-count, 0)
            }
            'H', 'f' -> { // Cursor Position (CUP)
                val row = if (csiParams.isNotEmpty()) csiParams[0] else 1
                val col = if (csiParams.size > 1) csiParams[1] else 1
                buffer.setCursor(col, row)
            }
            'J' -> { // Erase in Display (ED)
                val mode = csiParams.firstOrNull() ?: 0
                buffer.clearScreen(mode)
            }
            'K' -> { // Erase in Line (EL)
                val mode = csiParams.firstOrNull() ?: 0
                buffer.clearLine(mode)
            }
            'h' -> { // Set Mode
                if (isPrivateSequence && csiParams.contains(1049)) {
                    buffer.setAltScreenBuffer(true)
                }
            }
            'l' -> { // Reset Mode
                if (isPrivateSequence && csiParams.contains(1049)) {
                    buffer.setAltScreenBuffer(false)
                }
            }
        }
    }

    private fun handleSgr() {
        if (csiParams.isEmpty()) {
            buffer.currentStyle = AnsiStyle.DEFAULT
            return
        }

        var i = 0
        var current = buffer.currentStyle

        while (i < csiParams.size) {
            val code = csiParams[i]
            when (code) {
                0 -> current = AnsiStyle.DEFAULT
                1 -> current = current.copy(bold = true)
                2 -> current = current.copy(dim = true)
                3 -> current = current.copy(italic = true)
                4 -> current = current.copy(underline = true)
                7 -> current = current.copy(inverse = true)
                8 -> current = current.copy(hidden = true)
                22 -> current = current.copy(bold = false, dim = false)
                23 -> current = current.copy(italic = false)
                24 -> current = current.copy(underline = false)
                27 -> current = current.copy(inverse = false)
                28 -> current = current.copy(hidden = false)
                in 30..37 -> current = current.copy(fgColorIndex = code - 30, customFgRgb = null)
                38 -> { // Extended foreground color (256 color or RGB)
                    if (i + 2 < csiParams.size && csiParams[i + 1] == 5) {
                        current = current.copy(fgColorIndex = csiParams[i + 2], customFgRgb = null)
                        i += 2
                    } else if (i + 4 < csiParams.size && csiParams[i + 1] == 2) {
                        val r = csiParams[i + 2].coerceIn(0, 255)
                        val g = csiParams[i + 3].coerceIn(0, 255)
                        val b = csiParams[i + 4].coerceIn(0, 255)
                        val rgb = (0xFFL shl 24) or (r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong()
                        current = current.copy(customFgRgb = rgb, fgColorIndex = -1)
                        i += 4
                    }
                }
                39 -> current = current.copy(fgColorIndex = -1, customFgRgb = null)
                in 40..47 -> current = current.copy(bgColorIndex = code - 40, customBgRgb = null)
                48 -> { // Extended background color
                    if (i + 2 < csiParams.size && csiParams[i + 1] == 5) {
                        current = current.copy(bgColorIndex = csiParams[i + 2], customBgRgb = null)
                        i += 2
                    } else if (i + 4 < csiParams.size && csiParams[i + 1] == 2) {
                        val r = csiParams[i + 2].coerceIn(0, 255)
                        val g = csiParams[i + 3].coerceIn(0, 255)
                        val b = csiParams[i + 4].coerceIn(0, 255)
                        val rgb = (0xFFL shl 24) or (r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong()
                        current = current.copy(customBgRgb = rgb, bgColorIndex = -1)
                        i += 4
                    }
                }
                49 -> current = current.copy(bgColorIndex = -1, customBgRgb = null)
                in 90..97 -> current = current.copy(fgColorIndex = (code - 90) + 8, customFgRgb = null)
                in 100..107 -> current = current.copy(bgColorIndex = (code - 100) + 8, customBgRgb = null)
            }
            i++
        }

        buffer.currentStyle = current
    }
}
