package com.lichiai.browser.agent

import com.lichiai.browser.api.BrowserCapabilityAPI
import com.lichiai.browser.api.BrowserCommandParser
import com.lichiai.browser.api.BrowserUserIntent
import com.lichiai.browser.context.BrowserExecutionStatus
import com.lichiai.browser.context.BrowserMemory
import com.lichiai.browser.context.BrowserTaskContext
import com.lichiai.browser.events.BrowserActionLog
import com.lichiai.browser.events.BrowserEvent
import com.lichiai.browser.events.BrowserEventBus
import com.lichiai.browser.executor.BrowserExecutor
import com.lichiai.browser.llm.BrowserLLMClient
import com.lichiai.browser.planner.BrowserActionStep
import com.lichiai.browser.planner.BrowserPlanner
import com.lichiai.browser.storage.BrowserStorageManager
import com.lichiai.browser.verifier.BrowserVerifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

enum class BrowserInteractionMode {
    MANUAL,
    AGENT,
    HYBRID
}

data class BrowserAgentTelemetry(
    val totalTasks: Int = 0,
    val zeroLlmTasks: Int = 0,
    val llmCalls: Int = 0,
    val lastTaskDurationMs: Long = 0,
    val lastAction: String = ""
)

data class BrowserExecutionResult(
    val isSuccess: Boolean,
    val answer: String? = null,
    val summary: String = "",
    val extractedContext: String? = null,
    val finalUrl: String = "",
    val pageTitle: String = ""
)

/**
 * Autonomous, LLM-Native Browser Agent.
 * Uses the configured Browser LLM as the single reasoning engine for:
 * - Semantic Goal Comprehension (English / Hindi / Hinglish)
 * - Concise Search Query Formulation (Separated from natural language command)
 * - Candidate Link & DOM Element Selection
 * - Multi-Step Navigation & In-Page Actions
 * - Goal Verification & Task Completion
 */
