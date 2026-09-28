package com.lichiai.agentvision.state

import com.lichiai.agentvision.model.AgentVisualEvent
import com.lichiai.agentvision.model.VisualActionType
import com.lichiai.agentvision.model.VisualBounds
import com.lichiai.agentvision.model.VisualPhase
import com.lichiai.agentvision.model.VisualPosition
import com.lichiai.agentvision.model.VisualSource
import kotlinx.serialization.Serializable

@Serializable
data class AgentVisionTimelineItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    val stepId: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val actionType: VisualActionType = VisualActionType.MOVE,
    val title: String = "",
    val detail: String = "",
    val phase: VisualPhase = VisualPhase.COMPLETED,
    val isSuccess: Boolean = true,
    val targetIdentifier: String? = null
)

@Serializable
data class AgentVisionSessionState(
    val taskId: String = "",
    val userGoal: String = "",
    val source: VisualSource = VisualSource.ANDROID_AGENT,
    val currentAction: AgentVisualEvent? = null,
    val cursorPosition: VisualPosition = VisualPosition(0f, 0f),
    val targetBounds: VisualBounds? = null,
    val isInteracting: Boolean = false,
    val isTyping: Boolean = false,
    val typingMasked: String = "",
    val isScrolling: Boolean = false,
    val scrollDirection: String = "DOWN",
    val statusText: String = "",
    val stepIndex: Int = 0,
    val totalSteps: Int = 0,
    val isCompleted: Boolean = false,
    val isFailed: Boolean = false,
    val errorMessage: String? = null,
    val timeline: List<AgentVisionTimelineItem> = emptyList(),
    val trailPoints: List<VisualPosition> = emptyList(),
    val lastUpdated: Long = System.currentTimeMillis()
)

@Serializable
data class AgentVisionReplayData(
    val taskId: String = "",
    val goal: String = "",
    val source: VisualSource = VisualSource.ANDROID_AGENT,
    val events: List<AgentVisualEvent> = emptyList(),
    val durationMs: Long = 0
)
