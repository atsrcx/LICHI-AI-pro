package com.lichiai.toolruntime.brain

import android.content.Context
import android.util.Log
import com.lichiai.api.ChatMessage
import com.lichiai.api.LlmClient
import com.lichiai.data.ProviderConfig
import com.lichiai.intent.model.LichiCapability
import com.lichiai.toolruntime.discovery.ToolDiscoveryEngine
import com.lichiai.toolruntime.guard.ToolExecutionGuard
import com.lichiai.toolruntime.model.BrainDecision
import com.lichiai.toolruntime.model.PausedTaskState
import com.lichiai.toolruntime.model.TaskLifecycleState
import com.lichiai.toolruntime.model.ToolCall
import com.lichiai.toolruntime.model.ToolExecutionContext
import com.lichiai.toolruntime.model.ToolExecutionOutcome
import com.lichiai.toolruntime.model.ToolResult
import com.lichiai.toolruntime.model.VerificationResult
import com.lichiai.toolruntime.model.WorldRuntimeState
import com.lichiai.toolruntime.registry.UnifiedToolRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
internal data class StructuredBrainResponse(
    @SerialName("decision_summary") val decisionSummary: String = "",
    val decision: String = "FINAL_ANSWER", // TOOL_CALL | FINAL_ANSWER | CLARIFY | CONFIRM | DIRECT_CHAT
    val tool: String? = null,
    val arguments: Map<String, String> = emptyMap(),
    @SerialName("final_answer") val finalAnswer: String? = null,
    val question: String? = null,
    @SerialName("confirmation_prompt") val confirmationPrompt: String? = null
)

/**
 * Result returned by the central cognitive loop.
 */
data class BrainRunResult(
    val finalSpeech: String,
    val isSuccess: Boolean,
    val executedTools: List<ToolResult> = emptyList(),
    val primaryCapability: LichiCapability = LichiCapability.CHAT,
    val requiresBrowserUi: Boolean = false,
    val webContextPrompt: String? = null,
    val isDirectChat: Boolean = false,
    val directChatPrompt: String = "",
    val requiresConfirmation: Boolean = false,
    val confirmationPrompt: String? = null,
    val pausedTask: PausedTaskState? = null,
    val lifecycleState: TaskLifecycleState = if (isSuccess) TaskLifecycleState.COMPLETED else TaskLifecycleState.FAILED
)

/**
 * ONE CENTRAL Lichi AI BRAIN.
 *
 * Implements the authoritative closed-loop cognitive runtime:
 * OBSERVE -> CONTEXT ASSEMBLY -> MODEL DECISION -> VALIDATION -> REAL EXECUTION -> VERIFICATION -> STATE UPDATE -> NEXT DECISION -> GROUNDED ANSWER.
 */
