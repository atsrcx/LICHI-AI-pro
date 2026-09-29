package com.lichiai.orchestrator

import android.content.Context
import android.util.Log
import com.lichiai.api.LlmClient
import com.lichiai.data.ProviderConfig
import com.lichiai.intent.UniversalIntentEngine
import com.lichiai.intent.context.ContextBuilder
import com.lichiai.intent.dispatcher.DispatchExecutionResult
import com.lichiai.intent.dispatcher.RouteDispatcher
import com.lichiai.intent.model.BrowserActionType
import com.lichiai.intent.model.IntentContext
import com.lichiai.intent.model.LichiCapability
import com.lichiai.intent.model.ResolvedIntent
import com.lichiai.intent.normalizer.InputNormalizer
import com.lichiai.orchestrator.catalog.CapabilityCatalogV2
import com.lichiai.orchestrator.evaluator.TaskResultEvaluator
import com.lichiai.orchestrator.loop.OrchestratorLoopGuard
import com.lichiai.orchestrator.model.DecisionMode
import com.lichiai.orchestrator.model.EvaluationAction
import com.lichiai.orchestrator.model.OrchestrationDecision
import com.lichiai.orchestrator.model.OrchestratorMode
import com.lichiai.orchestrator.model.PlanStep
import com.lichiai.orchestrator.model.StepExecutionRecord
import com.lichiai.orchestrator.model.TaskPlan
import com.lichiai.orchestrator.model.TaskState
import com.lichiai.orchestrator.planner.UniversalLlmPlanner
import com.lichiai.orchestrator.shadow.ShadowExecutionComparator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID

/**
 * Result returned by the Universal Task Orchestrator V2.
 */
data class OrchestrationResult(
    val finalSpeech: String,
    val isSuccess: Boolean,
    val executedPlan: TaskPlan? = null,
    val primaryCapability: LichiCapability,
    val isDirectChat: Boolean = false,
    val directChatPrompt: String = "",
    val webContextPrompt: String? = null,
    val requiresBrowserUi: Boolean = false,
    val requiresConfirmation: Boolean = false,
    val confirmationPrompt: String? = null,
    val isPaused: Boolean = false,
    val spyProfile: com.lichiai.spy.model.PlatformProfile? = null,
    val spyProfiles: List<com.lichiai.spy.model.PlatformProfile> = emptyList()
)

typealias ConversationalAgentRuntime = UniversalTaskOrchestratorV2

/**
 * Universal LLM Task Orchestrator V2 for Lichi AI.
 * Also known as ConversationalAgentRuntime.
 *
 * Semantic brain coordinating verified existing peer capabilities:
 * - Understands user goals across English, Hindi, Hinglish, Roman Hindi
 * - Performs multi-capability and same-capability task planning
 * - Coordinates existing executors (Browser Agent, Autonomous Agent V2, Web Intelligence, UniversalCallEngine, Terminal)
 * - Implements closed-loop result verification, task resumption, interruption, and feedback
 * - Supports SHADOW, CANARY, and ENABLED rollout modes with instant rollback
 */
