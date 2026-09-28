package com.lichiai.terminal.core

import android.content.Context
import android.util.Log
import com.lichiai.terminal.backend.ssh.SshBackend
import com.lichiai.terminal.backend.termux.TermuxBackend
import com.lichiai.terminal.model.SshHostKey
import com.lichiai.terminal.model.SshProfile
import com.lichiai.terminal.model.TerminalBackendType
import com.lichiai.terminal.model.TerminalDimensions
import com.lichiai.terminal.model.TerminalSplitMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Manages multiple concurrent terminal sessions, tabs, and split-screen views.
 */
class TerminalSessionManager(private val context: Context) {

    companion object {
        private const val TAG = "TerminalSessionManager"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _sessions = MutableStateFlow<List<TerminalSession>>(emptyList())
    val sessions: StateFlow<List<TerminalSession>> = _sessions.asStateFlow()

    private val _activeSessionId = MutableStateFlow<String?>(null)
    val activeSessionId: StateFlow<String?> = _activeSessionId.asStateFlow()

    private val _secondarySessionId = MutableStateFlow<String?>(null) // For split mode
    val secondarySessionId: StateFlow<String?> = _secondarySessionId.asStateFlow()

    private val _splitMode = MutableStateFlow(TerminalSplitMode.NONE)
    val splitMode: StateFlow<TerminalSplitMode> = _splitMode.asStateFlow()

    var hostKeyPromptHandler: (suspend (SshHostKey) -> Boolean)? = null

    fun getActiveSession(): TerminalSession? {
        val id = _activeSessionId.value ?: return null
        return _sessions.value.firstOrNull { it.id == id }
    }

    fun getSecondarySession(): TerminalSession? {
        val id = _secondarySessionId.value ?: return null
        return _sessions.value.firstOrNull { it.id == id }
    }

    fun createLocalTermuxSession(title: String = "Local Shell"): TerminalSession? {
        if (_sessions.value.size >= TerminalResourceGovernor.MAX_SESSIONS) {
            Log.w(TAG, "Cannot create session: session ceiling of ${TerminalResourceGovernor.MAX_SESSIONS} reached")
            return null
        }
        val id = UUID.randomUUID().toString()
        val backend = TermuxBackend(context, id, title)
        val session = TerminalSession(
            id = id,
            backendType = TerminalBackendType.LOCAL_TERMUX,
            backend = backend
        )

        val updated = _sessions.value.toMutableList().apply { add(session) }
        _sessions.value = updated
        _activeSessionId.value = id

        scope.launch {
            session.start()
        }
        return session
    }

    fun createSshSession(profile: SshProfile): TerminalSession? {
        if (_sessions.value.size >= TerminalResourceGovernor.MAX_SESSIONS) {
            Log.w(TAG, "Cannot create session: session ceiling reached")
            return null
        }
        val id = UUID.randomUUID().toString()
        val backend = SshBackend(
            context = context,
            profile = profile,
            sessionId = id,
            onHostKeyPrompt = { hostKey ->
                hostKeyPromptHandler?.invoke(hostKey) ?: true
            }
        )
        val session = TerminalSession(
            id = id,
            backendType = TerminalBackendType.SSH,
            backend = backend,
            autoReconnect = profile.autoReconnect
        )

        val updated = _sessions.value.toMutableList().apply { add(session) }
        _sessions.value = updated
        _activeSessionId.value = id

        scope.launch {
            session.start()
        }
        return session
    }

    fun selectSession(sessionId: String) {
        if (_sessions.value.any { it.id == sessionId }) {
            _activeSessionId.value = sessionId
        }
    }

    fun closeSession(sessionId: String) {
        val session = _sessions.value.firstOrNull { it.id == sessionId } ?: return
        session.close()

        val updated = _sessions.value.toMutableList().apply { remove(session) }
        _sessions.value = updated

        if (_activeSessionId.value == sessionId) {
            _activeSessionId.value = updated.firstOrNull()?.id
        }
        if (_secondarySessionId.value == sessionId) {
            _secondarySessionId.value = null
            _splitMode.value = TerminalSplitMode.NONE
        }
    }

    fun setSplitMode(mode: TerminalSplitMode, secondaryId: String? = null) {
        _splitMode.value = mode
        if (mode == TerminalSplitMode.NONE) {
            _secondarySessionId.value = null
        } else {
            val candidate = secondaryId ?: _sessions.value.firstOrNull { it.id != _activeSessionId.value }?.id
            _secondarySessionId.value = candidate
        }
    }

    fun closeAll() {
        for (session in _sessions.value) {
            session.close()
        }
        _sessions.value = emptyList()
        _activeSessionId.value = null
        _secondarySessionId.value = null
        _splitMode.value = TerminalSplitMode.NONE
    }
}