class BrowserAgent(
    private val capabilityApi: BrowserCapabilityAPI,
    private val executor: BrowserExecutor,
    private val llmClient: BrowserLLMClient,
    private val storageManager: BrowserStorageManager,
    private val memory: BrowserMemory,
    private val actionLog: BrowserActionLog,
    private val eventBus: BrowserEventBus,
    private val scope: CoroutineScope
) {

    private val _interactionMode = MutableStateFlow(BrowserInteractionMode.HYBRID)
    val interactionMode: StateFlow<BrowserInteractionMode> = _interactionMode.asStateFlow()

    private val _telemetry = MutableStateFlow(BrowserAgentTelemetry())
    val telemetry: StateFlow<BrowserAgentTelemetry> = _telemetry.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    private val _pendingConfirmation = MutableStateFlow<com.lichiai.browser.context.BrowserConfirmationRequest?>(null)
    val pendingConfirmation: StateFlow<com.lichiai.browser.context.BrowserConfirmationRequest?> = _pendingConfirmation.asStateFlow()

    private var currentTaskJob: Job? = null
    private var lastTaskContext: BrowserTaskContext = BrowserTaskContext()

    fun setInteractionMode(mode: BrowserInteractionMode) {
        _interactionMode.value = mode
    }

    fun dismissConfirmation() {
        _pendingConfirmation.value?.let { req ->
            scope.launch { req.onCancel() }
        }
        _pendingConfirmation.value = null
    }

    fun approveConfirmation() {
        _pendingConfirmation.value?.let { req ->
            scope.launch { req.onConfirm() }
        }
        _pendingConfirmation.value = null
    }

    fun stopActiveTask(reason: String = "User requested stop") {
        currentTaskJob?.cancel()
        currentTaskJob = null
        _pendingConfirmation.value = null
        actionLog.cancelActive(reason)
        _isBusy.value = false
        eventBus.emit(BrowserEvent.TaskCancelled(lastTaskContext.taskId, reason))
    }

    /**
     * Submit natural language instruction or # shortcut asynchronously to the Browser Agent.
     */
    fun submitInstruction(instruction: String, onResult: ((String) -> Unit)? = null) {
        currentTaskJob?.cancel()
        currentTaskJob = scope.launch(Dispatchers.Default) {
            val res = executeInstruction(instruction)
            onResult?.invoke(res.answer ?: res.summary)
        }
    }

    /**
     * Synchronously/suspendingly executes an instruction with full lifecycle, progress, and result contract.
     */
    suspend fun executeInstruction(
        instruction: String,
        onProgress: ((step: Int, total: Int, text: String) -> Unit)? = null
    ): BrowserExecutionResult = kotlinx.coroutines.withContext(Dispatchers.Default) {
        val trimmed = instruction.trim()
        if (trimmed.isBlank()) return@withContext BrowserExecutionResult(isSuccess = true, summary = "")

        val taskId = UUID.randomUUID().toString()
        actionLog.setGoal(trimmed)
        eventBus.emit(BrowserEvent.TaskStarted(taskId, trimmed))

        // Check for immediate STOP
        val parsedIntent = BrowserCommandParser.parse(trimmed)
        if (parsedIntent is BrowserUserIntent.StopTask) {
            stopActiveTask()
            return@withContext BrowserExecutionResult(isSuccess = true, summary = "Browser task stopped.")
        }

        // Fast path for explicit hash shortcuts (e.g. #open https://..., #back, #down)
        if (trimmed.startsWith("#")) {
            return@withContext executeHashShortcutSync(taskId, trimmed, parsedIntent, onProgress)
        }

        _isBusy.value = true
        val startTime = System.currentTimeMillis()

        try {
            // 1. INITIAL OBSERVATION: Gather fresh browser state
            val initialContext = capabilityApi.getPageContext().copy(
                taskId = taskId,
                userGoal = trimmed,
                executionStatus = BrowserExecutionStatus.OBSERVING
            )
            lastTaskContext = initialContext

            // 2. LLM-NATIVE MULTI-STEP REASONING LOOP
            executeSemanticAgentLoopSync(taskId, trimmed, initialContext, onProgress, startTime)
        } catch (ce: CancellationException) {
            actionLog.cancelActive("Task stopped by user")
            BrowserExecutionResult(isSuccess = false, summary = "Task cancelled")
        } catch (e: Exception) {
            actionLog.failAction("error", "Error: ${e.message}")
            eventBus.emit(BrowserEvent.BrowserError(e.message ?: "Unknown error"))
            BrowserExecutionResult(isSuccess = false, summary = "Browser task error: ${e.message}")
        } finally {
            _isBusy.value = false
        }
    }

    private suspend fun executeHashShortcutSync(
        taskId: String,
        rawInput: String,
        intent: BrowserUserIntent,
        onProgress: ((step: Int, total: Int, text: String) -> Unit)?
    ): BrowserExecutionResult {
        _isBusy.value = true
        val startTime = System.currentTimeMillis()
        try {
            val currentContext = capabilityApi.getPageContext().copy(
                taskId = taskId,
                userGoal = rawInput
            )
            val defaultEngine = storageManager.settings.value.searchEngineUrl
            val plan = BrowserPlanner.planFromIntent(intent, currentContext, defaultEngine)

            _telemetry.value = _telemetry.value.copy(
                totalTasks = _telemetry.value.totalTasks + 1,
                zeroLlmTasks = _telemetry.value.zeroLlmTasks + 1,
                lastAction = "Shortcut: ${plan.goal}"
            )

            var finalMessage = ""
            var allSuccess = true
            for ((idx, step) in plan.steps.withIndex()) {
                onProgress?.invoke(idx + 1, plan.steps.size, step.userSummary)
                val stepRes = executor.executeStep(step, currentContext) { false }
                finalMessage = stepRes.summary
                if (!stepRes.success) {
                    allSuccess = false
                    break
                }
            }

            delay(500)
            val freshContext = capabilityApi.getPageContext()
            lastTaskContext = freshContext
            val duration = System.currentTimeMillis() - startTime
            _telemetry.value = _telemetry.value.copy(lastTaskDurationMs = duration)
            eventBus.emit(BrowserEvent.TaskCompleted(taskId, finalMessage))
            val snippet = capabilityApi.extractPageSummary()
            return BrowserExecutionResult(
                isSuccess = allSuccess,
                summary = finalMessage,
                extractedContext = snippet.takeIf { it.isNotBlank() },
                finalUrl = freshContext.currentUrl,
                pageTitle = freshContext.currentTitle
            )
        } finally {
            _isBusy.value = false
        }
    }

    private fun executeHashShortcut(
        taskId: String,
        rawInput: String,
        intent: BrowserUserIntent,
        onResult: ((String) -> Unit)?
    ) {
        currentTaskJob?.cancel()
        currentTaskJob = scope.launch(Dispatchers.Default) {
            val res = executeHashShortcutSync(taskId, rawInput, intent, null)
            onResult?.invoke(res.answer ?: res.summary)
        }
    }

    /**
     * Executes the multi-step goal-driven loop using the SAME configured Browser LLM.
     * TURN 1: User Goal -> LLM -> Action -> Executor -> Browser
     * TURN 2: Fresh Browser Result -> SAME LLM -> Next Action -> Executor -> Browser
     * ...
     * TURN N: Verified Destination -> SAME LLM -> STOP / ANSWER
     */
    private suspend fun executeSemanticAgentLoopSync(
        taskId: String,
        originalGoal: String,
        initialContext: BrowserTaskContext,
        onProgress: ((step: Int, total: Int, text: String) -> Unit)?,
        startTime: Long
    ): BrowserExecutionResult {
        var currentContext = initialContext
        val maxSteps = 8
        var currentStepNum = 0
        var isGoalAchieved = false
        var lastStepSummary = ""
        var finalExtractedAnswer: String? = null

        val actionHistory = mutableListOf<String>()
        val actionSignatures = mutableListOf<String>()

        while (currentStepNum < maxSteps && !isGoalAchieved) {
            currentStepNum++

            _telemetry.value = _telemetry.value.copy(
                totalTasks = _telemetry.value.totalTasks + 1,
                llmCalls = _telemetry.value.llmCalls + 1,
                lastAction = "LLM Step #$currentStepNum"
            )

            // 1. OBSERVE (Eyes)
            actionLog.startAction("step_$currentStepNum", "Step $currentStepNum: Analyzing page state...")
            val pageSnippet = capabilityApi.extractPageSummary()

            // 2. UNDERSTAND & DECIDE (Brain - SAME CONFIGURED MODEL)
            val decision = llmClient.decideNextAction(currentContext, pageSnippet)

            // Handle Immediate Answer to question
            if (decision.action == "ANSWER" && !decision.answer.isNullOrBlank()) {
                finalExtractedAnswer = decision.answer
                actionLog.completeAction("step_$currentStepNum", decision.summary.ifBlank { "Answer ready" })
                eventBus.emit(BrowserEvent.TaskCompleted(taskId, finalExtractedAnswer))
                isGoalAchieved = true
                val duration = System.currentTimeMillis() - startTime
                _telemetry.value = _telemetry.value.copy(lastTaskDurationMs = duration)
                return BrowserExecutionResult(
                    isSuccess = true,
                    answer = finalExtractedAnswer,
                    summary = decision.summary.ifBlank { finalExtractedAnswer },
                    extractedContext = pageSnippet.takeIf { it.isNotBlank() },
                    finalUrl = currentContext.currentUrl,
                    pageTitle = currentContext.currentTitle
                )
            }

            // Handle Task Completion / Stop
            if (decision.action == "STOP") {
                val msg = if (decision.summary.isNotBlank()) {
                    decision.summary
                } else if (lastStepSummary.isNotBlank()) {
                    lastStepSummary
                } else {
                    "${currentContext.currentTitle.ifBlank { "Page" }} open kar di gayi hai."
                }
                actionLog.completeAction("step_$currentStepNum", msg)
                eventBus.emit(BrowserEvent.TaskCompleted(taskId, msg))
                isGoalAchieved = true
                val duration = System.currentTimeMillis() - startTime
                _telemetry.value = _telemetry.value.copy(lastTaskDurationMs = duration)
                return BrowserExecutionResult(
                    isSuccess = true,
                    answer = decision.answer,
                    summary = msg,
                    extractedContext = pageSnippet.takeIf { it.isNotBlank() },
                    finalUrl = currentContext.currentUrl,
                    pageTitle = currentContext.currentTitle
                )
            }

            // 3. TRANSLATE TO STRUCTURED BROWSER ACTION
            val step = when (decision.action) {
                "SEARCH" -> {
                    val q = decision.query?.takeIf { it.isNotBlank() } ?: originalGoal
                    BrowserActionStep(
                        id = "step_${currentStepNum}_search",
                        toolName = "search",
                        arguments = mapOf(
                            "query" to q,
                            "engine" to (decision.engine ?: "")
                        ),
                        userSummary = decision.summary.ifBlank { "Searching for \"$q\"" },
                        requiresVerification = true
                    )
                }
                "CLICK_CANDIDATE" -> {
                    val idx = decision.index ?: 1
                    BrowserActionStep(
                        id = "step_${currentStepNum}_click_cand_$idx",
                        toolName = "clickCandidate",
                        arguments = mapOf("index" to idx.toString()),
                        userSummary = decision.summary.ifBlank { "Opening search result #$idx" },
                        requiresVerification = true
                    )
                }
                "CLICK_ELEMENT" -> {
                    val idx = decision.index ?: 1
                    BrowserActionStep(
                        id = "step_${currentStepNum}_click_el_$idx",
                        toolName = "clickElement",
                        arguments = mapOf("index" to idx.toString()),
                        userSummary = decision.summary.ifBlank { "Clicking element #$idx" },
                        requiresVerification = true
                    )
                }
                "CLICK_SELECTOR" -> {
                    val target = decision.text ?: decision.selector ?: ""
                    BrowserActionStep(
                        id = "step_${currentStepNum}_click_sel",
                        toolName = "clickSelector",
                        arguments = mapOf("text" to target),
                        userSummary = decision.summary.ifBlank { "Clicking \"$target\"" },
                        requiresVerification = true
                    )
                }
                "TYPE_TEXT" -> {
                    val textToType = decision.text ?: ""
                    BrowserActionStep(
                        id = "step_${currentStepNum}_type",
                        toolName = "typeText",
                        arguments = mapOf(
                            "text" to textToType,
                            "index" to (decision.index?.toString() ?: ""),
                            "selector" to (decision.selector ?: ""),
                            "submit" to decision.submit.toString()
                        ),
                        userSummary = decision.summary.ifBlank { "Typing \"$textToType\"" },
                        requiresVerification = true
                    )
                }
                "NAVIGATE" -> {
                    val destUrl = decision.url ?: "https://www.google.com"
                    BrowserActionStep(
                        id = "step_${currentStepNum}_nav",
                        toolName = "navigate",
                        arguments = mapOf("url" to destUrl),
                        userSummary = decision.summary.ifBlank { "Navigating to $destUrl" },
                        requiresVerification = true
                    )
                }
                "SCROLL" -> {
                    val dir = decision.direction ?: "DOWN"
                    BrowserActionStep(
                        id = "step_${currentStepNum}_scroll",
                        toolName = "scroll",
                        arguments = mapOf("direction" to dir),
                        userSummary = decision.summary.ifBlank { "Scrolling page $dir" },
                        requiresVerification = false
                    )
                }
                "GO_BACK" -> {
                    BrowserActionStep(
                        id = "step_${currentStepNum}_back",
                        toolName = "goBack",
                        arguments = emptyMap(),
                        userSummary = decision.summary.ifBlank { "Navigating back" },
                        requiresVerification = true
                    )
                }
                "GO_FORWARD" -> {
                    BrowserActionStep(
                        id = "step_${currentStepNum}_forward",
                        toolName = "goForward",
                        arguments = emptyMap(),
                        userSummary = decision.summary.ifBlank { "Navigating forward" },
                        requiresVerification = true
                    )
                }
                "RELOAD" -> {
                    BrowserActionStep(
                        id = "step_${currentStepNum}_reload",
                        toolName = "reload",
                        arguments = emptyMap(),
                        userSummary = decision.summary.ifBlank { "Reloading webpage" },
                        requiresVerification = true
                    )
                }
                "EXTRACT_TABLE" -> {
                    BrowserActionStep(
                        id = "step_${currentStepNum}_table",
                        toolName = "extractTables",
                        arguments = emptyMap(),
                        userSummary = decision.summary.ifBlank { "Extracting table data from page" },
                        requiresVerification = false
                    )
                }
                "HIGHLIGHT_ELEMENT" -> {
                    val idx = decision.index ?: 1
                    BrowserActionStep(
                        id = "step_${currentStepNum}_highlight",
                        toolName = "highlightElement",
                        arguments = mapOf("index" to idx.toString()),
                        userSummary = decision.summary.ifBlank { "Highlighting element #$idx" },
                        requiresVerification = false
                    )
                }
                "SWITCH_TAB" -> {
                    val targetTab = decision.tabId ?: ""
                    BrowserActionStep(
                        id = "step_${currentStepNum}_switch_tab",
                        toolName = "switchTab",
                        arguments = mapOf("tabId" to targetTab),
                        userSummary = decision.summary.ifBlank { "Switching tab" },
                        requiresVerification = true
                    )
                }
                else -> null
            }

            if (step == null) {
                val fallbackMsg = decision.summary.ifBlank { "Browser task complete." }
                actionLog.completeAction("step_$currentStepNum", fallbackMsg)
                eventBus.emit(BrowserEvent.TaskCompleted(taskId, fallbackMsg))
                isGoalAchieved = true
                val duration = System.currentTimeMillis() - startTime
                _telemetry.value = _telemetry.value.copy(lastTaskDurationMs = duration)
                return BrowserExecutionResult(
                    isSuccess = true,
                    answer = decision.answer,
                    summary = fallbackMsg,
                    extractedContext = pageSnippet.takeIf { it.isNotBlank() },
                    finalUrl = currentContext.currentUrl,
                    pageTitle = currentContext.currentTitle
                )
            }

            onProgress?.invoke(currentStepNum, maxSteps, step.userSummary)

            // High-risk action detection (payments, destructive actions, checkout, sensitive submits)
            val actionDesc = (step.userSummary + " " + step.arguments.values.joinToString(" ")).lowercase(java.util.Locale.ROOT)
            val isHighRisk = actionDesc.contains("buy now") || actionDesc.contains("place order") ||
                    actionDesc.contains("checkout") || actionDesc.contains("pay now") ||
                    actionDesc.contains("delete account") || actionDesc.contains("confirm payment")

            if (isHighRisk) {
                val confirmationDeferred = kotlinx.coroutines.CompletableDeferred<Boolean>()
                _pendingConfirmation.value = com.lichiai.browser.context.BrowserConfirmationRequest(
                    confirmationId = UUID.randomUUID().toString(),
                    actionType = step.toolName,
                    description = step.userSummary,
                    amount = currentContext.candidatePrices.firstOrNull(),
                    riskLevel = "CRITICAL",
                    onConfirm = { confirmationDeferred.complete(true) },
                    onCancel = { confirmationDeferred.complete(false) }
                )
                actionLog.startAction("step_${currentStepNum}_confirm", "User confirmation needed: ${step.userSummary}")
                val approved = confirmationDeferred.await()
                if (!approved) {
                    val cancelMsg = "Action cancelled by user for security."
                    actionLog.cancelActive(cancelMsg)
                    eventBus.emit(BrowserEvent.TaskCancelled(taskId, cancelMsg))
                    return BrowserExecutionResult(
                        isSuccess = false,
                        summary = cancelMsg,
                        finalUrl = currentContext.currentUrl,
                        pageTitle = currentContext.currentTitle
                    )
                }
            }

            var activeStep = step
            // 4. LOOP GUARD
            val signature = "${decision.action}_${step.arguments}_${currentContext.currentUrl}"
            val duplicateCount = actionSignatures.count { it == signature }
            if (duplicateCount >= 2) {
                if (currentContext.extractedCandidates.size > 1 && decision.action == "CLICK_CANDIDATE") {
                    val nextIdx = (decision.index ?: 1) + 1
                    activeStep = step.copy(arguments = mapOf("index" to nextIdx.toString()))
                } else {
                    val loopMsg = "${currentContext.currentTitle.ifBlank { "Website" }} open kar di gayi hai."
                    actionLog.completeAction("step_$currentStepNum", loopMsg)
                    eventBus.emit(BrowserEvent.TaskCompleted(taskId, loopMsg))
                    isGoalAchieved = true
                    val duration = System.currentTimeMillis() - startTime
                    _telemetry.value = _telemetry.value.copy(lastTaskDurationMs = duration)
                    return BrowserExecutionResult(
                        isSuccess = true,
                        answer = decision.answer,
                        summary = loopMsg,
                        extractedContext = pageSnippet.takeIf { it.isNotBlank() },
                        finalUrl = currentContext.currentUrl,
                        pageTitle = currentContext.currentTitle
                    )
                }
            }
            actionSignatures.add(signature)

            // 5. ACT (Hands)
            val res = executor.executeStep(activeStep, currentContext) { false }
            lastStepSummary = res.summary
            actionHistory.add("Step $currentStepNum: ${activeStep.toolName}(${activeStep.arguments}) -> ${if (res.success) "SUCCESS" else "FAILED"}")

            // 6. WAIT & RE-OBSERVE (Eyes)
            delay(1500)
            var freshContext = capabilityApi.getPageContext().copy(
                taskId = taskId,
                userGoal = originalGoal,
                stepCount = currentStepNum,
                lastAction = "${step.toolName}: ${step.arguments}",
                lastActionResult = if (res.success) "SUCCESS: ${res.summary}" else "FAILED: ${res.summary}"
            )

            if (step.toolName == "search" && freshContext.extractedCandidates.isEmpty()) {
                delay(1200)
                freshContext = capabilityApi.getPageContext().copy(
                    taskId = taskId,
                    userGoal = originalGoal,
                    stepCount = currentStepNum,
                    lastAction = "${step.toolName}: ${step.arguments}",
                    lastActionResult = "SUCCESS: Loaded search results"
                )
            }

            currentContext = freshContext
            lastTaskContext = currentContext

            // 7. DETERMINISTIC VERIFICATION CHECK
            val freshSnippet = capabilityApi.extractPageSummary()
            val autoVerification = BrowserVerifier.verifyGoalCompletion(originalGoal, null, currentContext, freshSnippet)
            if (autoVerification.passed && (decision.action == "CLICK_CANDIDATE" || decision.action == "CLICK_ELEMENT" || decision.action == "NAVIGATE")) {
                isGoalAchieved = true
                val finalMsg = "${currentContext.currentTitle.ifBlank { "Website" }} open ho gayi hai aur verify ho gayi."
                actionLog.completeAction("step_$currentStepNum", finalMsg)
                eventBus.emit(BrowserEvent.TaskCompleted(taskId, finalMsg))
                val duration = System.currentTimeMillis() - startTime
                _telemetry.value = _telemetry.value.copy(lastTaskDurationMs = duration)
                return BrowserExecutionResult(
                    isSuccess = true,
                    answer = decision.answer,
                    summary = finalMsg,
                    extractedContext = freshSnippet.takeIf { it.isNotBlank() },
                    finalUrl = currentContext.currentUrl,
                    pageTitle = currentContext.currentTitle
                )
            }
        }

        val freshSnippet = capabilityApi.extractPageSummary()
        val finalMsg = lastStepSummary.ifBlank { "Browser task finished after $currentStepNum steps." }
        eventBus.emit(BrowserEvent.TaskCompleted(taskId, finalMsg))

        val duration = System.currentTimeMillis() - startTime
        _telemetry.value = _telemetry.value.copy(lastTaskDurationMs = duration)
        return BrowserExecutionResult(
            isSuccess = isGoalAchieved || lastStepSummary.isNotBlank(),
            answer = finalExtractedAnswer,
            summary = finalMsg,
            extractedContext = freshSnippet.takeIf { it.isNotBlank() },
            finalUrl = currentContext.currentUrl,
            pageTitle = currentContext.currentTitle
        )
    }
}
