package com.lichiai.terminal.core

import com.lichiai.terminal.backend.TerminalBackend
import com.lichiai.terminal.backend.ssh.SftpManager
import com.lichiai.terminal.backend.ssh.SshBackend
import com.lichiai.terminal.emulator.AnsiParser
import com.lichiai.terminal.emulator.TerminalLine
import com.lichiai.terminal.emulator.TerminalScreenBuffer
import com.lichiai.terminal.model.TerminalBackendType
import com.lichiai.terminal.model.TerminalDimensions
import com.lichiai.terminal.model.TerminalSessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * An individual interactive terminal session with its own PTY, ANSI buffer, and I/O pipeline.
 */
class TerminalSession(
    val id: String,
    val backendType: TerminalBackendType,
    val backend: TerminalBackend,
    val initialDimensions: TerminalDimensions = TerminalDimensions(),
    val maxScrollback: Int = TerminalResourceGovernor.DEFAULT_SCROLLBACK_LINES,
    val autoReconnect: Boolean = true
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val buffer = TerminalScreenBuffer(
        rows = initialDimensions.rows,
        columns = initialDimensions.columns,
        maxScrollback = maxScrollback
    )
    val parser = AnsiParser(buffer)

    private val _bufferVersion = MutableStateFlow(0L)
    val bufferVersion: StateFlow<Long> = _bufferVersion.asStateFlow()

    private val _state = MutableStateFlow(TerminalSessionState.CREATING)
    val state: StateFlow<TerminalSessionState> = _state.asStateFlow()

    private val _title = MutableStateFlow(backend.title)
    val title: StateFlow<String> = _title.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var outputCollectionJob: Job? = null
    private var stateObservationJob: Job? = null
    private var reconnectJob: Job? = null
    private var reconnectAttempts = 0

    val sftpManager: SftpManager? = if (backend is SshBackend) {
        SftpManager { backend.jschSession }
    } else null

    init {
        stateObservationJob = scope.launch {
            backend.state.collect { backendState ->
                _state.value = backendState
                if (backendState == TerminalSessionState.DISCONNECTED && autoReconnect && reconnectAttempts < TerminalResourceGovernor.MAX_RECONNECT_ATTEMPTS) {
                    triggerReconnect()
                } else if (backendState == TerminalSessionState.ACTIVE) {
                    reconnectAttempts = 0
                }
            }
        }

        val outputChannel = kotlinx.coroutines.channels.Channel<String>(capacity = 1000)
        scope.launch {
            backend.outputFlow.collect { text ->
                outputChannel.send(text)
            }
        }

        outputCollectionJob = scope.launch {
            val batchBuilder = StringBuilder()
            while (isActive) {
                val first = outputChannel.receive()
                batchBuilder.append(first)
                // Drain any already available chunks in the channel
                var next = outputChannel.tryReceive().getOrNull()
                while (next != null && batchBuilder.length < 32768) {
                    batchBuilder.append(next)
                    next = outputChannel.tryReceive().getOrNull()
                }
                val batchText = batchBuilder.toString()
                batchBuilder.setLength(0)
                parser.process(batchText)
                _bufferVersion.value = System.currentTimeMillis()
            }
        }
    }

    suspend fun start() {
        backend.connect()
    }

    fun sendInput(input: String) {
        backend.sendInput(input)
    }

    fun resize(dimensions: TerminalDimensions) {
        buffer.resize(dimensions.rows, dimensions.columns)
        backend.resize(dimensions)
        _bufferVersion.value = System.currentTimeMillis()
    }

    fun triggerReconnect() {
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            reconnectAttempts++
            val delayMs = TerminalResourceGovernor.getBackoffDelayMs(reconnectAttempts)
            _state.value = TerminalSessionState.RECONNECTING
            buffer.writeChar('\r')
            buffer.writeChar('\n')
            parser.process("\u001B[33m[Connection lost. Reconnecting in ${delayMs / 1000}s (Attempt $reconnectAttempts/${TerminalResourceGovernor.MAX_RECONNECT_ATTEMPTS})...]\u001B[0m\r\n")
            _bufferVersion.value = System.currentTimeMillis()
            delay(delayMs)
            if (isActive) {
                backend.connect()
            }
        }
    }

    fun close() {
        reconnectJob?.cancel()
        outputCollectionJob?.cancel()
        stateObservationJob?.cancel()
        backend.disconnect()
        _state.value = TerminalSessionState.CLOSED
    }

    fun getLinesSnapshot(): List<TerminalLine> {
        return buffer.getSnapshot()
    }

    fun getRecentPlainText(maxLines: Int = 100): String {
        return buffer.getTailPlainText(maxLines)
    }
}
