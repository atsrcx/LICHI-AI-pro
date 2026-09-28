package com.lichiai.terminal.task

import android.content.Context
import android.util.Log
import com.lichiai.dynamicisland.LichiAssistantStateHub
import com.lichiai.terminal.model.TerminalBackendType
import com.lichiai.terminal.model.TerminalRiskLevel
import com.lichiai.ui.activity.ActivityKind
import com.lichiai.ui.activity.AssistantActivityState
import com.lichiai.ui.activity.AssistantActivityStep
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Single source of truth for Terminal V3 task execution, history, and multi-surface projection.
 * Coordinates Chat Live Activity, Status Screen, Dynamic Island, and Autonomous Agent V2.
 */
class TerminalTaskManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "TerminalTaskManager"
        private const val MAX_TASK_HISTORY = 50

        @Volatile
        private var instance: TerminalTaskManager? = null

        fun getInstance(context: Context): TerminalTaskManager {
            return instance ?: synchronized(this) {
                instance ?: TerminalTaskManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val _tasks = MutableStateFlow<List<TerminalTaskRecord>>(emptyList())
    val tasks: StateFlow<List<TerminalTaskRecord>> = _tasks.asStateFlow()

    private val _activeTaskId = MutableStateFlow<String?>(null)
    val activeTaskId: StateFlow<String?> = _activeTaskId.asStateFlow()

    private val _currentLiveActivity = MutableStateFlow<AssistantActivityState?>(null)
    val currentLiveActivity: StateFlow<AssistantActivityState?> = _currentLiveActivity.asStateFlow()

    fun createTask(
        command: String,
        requestId: String = "",
        messageId: String = "",
        backendType: TerminalBackendType = TerminalBackendType.LOCAL_TERMUX,
        targetHost: String? = null,
        username: String? = null,
        riskLevel: TerminalRiskLevel = TerminalRiskLevel.LOW_RISK
    ): TerminalTaskRecord {
        val taskId = "term_task_${UUID.randomUUID().toString().take(8)}"
        val initialEvent = TerminalTaskEvent(
            id = "evt_${System.currentTimeMillis()}",
            taskId = taskId,
            eventType = "TASK_STARTED",
            summary = "Task initialized",
            detail = "Target: ${if (backendType == TerminalBackendType.SSH) "$username@$targetHost" else "Local Termux"}"
        )

        val record = TerminalTaskRecord(
            taskId = taskId,
            requestId = requestId,
            conversationMessageId = messageId,
            backendType = backendType,
            targetHost = targetHost,
            username = username,
            command = command,
            status = TerminalTaskStatus.STARTED,
            riskLevel = riskLevel,
            events = listOf(initialEvent)
        )

        _tasks.value = (listOf(record) + _tasks.value).take(MAX_TASK_HISTORY)
        _activeTaskId.value = taskId

        projectToChatAndIsland(record)
        return record
    }

    fun updateTask(
        taskId: String,
        status: TerminalTaskStatus,
        eventSummary: String,
        eventDetail: String? = null,
        rawOutput: String? = null,
        outputSummary: String? = null,
        error: String? = null
    ) {
        val currentList = _tasks.value
        val existing = currentList.firstOrNull { it.taskId == taskId } ?: return

        val newEvent = TerminalTaskEvent(
            id = "evt_${System.currentTimeMillis()}",
            taskId = taskId,
            eventType = status.name,
            summary = eventSummary,
            detail = eventDetail
        )

        val updatedRecord = existing.copy(
            status = status,
            updatedAt = System.currentTimeMillis(),
            durationMs = System.currentTimeMillis() - existing.createdAt,
            rawOutput = rawOutput ?: existing.rawOutput,
            outputSummary = outputSummary ?: existing.outputSummary,
            error = error ?: existing.error,
            events = existing.events + newEvent
        )

        _tasks.value = currentList.map { if (it.taskId == taskId) updatedRecord else it }

        if (updatedRecord.isFinished) {
            if (_activeTaskId.value == taskId) {
                _activeTaskId.value = null
            }
        }

        projectToChatAndIsland(updatedRecord)
    }

    fun clearHistory() {
        _tasks.value = _tasks.value.filter { !it.isFinished }
    }

    private fun projectToChatAndIsland(record: TerminalTaskRecord) {
        val isRunning = !record.isFinished
        val targetDesc = if (record.backendType == TerminalBackendType.SSH) {
            "${record.username ?: "user"}@${record.targetHost ?: "remote"}"
        } else {
            "Local Termux"
        }

        val stepHistory = record.events.mapIndexed { idx, evt ->
            AssistantActivityStep(
                stepIndex = idx + 1,
                title = evt.summary,
                detail = evt.detail ?: "",
                isCompleted = true,
                isFailed = record.status == TerminalTaskStatus.FAILED && idx == record.events.lastIndex
            )
        }

        val kind = when (record.status) {
            TerminalTaskStatus.COMPLETED -> ActivityKind.COMPLETED
            TerminalTaskStatus.FAILED -> ActivityKind.FAILED
            TerminalTaskStatus.CANCELLED -> ActivityKind.IDLE
            else -> ActivityKind.TERMINAL_EXECUTING
        }

        val title = when (record.status) {
            TerminalTaskStatus.STARTED, TerminalTaskStatus.CONNECTING -> "Connecting to $targetDesc"
            TerminalTaskStatus.CONNECTED -> "Connected to $targetDesc"
            TerminalTaskStatus.EXECUTING -> "Running: ${record.command.take(30)}"
            TerminalTaskStatus.VERIFYING -> "Verifying command output"
            TerminalTaskStatus.COMPLETED -> "Terminal execution completed"
            TerminalTaskStatus.FAILED -> "Terminal execution failed"
            TerminalTaskStatus.CANCELLED -> "Terminal task cancelled"
        }

        val subtitle = if (record.status == TerminalTaskStatus.FAILED) {
            record.error ?: "Encountered an error"
        } else {
            "${record.backendType.name.replace("_", " ")} • ${record.command.take(40)}"
        }

        val chatActivity = AssistantActivityState(
            requestId = record.requestId,
            messageId = record.conversationMessageId,
            kind = kind,
            title = title,
            subtitle = subtitle,
            step = record.events.size,
            totalSteps = 5,
            isActive = isRunning,
            error = record.error,
            detailNotes = listOf("Command: ${record.command}", "Host: $targetDesc"),
            stepHistory = stepHistory,
            timestamp = record.updatedAt
        )

        _currentLiveActivity.value = chatActivity

        // Project to Dynamic Island
        try {
            if (isRunning) {
                LichiAssistantStateHub.onToolExecution("Terminal: ${record.command.take(24)}")
            } else {
                LichiAssistantStateHub.resetToIdle()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed projecting to Dynamic Island", t)
        }
    }
}