class UniversalTaskOrchestratorV2(
    private val context: Context,
    private val capabilityCatalog: CapabilityCatalogV2,
    private val contextBuilder: ContextBuilder,
    private val routeDispatcher: RouteDispatcher,
    private val legacyIntentEngine: UniversalIntentEngine,
    private val llmClient: LlmClient,
    private val shadowComparator: ShadowExecutionComparator = ShadowExecutionComparator(),
    private val loopGuard: OrchestratorLoopGuard = OrchestratorLoopGuard(),
    private val evaluator: TaskResultEvaluator = TaskResultEvaluator(llmClient)
) {

    companion object {
        private const val TAG = "TaskOrchestratorV2"
    }

    private val planner = UniversalLlmPlanner(llmClient, capabilityCatalog)

    val shadowExecutionComparator: ShadowExecutionComparator get() = shadowComparator

    /**
     * Executes or plans a user request with complete closed-loop feedback.
     */
    suspend fun orchestrate(
        rawInput: String,
        context: IntentContext? = null,
        provider: ProviderConfig? = null,
        modelId: String? = null,
        mode: OrchestratorMode = OrchestratorMode.ENABLED,
        requestId: String = "",
        messageId: String = "",
        conversationId: String = "",
        onProgress: ((step: Int, total: Int, text: String) -> Unit)? = null
    ): OrchestrationResult = withContext(Dispatchers.Main) {
        val trimmed = rawInput.trim()
        if (trimmed.isBlank()) {
            return@withContext OrchestrationResult(
                finalSpeech = "",
                isSuccess = true,
                primaryCapability = LichiCapability.CHAT,
                isDirectChat = true,
                directChatPrompt = ""
            )
        }

        val normalized = InputNormalizer.normalize(trimmed)
        val convId = conversationId.ifBlank { context?.conversationId ?: "default_session" }
        val activeContext = context ?: contextBuilder.buildContext(convId)

        // 0. DETERMINISTIC #SPY PLATFORM INTELLIGENCE GATE & UNIVERSAL CONTEXT CONTINUATION
        val spyTrigger = com.lichiai.spy.core.SpyGate.checkTrigger(trimmed)
        if (spyTrigger is com.lichiai.spy.core.SpyGateResult.Triggered) {
            val appContext = this@UniversalTaskOrchestratorV2.context
            val spyOrchestrator = com.lichiai.spy.orchestrator.SpyRuntimeOrchestrator(
                context = appContext,
                settingsRepository = com.lichiai.data.SettingsRepository(appContext),
                llmClient = llmClient
            )
            val spyResult = spyOrchestrator.execute(
                rawInput = trimmed,
                provider = provider,
                modelId = modelId,
                requestId = requestId,
                messageId = messageId,
                onProgress = onProgress
            )
            contextBuilder.recordSpyExecution(spyResult.primaryProfile, spyResult.profiles, trimmed, spyResult.speech, conversationId = convId)
            return@withContext OrchestrationResult(
                finalSpeech = spyResult.speech,
                isSuccess = spyResult.isSuccess,
                primaryCapability = LichiCapability.CHAT,
                spyProfile = spyResult.primaryProfile,
                spyProfiles = spyResult.profiles
            )
        }

        // 1. FAST PATH: Deterministic URLs, Browser navigation, and Conversational Reference/Correction
        val legacyResult = legacyIntentEngine.resolve(
            rawInput = trimmed,
            context = activeContext,
            provider = provider,
            modelId = modelId,
            allowLlmFallback = false
        )

        // 1.1 Contextual Question (e.g. "iska matlab kya hai?", "is account ki specific cheezein batao", "iske followers kitne hain?")
        if (legacyResult.intent is ResolvedIntent.ContextualQuestion) {
            val q = legacyResult.intent.question
            val ref = legacyResult.intent.referenceContext
            val combinedPrompt = if (!ref.isNullOrBlank()) {
                "User asks: \"$q\"\nRelevant Context / Previous Output: \"$ref\""
            } else q
            return@withContext OrchestrationResult(
                finalSpeech = "",
                isSuccess = true,
                primaryCapability = LichiCapability.CHAT,
                isDirectChat = true,
                directChatPrompt = combinedPrompt,
                spyProfile = activeContext.lastPlatformProfile,
                spyProfiles = activeContext.recentProfiles
            )
        }

        // 1.15 Clarification Request (e.g. multiple candidate profiles detected without qualifier)
        if (legacyResult.intent is ResolvedIntent.Clarification) {
            return@withContext OrchestrationResult(
                finalSpeech = legacyResult.intent.question,
                isSuccess = true,
                primaryCapability = LichiCapability.CHAT
            )
        }

        // 1.2 Resume Task ("continue", "resume", "continue that")
        if (legacyResult.intent is ResolvedIntent.ResumeTask) {
            val paused = contextBuilder.popPausedTask()
            if (paused == null) {
                return@withContext OrchestrationResult(
                    finalSpeech = "Koi paused task nahi mila jise resume kiya ja sake.",
                    isSuccess = true,
                    primaryCapability = LichiCapability.CHAT
                )
            }
            return@withContext resumePausedTask(paused, activeContext, provider, modelId, onProgress)
        }

        // 1.3 Task Interruption ("ruko, rahul ko call karo")
        if (legacyResult.intent is ResolvedIntent.TaskInterruption) {
            val active = contextBuilder.activeTaskPlan.value
            if (active != null) {
                contextBuilder.pushPausedTask(active)
            }
            val reason = legacyResult.intent.reason
            val nextCommand = reason.removePrefix("User interrupted with:").trim()
            if (nextCommand.isNotBlank()) {
                return@withContext orchestrate(
                    rawInput = nextCommand,
                    context = activeContext,
                    provider = provider,
                    modelId = modelId,
                    mode = mode,
                    requestId = requestId,
                    messageId = messageId,
                    conversationId = conversationId,
                    onProgress = onProgress
                )
            }
        }

        // 1.4 SPY FOLLOW-UP EXTERNAL RE-SCRAPING (Only when explicitly requesting new scrapes/posts/external data)
        val lower = trimmed.lowercase(Locale.ROOT)
        val isExplicitScrape = lower.contains("scrape") || lower.contains("fetch fresh") || lower.contains("fresh data") ||
                lower.contains("phir se scrape") || lower.contains("dubara dhoondo")
        if (isExplicitScrape && activeContext.lastPlatformProfile != null) {
            val profile = activeContext.lastPlatformProfile
            val platformName = profile.platform.displayName
            val username = profile.username
            val constructedQuery = "#Spy $platformName @$username $trimmed"

            val appContext = this@UniversalTaskOrchestratorV2.context
            val spyOrchestrator = com.lichiai.spy.orchestrator.SpyRuntimeOrchestrator(
                context = appContext,
                settingsRepository = com.lichiai.data.SettingsRepository(appContext),
                llmClient = llmClient
            )
            val spyResult = spyOrchestrator.execute(
                rawInput = constructedQuery,
                provider = provider,
                modelId = modelId,
                requestId = requestId,
                messageId = messageId,
                onProgress = onProgress
            )
            contextBuilder.recordSpyExecution(spyResult.primaryProfile ?: profile, spyResult.profiles, trimmed, spyResult.speech)
            return@withContext OrchestrationResult(
                finalSpeech = spyResult.speech,
                isSuccess = spyResult.isSuccess,
                primaryCapability = LichiCapability.CHAT,
                spyProfile = spyResult.primaryProfile ?: profile,
                spyProfiles = spyResult.profiles.ifEmpty { activeContext.recentProfiles }
            )
        }

        // If an active task was running and user gave a distinct command, pause it so it's recoverable
        val activeRunningTask = contextBuilder.activeTaskPlan.value
        if (activeRunningTask != null && legacyResult.intent !is ResolvedIntent.Cancellation) {
            contextBuilder.pushPausedTask(activeRunningTask)
        }

        // If legacy route matched a deterministic high-confidence rule (e.g. direct URL or scroll/back/refresh)
        if (legacyResult.source == com.lichiai.intent.model.ResolutionSource.DETERMINISTIC_RULE) {
            Log.d(TAG, "Fast deterministic rule matched: ${legacyResult.intent::class.simpleName}")
            val execResult = routeDispatcher.dispatch(legacyResult.intent, onProgress)
            return@withContext buildResultFromDispatch(execResult, legacyResult.intent, trimmed)
        }

        // 2. High-priority context follow-up or correction (e.g. "doosra result kholo", "nahi browser mein karo", "chhodo")
        if (legacyResult.source == com.lichiai.intent.model.ResolutionSource.CORRECTION ||
            legacyResult.source == com.lichiai.intent.model.ResolutionSource.CONTEXT_FOLLOWUP
        ) {
            Log.d(TAG, "Contextual reference/correction matched: ${legacyResult.intent::class.simpleName}")
            val execResult = routeDispatcher.dispatch(legacyResult.intent, onProgress)
            return@withContext buildResultFromDispatch(execResult, legacyResult.intent, trimmed)
        }

        // 3. Mode check: If DISABLED, execute legacy path directly
        if (mode == OrchestratorMode.DISABLED) {
            val execResult = routeDispatcher.dispatch(legacyResult.intent, onProgress)
            return@withContext buildResultFromDispatch(execResult, legacyResult.intent, trimmed)
        }

        // 4. Plan using Universal LLM Planner
        val v2Decision = if (provider != null && !modelId.isNullOrBlank()) {
            planner.plan(
                rawInput = trimmed,
                normalizedInput = normalized,
                context = activeContext,
                provider = provider,
                modelId = modelId
            )
        } else null

        // 5. SHADOW MODE: Compare decisions, execute ONLY legacy route (NO duplicate execution!)
        if (mode == OrchestratorMode.SHADOW) {
            if (v2Decision != null) {
                shadowComparator.compare(trimmed, legacyResult.intent, v2Decision)
            }
            val execResult = routeDispatcher.dispatch(legacyResult.intent, onProgress)
            return@withContext buildResultFromDispatch(execResult, legacyResult.intent, trimmed)
        }

        // 6. If V2 decision is null (e.g. offline/no API key), fall back gracefully to legacy router
        if (v2Decision == null) {
            Log.d(TAG, "Planner returned null; falling back to legacy intent engine.")
            val execResult = routeDispatcher.dispatch(legacyResult.intent, onProgress)
            return@withContext buildResultFromDispatch(execResult, legacyResult.intent, trimmed)
        }

        // 7. Handle non-execution modes
        when (v2Decision.mode) {
            DecisionMode.CONVERSE -> {
                return@withContext OrchestrationResult(
                    finalSpeech = "",
                    isSuccess = true,
                    primaryCapability = LichiCapability.CHAT,
                    isDirectChat = true,
                    directChatPrompt = trimmed
                )
            }
            DecisionMode.CLARIFY -> {
                val q = v2Decision.clarificationQuestion ?: "Please clarify."
                return@withContext OrchestrationResult(
                    finalSpeech = q,
                    isSuccess = true,
                    primaryCapability = LichiCapability.CHAT
                )
            }
            DecisionMode.CONFIRM -> {
                return@withContext OrchestrationResult(
                    finalSpeech = v2Decision.confirmationPrompt ?: "Please confirm.",
                    isSuccess = true,
                    primaryCapability = v2Decision.plan.firstOrNull()?.capability ?: LichiCapability.CHAT,
                    requiresConfirmation = true,
                    confirmationPrompt = v2Decision.confirmationPrompt
                )
            }
            DecisionMode.CANCEL -> {
                contextBuilder.reset()
                return@withContext OrchestrationResult(
                    finalSpeech = v2Decision.naturalAcknowledgment,
                    isSuccess = true,
                    primaryCapability = LichiCapability.CHAT
                )
            }
            DecisionMode.RESUME -> {
                val paused = contextBuilder.popPausedTask()
                if (paused != null) {
                    return@withContext resumePausedTask(paused, activeContext, provider, modelId, onProgress)
                }
                return@withContext OrchestrationResult(
                    finalSpeech = "Koi paused task nahi mila jise resume kiya ja sake.",
                    isSuccess = true,
                    primaryCapability = LichiCapability.CHAT
                )
            }
            DecisionMode.INTERRUPT -> {
                val active = contextBuilder.activeTaskPlan.value
                if (active != null) {
                    contextBuilder.pushPausedTask(active)
                }
            }
            DecisionMode.MODIFY_TASK -> {
                // Modified plan proceeds to execution
            }
            DecisionMode.DONE -> {
                return@withContext OrchestrationResult(
                    finalSpeech = v2Decision.naturalAcknowledgment,
                    isSuccess = true,
                    primaryCapability = LichiCapability.CHAT
                )
            }
            DecisionMode.EXECUTE -> {
                // Proceed to closed-loop plan execution below
            }
        }

        // 8. CLOSED-LOOP PLAN EXECUTION
        val taskPlan = TaskPlan(
            taskId = UUID.randomUUID().toString(),
            conversationId = conversationId,
            requestId = requestId,
            messageId = messageId,
            userGoal = v2Decision.goal,
            rawInput = trimmed,
            steps = v2Decision.plan,
            taskState = TaskState.RUNNING
        )
        contextBuilder.setActiveTask(taskPlan)

        loopGuard.reset()
        val executedRecords = mutableListOf<StepExecutionRecord>()
        var lastWebContextPrompt: String? = null
        var requiresBrowserUi = false

        val totalSteps = taskPlan.steps.size
        for ((idx, step) in taskPlan.steps.withIndex()) {
            if (!loopGuard.canExecuteStep(step, activeContext.currentBrowserUrl ?: "")) {
                Log.w(TAG, "LoopGuard triggered for step ${idx + 1}: ${step.action}. Aborting.")
                break
            }
            loopGuard.recordStep(step, activeContext.currentBrowserUrl ?: "")

            onProgress?.invoke(idx + 1, totalSteps, "Executing ${step.capability.displayName}...")

            val resolvedIntent = mapStepToResolvedIntent(step, v2Decision.goal)
            if (step.capability == LichiCapability.BROWSER) {
                requiresBrowserUi = true
            }

            val dispatchResult = routeDispatcher.dispatch(resolvedIntent) { s, t, txt ->
                onProgress?.invoke(idx + 1, totalSteps, txt)
            }

            val (isSuccess, summary) = when (dispatchResult) {
                is DispatchExecutionResult.BrowserExecuted -> Pair(true, dispatchResult.message)
                is DispatchExecutionResult.WebSearchExecuted -> {
                    lastWebContextPrompt = dispatchResult.contextPrompt
                    Pair(true, dispatchResult.message)
                }
                is DispatchExecutionResult.AndroidAgentExecuted -> Pair(dispatchResult.isSuccess, dispatchResult.summary)
                is DispatchExecutionResult.CallExecuted -> Pair(dispatchResult.isSuccess, dispatchResult.message)
                is DispatchExecutionResult.MediaExecuted -> Pair(true, dispatchResult.message)
                is DispatchExecutionResult.DeviceControlExecuted -> Pair(true, dispatchResult.message)
                is DispatchExecutionResult.TimeReminderExecuted -> Pair(dispatchResult.isSuccess, dispatchResult.message)
                is DispatchExecutionResult.TerminalExecuted -> Pair(dispatchResult.isSuccess, dispatchResult.message)
                is DispatchExecutionResult.ClarificationNeeded -> Pair(true, dispatchResult.question)
                is DispatchExecutionResult.FallbackChat -> Pair(true, dispatchResult.prompt)
                is DispatchExecutionResult.ExecutionFailed -> Pair(false, dispatchResult.error)
            }

            val record = StepExecutionRecord(
                step = step,
                isSuccess = isSuccess,
                outputSummary = summary,
                rawResult = dispatchResult
            )
            executedRecords.add(record)

            val hasMore = idx + 1 < totalSteps
            val nextStep = if (hasMore) taskPlan.steps[idx + 1] else null

            // 9. Closed-Loop Result Evaluation
            val evaluation = evaluator.evaluateStepResult(
                userGoal = v2Decision.goal,
                executedStep = step,
                stepRecord = record,
                hasMoreSteps = hasMore,
                nextStep = nextStep,
                allRecords = executedRecords,
                provider = provider,
                modelId = modelId
            )

            when (evaluation.action) {
                EvaluationAction.NEXT_STEP -> {
                    // Continue to next planned step
                    continue
                }
                EvaluationAction.COMPLETE -> {
                    // Task satisfied!
                    contextBuilder.setActiveTask(null)
                    contextBuilder.recordExecution(
                        capability = step.capability,
                        userGoal = v2Decision.goal,
                        assistantResponse = evaluation.verifiedResponse,
                        actionType = step.action
                    )
                    return@withContext OrchestrationResult(
                        finalSpeech = evaluation.verifiedResponse,
                        isSuccess = true,
                        executedPlan = taskPlan.copy(
                            executionRecords = executedRecords,
                            isCompleted = true,
                            currentStepIndex = idx + 1,
                            taskState = TaskState.COMPLETED
                        ),
                        primaryCapability = taskPlan.steps.first().capability,
                        webContextPrompt = lastWebContextPrompt,
                        requiresBrowserUi = requiresBrowserUi
                    )
                }
                EvaluationAction.ABORT, EvaluationAction.RETRY, EvaluationAction.RECOVER -> {
                    // Report truthful executor failure
                    contextBuilder.setActiveTask(null)
                    return@withContext OrchestrationResult(
                        finalSpeech = evaluation.verifiedResponse,
                        isSuccess = false,
                        executedPlan = taskPlan.copy(
                            executionRecords = executedRecords,
                            isCompleted = false,
                            failedStepIndex = idx,
                            failureReason = evaluation.reasoning,
                            taskState = TaskState.FAILED
                        ),
                        primaryCapability = step.capability,
                        requiresBrowserUi = requiresBrowserUi
                    )
                }
            }
        }

        contextBuilder.setActiveTask(null)
        val finalSummary = executedRecords.lastOrNull()?.outputSummary ?: v2Decision.naturalAcknowledgment
        return@withContext OrchestrationResult(
            finalSpeech = finalSummary,
            isSuccess = executedRecords.all { it.isSuccess },
            executedPlan = taskPlan.copy(
                executionRecords = executedRecords,
                isCompleted = true,
                currentStepIndex = executedRecords.size,
                taskState = TaskState.COMPLETED
            ),
            primaryCapability = taskPlan.steps.firstOrNull()?.capability ?: LichiCapability.CHAT,
            webContextPrompt = lastWebContextPrompt,
            requiresBrowserUi = requiresBrowserUi
        )
    }

    private suspend fun resumePausedTask(
        pausedTask: TaskPlan,
        activeContext: IntentContext,
        provider: ProviderConfig?,
        modelId: String?,
        onProgress: ((step: Int, total: Int, text: String) -> Unit)?
    ): OrchestrationResult {
        onProgress?.invoke(pausedTask.currentStepIndex + 1, pausedTask.steps.size, "Resuming ${pausedTask.userGoal}...")
        contextBuilder.setActiveTask(pausedTask.copy(taskState = TaskState.RUNNING))

        val executedRecords = pausedTask.executionRecords.toMutableList()
        val totalSteps = pausedTask.steps.size
        var lastWebContextPrompt: String? = null
        var requiresBrowserUi = false

        for (idx in pausedTask.currentStepIndex until totalSteps) {
            val step = pausedTask.steps[idx]
            onProgress?.invoke(idx + 1, totalSteps, "Executing ${step.capability.displayName}...")

            val resolvedIntent = mapStepToResolvedIntent(step, pausedTask.userGoal)
            if (step.capability == LichiCapability.BROWSER) {
                requiresBrowserUi = true
            }

            val dispatchResult = routeDispatcher.dispatch(resolvedIntent) { s, t, txt ->
                onProgress?.invoke(idx + 1, totalSteps, txt)
            }

            val (isSuccess, summary) = when (dispatchResult) {
                is DispatchExecutionResult.BrowserExecuted -> Pair(true, dispatchResult.message)
                is DispatchExecutionResult.WebSearchExecuted -> {
                    lastWebContextPrompt = dispatchResult.contextPrompt
                    Pair(true, dispatchResult.message)
                }
                is DispatchExecutionResult.AndroidAgentExecuted -> Pair(dispatchResult.isSuccess, dispatchResult.summary)
                is DispatchExecutionResult.CallExecuted -> Pair(dispatchResult.isSuccess, dispatchResult.message)
                is DispatchExecutionResult.MediaExecuted -> Pair(true, dispatchResult.message)
                is DispatchExecutionResult.DeviceControlExecuted -> Pair(true, dispatchResult.message)
                is DispatchExecutionResult.TimeReminderExecuted -> Pair(dispatchResult.isSuccess, dispatchResult.message)
                is DispatchExecutionResult.TerminalExecuted -> Pair(dispatchResult.isSuccess, dispatchResult.message)
                is DispatchExecutionResult.ClarificationNeeded -> Pair(true, dispatchResult.question)
                is DispatchExecutionResult.FallbackChat -> Pair(true, dispatchResult.prompt)
                is DispatchExecutionResult.ExecutionFailed -> Pair(false, dispatchResult.error)
            }

            val record = StepExecutionRecord(
                step = step,
                isSuccess = isSuccess,
                outputSummary = summary,
                rawResult = dispatchResult
            )
            executedRecords.add(record)

            val hasMore = idx + 1 < totalSteps
            val nextStep = if (hasMore) pausedTask.steps[idx + 1] else null

            val evaluation = evaluator.evaluateStepResult(
                userGoal = pausedTask.userGoal,
                executedStep = step,
                stepRecord = record,
                hasMoreSteps = hasMore,
                nextStep = nextStep,
                allRecords = executedRecords,
                provider = provider,
                modelId = modelId
            )

            when (evaluation.action) {
                EvaluationAction.NEXT_STEP -> continue
                EvaluationAction.COMPLETE -> {
                    contextBuilder.setActiveTask(null)
                    contextBuilder.recordExecution(
                        capability = step.capability,
                        userGoal = pausedTask.userGoal,
                        assistantResponse = evaluation.verifiedResponse,
                        actionType = step.action
                    )
                    return OrchestrationResult(
                        finalSpeech = evaluation.verifiedResponse,
                        isSuccess = true,
                        executedPlan = pausedTask.copy(
                            executionRecords = executedRecords,
                            isCompleted = true,
                            currentStepIndex = idx + 1,
                            taskState = TaskState.COMPLETED
                        ),
                        primaryCapability = pausedTask.steps.first().capability,
                        webContextPrompt = lastWebContextPrompt,
                        requiresBrowserUi = requiresBrowserUi
                    )
                }
                EvaluationAction.ABORT, EvaluationAction.RETRY, EvaluationAction.RECOVER -> {
                    contextBuilder.setActiveTask(null)
                    return OrchestrationResult(
                        finalSpeech = evaluation.verifiedResponse,
                        isSuccess = false,
                        executedPlan = pausedTask.copy(
                            executionRecords = executedRecords,
                            isCompleted = false,
                            failedStepIndex = idx,
                            failureReason = evaluation.reasoning,
                            taskState = TaskState.FAILED
                        ),
                        primaryCapability = step.capability,
                        requiresBrowserUi = requiresBrowserUi
                    )
                }
            }
        }

        contextBuilder.setActiveTask(null)
        val finalSummary = executedRecords.lastOrNull()?.outputSummary ?: "Task resumed and completed."
        return OrchestrationResult(
            finalSpeech = finalSummary,
            isSuccess = executedRecords.all { it.isSuccess },
            executedPlan = pausedTask.copy(
                executionRecords = executedRecords,
                isCompleted = true,
                currentStepIndex = executedRecords.size,
                taskState = TaskState.COMPLETED
            ),
            primaryCapability = pausedTask.steps.firstOrNull()?.capability ?: LichiCapability.CHAT,
            webContextPrompt = lastWebContextPrompt,
            requiresBrowserUi = requiresBrowserUi
        )
    }

    private fun buildResultFromDispatch(
        result: DispatchExecutionResult,
        intent: ResolvedIntent,
        rawInput: String
    ): OrchestrationResult {
        return when (result) {
            is DispatchExecutionResult.BrowserExecuted -> OrchestrationResult(
                finalSpeech = result.message,
                isSuccess = true,
                primaryCapability = LichiCapability.BROWSER,
                requiresBrowserUi = true
            )
            is DispatchExecutionResult.WebSearchExecuted -> OrchestrationResult(
                finalSpeech = result.message,
                isSuccess = true,
                primaryCapability = LichiCapability.WEB_SEARCH,
                webContextPrompt = result.contextPrompt
            )
            is DispatchExecutionResult.AndroidAgentExecuted -> OrchestrationResult(
                finalSpeech = result.summary,
                isSuccess = result.isSuccess,
                primaryCapability = LichiCapability.ANDROID_AGENT
            )
            is DispatchExecutionResult.CallExecuted -> OrchestrationResult(
                finalSpeech = result.message,
                isSuccess = result.isSuccess,
                primaryCapability = LichiCapability.CALLS
            )
            is DispatchExecutionResult.MediaExecuted -> OrchestrationResult(
                finalSpeech = result.message,
                isSuccess = true,
                primaryCapability = LichiCapability.MEDIA_YOUTUBE
            )
            is DispatchExecutionResult.DeviceControlExecuted -> OrchestrationResult(
                finalSpeech = result.message,
                isSuccess = true,
                primaryCapability = LichiCapability.DEVICE_CONTROL
            )
            is DispatchExecutionResult.TimeReminderExecuted -> OrchestrationResult(
                finalSpeech = result.message,
                isSuccess = result.isSuccess,
                primaryCapability = LichiCapability.TIME_REMINDER
            )
            is DispatchExecutionResult.TerminalExecuted -> OrchestrationResult(
                finalSpeech = result.message,
                isSuccess = result.isSuccess,
                primaryCapability = LichiCapability.TERMINAL,
                requiresBrowserUi = false
            )
            is DispatchExecutionResult.ClarificationNeeded -> OrchestrationResult(
                finalSpeech = result.question,
                isSuccess = true,
                primaryCapability = LichiCapability.CHAT
            )
            is DispatchExecutionResult.FallbackChat -> OrchestrationResult(
                finalSpeech = "",
                isSuccess = true,
                primaryCapability = LichiCapability.CHAT,
                isDirectChat = true,
                directChatPrompt = rawInput
            )
            is DispatchExecutionResult.ExecutionFailed -> OrchestrationResult(
                finalSpeech = "⚠️ ${result.error}",
                isSuccess = false,
                primaryCapability = intent.capability
            )
        }
    }

    private fun mapStepToResolvedIntent(step: PlanStep, userGoal: String): ResolvedIntent {
        return when (step.capability) {
            LichiCapability.BROWSER -> {
                val action = when (step.action.uppercase(Locale.ROOT)) {
                    "NAVIGATE" -> BrowserActionType.NAVIGATE
                    "CLICK_CANDIDATE" -> BrowserActionType.CLICK_CANDIDATE
                    "SCROLL_DOWN" -> BrowserActionType.SCROLL_DOWN
                    "SCROLL_UP" -> BrowserActionType.SCROLL_UP
                    "FIND_ON_PAGE" -> BrowserActionType.FIND_ON_PAGE
                    "BACK" -> BrowserActionType.BACK
                    "FORWARD" -> BrowserActionType.FORWARD
                    "RELOAD" -> BrowserActionType.RELOAD
                    "NEW_TAB" -> BrowserActionType.NEW_TAB
                    "CLOSE_TAB" -> BrowserActionType.CLOSE_TAB
                    else -> BrowserActionType.SEARCH
                }
                ResolvedIntent.BrowserTask(
                    action = action,
                    query = step.arguments["query"] ?: userGoal,
                    url = step.arguments["url"],
                    searchEngine = step.arguments["engine"] ?: "google",
                    candidateIndex = step.arguments["index"]?.toIntOrNull(),
                    findTarget = step.arguments["target"],
                    rawPrompt = userGoal,
                    naturalAcknowledgment = step.expectedOutcome
                )
            }
            LichiCapability.WEB_SEARCH -> {
                ResolvedIntent.WebSearchTask(
                    query = step.arguments["query"] ?: userGoal,
                    isNewsSearch = step.arguments["news"]?.equals("true", true) == true,
                    isImageSearch = step.arguments["images"]?.equals("true", true) == true,
                    naturalAcknowledgment = step.expectedOutcome
                )
            }
            LichiCapability.ANDROID_AGENT -> {
                ResolvedIntent.AndroidAgentTask(
                    goal = step.arguments["task"] ?: userGoal,
                    targetApp = step.arguments["app"],
                    naturalAcknowledgment = step.expectedOutcome
                )
            }
            LichiCapability.CALLS -> {
                val contact = step.arguments["target"] ?: step.arguments["contact"] ?: step.arguments["phone"] ?: userGoal
                ResolvedIntent.CallTask(
                    callIntent = com.lichiai.calling.intent.CallIntent(
                        action = com.lichiai.calling.intent.CallAction.CALL_CONTACT,
                        targetText = contact,
                        originalText = userGoal
                    ),
                    naturalAcknowledgment = step.expectedOutcome
                )
            }
            LichiCapability.MEDIA_YOUTUBE -> {
                ResolvedIntent.MediaTask(
                    query = step.arguments["query"] ?: userGoal,
                    targetApp = step.arguments["app"] ?: "youtube",
                    naturalAcknowledgment = step.expectedOutcome
                )
            }
            LichiCapability.DEVICE_CONTROL -> {
                ResolvedIntent.DeviceControlTask(
                    setting = com.lichiai.intent.model.DeviceSettingType.VOLUME,
                    action = com.lichiai.intent.model.DeviceActionType.INCREASE,
                    naturalAcknowledgment = step.expectedOutcome
                )
            }
            LichiCapability.TIME_REMINDER -> {
                ResolvedIntent.TimeReminderTask(
                    rawInput = step.arguments["query"] ?: userGoal,
                    naturalAcknowledgment = step.expectedOutcome
                )
            }
            LichiCapability.TERMINAL -> {
                ResolvedIntent.TerminalTask(
                    command = step.arguments["command"] ?: step.action,
                    action = step.action,
                    host = step.arguments["host"],
                    user = step.arguments["user"],
                    port = step.arguments["port"]?.toIntOrNull() ?: 22,
                    rawPrompt = userGoal,
                    naturalAcknowledgment = step.expectedOutcome
                )
            }
            LichiCapability.DORK_SEARCH -> {
                ResolvedIntent.DorkSearchTask(
                    query = step.arguments["query"] ?: userGoal,
                    site = step.arguments["site"],
                    fileType = step.arguments["fileType"],
                    exactPhrase = step.arguments["exactPhrase"],
                    naturalAcknowledgment = step.expectedOutcome
                )
            }
            LichiCapability.SITE_SEARCH -> {
                ResolvedIntent.SiteSearchTask(
                    domain = step.arguments["domain"] ?: "developer.android.com",
                    query = step.arguments["query"] ?: userGoal,
                    maxPages = step.arguments["maxPages"]?.toIntOrNull() ?: 3,
                    naturalAcknowledgment = step.expectedOutcome
                )
            }
            LichiCapability.DEEP_SEARCH -> {
                ResolvedIntent.DeepSearchTask(
                    query = step.arguments["query"] ?: userGoal,
                    maxBudgetQueries = step.arguments["maxBudget"]?.toIntOrNull() ?: 3,
                    naturalAcknowledgment = step.expectedOutcome
                )
            }
            LichiCapability.RESEARCH -> {
                ResolvedIntent.ResearchTask(
                    topic = step.arguments["topic"] ?: userGoal,
                    queries = step.arguments["queries"]?.split(";")?.map { it.trim() } ?: emptyList(),
                    naturalAcknowledgment = step.expectedOutcome
                )
            }
            LichiCapability.NAVIGATE -> {
                ResolvedIntent.NavigateTask(
                    url = step.arguments["url"] ?: "https://www.google.com",
                    naturalAcknowledgment = step.expectedOutcome
                )
            }
            LichiCapability.EXTRACT -> {
                ResolvedIntent.ExtractTask(
                    target = step.arguments["target"] ?: "ALL",
                    url = step.arguments["url"],
                    naturalAcknowledgment = step.expectedOutcome
                )
            }
            LichiCapability.FIND_ON_PAGE -> {
                ResolvedIntent.FindOnPageTask(
                    keyword = step.arguments["keyword"] ?: userGoal,
                    naturalAcknowledgment = step.expectedOutcome
                )
            }
            LichiCapability.COMPARE -> {
                val entitiesList = step.arguments["entities"]?.split(";")?.map { it.trim() }
                    ?: listOf(userGoal)
                ResolvedIntent.CompareTask(
                    entities = entitiesList,
                    criteria = step.arguments["criteria"]?.split(";")?.map { it.trim() } ?: emptyList(),
                    naturalAcknowledgment = step.expectedOutcome
                )
            }
            LichiCapability.VERIFY -> {
                ResolvedIntent.VerifyTask(
                    claim = step.arguments["claim"] ?: userGoal,
                    domain = step.arguments["domain"],
                    naturalAcknowledgment = step.expectedOutcome
                )
            }
            LichiCapability.FORMS -> {
                val fields = step.arguments.filterKeys { it != "submit" }
                ResolvedIntent.FormsTask(
                    fieldValues = fields,
                    submit = step.arguments["submit"]?.toBooleanStrictOrNull() ?: true,
                    naturalAcknowledgment = step.expectedOutcome
                )
            }
            LichiCapability.DOWNLOAD -> {
                ResolvedIntent.DownloadTask(
                    url = step.arguments["url"] ?: "about:blank",
                    naturalAcknowledgment = step.expectedOutcome
                )
            }
            LichiCapability.UPLOAD -> {
                ResolvedIntent.UploadTask(
                    targetIdOrIndex = step.arguments["targetId"] ?: "1",
                    filePath = step.arguments["filePath"] ?: "",
                    naturalAcknowledgment = step.expectedOutcome
                )
            }
            LichiCapability.MULTI_TAB -> {
                ResolvedIntent.MultiTabTask(
                    action = step.arguments["action"] ?: "LIST",
                    tabId = step.arguments["tabId"],
                    url = step.arguments["url"],
                    naturalAcknowledgment = step.expectedOutcome
                )
            }
            LichiCapability.PAGE_SUMMARY -> {
                ResolvedIntent.PageSummaryTask(
                    focus = step.arguments["focus"],
                    naturalAcknowledgment = step.expectedOutcome
                )
            }
            LichiCapability.INSPECT_PAGE -> {
                ResolvedIntent.InspectTask(
                    mode = step.arguments["mode"] ?: "FULL_INSPECTION",
                    query = step.arguments["query"] ?: userGoal,
                    showUi = step.arguments["showUi"]?.toBooleanStrictOrNull() ?: false,
                    naturalAcknowledgment = step.expectedOutcome
                )
            }
            LichiCapability.SKILL_MANAGEMENT -> {
                ResolvedIntent.NormalChat(prompt = userGoal, naturalAcknowledgment = step.expectedOutcome)
            }
            LichiCapability.CHAT -> {
                ResolvedIntent.NormalChat(prompt = userGoal, naturalAcknowledgment = "")
            }
        }
    }
}
