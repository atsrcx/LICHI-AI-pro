package com.lichiai.terminal.emulator

import com.lichiai.terminal.core.TerminalResourceGovernor

/**
 * High-performance terminal screen and scrollback buffer.
 * Thread-safe for terminal background processing and UI reading.
 */
class TerminalScreenBuffer(
    var rows: Int = 24,
    var columns: Int = 80,
    var maxScrollback: Int = TerminalResourceGovernor.DEFAULT_SCROLLBACK_LINES
) {
    private val lock = Any()

    var cursorX = 0
        private set
    var cursorY = 0
        private set

    // Primary screen lines
    private val screenLines = ArrayList<TerminalLine>()
    // Scrollback history lines
    private val scrollback = ArrayList<TerminalLine>()

    // Alternate screen buffer (for apps like vim, htop, less)
    private val altScreenLines = ArrayList<TerminalLine>()
    private var isAltScreenActive = false

    var currentStyle = AnsiStyle.DEFAULT

    init {
        initScreen()
    }

    private fun initScreen() {
        screenLines.clear()
        for (r in 0 until rows) {
            screenLines.add(createBlankLine())
        }
        altScreenLines.clear()
        for (r in 0 until rows) {
            altScreenLines.add(createBlankLine())
        }
    }

    private fun createBlankLine(): TerminalLine {
        val list = ArrayList<TerminalCell>(columns)
        for (c in 0 until columns) {
            list.add(TerminalCell(' ', AnsiStyle.DEFAULT))
        }
        return TerminalLine(list)
    }

    fun resize(newRows: Int, newCols: Int) {
        synchronized(lock) {
            if (newRows <= 0 || newCols <= 0) return
            if (newRows == rows && newCols == columns) return

            rows = newRows
            columns = newCols

            // Adjust screen lines to match newRows
            while (screenLines.size < rows) {
                screenLines.add(createBlankLine())
            }
            while (screenLines.size > rows) {
                // Push extra top lines into scrollback
                val removed = screenLines.removeAt(0)
                pushScrollback(removed)
            }

            // Adjust each line's cells to match newCols
            for (line in screenLines) {
                while (line.cells.size < columns) {
                    line.cells.add(TerminalCell(' ', AnsiStyle.DEFAULT))
                }
                while (line.cells.size > columns) {
                    line.cells.removeAt(line.cells.size - 1)
                }
            }

            cursorX = cursorX.coerceIn(0, columns - 1)
            cursorY = cursorY.coerceIn(0, rows - 1)
        }
    }

    fun writeChar(ch: Char) {
        synchronized(lock) {
            when (ch) {
                '\r' -> {
                    cursorX = 0
                }
                '\n' -> {
                    newLine()
                }
                '\b' -> {
                    if (cursorX > 0) cursorX--
                }
                '\t' -> {
                    val nextTab = (cursorX / 8 + 1) * 8
                    cursorX = nextTab.coerceAtMost(columns - 1)
                }
                else -> {
                    if (cursorX >= columns) {
                        cursorX = 0
                        newLine()
                    }
                    val targetLine = getActiveLines()[cursorY]
                    while (targetLine.cells.size <= cursorX) {
                        targetLine.cells.add(TerminalCell(' ', AnsiStyle.DEFAULT))
                    }
                    targetLine.cells[cursorX] = TerminalCell(ch, currentStyle)
                    cursorX++
                }
            }
        }
    }

    fun newLine() {
        synchronized(lock) {
            cursorX = 0
            if (cursorY < rows - 1) {
                cursorY++
            } else {
                scrollUp()
            }
        }
    }

    fun scrollUp() {
        synchronized(lock) {
            val lines = getActiveLines()
            if (lines.isNotEmpty()) {
                val top = lines.removeAt(0)
                if (!isAltScreenActive) {
                    pushScrollback(top)
                }
                lines.add(createBlankLine())
            }
        }
    }

    private fun pushScrollback(line: TerminalLine) {
        scrollback.add(line)
        val limit = maxScrollback.coerceAtMost(TerminalResourceGovernor.MAX_SCROLLBACK_LINES)
        while (scrollback.size > limit) {
            scrollback.removeAt(0)
        }
    }

    fun setCursor(col: Int, row: Int) {
        synchronized(lock) {
            cursorX = (col - 1).coerceIn(0, columns - 1)
            cursorY = (row - 1).coerceIn(0, rows - 1)
        }
    }

    fun moveCursor(dx: Int, dy: Int) {
        synchronized(lock) {
            cursorX = (cursorX + dx).coerceIn(0, columns - 1)
            cursorY = (cursorY + dy).coerceIn(0, rows - 1)
        }
    }

    fun clearScreen(mode: Int) {
        synchronized(lock) {
            val lines = getActiveLines()
            when (mode) {
                0 -> { // From cursor to end of screen
                    for (c in cursorX until columns) {
                        if (c < lines[cursorY].cells.size) lines[cursorY].cells[c] = TerminalCell(' ', currentStyle)
                    }
                    for (r in (cursorY + 1) until rows) {
                        lines[r] = createBlankLine()
                    }
                }
                1 -> { // From beginning to cursor
                    for (r in 0 until cursorY) {
                        lines[r] = createBlankLine()
                    }
                    for (c in 0..cursorX) {
                        if (c < lines[cursorY].cells.size) lines[cursorY].cells[c] = TerminalCell(' ', currentStyle)
                    }
                }
                2, 3 -> { // Entire screen (and scrollback if 3)
                    for (r in 0 until rows) {
                        lines[r] = createBlankLine()
                    }
                    if (mode == 3 && !isAltScreenActive) {
                        scrollback.clear()
                    }
                    cursorX = 0
                    cursorY = 0
                }
            }
        }
    }

    fun clearLine(mode: Int) {
        synchronized(lock) {
            val line = getActiveLines()[cursorY]
            when (mode) {
                0 -> { // Cursor to end of line
                    for (c in cursorX until columns) {
                        if (c < line.cells.size) line.cells[c] = TerminalCell(' ', currentStyle)
                    }
                }
                1 -> { // Start of line to cursor
                    for (c in 0..cursorX) {
                        if (c < line.cells.size) line.cells[c] = TerminalCell(' ', currentStyle)
                    }
                }
                2 -> { // Entire line
                    for (c in 0 until columns) {
                        if (c < line.cells.size) line.cells[c] = TerminalCell(' ', currentStyle)
                    }
                }
            }
        }
    }

    fun setAltScreenBuffer(active: Boolean) {
        synchronized(lock) {
            isAltScreenActive = active
            cursorX = 0
            cursorY = 0
        }
    }

    private fun getActiveLines(): ArrayList<TerminalLine> {
        return if (isAltScreenActive) altScreenLines else screenLines
    }

    /**
     * Snapshot of the combined scrollback + visible screen lines for rendering.
     */
    fun getSnapshot(): List<TerminalLine> {
        synchronized(lock) {
            val list = ArrayList<TerminalLine>()
            if (!isAltScreenActive) {
                for (line in scrollback) {
                    list.add(TerminalLine(ArrayList(line.cells)))
                }
                val active = getActiveLines()
                val lastContentRow = active.indexOfLast { line ->
                    line.cells.any { it.char != ' ' }
                }
                val maxVisibleRow = maxOf(cursorY, lastContentRow).coerceIn(0, (active.size - 1).coerceAtLeast(0))
                for (r in 0..maxVisibleRow) {
                    if (r < active.size) {
                        list.add(TerminalLine(ArrayList(active[r].cells)))
                    }
                }
            } else {
                for (line in getActiveLines()) {
                    list.add(TerminalLine(ArrayList(line.cells)))
                }
            }
            return if (list.isEmpty()) listOf(createBlankLine()) else list
        }
    }

    fun getPlainText(): String {
        return getSnapshot().joinToString("\n") { it.toPlainText() }.trimEnd()
    }

    fun getTailPlainText(maxLines: Int = 100): String {
        val snapshot = getSnapshot()
        val tail = snapshot.takeLast(maxLines)
        return tail.joinToString("\n") { it.toPlainText() }.trimEnd()
    }
}
