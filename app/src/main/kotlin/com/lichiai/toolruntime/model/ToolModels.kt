package com.lichiai.toolruntime.model

import com.lichiai.intent.model.LichiCapability
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * High-level category grouping for Lichi tools.
 * Used for token-budgeted tool discovery and selective context activation.
 */
enum class ToolCategory(val displayName: String) {
    WEB("Real-Time Web Intelligence"),
    BROWSER("Chromium Browser & Web Navigation"),
    ANDROID("Android Device Interaction & UI Automation"),
    TELEPHONY("Phone Calls & Telephony"),
    TERMINAL("Linux / SSH Terminal"),
    DEVICE("System & Device Controls"),
    TIME_REMINDER("Alarms, Timers & Reminders"),
    MEMORY("Long-Term User Memory & Knowledge"),
    SKILLS("Installed Skills & Tool Management"),
    MEDIA("Music & Media Playback")
}

/**
 * Risk classification for tool operations.
 * High-risk or destructive actions require explicit user confirmation.
 */
enum class ToolRiskLevel {
    READ_ONLY,
    LOW_RISK_STATE_CHANGE,
    HIGH_RISK_STATE_CHANGE,
    DESTRUCTIVE,
    EXTERNAL_COMMUNICATION,
    PRIVACY_SENSITIVE
}

/**
 * Lifecycle state for autonomous task execution.
 */
enum class TaskLifecycleState {
    CREATED,
    UNDERSTANDING,
    DECIDING,
    WAITING_CONFIRMATION,
    EXECUTING,
    VERIFYING,
    RECOVERING,
    COMPLETED,
    FAILED,
    CANCELLED,
    PAUSED
}

/**
 * Outcome status of a tool execution and verification cycle.
 */
enum class ToolExecutionOutcome {
    EXECUTION_FAILED,
    EXECUTION_SUCCEEDED_UNVERIFIED,
    EXECUTION_SUCCEEDED_VERIFIED,
    VERIFICATION_FAILED,
    RECOVERABLE_FAILURE,
    UNRECOVERABLE_FAILURE
}

/**
 * Parameter definition for a tool's machine-readable schema.
 */
@Serializable
data class ToolParameter(
    val name: String,
    val type: String, // "string", "number", "boolean", "array", "object"
    val description: String,
    val required: Boolean = true,
    val enumValues: List<String>? = null,
    val defaultValue: String? = null
)

/**
 * Authoritative capability contract for a Lichi tool.
 * The central brain only sees tools that have REAL executable implementations.
 */
data class ToolDefinition(
    val id: String,
    val name: String,
    val description: String,
    val purpose: String,
    val category: ToolCategory,
    val mappedCapability: LichiCapability,
    val parameters: List<ToolParameter>,
    val riskLevel: ToolRiskLevel = ToolRiskLevel.LOW_RISK_STATE_CHANGE,
    val requiresConfirmation: Boolean = false,
    val idempotent: Boolean = false,
    val timeoutMs: Long = 30_000L,
    val requiresNetwork: Boolean = false,
    val changesWorldState: Boolean = false,
    val isAvailable: () -> Boolean = { true }
) {
    fun toCompactSummary(): String {
        val params = parameters.joinToString(", ") { p ->
            "${p.name}${if (p.required) "*" else ""}: ${p.type}"
        }
        return "[$id] ($name): $description. Parameters: ($params)"
    }
}

/**
 * Structured tool call request produced by the central LLM brain.
 * Uses compact decision summary instead of raw chain-of-thought.
 */
@Serializable
data class ToolCall(
    val callId: String = UUID.randomUUID().toString(),
    val toolId: String,
    val arguments: Map<String, String> = emptyMap(),
    @SerialName("decision_summary") val decisionSummary: String = ""
) {
    // Backwards-compatible accessor
    val thought: String get() = decisionSummary
}

/**
 * Truthful, structured execution result returned by a tool executor.
 * Invariant: outputSummary represents what ACTUALLY happened, not what was expected.
 */
