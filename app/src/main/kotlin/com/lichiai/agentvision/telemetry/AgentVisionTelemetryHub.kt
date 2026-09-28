package com.lichiai.agentvision.telemetry

import android.util.Log
import com.lichiai.agentvision.coordinate.CoordinateMapper
import com.lichiai.agentvision.model.AgentVisualEvent
import com.lichiai.agentvision.model.VisualActionType
import com.lichiai.agentvision.model.VisualBounds
import com.lichiai.agentvision.model.VisualPhase
import com.lichiai.agentvision.model.VisualPosition
import com.lichiai.agentvision.model.VisualSource
import com.lichiai.agentvision.state.AgentVisionReplayData
import com.lichiai.agentvision.state.AgentVisionSessionState
import com.lichiai.agentvision.state.AgentVisionTimelineItem
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Isolated Telemetry Hub for Agent Vision.
 *
 * OBSERVER ONLY:
 * 1. Receives real events from Agent V2 ActionExecutor and BrowserExecutor.
 * 2. Enforces Task Isolation (each task has its own state & timeline).
 * 3. Sanitizes sensitive data (passwords, OTPs, tokens, banking info).
 * 4. Dispatches updates to UI renderers and Dynamic Island.
 */
object AgentVisionTelemetryHub {

    private const val TAG = "AgentVisionHub"

    private val _eventsFlow = MutableSharedFlow<AgentVisualEvent>(extraBufferCapacity = 64)
    val eventsFlow: SharedFlow<AgentVisualEvent> = _eventsFlow.asSharedFlow()

    // Task-isolated session states indexed by taskId
    private val sessions = ConcurrentHashMap<String, AgentVisionSessionState>()
    private val sessionHistories = ConcurrentHashMap<String, MutableList<AgentVisualEvent>>()

    // Active session state flow for current foreground task
    private val _activeSessionState = MutableStateFlow<AgentVisionSessionState?>(null)
    val activeSessionState: StateFlow<AgentVisionSessionState?> = _activeSessionState.asStateFlow()

    @Volatile
    private var currentActiveTaskId: String? = null

    /**
     * Emits a real visual event from an executor.
     */
    fun emitEvent(event: AgentVisualEvent) {
        val sanitized = sanitizeEvent(event)
        _eventsFlow.tryEmit(sanitized)

        val taskId = sanitized.taskId.ifBlank { "default_task" }
        currentActiveTaskId = taskId

        val history = sessionHistories.getOrPut(taskId) { mutableListOf() }
        synchronized(history) {
            history.add(sanitized)
            if (history.size > 200) history.removeAt(0)
        }

        updateSessionState(taskId, sanitized)
    }

    /**
     * Sets or switches the active foreground task for Agent Vision rendering.
     */
    fun setActiveTask(taskId: String, goal: String = "", source: VisualSource = VisualSource.ANDROID_AGENT) {
        currentActiveTaskId = taskId
        val existing = sessions[taskId]
        if (existing != null) {
            _activeSessionState.value = existing
        } else {
            val fresh = AgentVisionSessionState(
                taskId = taskId,
                userGoal = goal,
                source = source
            )
            sessions[taskId] = fresh
            _activeSessionState.value = fresh
        }
    }

    /**
     * Gets the current session state for a specific task.
     */
    fun getSession(taskId: String): AgentVisionSessionState? = sessions[taskId]

    /**
     * Gets visual replay history for a completed task (VISUAL ONLY).
     */
    fun getReplayData(taskId: String): AgentVisionReplayData? {
        val session = sessions[taskId] ?: return null
        val history = sessionHistories[taskId] ?: emptyList()
        val duration = if (history.size >= 2) {
            history.last().timestamp - history.first().timestamp
        } else 0L

        return AgentVisionReplayData(
            taskId = taskId,
            goal = session.userGoal,
            source = session.source,
            events = history.toList(),
            durationMs = duration
        )
    }

    /**
     * Clears old or cancelled task visualization state to prevent leaks.
     */
    fun clearTask(taskId: String) {
        sessions.remove(taskId)
        sessionHistories.remove(taskId)
        if (currentActiveTaskId == taskId) {
            currentActiveTaskId = null
            _activeSessionState.value = null
        }
    }

    fun resetAll() {
        sessions.clear()
        sessionHistories.clear()
        currentActiveTaskId = null
        _activeSessionState.value = null
    }

