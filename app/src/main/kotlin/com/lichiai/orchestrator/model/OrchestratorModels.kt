package com.lichiai.orchestrator.model

import com.lichiai.intent.model.LichiCapability
import com.lichiai.intent.model.ResolvedIntent
import com.lichiai.intent.model.RiskLevel
import kotlinx.serialization.Serializable

/**
 * Operating mode of the Universal Task Orchestrator V2.
 * Controlled via AppSettings to allow safe shadow evaluation and canary rollouts.
 */
enum class OrchestratorMode {
    DISABLED,  // Legacy routing path only
    SHADOW,    // Runs Orchestrator V2 in shadow mode, logs comparison, executes legacy route
    CANARY,    // Runs Orchestrator V2 for non-destructive / verified capability subset
    ENABLED    // Universal closed-loop orchestration across all capabilities
}

/**
 * Top-level classification of what the user is asking the system to do.
 */
enum class DecisionMode {
    EXECUTE,      // Execute one or more capabilities according to a plan
    CONVERSE,     // Direct conversational answer / chit-chat
    CLARIFY,      // Ambiguity cannot be resolved safely; ask user for clarification
    CONFIRM,      // High-risk action requiring explicit user confirmation
    CANCEL,       // Cancel active task / dismiss
    INTERRUPT,    // Pause current task and execute higher-priority request
    RESUME,       // Resume a paused/interrupted task
    MODIFY_TASK,  // User changed parameters or targets for active task
    DONE          // Task finished
}

/**
 * Lifecycle state of a specific unit of work (Task).
 */
enum class TaskState {
    PENDING,
    RUNNING,
    PAUSED,
    WAITING_CONFIRMATION,
    WAITING_CLARIFICATION,
    COMPLETED,
    FAILED,
    CANCELLED
}

/**
 * A single atomic step in an orchestrated task plan.
 */
@Serializable
data class PlanStep(
    val stepIndex: Int,
    val capability: LichiCapability,
    val action: String,
    val arguments: Map<String, String> = emptyMap(),
    val expectedOutcome: String,
    val riskLevel: RiskLevel = RiskLevel.LOW
)

/**
 * Execution record of an individual plan step.
 */
data class StepExecutionRecord(
    val step: PlanStep,
    val isSuccess: Boolean,
    val outputSummary: String,
    val rawResult: Any? = null,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Canonical task plan representing a sequence of capabilities needed to satisfy the user goal.
 * Every actionable task maintains its own deterministic IDs.
 */
data class TaskPlan(
    val taskId: String,
    val userGoal: String,
    val rawInput: String,
    val steps: List<PlanStep>,
    val currentStepIndex: Int = 0,
    val executionRecords: List<StepExecutionRecord> = emptyList(),
    val isCompleted: Boolean = false,
    val failedStepIndex: Int? = null,
    val failureReason: String? = null,
    val conversationId: String = "",
    val requestId: String = "",
    val messageId: String = "",
    val sessionId: String = "",
    val taskState: TaskState = TaskState.RUNNING,
    val pausedAt: Long? = null
) {
    val currentStep: PlanStep? get() = steps.getOrNull(currentStepIndex)
    val hasMoreSteps: Boolean get() = currentStepIndex < steps.size
    val isPaused: Boolean get() = taskState == TaskState.PAUSED
}

/**
 * Structured decision emitted by the LLM Planner.
 */
data class OrchestrationDecision(
    val mode: DecisionMode,
    val goal: String,
    val plan: List<PlanStep> = emptyList(),
    val naturalAcknowledgment: String,
    val reasoningSummary: String = "",
    val clarificationQuestion: String? = null,
    val confirmationPrompt: String? = null,
    val requiresConfirmation: Boolean = false,
    val legacyResolvedIntent: ResolvedIntent? = null,
    val isDirectResponse: Boolean = false,
    val directResponseText: String = "",
    val dialogueAct: com.lichiai.context.model.DialogueAct? = null
)

/**
 * Result evaluation action produced by the closed-loop feedback evaluator.
 */
enum class EvaluationAction {
    COMPLETE,    // Outcome satisfied; generate final response
    NEXT_STEP,   // Step succeeded; advance to next step in plan
    RETRY,       // Transient issue; retry same step with adjusted params
    RECOVER,     // Step failed; execute alternative recovery step
    ABORT        // Failure is unrecoverable; terminate gracefully
}

/**
 * Evaluation of an executed step against expected outcome and overall goal.
 */
data class StepEvaluation(
    val action: EvaluationAction,
    val reasoning: String,
    val verifiedResponse: String,
    val recoveryStep: PlanStep? = null
)

/**
 * Comparison classification for Shadow Mode.
 */
enum class ShadowClassification {
    MATCH,             // Both legacy and V2 selected equivalent capability & goal
    SAFE_IMPROVEMENT,  // V2 planned multi-step or higher accuracy than legacy
    REGRESSION,        // V2 diverted from an established working legacy pattern
    AMBIGUOUS          // Divergence due to differing acceptable interpretations
}

/**
 * Shadow comparison record between legacy router and V2 orchestrator.
 */
data class ShadowComparison(
    val input: String,
    val legacyCapability: LichiCapability,
    val v2Capability: LichiCapability?,
    val classification: ShadowClassification,
    val notes: String,
    val timestamp: Long = System.currentTimeMillis()
)