data class ToolResult(
    val callId: String,
    val toolId: String,
    val isSuccess: Boolean,
    val outputSummary: String,
    val data: Map<String, String> = emptyMap(),
    val rawOutput: String? = null,
    val errorMessage: String? = null,
    val isRecoverable: Boolean = true,
    val outcome: ToolExecutionOutcome = if (isSuccess) ToolExecutionOutcome.EXECUTION_SUCCEEDED_UNVERIFIED else ToolExecutionOutcome.EXECUTION_FAILED
) {
    companion object {
        fun success(
            callId: String,
            toolId: String,
            summary: String,
            data: Map<String, String> = emptyMap(),
            rawOutput: String? = null,
            outcome: ToolExecutionOutcome = ToolExecutionOutcome.EXECUTION_SUCCEEDED_UNVERIFIED
        ) = ToolResult(
            callId = callId,
            toolId = toolId,
            isSuccess = true,
            outputSummary = summary,
            data = data,
            rawOutput = rawOutput,
            outcome = outcome
        )

        fun failure(
            callId: String,
            toolId: String,
            error: String,
            isRecoverable: Boolean = true,
            outcome: ToolExecutionOutcome = ToolExecutionOutcome.EXECUTION_FAILED
        ) = ToolResult(
            callId = callId,
            toolId = toolId,
            isSuccess = false,
            outputSummary = error,
            errorMessage = error,
            isRecoverable = isRecoverable,
            outcome = outcome
        )
    }
}

/**
 * Result of deterministic post-execution verification.
 */
data class VerificationResult(
    val isVerified: Boolean,
    val verifiedState: String,
    val notes: String = "",
    val outcome: ToolExecutionOutcome = if (isVerified) ToolExecutionOutcome.EXECUTION_SUCCEEDED_VERIFIED else ToolExecutionOutcome.VERIFICATION_FAILED
)

/**
 * Execution context provided to a tool during runtime.
 */
data class ToolExecutionContext(
    val conversationId: String = "default_session",
    val requestId: String = "",
    val userGoal: String = "",
    val worldState: WorldRuntimeState = WorldRuntimeState(),
    val onProgress: ((step: Int, total: Int, text: String) -> Unit)? = null
)

/**
 * Unified runtime world state representing known environment reality.
 * Compact and efficient to prevent token explosion.
 */
@Serializable
data class WorldRuntimeState(
    val activeTopic: String? = null,
    val activeGoal: String? = null,
    val currentBrowserUrl: String? = null,
    val currentBrowserTitle: String? = null,
    val activeCallState: String? = null,
    val activeTerminalSession: String? = null,
    val verifiedFacts: Map<String, String> = emptyMap(),
    val recentExecutedTools: List<String> = emptyList(),
    val recentToolSummaries: List<String> = emptyList(),
    val batteryOrSystemInfo: String? = null
)

/**
 * Paused cognitive task state for seamless resumption.
 */
@Serializable
data class PausedTaskState(
    val taskId: String = UUID.randomUUID().toString(),
    val conversationId: String = "default_session",
    val userGoal: String,
    val worldState: WorldRuntimeState = WorldRuntimeState(),
    val executedToolIds: List<String> = emptyList(),
    val verifiedSummaries: List<String> = emptyList(),
    val pendingConfirmationPrompt: String? = null,
    val pendingToolCall: ToolCall? = null,
    val status: TaskLifecycleState = TaskLifecycleState.PAUSED,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Decision returned by the central cognitive brain at each reasoning step.
 */
sealed class BrainDecision {
    data class InvokeTool(val toolCall: ToolCall, val decisionSummary: String = "") : BrainDecision()
    data class FinalAnswer(val answer: String, val decisionSummary: String = "", val verifiedResults: List<ToolResult> = emptyList()) : BrainDecision()
    data class Clarify(val question: String, val decisionSummary: String = "") : BrainDecision()
    data class RequireConfirmation(val confirmationPrompt: String, val toolCall: ToolCall, val decisionSummary: String = "") : BrainDecision()
    data class DirectChat(val prompt: String) : BrainDecision()
    data class Abort(val reason: String) : BrainDecision()
}
