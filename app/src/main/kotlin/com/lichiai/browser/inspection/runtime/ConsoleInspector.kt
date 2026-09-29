package com.lichiai.browser.inspection.runtime

import android.webkit.ConsoleMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

@Serializable
data class ConsoleMessageRecord(
    val message: String,
    val level: String,
    val sourceId: String = "",
    val lineNumber: Int = 0,
    val timestamp: Long = System.currentTimeMillis()
)

class ConsoleInspector {

    private val _logs = MutableStateFlow<List<ConsoleMessageRecord>>(emptyList())
    val logs: StateFlow<List<ConsoleMessageRecord>> = _logs.asStateFlow()

    private val activeLogs = mutableListOf<ConsoleMessageRecord>()

    fun recordConsoleMessage(consoleMessage: ConsoleMessage) {
        val levelStr = when (consoleMessage.messageLevel()) {
            ConsoleMessage.MessageLevel.ERROR -> "ERROR"
            ConsoleMessage.MessageLevel.WARNING -> "WARN"
            ConsoleMessage.MessageLevel.LOG -> "LOG"
            ConsoleMessage.MessageLevel.TIP -> "INFO"
            ConsoleMessage.MessageLevel.DEBUG -> "DEBUG"
            else -> "LOG"
        }
        val record = ConsoleMessageRecord(
            message = consoleMessage.message() ?: "",
            level = levelStr,
            sourceId = consoleMessage.sourceId() ?: "",
            lineNumber = consoleMessage.lineNumber(),
            timestamp = System.currentTimeMillis()
        )
        synchronized(activeLogs) {
            activeLogs.add(0, record)
            if (activeLogs.size > 200) activeLogs.removeAt(activeLogs.lastIndex)
            _logs.value = activeLogs.toList()
        }
    }

    fun recordJsRuntimeError(message: String, source: String, lineno: Int, colno: Int) {
        val record = ConsoleMessageRecord(
            message = "Uncaught Error: $message (col $colno)",
            level = "ERROR",
            sourceId = source,
            lineNumber = lineno,
            timestamp = System.currentTimeMillis()
        )
        synchronized(activeLogs) {
            activeLogs.add(0, record)
            if (activeLogs.size > 200) activeLogs.removeAt(activeLogs.lastIndex)
            _logs.value = activeLogs.toList()
        }
    }

    fun clear() {
        synchronized(activeLogs) {
            activeLogs.clear()
            _logs.value = emptyList()
        }
    }

    fun getErrorsCount(): Int = synchronized(activeLogs) {
        activeLogs.count { it.level == "ERROR" }
    }
}
