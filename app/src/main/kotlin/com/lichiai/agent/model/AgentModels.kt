package com.lichiai.agent.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Structured Agent Action produced by Agent Gemini reasoning.
 */
data class AgentAction(
    val name: String,
    val params: Map<String, String> = emptyMap()
) {
    fun param(key: String, default: String = ""): String = params[key] ?: default
    fun paramInt(key: String, default: Int = -1): Int = params[key]?.toIntOrNull() ?: default
}

/**
 * Standard structured output from Autonomous Agent V2 thinking.
 */
data class AgentOutput(
    val thinking: String = "",
    val evaluationPreviousGoal: String = "",
    val memory: String = "",
    val nextGoal: String = "",
    val action: List<AgentAction> = emptyList()
)

/**
 * Perceived interactive Android UI element.
 */
data class AndroidElement(
    val index: Int,
    val className: String = "",
    val text: String = "",
    val contentDescription: String = "",
    val resourceId: String = "",
    val bounds: String = "",
    val isClickable: Boolean = false,
    val isEditable: Boolean = false,
    val isCheckable: Boolean = false,
    val isChecked: Boolean = false,
    val isEnabled: Boolean = true
) {
    fun toReadableString(): String {
        val label = when {
            text.isNotBlank() && contentDescription.isNotBlank() -> "\"$text\" ($contentDescription)"
            text.isNotBlank() -> "\"$text\""
            contentDescription.isNotBlank() -> "\"$contentDescription\""
            resourceId.isNotBlank() -> "id:${resourceId.substringAfterLast('/')}"
            else -> className.substringAfterLast('.')
        }
        val typeShort = className.substringAfterLast('.')
        val traits = buildList {
            if (isClickable) add("clickable")
            if (isEditable) add("editable")
            if (isCheckable) add(if (isChecked) "checked" else "unchecked")
        }.joinToString(",")

        val traitStr = if (traits.isNotBlank()) " [$traits]" else ""
        return "[$index] $typeShort: $label$traitStr bounds=$bounds"
    }
}

/**
 * Complete snapshot of Android State perceived by the Agent.
 */
data class AndroidState(
    val packageName: String = "",
    val currentActivity: String = "",
    val elements: List<AndroidElement> = emptyList(),
    val isKeyboardOpen: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toPromptString(): String {
        val sb = StringBuilder()
        sb.appendLine("Current Package: $packageName")
        if (currentActivity.isNotBlank()) {
            sb.appendLine("Current Activity: $currentActivity")
        }
        sb.appendLine("Keyboard Open: $isKeyboardOpen")
        sb.appendLine("Interactive Elements (${elements.size}):")
        if (elements.isEmpty()) {
            sb.appendLine("  (No interactive elements detected on active window)")
        } else {
            for (elem in elements.take(60)) {
                sb.appendLine("  ${elem.toReadableString()}")
            }
            if (elements.size > 60) {
                sb.appendLine("  ... (+${elements.size - 60} more elements)")
            }
        }
        return sb.toString()
    }
}

/**
 * Historical record of a single Sense-Think-Act turn.
 */
data class AgentStepRecord(
    val stepNumber: Int,
    val nextGoal: String,
    val thinking: String,
    val actions: List<AgentAction>,
    val observation: String
)

enum class AgentRunStatus {
    IDLE,
    SENSING,
    THINKING,
    ACTING,
    OBSERVING,
    DONE,
    ERROR,
    CANCELLED
}

enum class AgentExecutionState {
    IDLE,
    STARTING,
    PERCEIVING,
    THINKING,
    EXECUTING,
    WAITING,
    COMPLETED,
    FAILED,
    CANCELLED
}

/**
 * Observable runtime status reflecting real-time Agent actions and states.
 */
data class AgentLiveStatus(
    val state: AgentExecutionState = AgentExecutionState.IDLE,
    val task: String = "",
    val step: Int = 0,
    val totalSteps: Int = 0,
    val activityText: String = "",
    val timestamp: Long = System.currentTimeMillis()
) {
    val isActive: Boolean
        get() = state in listOf(
            AgentExecutionState.STARTING,
            AgentExecutionState.PERCEIVING,
            AgentExecutionState.THINKING,
            AgentExecutionState.EXECUTING,
            AgentExecutionState.WAITING
        )
}

data class AgentRunResult(
    val isSuccess: Boolean,
    val summary: String,
    val totalSteps: Int,
    val error: String? = null
)