class LichiCentralBrain(
    private val context: Context? = null,
    private val toolRegistry: UnifiedToolRegistry,
    private val llmClient: LlmClient,
    private val executionGuard: ToolExecutionGuard = ToolExecutionGuard(context),
    private val discoveryEngine: ToolDiscoveryEngine = ToolDiscoveryEngine(toolRegistry)
) {

    companion object {
        private const val TAG = "LichiCentralBrain"
        private const val MAX_TOOL_STEPS = 6

        private const val SYSTEM_PROMPT = """You are the Central Cognitive Brain of LICHI-AI, an autonomous AI assistant client for Android.
Your job is to understand what the user wants, selectively invoke real tools, verify real results, and synthesize a truthful, grounded response.

OPERATIONAL INVARIANTS:
1. NEVER assume tool success. You will receive REAL verified tool execution results.
2. NEVER invent facts or hallucinate search results. If a search returns no information, acknowledge it truthfully.
3. If an action fails, state the failure honestly or choose an appropriate recovery action.
4. Distinguish purely conversational chat from real tool actions.
5. If the user asks a factual question (current events, dates, live prices, news), call "web.search".
6. If the user asks to open an app or browse a site, call the appropriate tool.
7. For simple greetings or timeless conversational questions, answer directly with decision "FINAL_ANSWER" or "DIRECT_CHAT".

Output strictly valid JSON with NO code fences and NO markdown wrapping:
{
  "decision_summary": "1 concise sentence on reasoning and next action",
  "decision": "TOOL_CALL" | "FINAL_ANSWER" | "CLARIFY" | "CONFIRM",
  "tool": "tool.id if decision is TOOL_CALL",
  "arguments": { "key": "value" },
  "final_answer": "Complete, polite response grounded strictly in actual results (if FINAL_ANSWER)",
  "question": "Question to ask user (if CLARIFY)",
  "confirmation_prompt": "Prompt asking user to confirm high-risk action (if CONFIRM)"
}"""
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Executes the central cognitive loop for a user request.
     */
    suspend fun executeGoal(
        rawInput: String,
        provider: ProviderConfig?,
        modelId: String?,
        conversationId: String = "default_session",
        requestId: String = UUID.randomUUID().toString(),
        worldState: WorldRuntimeState = WorldRuntimeState(),
        userConfirmed: Boolean = false,
        onProgress: ((step: Int, total: Int, text: String) -> Unit)? = null
    ): BrainRunResult = withContext(Dispatchers.Default) {
        val trimmed = rawInput.trim()
        if (trimmed.isBlank()) {
            return@withContext BrainRunResult(
                finalSpeech = "",
                isSuccess = true,
                isDirectChat = true,
                lifecycleState = TaskLifecycleState.COMPLETED
            )
        }

        // Fast offline fallback when provider is unconfigured
        if (provider == null || modelId.isNullOrBlank() || (provider.apiKey.isBlank() && !provider.baseUrl.contains("localhost") && !provider.baseUrl.contains("10.0.2.2"))) {
            return@withContext executeOfflineFallback(trimmed, conversationId, requestId, onProgress)
        }

        val executionHistory = mutableListOf<ToolResult>()
        val verificationHistory = mutableListOf<VerificationResult>()
        val callSignatureHistory = mutableSetOf<String>()
        var currentWorldState = worldState.copy(activeGoal = trimmed)
        var lastWebContext: String? = null
        var requiresBrowserUi = false
        var primaryCapability = LichiCapability.CHAT

        // 1. Two-stage tool discovery
        val selectedTools = discoveryEngine.selectRelevantTools(trimmed, currentWorldState)
        val toolsPrompt = toolRegistry.formatToolsForPrompt(selectedTools)
        val capabilityIndex = discoveryEngine.formatCapabilityIndex(toolRegistry.getAvailableTools())

        val conversationTurns = mutableListOf<ChatMessage>()
        conversationTurns.add(ChatMessage("system", SYSTEM_PROMPT))

        val initialUserPrompt = buildInitialPrompt(trimmed, capabilityIndex, toolsPrompt, currentWorldState)
        conversationTurns.add(ChatMessage("user", initialUserPrompt))

        onProgress?.invoke(1, MAX_TOOL_STEPS, "Lichi Brain: Evaluating task...")

        var stepCount = 0
        while (stepCount < MAX_TOOL_STEPS && isActive) {
            stepCount++

            // 2. MODEL DECISION STEP
            val llmResponseText = try {
                llmClient.chatCompletion(
                    provider = provider,
                    modelId = modelId,
                    messages = conversationTurns,
                    temperature = 0.1f
                )
            } catch (e: Exception) {
                Log.e(TAG, "LLM decision call failed: ${e.message}")
                break
            }

            val decision = parseResponse(llmResponseText)
            if (decision == null) {
                Log.w(TAG, "Could not parse JSON from LLM: $llmResponseText")
                break
            }

            // 3. PROCESS MODEL DECISION
            when (decision) {
                is BrainDecision.DirectChat -> {
                    return@withContext BrainRunResult(
                        finalSpeech = "",
                        isSuccess = true,
                        isDirectChat = true,
                        directChatPrompt = decision.prompt.ifBlank { trimmed },
                        lifecycleState = TaskLifecycleState.COMPLETED
                    )
                }

                is BrainDecision.Clarify -> {
                    return@withContext BrainRunResult(
                        finalSpeech = decision.question,
                        isSuccess = true,
                        primaryCapability = primaryCapability,
                        lifecycleState = TaskLifecycleState.COMPLETED
                    )
                }

                is BrainDecision.RequireConfirmation -> {
                    val paused = PausedTaskState(
                        conversationId = conversationId,
                        userGoal = trimmed,
                        worldState = currentWorldState,
                        executedToolIds = executionHistory.map { it.toolId },
                        verifiedSummaries = executionHistory.map { it.outputSummary },
                        pendingConfirmationPrompt = decision.confirmationPrompt,
                        pendingToolCall = decision.toolCall,
                        status = TaskLifecycleState.WAITING_CONFIRMATION
                    )
                    return@withContext BrainRunResult(
                        finalSpeech = decision.confirmationPrompt,
                        isSuccess = true,
                        requiresConfirmation = true,
                        confirmationPrompt = decision.confirmationPrompt,
                        pausedTask = paused,
                        primaryCapability = primaryCapability,
                        lifecycleState = TaskLifecycleState.WAITING_CONFIRMATION
                    )
                }

                is BrainDecision.FinalAnswer -> {
                    val answer = decision.answer.ifBlank { "Task completed." }
                    val allSuccess = executionHistory.all { it.isSuccess && it.outcome == ToolExecutionOutcome.EXECUTION_SUCCEEDED_VERIFIED }
                    return@withContext BrainRunResult(
                        finalSpeech = answer,
                        isSuccess = if (executionHistory.isEmpty()) true else allSuccess,
                        executedTools = executionHistory,
                        primaryCapability = primaryCapability,
                        requiresBrowserUi = requiresBrowserUi,
                        webContextPrompt = lastWebContext,
                        lifecycleState = TaskLifecycleState.COMPLETED
                    )
                }

                is BrainDecision.Abort -> {
                    return@withContext BrainRunResult(
                        finalSpeech = decision.reason,
                        isSuccess = false,
                        executedTools = executionHistory,
                        lifecycleState = TaskLifecycleState.FAILED
                    )
                }

                is BrainDecision.InvokeTool -> {
                    val call = decision.toolCall
                    val tool = toolRegistry.getTool(call.toolId)

                    // 4. CENTRALIZED TOOL EXECUTION GUARD CHECK
                    val guardResult = executionGuard.evaluate(tool, call, currentWorldState, userConfirmed)
                    when (guardResult) {
                        is ToolExecutionGuard.GuardResult.Denied -> {
                            Log.w(TAG, "Tool '${call.toolId}' denied by guard: ${guardResult.reason}")
                            conversationTurns.add(ChatMessage("assistant", llmResponseText))
                            conversationTurns.add(ChatMessage("user", "SECURITY NOTICE: ${guardResult.reason} Please select an alternative action or explain to user."))
                            continue
                        }
                        is ToolExecutionGuard.GuardResult.RequiresConfirmation -> {
                            val paused = PausedTaskState(
                                conversationId = conversationId,
                                userGoal = trimmed,
                                worldState = currentWorldState,
                                executedToolIds = executionHistory.map { it.toolId },
                                verifiedSummaries = executionHistory.map { it.outputSummary },
                                pendingConfirmationPrompt = guardResult.prompt,
                                pendingToolCall = call,
                                status = TaskLifecycleState.WAITING_CONFIRMATION
                            )
                            return@withContext BrainRunResult(
                                finalSpeech = guardResult.prompt,
                                isSuccess = true,
                                requiresConfirmation = true,
                                confirmationPrompt = guardResult.prompt,
                                pausedTask = paused,
                                primaryCapability = tool?.definition?.mappedCapability ?: primaryCapability,
                                lifecycleState = TaskLifecycleState.WAITING_CONFIRMATION
                            )
                        }
                        is ToolExecutionGuard.GuardResult.Allowed -> {
                            // Proceed
                        }
                    }

                    // Anti-loop / duplicate call guard
                    val callSig = "${call.toolId}:${call.arguments}"
                    if (callSignatureHistory.contains(callSig) && !tool!!.definition.idempotent) {
                        Log.w(TAG, "Detected duplicate non-idempotent tool call: $callSig. Aborting loop.")
                        conversationTurns.add(ChatMessage("assistant", llmResponseText))
                        conversationTurns.add(ChatMessage("user", "TOOL NOTICE: You have already executed '$callSig'. Do not repeat identical calls. Synthesize your final answer from existing evidence."))
                        continue
                    }
                    callSignatureHistory.add(callSig)

                    val boundTool = tool!!
                    primaryCapability = boundTool.definition.mappedCapability
                    if (primaryCapability == LichiCapability.BROWSER) {
                        requiresBrowserUi = true
                    }

                    onProgress?.invoke(stepCount, MAX_TOOL_STEPS, "Executing ${boundTool.definition.name}...")

                    val execContext = ToolExecutionContext(
                        conversationId = conversationId,
                        requestId = requestId,
                        userGoal = trimmed,
                        worldState = currentWorldState,
                        onProgress = onProgress
                    )

                    // 5. CONTROLLED EXECUTION WITH GLOBAL TIMEOUT
                    val toolResult = try {
                        val executed = withTimeoutOrNull(boundTool.definition.timeoutMs) {
                            boundTool.execute(call, execContext)
                        }
                        executed ?: ToolResult.failure(
                            call.callId,
                            boundTool.definition.id,
                            "Operation timed out after ${boundTool.definition.timeoutMs}ms.",
                            isRecoverable = true,
                            outcome = ToolExecutionOutcome.EXECUTION_FAILED
                        )
                    } catch (e: Exception) {
                        ToolResult.failure(call.callId, boundTool.definition.id, "Tool execution exception: ${e.message}")
                    }

                    // 6. DETERMINISTIC POST-EXECUTION VERIFICATION
                    onProgress?.invoke(stepCount, MAX_TOOL_STEPS, "Verifying ${boundTool.definition.name} result...")
                    val verification = boundTool.verify(call, toolResult, execContext)
                    verificationHistory.add(verification)

                    val finalizedResult = if (boundTool.definition.changesWorldState && !verification.isVerified) {
                        toolResult.copy(
                            isSuccess = false,
                            outcome = ToolExecutionOutcome.VERIFICATION_FAILED,
                            outputSummary = "Verification failed: ${verification.notes.ifBlank { "System state did not confirm action completion" }}"
                        )
                    } else {
                        toolResult.copy(outcome = verification.outcome)
                    }
                    executionHistory.add(finalizedResult)

                    if (boundTool.definition.id == "web.search" && finalizedResult.rawOutput != null) {
                        lastWebContext = finalizedResult.rawOutput
                    }

                    // 7. STATE UPDATE
                    currentWorldState = currentWorldState.copy(
                        recentExecutedTools = currentWorldState.recentExecutedTools + boundTool.definition.id,
                        recentToolSummaries = currentWorldState.recentToolSummaries + finalizedResult.outputSummary
                    )

                    // 8. ADAPTIVE FEEDBACK TO BRAIN
                    conversationTurns.add(ChatMessage("assistant", llmResponseText))
                    val resultFeedback = buildResultFeedbackPrompt(call, finalizedResult, verification)
                    conversationTurns.add(ChatMessage("user", resultFeedback))
                }
            }
        }

        // Loop finished: Synthesize grounded final summary
        val finalSummary = if (executionHistory.isNotEmpty()) {
            val last = executionHistory.last()
            if (last.isSuccess && last.outcome == ToolExecutionOutcome.EXECUTION_SUCCEEDED_VERIFIED) {
                last.outputSummary
            } else {
                "⚠️ ${last.outputSummary}"
            }
        } else {
            "Task finished."
        }

        val allVerified = executionHistory.isNotEmpty() && executionHistory.all { it.isSuccess }

        BrainRunResult(
            finalSpeech = finalSummary,
            isSuccess = allVerified,
            executedTools = executionHistory,
            primaryCapability = primaryCapability,
            requiresBrowserUi = requiresBrowserUi,
            webContextPrompt = lastWebContext,
            lifecycleState = if (allVerified) TaskLifecycleState.COMPLETED else TaskLifecycleState.FAILED
        )
    }

    /**
     * Resumes a paused cognitive task after user confirmation.
     */
    suspend fun resumeGoal(
        pausedTask: PausedTaskState,
        provider: ProviderConfig?,
        modelId: String?,
        userConfirmed: Boolean = true,
        onProgress: ((step: Int, total: Int, text: String) -> Unit)? = null
    ): BrainRunResult {
        return executeGoal(
            rawInput = pausedTask.userGoal,
            provider = provider,
            modelId = modelId,
            conversationId = pausedTask.conversationId,
            worldState = pausedTask.worldState,
            userConfirmed = userConfirmed,
            onProgress = onProgress
        )
    }

    private fun buildInitialPrompt(userGoal: String, capabilityIndex: String, toolsPrompt: String, state: WorldRuntimeState): String {
        return buildString {
            appendLine(capabilityIndex)
            appendLine()
            appendLine(toolsPrompt)
            appendLine("\nCURRENT RUNTIME STATE:")
            if (!state.currentBrowserUrl.isNullOrBlank()) {
                appendLine("• Active Browser: ${state.currentBrowserUrl} ('${state.currentBrowserTitle}')")
            }
            if (!state.activeCallState.isNullOrBlank()) {
                appendLine("• Telephony State: ${state.activeCallState}")
            }
            if (!state.activeTerminalSession.isNullOrBlank()) {
                appendLine("• Terminal Session: ${state.activeTerminalSession}")
            }
            if (state.verifiedFacts.isNotEmpty()) {
                appendLine("• Verified Facts: ${state.verifiedFacts}")
            }
            appendLine("\nUSER GOAL:\n\"$userGoal\"")
        }
    }

    private fun buildResultFeedbackPrompt(call: ToolCall, result: ToolResult, verification: VerificationResult): String {
        return buildString {
            appendLine("TOOL RESULT FOR [${call.toolId}]:")
            appendLine("• Status: ${if (result.isSuccess) "SUCCESS" else "FAILED"} (Outcome: ${result.outcome})")
            appendLine("• Summary: ${result.outputSummary}")
            if (result.data.isNotEmpty()) {
                appendLine("• Data: ${result.data}")
            }
            appendLine("• Deterministic Verification: ${if (verification.isVerified) "VERIFIED (${verification.verifiedState})" else "UNVERIFIED: ${verification.notes}"}")
            appendLine("\nNext: If the user goal is now satisfied, return FINAL_ANSWER. If further steps are required, return TOOL_CALL. Never invent unverified results.")
        }
    }

    private fun parseResponse(rawText: String): BrainDecision? {
        val clean = extractJson(rawText) ?: return null
        return try {
            val resp = json.decodeFromString(StructuredBrainResponse.serializer(), clean)
            when (resp.decision.uppercase()) {
                "TOOL_CALL" -> {
                    val toolId = resp.tool ?: return null
                    BrainDecision.InvokeTool(
                        ToolCall(
                            toolId = toolId,
                            arguments = resp.arguments,
                            decisionSummary = resp.decisionSummary
                        ),
                        decisionSummary = resp.decisionSummary
                    )
                }
                "FINAL_ANSWER" -> {
                    BrainDecision.FinalAnswer(
                        answer = resp.finalAnswer ?: resp.decisionSummary,
                        decisionSummary = resp.decisionSummary
                    )
                }
                "CLARIFY" -> {
                    BrainDecision.Clarify(
                        question = resp.question ?: resp.finalAnswer ?: "Could you please clarify?",
                        decisionSummary = resp.decisionSummary
                    )
                }
                "CONFIRM" -> {
                    val toolId = resp.tool ?: ""
                    BrainDecision.RequireConfirmation(
                        confirmationPrompt = resp.confirmationPrompt ?: "Do you confirm this action?",
                        toolCall = ToolCall(toolId = toolId, arguments = resp.arguments, decisionSummary = resp.decisionSummary),
                        decisionSummary = resp.decisionSummary
                    )
                }
                "DIRECT_CHAT" -> BrainDecision.DirectChat(resp.finalAnswer ?: resp.decisionSummary)
                else -> BrainDecision.FinalAnswer(answer = resp.finalAnswer ?: rawText)
            }
        } catch (e: Exception) {
            // Regex fallback for minor JSON parsing irregularities
            val answerMatch = Regex("\"final_answer\"\\s*:\\s*\"([^\"]+)\"").find(clean)
            if (answerMatch != null) {
                BrainDecision.FinalAnswer(answerMatch.groupValues[1])
            } else {
                null
            }
        }
    }

    private fun extractJson(text: String): String? {
        val trimmed = text.trim()
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start != -1 && end != -1 && end > start) {
            return trimmed.substring(start, end + 1)
        }
        return null
    }

    private suspend fun executeOfflineFallback(
        goal: String,
        conversationId: String,
        requestId: String,
        onProgress: ((step: Int, total: Int, text: String) -> Unit)?
    ): BrainRunResult {
        val lower = goal.lowercase()
        val execContext = ToolExecutionContext(conversationId = conversationId, requestId = requestId, userGoal = goal, onProgress = onProgress)

        // Web search fallback
        if (lower.startsWith("search ") || lower.contains("web search") || lower.contains("google ")) {
            val query = goal.replace(Regex("^(search|google|web search for)\\s*", RegexOption.IGNORE_CASE), "").trim()
            val webTool = toolRegistry.getTool("web.search")
            if (webTool != null) {
                val call = ToolCall(toolId = "web.search", arguments = mapOf("query" to query))
                val res = webTool.execute(call, execContext)
                return BrainRunResult(
                    finalSpeech = res.outputSummary,
                    isSuccess = res.isSuccess,
                    executedTools = listOf(res),
                    primaryCapability = LichiCapability.WEB_SEARCH,
                    webContextPrompt = res.rawOutput
                )
            }
        }

        // Alarm fallback
        if (lower.contains("alarm") && (lower.contains("set") || lower.contains("laga"))) {
            val hourMatch = Regex("(\\d{1,2})\\s*(baje|am|pm|:)?").find(lower)
            val hour = hourMatch?.groupValues?.get(1)?.toIntOrNull() ?: 7
            val alarmTool = toolRegistry.getTool("time.create_alarm")
            if (alarmTool != null) {
                val call = ToolCall(toolId = "time.create_alarm", arguments = mapOf("hour" to hour.toString(), "minute" to "0"))
                val res = alarmTool.execute(call, execContext)
                return BrainRunResult(
                    finalSpeech = res.outputSummary,
                    isSuccess = res.isSuccess,
                    executedTools = listOf(res),
                    primaryCapability = LichiCapability.TIME_REMINDER
                )
            }
        }

        // Direct conversational fallback
        return BrainRunResult(
            finalSpeech = "",
            isSuccess = true,
            isDirectChat = true,
            directChatPrompt = goal
        )
    }
}
