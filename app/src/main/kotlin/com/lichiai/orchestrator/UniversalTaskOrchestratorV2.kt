package com.lichiai.orchestrator

import android.content.Context
import android.util.Log
import com.lichiai.api.LlmClient
import com.lichiai.data.ProviderConfig
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
    val spyProfiles: List<com.lichiai.spy.model.PlatformProfile> = emptyList(),
    val requiresLlmSynthesis: Boolean = false
)

typealias ConversationalAgentRuntime = UniversalTaskOrchestratorV2

/**
 * Universal LLM Task Orchestrator V2 for Lichi AI.
 * Also known as ConversationalAgentRuntime.
 *
 * Authoritative canonical task orchestration engine:
 * USER INPUT -> UniversalLlmPlanner -> CapabilityCatalogV2 -> PlanStep -> RouteDispatcher (Executor) -> TaskResultEvaluator -> Result
 */
class UniversalTaskOrchestratorV2(
    private val context: Context,
    private val capabilityCatalog: CapabilityCatalogV2,
    private val contextBuilder: ContextBuilder,
    private val routeDispatcher: RouteDispatcher,
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

        // 1. CANONICAL PLANNING VIA UNIVERSAL LLM PLANNER
        val v2Decision = if (provider != null && !modelId.isNullOrBlank()) {
            planner.plan(
                rawInput = trimmed,
                normalizedInput = normalized,
                context = activeContext,
                provider = provider,
                modelId = modelId
            )
        } else null

        // If planner returned null (e.g. offline / no provider credentials configured)
        if (v2Decision == null) {
            Log.d(TAG, "Planner returned null; falling back to direct conversation.")
            return@withContext OrchestrationResult(
                finalSpeech = "",
                isSuccess = true,
                primaryCapability = LichiCapability.CHAT,
                isDirectChat = true,
                directChatPrompt = trimmed
            )
        }

        // 2. Handle non-execution modes
        when (v2Decision.mode) {
            DecisionMode.CONVERSE -> {
                return@withContext OrchestrationResult(
                    finalSpeech = v2Decision.directResponseText ?: "",
                    isSuccess = true,
                    primaryCapability = LichiCapability.CHAT,
                    isDirectChat = v2Decision.directResponseText.isNullOrBlank(),
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

        // 3. CLOSED-LOOP PLAN EXECUTION
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

            var stepExtractedAnswer: String? = null

            val (isSuccess, summary) = when (dispatchResult) {
                is DispatchExecutionResult.BrowserExecuted -> {
                    stepExtractedAnswer = dispatchResult.extractedAnswer
                    if (dispatchResult.extractedContext != null) {
                        lastWebContextPrompt = dispatchResult.extractedContext
                    }
                    Pair(dispatchResult.isSuccess, dispatchResult.extractedAnswer ?: dispatchResult.message)
                }
                is DispatchExecutionResult.WebSearchExecuted -> {
                    lastWebContextPrompt = dispatchResult.contextPrompt
                    stepExtractedAnswer = dispatchResult.response.directAnswer
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

            // 4. Closed-Loop Result Evaluation
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
                    val verifiedSpeech = stepExtractedAnswer ?: evaluation.verifiedResponse
                    contextBuilder.recordExecution(
                        capability = step.capability,
                        userGoal = v2Decision.goal,
                        assistantResponse = verifiedSpeech,
                        actionType = step.action
                    )
                    val requiresLlm = lastWebContextPrompt != null && stepExtractedAnswer == null
                    return@withContext OrchestrationResult(
                        finalSpeech = verifiedSpeech,
                        isSuccess = true,
                        executedPlan = taskPlan.copy(
                            executionRecords = executedRecords,
                            isCompleted = true,
                            currentStepIndex = idx + 1,
                            taskState = TaskState.COMPLETED
                        ),
                        primaryCapability = taskPlan.steps.first().capability,
                        webContextPrompt = lastWebContextPrompt,
                        requiresBrowserUi = requiresBrowserUi,
                        requiresLlmSynthesis = requiresLlm
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
        val requiresLlm = lastWebContextPrompt != null
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
            requiresBrowserUi = requiresBrowserUi,
            requiresLlmSynthesis = requiresLlm
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

        loopGuard.reset()
        val executedRecords = pausedTask.executionRecords.toMutableList()
        var lastWebContextPrompt: String? = null
        var requiresBrowserUi = false

        val totalSteps = pausedTask.steps.size
        for (idx in pausedTask.currentStepIndex until totalSteps) {
            val step = pausedTask.steps[idx]
            if (!loopGuard.canExecuteStep(step, activeContext.currentBrowserUrl ?: "")) {
                Log.w(TAG, "LoopGuard triggered on resumption for step ${idx + 1}. Aborting.")
                break
            }
            loopGuard.recordStep(step, activeContext.currentBrowserUrl ?: "")

            onProgress?.invoke(idx + 1, totalSteps, "Executing ${step.capability.displayName}...")

            val resolvedIntent = mapStepToResolvedIntent(step, pausedTask.userGoal)
            if (step.capability == LichiCapability.BROWSER) {
                requiresBrowserUi = true
            }

            val dispatchResult = routeDispatcher.dispatch(resolvedIntent) { s, t, txt ->
                onProgress?.invoke(idx + 1, totalSteps, txt)
            }

            var stepExtractedAnswer: String? = null

            val (isSuccess, summary) = when (dispatchResult) {
                is DispatchExecutionResult.BrowserExecuted -> {
                    stepExtractedAnswer = dispatchResult.extractedAnswer
                    if (dispatchResult.extractedContext != null) {
                        lastWebContextPrompt = dispatchResult.extractedContext
                    }
                    Pair(dispatchResult.isSuccess, dispatchResult.extractedAnswer ?: dispatchResult.message)
                }
                is DispatchExecutionResult.WebSearchExecuted -> {
                    lastWebContextPrompt = dispatchResult.contextPrompt
                    stepExtractedAnswer = dispatchResult.response.directAnswer
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
                    val verifiedSpeech = stepExtractedAnswer ?: evaluation.verifiedResponse
                    contextBuilder.recordExecution(
                        capability = step.capability,
                        userGoal = pausedTask.userGoal,
                        assistantResponse = verifiedSpeech,
                        actionType = step.action
                    )
                    val requiresLlm = lastWebContextPrompt != null && stepExtractedAnswer == null
                    return OrchestrationResult(
                        finalSpeech = verifiedSpeech,
                        isSuccess = true,
                        executedPlan = pausedTask.copy(
                            executionRecords = executedRecords,
                            isCompleted = true,
                            currentStepIndex = idx + 1,
                            taskState = TaskState.COMPLETED
                        ),
                        primaryCapability = pausedTask.steps.first().capability,
                        webContextPrompt = lastWebContextPrompt,
                        requiresBrowserUi = requiresBrowserUi,
                        requiresLlmSynthesis = requiresLlm
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
        val requiresLlm = lastWebContextPrompt != null
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
            requiresBrowserUi = requiresBrowserUi,
            requiresLlmSynthesis = requiresLlm
        )
    }

    private fun generateStepAcknowledgment(step: PlanStep, userGoal: String): String {
        return when (step.capability) {
            LichiCapability.WEB_SEARCH -> "Searching the web for \"${step.arguments["query"] ?: userGoal}\"..."
            LichiCapability.BROWSER -> "Opening browser for ${step.arguments["query"] ?: userGoal}..."
            LichiCapability.ANDROID_AGENT -> "Executing device task: ${step.arguments["task"] ?: userGoal}..."
            LichiCapability.CALLS -> "Calling ${step.arguments["target"] ?: step.arguments["contact"] ?: userGoal}..."
            LichiCapability.MEDIA_YOUTUBE -> "Playing ${step.arguments["query"] ?: userGoal} on YouTube..."
            LichiCapability.DEVICE_CONTROL -> "Adjusting device setting..."
            LichiCapability.TIME_REMINDER -> "Setting reminder..."
            LichiCapability.TERMINAL -> "Executing terminal command..."
            LichiCapability.DORK_SEARCH, LichiCapability.SITE_SEARCH, LichiCapability.DEEP_SEARCH, LichiCapability.RESEARCH, LichiCapability.COMPARE, LichiCapability.VERIFY ->
                "Gathering web research..."
            LichiCapability.NAVIGATE -> "Navigating to ${step.arguments["url"] ?: "website"}..."
            LichiCapability.EXTRACT, LichiCapability.FIND_ON_PAGE, LichiCapability.PAGE_SUMMARY, LichiCapability.INSPECT_PAGE ->
                "Inspecting page..."
            LichiCapability.FORMS, LichiCapability.DOWNLOAD, LichiCapability.UPLOAD, LichiCapability.MULTI_TAB ->
                "Executing browser action..."
            LichiCapability.SKILL_MANAGEMENT -> "Managing skills..."
            LichiCapability.CHAT -> ""
        }
    }

    private fun mapStepToResolvedIntent(step: PlanStep, userGoal: String): ResolvedIntent {
        val ack = generateStepAcknowledgment(step, userGoal)
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
                    naturalAcknowledgment = ack
                )
            }
            LichiCapability.WEB_SEARCH -> {
                ResolvedIntent.WebSearchTask(
                    query = step.arguments["query"] ?: userGoal,
                    isNewsSearch = step.arguments["news"]?.equals("true", true) == true,
                    isImageSearch = step.arguments["images"]?.equals("true", true) == true,
                    naturalAcknowledgment = ack
                )
            }
            LichiCapability.ANDROID_AGENT -> {
                ResolvedIntent.AndroidAgentTask(
                    goal = step.arguments["task"] ?: userGoal,
                    targetApp = step.arguments["app"],
                    naturalAcknowledgment = ack
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
                    naturalAcknowledgment = ack
                )
            }
            LichiCapability.MEDIA_YOUTUBE -> {
                ResolvedIntent.MediaTask(
                    query = step.arguments["query"] ?: userGoal,
                    targetApp = step.arguments["app"] ?: "youtube",
                    naturalAcknowledgment = ack
                )
            }
            LichiCapability.DEVICE_CONTROL -> {
                ResolvedIntent.DeviceControlTask(
                    setting = com.lichiai.intent.model.DeviceSettingType.VOLUME,
                    action = com.lichiai.intent.model.DeviceActionType.INCREASE,
                    naturalAcknowledgment = ack
                )
            }
            LichiCapability.TIME_REMINDER -> {
                val action = step.action.ifBlank { "CREATE" }
                val title = step.arguments["title"] ?: step.arguments["task"] ?: userGoal
                val isAlarm = step.arguments["is_alarm"]?.equals("true", true) == true ||
                        step.arguments["type"]?.equals("alarm", true) == true
                ResolvedIntent.TimeReminderTask(
                    rawInput = step.arguments["input"] ?: step.arguments["query"] ?: userGoal,
                    action = action,
                    title = title,
                    time = step.arguments["time"],
                    isAlarm = isAlarm,
                    recurrence = step.arguments["recurrence"],
                    id = step.arguments["id"],
                    minutes = step.arguments["minutes"]?.toIntOrNull() ?: 10,
                    naturalAcknowledgment = ack
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
                    naturalAcknowledgment = ack
                )
            }
            LichiCapability.DORK_SEARCH -> {
                ResolvedIntent.DorkSearchTask(
                    query = step.arguments["query"] ?: userGoal,
                    site = step.arguments["site"],
                    fileType = step.arguments["fileType"],
                    exactPhrase = step.arguments["exactPhrase"],
                    naturalAcknowledgment = ack
                )
            }
            LichiCapability.SITE_SEARCH -> {
                ResolvedIntent.SiteSearchTask(
                    domain = step.arguments["domain"] ?: "developer.android.com",
                    query = step.arguments["query"] ?: userGoal,
                    maxPages = step.arguments["maxPages"]?.toIntOrNull() ?: 3,
                    naturalAcknowledgment = ack
                )
            }
            LichiCapability.DEEP_SEARCH -> {
                ResolvedIntent.DeepSearchTask(
                    query = step.arguments["query"] ?: userGoal,
                    maxBudgetQueries = step.arguments["maxBudget"]?.toIntOrNull() ?: 3,
                    naturalAcknowledgment = ack
                )
            }
            LichiCapability.RESEARCH -> {
                ResolvedIntent.ResearchTask(
                    topic = step.arguments["topic"] ?: userGoal,
                    queries = step.arguments["queries"]?.split(";")?.map { it.trim() } ?: emptyList(),
                    naturalAcknowledgment = ack
                )
            }
            LichiCapability.NAVIGATE -> {
                ResolvedIntent.NavigateTask(
                    url = step.arguments["url"] ?: "https://www.google.com",
                    naturalAcknowledgment = ack
                )
            }
            LichiCapability.EXTRACT -> {
                ResolvedIntent.ExtractTask(
                    target = step.arguments["target"] ?: "ALL",
                    url = step.arguments["url"],
                    naturalAcknowledgment = ack
                )
            }
            LichiCapability.FIND_ON_PAGE -> {
                ResolvedIntent.FindOnPageTask(
                    keyword = step.arguments["keyword"] ?: userGoal,
                    naturalAcknowledgment = ack
                )
            }
            LichiCapability.COMPARE -> {
                val entitiesList = step.arguments["entities"]?.split(";")?.map { it.trim() }
                    ?: listOf(userGoal)
                ResolvedIntent.CompareTask(
                    entities = entitiesList,
                    criteria = step.arguments["criteria"]?.split(";")?.map { it.trim() } ?: emptyList(),
                    naturalAcknowledgment = ack
                )
            }
            LichiCapability.VERIFY -> {
                ResolvedIntent.VerifyTask(
                    claim = step.arguments["claim"] ?: userGoal,
                    domain = step.arguments["domain"],
                    naturalAcknowledgment = ack
                )
            }
            LichiCapability.FORMS -> {
                val fields = step.arguments["fields"]?.split(";")?.associate {
                    val parts = it.split(":")
                    if (parts.size >= 2) parts[0].trim() to parts[1].trim() else it to ""
                } ?: emptyMap()
                ResolvedIntent.FormsTask(
                    fieldValues = fields,
                    submit = step.arguments["submit"]?.equals("true", true) ?: true,
                    naturalAcknowledgment = ack
                )
            }
            LichiCapability.DOWNLOAD -> {
                ResolvedIntent.DownloadTask(
                    url = step.arguments["url"] ?: "https://example.com",
                    naturalAcknowledgment = ack
                )
            }
            LichiCapability.UPLOAD -> {
                ResolvedIntent.UploadTask(
                    targetIdOrIndex = step.arguments["targetId"] ?: "0",
                    filePath = step.arguments["filePath"] ?: "",
                    naturalAcknowledgment = ack
                )
            }
            LichiCapability.MULTI_TAB -> {
                ResolvedIntent.MultiTabTask(
                    action = step.arguments["action"] ?: "OPEN",
                    tabId = step.arguments["tabId"],
                    url = step.arguments["url"],
                    naturalAcknowledgment = ack
                )
            }
            LichiCapability.PAGE_SUMMARY -> {
                ResolvedIntent.PageSummaryTask(
                    focus = step.arguments["focus"],
                    naturalAcknowledgment = ack
                )
            }
            LichiCapability.INSPECT_PAGE -> {
                ResolvedIntent.InspectTask(
                    mode = step.arguments["mode"] ?: "FULL_INSPECTION",
                    query = step.arguments["query"] ?: userGoal,
                    showUi = step.arguments["showUi"]?.equals("true", true) ?: false,
                    naturalAcknowledgment = ack
                )
            }
            LichiCapability.SKILL_MANAGEMENT -> {
                ResolvedIntent.SkillManagementTask(
                    request = com.lichiai.skill.router.SkillManagementRequest.ListSkills,
                    naturalAcknowledgment = ack
                )
            }
            LichiCapability.CHAT -> {
                ResolvedIntent.NormalChat(
                    prompt = userGoal,
                    naturalAcknowledgment = ack
                )
            }
        }
    }
}