    private fun updateSessionState(taskId: String, event: AgentVisualEvent) {
        val prev = sessions[taskId] ?: AgentVisionSessionState(taskId = taskId, source = event.source)

        val newTargetBounds = if (event.targetBounds != null && event.targetBounds.isValid()) {
            event.targetBounds
        } else if (event.actionType == VisualActionType.SCROLL || event.actionType == VisualActionType.NAVIGATE) {
            null // Invalidate target on page scroll or navigation
        } else {
            prev.targetBounds
        }

        val newPosition = event.targetPosition
            ?: CoordinateMapper.calculateCenter(event.targetBounds)
            ?: prev.cursorPosition

        val isInteracting = event.phase == VisualPhase.EXECUTING || event.phase == VisualPhase.STARTED
        val isTyping = event.actionType == VisualActionType.TYPE && isInteracting
        val isScrolling = event.actionType == VisualActionType.SCROLL && isInteracting

        // Trail points
        val updatedTrail = (prev.trailPoints + newPosition).takeLast(16)

        // Timeline management
        val updatedTimeline = prev.timeline.toMutableList()
        if (event.phase == VisualPhase.COMPLETED || event.phase == VisualPhase.FAILED) {
            val item = AgentVisionTimelineItem(
                stepId = event.stepId,
                timestamp = event.timestamp,
                actionType = event.actionType,
                title = event.operationalDescription.ifBlank { formatDefaultTitle(event) },
                detail = event.resultSummary ?: event.error ?: "",
                phase = event.phase,
                isSuccess = event.phase == VisualPhase.COMPLETED,
                targetIdentifier = event.targetIdentifier
            )
            updatedTimeline.add(item)
            if (updatedTimeline.size > 25) updatedTimeline.removeAt(0)
        }

        val updatedSession = prev.copy(
            source = event.source,
            currentAction = event,
            cursorPosition = newPosition,
            targetBounds = newTargetBounds,
            isInteracting = isInteracting,
            isTyping = isTyping,
            typingMasked = if (isTyping) (event.typedMaskedText ?: "••••••") else "",
            isScrolling = isScrolling,
            scrollDirection = if (event.scrollDeltaY > 0) "DOWN" else if (event.scrollDeltaY < 0) "UP" else "DOWN",
            statusText = event.operationalDescription.ifBlank { formatDefaultStatus(event) },
            isCompleted = event.actionType == VisualActionType.SUCCESS || (event.actionType == VisualActionType.VERIFY && event.phase == VisualPhase.COMPLETED),
            isFailed = event.phase == VisualPhase.FAILED || event.actionType == VisualActionType.FAILURE,
            errorMessage = event.error,
            timeline = updatedTimeline,
            trailPoints = updatedTrail,
            lastUpdated = System.currentTimeMillis()
        )

        sessions[taskId] = updatedSession
        if (currentActiveTaskId == taskId) {
            _activeSessionState.value = updatedSession
        }
    }

    private fun formatDefaultTitle(event: AgentVisualEvent): String {
        return when (event.actionType) {
            VisualActionType.TAP -> "Clicked ${event.targetIdentifier ?: "element"}"
            VisualActionType.TYPE -> "Typed text"
            VisualActionType.SCROLL -> "Scrolled screen"
            VisualActionType.OPEN_TAB -> "Opened new tab"
            VisualActionType.CLOSE_TAB -> "Closed tab"
            VisualActionType.SWITCH_TAB -> "Switched tab"
            VisualActionType.NAVIGATE -> "Navigated to destination"
            VisualActionType.VERIFY -> "Verified screen state"
            VisualActionType.SUCCESS -> "Task completed"
            VisualActionType.FAILURE -> "Action failed"
            else -> "Interacted with screen"
        }
    }

    private fun formatDefaultStatus(event: AgentVisualEvent): String {
        return when (event.actionType) {
            VisualActionType.TAP -> "Clicking ${event.targetIdentifier ?: "element"}..."
            VisualActionType.TYPE -> "Typing in input field..."
            VisualActionType.SCROLL -> "Scrolling..."
            VisualActionType.NAVIGATE -> "Navigating..."
            VisualActionType.VERIFY -> "Verifying destination..."
            VisualActionType.SUCCESS -> "Task completed"
            VisualActionType.FAILURE -> "Failed: ${event.error ?: "Unknown error"}"
            else -> "Agent working..."
        }
    }

    /**
     * Sanitizes telemetry to prevent leaking API keys, passwords, OTPs, or auth tokens.
     */
    private fun sanitizeEvent(event: AgentVisualEvent): AgentVisualEvent {
        val desc = redactSensitiveInfo(event.operationalDescription)
        val summary = event.resultSummary?.let { redactSensitiveInfo(it) }
        val err = event.error?.let { redactSensitiveInfo(it) }
        val maskedText = if (event.typedMaskedText != null) {
            maskTypedContent(event.typedMaskedText)
        } else if (event.actionType == VisualActionType.TYPE && event.textLength > 0) {
            "•".repeat(event.textLength.coerceIn(1, 16))
        } else null

        return event.copy(
            operationalDescription = desc,
            resultSummary = summary,
            error = err,
            typedMaskedText = maskedText
        )
    }

    private val SENSITIVE_PATTERNS = listOf(
        Regex("(?i)(password|passwd|pwd)\\s*[:=]\\s*\\S+"),
        Regex("(?i)(api[_-]?key|token|bearer|secret)\\s*[:=]\\s*[A-Za-z0-9_\\-\\.]+"),
        Regex("\\b\\d{4,8}\\b"), // OTP / pin pattern
        Regex("(?i)(cvv|card number|cardholder)\\s*[:=]?\\s*\\S+")
    )

    private fun redactSensitiveInfo(input: String): String {
        var text = input
        for (pattern in SENSITIVE_PATTERNS) {
            text = text.replace(pattern, "[PROTECTED]")
        }
        return text
    }

    private fun maskTypedContent(text: String): String {
        val lower = text.lowercase()
        val isLikelySensitive = lower.contains("key") || lower.contains("pass") ||
                lower.contains("pin") || lower.contains("otp") || text.matches(Regex("\\d{4,8}"))

        return if (isLikelySensitive) {
            "•".repeat(text.length.coerceIn(4, 16))
        } else {
            // Mask all but first and last character for privacy
            if (text.length <= 3) "•••"
            else "${text.first()}${"•".repeat(text.length - 2)}${text.last()}"
        }
    }
}
