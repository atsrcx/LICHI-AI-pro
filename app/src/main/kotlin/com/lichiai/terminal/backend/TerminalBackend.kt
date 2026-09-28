package com.lichiai.terminal.backend

import com.lichiai.terminal.model.TerminalDimensions
import com.lichiai.terminal.model.TerminalSessionState
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

interface TerminalBackend {
    val sessionId: String
    val title: String
    val state: StateFlow<TerminalSessionState>
    val outputFlow: SharedFlow<String>
    val errorState: StateFlow<String?>
    val dimensions: StateFlow<TerminalDimensions>

    suspend fun connect()
    fun sendInput(input: String)
    fun resize(dimensions: TerminalDimensions)
    fun disconnect()
    fun isConnected(): Boolean
}
