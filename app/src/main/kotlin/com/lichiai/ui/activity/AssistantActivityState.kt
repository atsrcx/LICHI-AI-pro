package com.lichiai.ui.activity

import com.lichiai.agent.model.AgentExecutionState
import com.lichiai.agent.model.AgentLiveStatus
import com.lichiai.web.model.ImageResult
import com.lichiai.web.model.WebActivityState
import com.lichiai.web.model.WebActivityStatus
import com.lichiai.web.model.WebResult
import kotlinx.serialization.Serializable

enum class ActivityKind {
    IDLE,
    THINKING,
    SEARCHING_WEB,
    READING_SOURCES,
    RESEARCHING,
    OPENING_BROWSER,
    INSPECTING_PAGE,
    AGENT_WORKING,
    INTERACTING_SCREEN,
    TERMINAL_EXECUTING,
    VERIFYING,
    COMPLETED,
    FAILED
}

@Serializable
data class AssistantActivityStep(
    val stepIndex: Int = 0,
    val title: String = "",
    val detail: String = "",
    val isCompleted: Boolean = true,
    val isFailed: Boolean = false
)

@Serializable
data class AssistantActivityState(
    val requestId: String = "",
    val messageId: String = "",
    val kind: ActivityKind = ActivityKind.IDLE,
    val title: String = "",
    val subtitle: String = "",
    val step: Int = 0,
    val totalSteps: Int = 0,
    val isActive: Boolean = false,
    val sources: List<WebResult> = emptyList(),
    val images: List<ImageResult> = emptyList(),
    val error: String? = null,
    val detailNotes: List<String> = emptyList(),
    val stepHistory: List<AssistantActivityStep> = emptyList(),
    val disagreementNotice: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

fun WebActivityState.toAssistantActivity(
    requestId: String = "",
    messageId: String = ""
): AssistantActivityState {
    if (this.status == WebActivityStatus.IDLE) return AssistantActivityState(requestId = requestId, messageId = messageId)

    val kind = when (this.status) {
        WebActivityStatus.STARTING, WebActivityStatus.BUILDING_QUERY, WebActivityStatus.SEARCHING ->
            ActivityKind.SEARCHING_WEB
        WebActivityStatus.RESULTS_RECEIVED, WebActivityStatus.READING_SOURCES, WebActivityStatus.EXTRACTING ->
            ActivityKind.READING_SOURCES
        WebActivityStatus.VERIFYING_FACTS ->
            ActivityKind.VERIFYING
        WebActivityStatus.ANALYZING, WebActivityStatus.GENERATING ->
            ActivityKind.RESEARCHING
        WebActivityStatus.COMPLETED ->
            ActivityKind.COMPLETED
        WebActivityStatus.FAILED ->
            ActivityKind.FAILED
        WebActivityStatus.CANCELLED, WebActivityStatus.IDLE ->
            ActivityKind.IDLE
    }

    val title = when (kind) {
        ActivityKind.SEARCHING_WEB -> "Searching the web"
        ActivityKind.READING_SOURCES -> "Reading sources"
        ActivityKind.VERIFYING -> "Verifying facts"
        ActivityKind.RESEARCHING -> "Synthesizing answer"
        ActivityKind.COMPLETED -> if (this.completedSources.isNotEmpty()) "Web research" else "Completed"
        ActivityKind.FAILED -> "Web search failed"
        else -> "Searching the web"
    }

    val subtitle = when {
        this.status == WebActivityStatus.FAILED ->
            this.error?.ifBlank { "Web search failed" } ?: "Web search failed"
        this.activeDomains.isNotEmpty() ->
            this.activeDomains.first()
        this.completedSources.isNotEmpty() -> {
            val providerPrefix = if (this.providerName.isNotBlank()) "${this.providerName} · " else ""
            "$providerPrefix${this.completedSources.size} sources"
        }
        this.query.isNotBlank() ->
            "\"${this.query.take(35)}\""
        this.message.isNotBlank() ->
            this.message
        else -> ""
    }

    return AssistantActivityState(
        requestId = requestId,
        messageId = messageId,
        kind = kind,
        title = title,
        subtitle = subtitle,
        isActive = this.isActive,
        sources = this.completedSources,
        images = this.images,
        error = this.error,
        detailNotes = this.strategyQueries,
        disagreementNotice = this.factEvidence?.disagreementNotice
    )
}

fun AgentLiveStatus.toAssistantActivity(
    requestId: String = "",
    messageId: String = ""
): AssistantActivityState {
    if (this.state == AgentExecutionState.IDLE) return AssistantActivityState(requestId = requestId, messageId = messageId)

    val kind = when (this.state) {
        AgentExecutionState.STARTING, AgentExecutionState.PERCEIVING, AgentExecutionState.THINKING ->
            ActivityKind.AGENT_WORKING
        AgentExecutionState.EXECUTING ->
            ActivityKind.INTERACTING_SCREEN
        AgentExecutionState.WAITING ->
            ActivityKind.VERIFYING
        AgentExecutionState.COMPLETED ->
            ActivityKind.COMPLETED
        AgentExecutionState.FAILED, AgentExecutionState.CANCELLED ->
            ActivityKind.FAILED
        AgentExecutionState.IDLE ->
            ActivityKind.IDLE
    }

    val title = when (kind) {
        ActivityKind.INTERACTING_SCREEN -> "Interacting with screen"
        ActivityKind.AGENT_WORKING -> "Agent working"
        ActivityKind.VERIFYING -> "Verifying device state"
        ActivityKind.COMPLETED -> "Agent completed"
        ActivityKind.FAILED -> "Agent paused"
        else -> "Agent working"
    }

    val subtitle = buildString {
        if (totalSteps > 0 && step > 0) {
            append("Step $step/$totalSteps")
            if (activityText.isNotBlank()) append(" · ")
        }
        if (activityText.isNotBlank()) {
            append(activityText)
        } else if (task.isNotBlank()) {
            append(task)
        }
    }

    return AssistantActivityState(
        requestId = requestId,
        messageId = messageId,
        kind = kind,
        title = title,
        subtitle = subtitle,
        step = this.step,
        totalSteps = this.totalSteps,
        isActive = this.isActive
    )
}
