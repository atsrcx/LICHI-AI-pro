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
     * Submit natural language instruction or # shortcut to the Browser Agent.
     */
    fun submitInstruction(instruction: String, onResult: ((String) -> Unit)? = null) {
        val trimmed = instruction.trim()
        if (trimmed.isBlank()) return

        val taskId = UUID.randomUUID().toString()
        actionLog.setGoal(trimmed)
        eventBus.emit(BrowserEvent.TaskStarted(taskId, trimmed))

        // Check for immediate STOP
        val parsedIntent = BrowserCommandParser.parse(trimmed)
        if (parsedIntent is BrowserUserIntent.StopTask) {
            stopActiveTask()
            onResult?.invoke("Browser task stopped.")
            return
        }

        // Fast path for explicit hash shortcuts (e.g. #open https://..., #back, #down)
        if (trimmed.startsWith("#")) {
            executeHashShortcut(taskId, trimmed, parsedIntent, onResult)
            return
        }

        currentTaskJob?.cancel()
        currentTaskJob = scope.launch(Dispatchers.Default) {
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
                // The configured Browser Agent LLM itself understands the goal, formulates search queries,
                // chooses actions, observes results, and verifies completion.
                executeSemanticAgentLoop(taskId, trimmed, initialContext, onResult, startTime)

            } catch (ce: CancellationException) {
                actionLog.cancelActive("Task stopped by user")
            } catch (e: Exception) {
                actionLog.failAction("error", "Error: ${e.message}")
                eventBus.emit(BrowserEvent.BrowserError(e.message ?: "Unknown error"))
                onResult?.invoke("Browser task error: ${e.message}")
            } finally {
                _isBusy.value = false
            }
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
                for (step in plan.steps) {
                    if (currentTaskJob?.isCancelled == true) break
                    val stepRes = executor.executeStep(step, currentContext) { currentTaskJob?.isCancelled == true }
                    finalMessage = stepRes.summary
                    if (!stepRes.success) break
                }

                delay(500)
                lastTaskContext = capabilityApi.getPageContext()
                val duration = System.currentTimeMillis() - startTime
                _telemetry.value = _telemetry.value.copy(lastTaskDurationMs = duration)
                eventBus.emit(BrowserEvent.TaskCompleted(taskId, finalMessage))
                onResult?.invoke(finalMessage)
            } finally {
                _isBusy.value = false
            }
        }
    }

    /**
     * Executes the multi-step goal-driven loop using the SAME configured Browser LLM.
     * TURN 1: User Goal -> LLM -> Action -> Executor -> Browser
     * TURN 2: Fresh Browser Result -> SAME LLM -> Next Action -> Executor -> Browser
     * ...
     * TURN N: Verified Destination -> SAME LLM -> STOP / ANSWER
     */
    private suspend fun executeSemanticAgentLoop(
        taskId: String,
        originalGoal: String,
        initialContext: BrowserTaskContext,
        onResult: ((String) -> Unit)?,
        startTime: Long
    ) {
        var currentContext = initialContext
        val maxSteps = 10
        var currentStepNum = 0
        var isGoalAchieved = false
        var lastStepSummary = ""

        // Loop guard to prevent repeating the identical action 3 times on unchanged page
        val actionHistory = mutableListOf<String>()
        val actionSignatures = mutableListOf<String>()

        while (currentStepNum < maxSteps && !isGoalAchieved) {
            if (currentTaskJob?.isCancelled == true) break
            currentStepNum++

            _telemetry.value = _telemetry.value.copy(
                totalTasks = _telemetry.value.totalTasks + 1,
                llmCalls = _telemetry.value.llmCalls + 1,
                lastAction = "LLM Step #$currentStepNum"
            )

            // 1. OBSERVE (Eyes)
            actionLog.startAction("step_$currentStepNum", "Step $currentStepNum: Page state analyze kar rahi hoon...")
            val pageSnippet = capabilityApi.extractPageSummary()

            // 2. UNDERSTAND & DECIDE (Brain - SAME CONFIGURED MODEL)
            val decision = llmClient.decideNextAction(currentContext, pageSnippet)

            // Handle Immediate Answer to question
            if (decision.action == "ANSWER" && !decision.answer.isNullOrBlank()) {
                val finalAnswer = decision.answer
                actionLog.completeAction("step_$currentStepNum", decision.summary.ifBlank { "Answer ready" })
                eventBus.emit(BrowserEvent.TaskCompleted(taskId, finalAnswer))
                onResult?.invoke(finalAnswer)
                isGoalAchieved = true
                break
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
                onResult?.invoke(msg)
                isGoalAchieved = true
                break
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
                onResult?.invoke(fallbackMsg)
                isGoalAchieved = true
                break
            }

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
                    onResult?.invoke(cancelMsg)
                    break
                }
            }

            var activeStep = step
            // 4. LOOP GUARD
            val signature = "${decision.action}_${step.arguments}_${currentContext.currentUrl}"
            val duplicateCount = actionSignatures.count { it == signature }
            if (duplicateCount >= 2) {
                // If the same action repeated on same URL, break loop or try scrolling
                if (currentContext.extractedCandidates.size > 1 && decision.action == "CLICK_CANDIDATE") {
                    // Try next candidate to unblock
                    val nextIdx = (decision.index ?: 1) + 1
                    activeStep = step.copy(arguments = mapOf("index" to nextIdx.toString()))
                } else {
                    val loopMsg = "${currentContext.currentTitle.ifBlank { "Website" }} open kar di gayi hai."
                    actionLog.completeAction("step_$currentStepNum", loopMsg)
                    eventBus.emit(BrowserEvent.TaskCompleted(taskId, loopMsg))
                    onResult?.invoke(loopMsg)
                    isGoalAchieved = true
                    break
                }
            }
            actionSignatures.add(signature)

            // 5. ACT (Hands)
            val res = executor.executeStep(activeStep, currentContext) { currentTaskJob?.isCancelled == true }
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

            // If candidates not populated yet on search results, short delay to catch dynamic DOM
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
                onResult?.invoke(finalMsg)
                break
            }
        }

        if (!isGoalAchieved) {
            val finalMsg = lastStepSummary.ifBlank { "Browser task finished after $currentStepNum steps." }
            eventBus.emit(BrowserEvent.TaskCompleted(taskId, finalMsg))
            onResult?.invoke(finalMsg)
        }

        val duration = System.currentTimeMillis() - startTime
        _telemetry.value = _telemetry.value.copy(lastTaskDurationMs = duration)
    }
}
