package com.lichiai.terminal.ipc

import android.os.Binder
import com.lichiai.terminal.core.TerminalSessionManager

/**
 * Concrete IPC Binder running in the isolated :terminal process.
 */
class TerminalBinder(val sessionManager: TerminalSessionManager) : Binder() {

    fun createLocalSession(title: String): String? {
        val s = sessionManager.createLocalTermuxSession(title)
        return s?.id
    }

    fun sendInput(sessionId: String, input: String) {
        val s = sessionManager.sessions.value.firstOrNull { it.id == sessionId }
        s?.sendInput(input)
    }

    fun resize(sessionId: String, rows: Int, cols: Int, widthPx: Int, heightPx: Int) {
        val s = sessionManager.sessions.value.firstOrNull { it.id == sessionId }
        s?.resize(com.lichiai.terminal.model.TerminalDimensions(rows, cols, widthPx, heightPx))
    }

    fun closeSession(sessionId: String) {
        sessionManager.closeSession(sessionId)
    }

    fun getSessionText(sessionId: String, maxLines: Int = 100): String {
        val s = sessionManager.sessions.value.firstOrNull { it.id == sessionId }
        return s?.getRecentPlainText(maxLines) ?: ""
    }
}
