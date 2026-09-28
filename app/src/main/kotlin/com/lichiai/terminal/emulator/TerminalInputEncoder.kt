package com.lichiai.terminal.emulator

/**
 * Encodes user input and key strokes into VT100 / xterm control byte sequences.
 */
object TerminalInputEncoder {

    const val CTRL_C = "\u0003"
    const val CTRL_D = "\u0004"
    const val CTRL_Z = "\u001A"
    const val CTRL_L = "\u000C"
    const val ESCAPE = "\u001B"
    const val TAB = "\t"
    const val BACKSPACE = "\u007F"
    const val ENTER = "\r"

    const val ARROW_UP = "\u001B[A"
    const val ARROW_DOWN = "\u001B[B"
    const val ARROW_RIGHT = "\u001B[C"
    const val ARROW_LEFT = "\u001B[D"

    const val HOME = "\u001B[H"
    const val END = "\u001B[F"
    const val PAGE_UP = "\u001B[5~"
    const val PAGE_DOWN = "\u001B[6~"
    const val DELETE = "\u001B[3~"

    fun encodeCtrlKey(char: Char): String {
        val uppercase = char.uppercaseChar()
        if (uppercase in 'A'..'Z') {
            val code = uppercase.code - 'A'.code + 1
            return code.toChar().toString()
        }
        return char.toString()
    }
}
