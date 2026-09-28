package com.lichiai.terminal.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.lichiai.terminal.ipc.TerminalBinder
import com.lichiai.terminal.ipc.TerminalService
import com.lichiai.terminal.model.SshHostKey
import com.lichiai.terminal.model.SshProfile
import com.lichiai.terminal.model.TerminalDimensions
import com.lichiai.terminal.model.TerminalResult
import com.lichiai.terminal.model.TerminalRiskLevel
import com.lichiai.terminal.model.TerminalSettings
import com.lichiai.terminal.model.TerminalSplitMode
import com.lichiai.terminal.storage.SshHostKeyStore
import com.lichiai.terminal.storage.SshProfileRepository
import com.lichiai.terminal.storage.TerminalPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Primary coordinator and API gateway for Lichi Terminal V2.
 * Connects the UI, Agent V2, and Universal Task Orchestrator to the terminal runtime.
 */
class TerminalManager(private val context: Context) {

    companion object {
        private const val TAG = "TerminalManager"

        @Volatile
        private var instance: TerminalManager? = null

        fun getInstance(context: Context): TerminalManager {
            return instance ?: synchronized(this) {
                instance ?: TerminalManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val sessionManager = TerminalSessionManager(context)
    val preferences = TerminalPreferences(context)
    val profileRepository = SshProfileRepository(context)
    val hostKeyStore = SshHostKeyStore(context)

    val sessions = sessionManager.sessions
    val activeSessionId = sessionManager.activeSessionId
    val splitMode = sessionManager.splitMode
    val secondarySessionId = sessionManager.secondarySessionId

    val settings: StateFlow<TerminalSettings> = preferences.settingsFlow.stateIn(
        scope,
        SharingStarted.Eagerly,
        TerminalSettings()
    )

    val profiles: StateFlow<List<SshProfile>> = profileRepository.profilesFlow.stateIn(
        scope,
        SharingStarted.Eagerly,
        emptyList()
    )

    private val _isRuntimeServiceBound = MutableStateFlow(false)
    val isRuntimeServiceBound: StateFlow<Boolean> = _isRuntimeServiceBound.asStateFlow()

    private var terminalBinder: TerminalBinder? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.i(TAG, "Terminal runtime service connected")
            terminalBinder = service as? TerminalBinder
            _isRuntimeServiceBound.value = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "Terminal runtime service disconnected! Reconnecting...")
            terminalBinder = null
            _isRuntimeServiceBound.value = false
            bindRuntimeService()
        }
    }

    init {
        bindRuntimeService()
    }

    fun bindRuntimeService() {
        try {
            val intent = Intent(context, TerminalService::class.java)
            context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind TerminalService: ${e.message}")
        }
    }

    fun createLocalSession(title: String = "Local Shell"): TerminalSession? {
        return sessionManager.createLocalTermuxSession(title)
    }

    fun createSshSession(profile: SshProfile): TerminalSession? {
        return sessionManager.createSshSession(profile)
    }

    fun selectSession(sessionId: String) {
        sessionManager.selectSession(sessionId)
    }

    fun closeSession(sessionId: String) {
        sessionManager.closeSession(sessionId)
    }

    fun setSplitMode(mode: TerminalSplitMode, secondaryId: String? = null) {
        sessionManager.setSplitMode(mode, secondaryId)
    }

    /**
     * Executes a command on the active terminal session, with command risk analysis,
     * history recording, and output capture for Agent V2 or user input.
     */
    suspend fun executeCommand(
        commandLine: String,
        sessionId: String? = null,
        timeoutMs: Long = TerminalResourceGovernor.DEFAULT_COMMAND_TIMEOUT_MS
    ): TerminalResult = withContext(Dispatchers.IO) {
        val targetId = sessionId ?: activeSessionId.value
        val session = if (targetId != null) {
            sessions.value.firstOrNull { it.id == targetId }
        } else {
            createLocalSession()
        }

        if (session == null) {
            return@withContext TerminalResult(
                commandId = "err",
                exitCode = -1,
                output = "",
                errorOutput = "No active terminal session available.",
                isSuccess = false
            )
        }

        val risk = TerminalCommandRiskAnalyzer.analyzeRisk(commandLine)
        preferences.appendCommandHistory(commandLine)

        val startTime = System.currentTimeMillis()
        val initialLinesCount = session.getLinesSnapshot().size

        // Send command to shell / PTY with proper newline
        session.sendInput(commandLine.trim() + "\n")

        // Wait dynamically for output lines to change
        var waited = 0L
        while (waited < 1200L) {
            delay(80L)
            waited += 80L
            if (session.getLinesSnapshot().size > initialLinesCount) {
                delay(120L) // Small settle window
                break
            }
        }
        val elapsed = System.currentTimeMillis() - startTime
        val output = session.getRecentPlainText(50)

        TerminalResult(
            commandId = "cmd_${System.currentTimeMillis()}",
            exitCode = 0,
            output = output,
            executionDurationMs = elapsed,
            isSuccess = true,
            riskLevel = risk
        )
    }
}
