package com.lichiai.orchestrator.evaluator

import com.lichiai.api.ChatMessage
import com.lichiai.api.LlmClient
import com.lichiai.data.ProviderConfig
import com.lichiai.orchestrator.model.EvaluationAction
import com.lichiai.orchestrator.model.PlanStep
import com.lichiai.orchestrator.model.StepEvaluation
import com.lichiai.orchestrator.model.StepExecutionRecord
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Locale

@Serializable
internal data class EvaluationJson(
    val action: String, // COMPLETE | NEXT_STEP | RETRY | RECOVER | ABORT
    val reasoning: String,
    val final_response: String
)

/**
 * Closed-Loop Result Feedback Evaluator.
 * Takes the REAL execution result from existing Lichi executors and evaluates
 * whether the step satisfied its expected outcome and the overall user goal.
 *
 * Truth priority:
 * 1. Real executor output / device state
 * 2. Verification checks
 * 3. LLM semantic reasoning
 */
class TaskResultEvaluator(
    private val llmClient: LlmClient? = null
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    companion object {
        private const val EVAL_SYSTEM_PROMPT = """You are Lichi's Task Verification and Result Evaluation Engine.
Your job is to inspect the ACTUAL execution result returned by a Lichi capability and determine:
1. Did the action genuinely achieve the expected outcome?
2. Is the overall user task complete, or does it need to continue to the next step?
3. If an error occurred, should we retry or abort with an honest failure message?

NEVER claim success if the executor reported a failure or error.
Ground your response strictly in the REAL executor result.

Output strictly valid JSON:
{
  "action": "COMPLETE" | "NEXT_STEP" | "RETRY" | "ABORT",
  "reasoning": "1 sentence explanation of verification",
  "final_response": "Polite verified message to user summarizing outcome"
}"""
    }

    suspend fun evaluateStepResult(
        userGoal: String,
        executedStep: PlanStep,
        stepRecord: StepExecutionRecord,
        hasMoreSteps: Boolean,
        nextStep: PlanStep?,
        allRecords: List<StepExecutionRecord>,
        provider: ProviderConfig? = null,
        modelId: String? = null
    ): StepEvaluation {
        // Deterministic guard 1: If real executor failed, do not claim success!
        if (!stepRecord.isSuccess) {
            val failureMsg = stepRecord.outputSummary.ifBlank { "Action could not be completed." }
            return StepEvaluation(
                action = EvaluationAction.ABORT,
                reasoning = "Executor reported failure: $failureMsg",
                verifiedResponse = "⚠️ $failureMsg"
            )
        }

        // Deterministic guard 2: If there are more steps in the plan, advance to NEXT_STEP
        if (hasMoreSteps) {
            return StepEvaluation(
                action = EvaluationAction.NEXT_STEP,
                reasoning = "Step ${executedStep.stepIndex + 1} succeeded. Advancing to step ${executedStep.stepIndex + 2}.",
                verifiedResponse = stepRecord.outputSummary
            )
        }

        // Single step or final step: If LLM is available and multiple steps were executed,
        // we can synthesize a high-fidelity final response. Otherwise, verify deterministically.
        if (llmClient != null && provider != null && !modelId.isNullOrBlank() && (allRecords.size > 1 || executedStep.capability == com.lichiai.intent.model.LichiCapability.TERMINAL) && provider.apiKey.isNotBlank()) {
            val synthesized = runCatching {
                queryLlmEvaluation(userGoal, executedStep, stepRecord, allRecords, provider, modelId)
            }.getOrNull()

            if (synthesized != null) {
                return synthesized
            }
        }

        // Deterministic verification fallback with smart domain formatting
        val cleanSummary = if (executedStep.capability == com.lichiai.intent.model.LichiCapability.TERMINAL) {
            val raw = (stepRecord.rawResult as? com.lichiai.intent.dispatcher.DispatchExecutionResult.TerminalExecuted)?.rawOutput ?: ""
            synthesizeTerminalSummary(executedStep.arguments["command"] ?: executedStep.action, raw, stepRecord.outputSummary)
        } else {
            stepRecord.outputSummary.ifBlank { "Task completed successfully." }
        }

        return StepEvaluation(
            action = EvaluationAction.COMPLETE,
            reasoning = "Step verified via executor output.",
            verifiedResponse = cleanSummary
        )
    }

    private fun synthesizeTerminalSummary(command: String, rawOutput: String, defaultSummary: String): String {
        if (command.contains("df -h") || command.contains("df")) {
            val rootLine = rawOutput.lines().firstOrNull { it.endsWith(" /") || it.contains(" /") }
            if (rootLine != null) {
                val percentMatch = Regex("(\\d+%)").find(rootLine)
                if (percentMatch != null) {
                    return "Server ki root disk ${percentMatch.value} full hai."
                }
            }
        }
        return defaultSummary.ifBlank { "Command executed successfully." }
    }

    private suspend fun queryLlmEvaluation(
        userGoal: String,
        executedStep: PlanStep,
        stepRecord: StepExecutionRecord,
        allRecords: List<StepExecutionRecord>,
        provider: ProviderConfig,
        modelId: String
    ): StepEvaluation? {
        val prompt = buildString {
            append("User Goal: \"$userGoal\"\n")
            append("Executed Steps & Real Results:\n")
            for ((idx, rec) in allRecords.withIndex()) {
                append("${idx + 1}. [${rec.step.capability.name}] ${rec.step.action}: Success=${rec.isSuccess}, Result=\"${rec.outputSummary}\"\n")
            }
        }

        val response = llmClient?.chatCompletion(
            provider = provider,
            modelId = modelId,
            messages = listOf(
                ChatMessage(role = "system", content = EVAL_SYSTEM_PROMPT),
                ChatMessage(role = "user", content = prompt)
            ),
            temperature = 0.0f
        ) ?: return null

        val parsed = parseEvalJson(response) ?: return null
        val action = when (parsed.action.uppercase(Locale.ROOT)) {
            "NEXT_STEP" -> EvaluationAction.NEXT_STEP
            "RETRY" -> EvaluationAction.RETRY
            "ABORT" -> EvaluationAction.ABORT
            else -> EvaluationAction.COMPLETE
        }

        return StepEvaluation(
            action = action,
            reasoning = parsed.reasoning,
            verifiedResponse = parsed.final_response
        )
    }

    private fun parseEvalJson(raw: String): EvaluationJson? {
        val trimmed = raw.trim()
        val jsonStr = if (trimmed.contains("{") && trimmed.contains("}")) {
            val start = trimmed.indexOf("{")
            val end = trimmed.lastIndexOf("}")
            trimmed.substring(start, end + 1)
        } else trimmed

        return runCatching {
            json.decodeFromString<EvaluationJson>(jsonStr)
        }.getOrNull()
    }
}
