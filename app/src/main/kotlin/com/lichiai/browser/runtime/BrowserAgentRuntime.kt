package com.lichiai.browser.runtime

import com.lichiai.browser.BrowserController
import com.lichiai.browser.actions.ActionExecutionStatus
import com.lichiai.browser.actions.BrowserActionEngine
import com.lichiai.browser.actions.BrowserActionResult
import com.lichiai.browser.actions.TypedBrowserAction
import com.lichiai.browser.actions.UserInterventionKind
import com.lichiai.browser.agent.BrowserExecutionResult
import com.lichiai.browser.api.BrowserCommandParser
import com.lichiai.browser.api.BrowserUserIntent
import com.lichiai.browser.api.ScrollDirection
import com.lichiai.browser.context.BrowserExecutionStatus
import com.lichiai.browser.context.BrowserMemory
import com.lichiai.browser.context.BrowserTaskContext
import com.lichiai.browser.events.BrowserActionLog
import com.lichiai.browser.events.BrowserEvent
import com.lichiai.browser.events.BrowserEventBus
import com.lichiai.browser.llm.BrowserAgentDecision
import com.lichiai.browser.llm.BrowserLLMClient
import com.lichiai.browser.perception.BrowserPerceptionLayer
import com.lichiai.browser.perception.PagePerceptionSnapshot
import com.lichiai.browser.recovery.BrowserRecovery
import com.lichiai.browser.storage.BrowserStorageManager
import com.lichiai.browser.verifier.BrowserVerifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

enum class GoalVerificationStatus {
    VERIFIED,
    NOT_VERIFIED,
    PARTIALLY_VERIFIED,
    USER_INTERVENTION_REQUIRED,
    FAILED
}

data class GoalVerificationResult(
    val status: GoalVerificationStatus,
    val expectedState: String,
    val observedState: String,
    val verificationEvidence: String,
    val confidence: Float = 1.0f,
    val reasonCode: String = ""
)

/**
 * Authoritative Unified Autonomous Browser Operating System Runtime.
 *
 * Implements the closed-loop execution contract:
 * OBSERVE -> GROUND TARGET -> DECIDE ACTION -> SAFETY CHECK -> EXECUTE -> OBSERVE CHANGE -> VERIFY -> RECOVER -> UPDATE MODEL -> NEXT / FINISH.
 */
class BrowserAgentRuntime(
    private val browserController: BrowserController,
    private val perceptionLayer: BrowserPerceptionLayer,
    private val actionEngine: BrowserActionEngine,
    private val llmClient: BrowserLLMClient,
    private val storageManager: BrowserStorageManager,
    private val memory: BrowserMemory,
    private val actionLog: BrowserActionLog,
    private val eventBus: BrowserEventBus,
    private val targetResolver: BrowserTargetResolver = BrowserTargetResolver(),
    private val formEngine: BrowserFormEngine = BrowserFormEngine(),
    private val recovery: BrowserRecovery = BrowserRecovery(browserController, eventBus),
    private val shortcutExecutor: BrowserShortcutExecutor = BrowserShortcutExecutor(browserController, actionEngine, perceptionLayer, storageManager, eventBus),
    private val scope: CoroutineScope
) {

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    private val _currentWorldModel = MutableStateFlow(BrowserWorldModel())
    val currentWorldModel: StateFlow<BrowserWorldModel> = _currentWorldModel.asStateFlow()

    private val _pendingIntervention = MutableStateFlow<BrowserActionResult?>(null)
    val pendingIntervention: StateFlow<BrowserActionResult?> = _pendingIntervention.asStateFlow()

    private var activeJob: Job? = null
    private var lastIntent: BrowserTaskIntent? = null
    private var lastTaskId: String = ""

    companion object {
        private const val MAX_STEPS = 8
        private const val MAX_RECOVERY_ATTEMPTS = 3
    }

    /**
     * Executes a high-level structured browser task intent.
     */
    suspend fun executeGoal(
        intent: BrowserTaskIntent,
        onProgress: ((step: Int, total: Int, text: String) -> Unit)? = null
    ): BrowserExecutionResult = withContext(Dispatchers.Default) {
        val trimmed = intent.goal.trim()
        if (trimmed.isBlank()) {
            return@withContext BrowserExecutionResult(isSuccess = true, summary = "Empty browser goal.")
        }

        val taskId = UUID.randomUUID().toString()
        lastTaskId = taskId
        lastIntent = intent

        actionLog.setGoal(trimmed)
        eventBus.emit(BrowserEvent.TaskStarted(taskId, trimmed))

        // Check for immediate STOP command
        val parsedIntent = BrowserCommandParser.parse(trimmed)
        if (parsedIntent is BrowserUserIntent.StopTask) {
            cancel("User requested stop")
            return@withContext BrowserExecutionResult(isSuccess = true, summary = "Browser task stopped.")
        }

        // Fast path for explicit # shortcut commands via BrowserShortcutExecutor
        if (trimmed.startsWith("#")) {
            _isBusy.value = true
            return@withContext try {
                shortcutExecutor.executeShortcut(taskId, trimmed, onProgress)
            } finally {
                _isBusy.value = false
            }
        }

        _isBusy.value = true
        val startTime = System.currentTimeMillis()

        try {
            // 1. INITIAL OBSERVATION
            onProgress?.invoke(1, MAX_STEPS, "Perceiving browser state...")
            val initialSnapshot = perceptionLayer.observePage(browserController.activeEngine.value)
            updateWorldModel(initialSnapshot)

            // 2. CLOSED-LOOP REASONING & EXECUTION
            executeClosedLoop(taskId, intent, onProgress, startTime)
        } catch (ce: CancellationException) {
            actionLog.cancelActive("Task cancelled")
            BrowserExecutionResult(isSuccess = false, summary = "Task cancelled")
        } catch (e: Exception) {
            actionLog.failAction("error", "Runtime error: ${e.message}")
            eventBus.emit(BrowserEvent.BrowserError(e.message ?: "Unknown error"))
            BrowserExecutionResult(isSuccess = false, summary = "Browser error: ${e.message}")
        } finally {
            _isBusy.value = false
        }
    }

    /**
     * Compatibility helper: executes a string instruction.
     */
    suspend fun executeInstruction(
        instruction: String,
        onProgress: ((step: Int, total: Int, text: String) -> Unit)? = null
    ): BrowserExecutionResult {
        val intent = BrowserTaskIntent.fromGoal(instruction)
        return executeGoal(intent, onProgress)
    }

    /**
     * Primary Closed-Loop Execution Cycle.
     */
    private suspend fun executeClosedLoop(
        taskId: String,
        intent: BrowserTaskIntent,
        onProgress: ((step: Int, total: Int, text: String) -> Unit)?,
        startTime: Long
    ): BrowserExecutionResult {
        var currentStepNum = 0
        var isGoalAchieved = false
        var lastStepSummary = ""
        var finalExtractedAnswer: String? = null
        var recoveryCount = 0

        val actionSignatures = mutableListOf<String>()

        while (currentStepNum < MAX_STEPS && !isGoalAchieved) {
            currentStepNum++

            // STEP 1: OBSERVE
            val preSnapshot = perceptionLayer.observePage(browserController.activeEngine.value)
            val currentWorld = updateWorldModel(preSnapshot, lastStepSummary)

            actionLog.startAction("step_$currentStepNum", "Step $currentStepNum: Observing page state (${preSnapshot.url})...")
            onProgress?.invoke(currentStepNum, MAX_STEPS, "Analyzing page state (Step $currentStepNum)...")

            // Check for CAPTCHA / Login / Security Interventions
            if (preSnapshot.hasCaptchaOrLogin) {
                val pauseMsg = "CAPTCHA or login verification detected on page. Pausing for human interaction."
                actionLog.completeAction("step_$currentStepNum", pauseMsg)
                val interventionResult = BrowserActionResult(
                    status = ActionExecutionStatus.PAUSED_FOR_USER,
                    actionName = "CaptchaDetection",
                    isSuccess = false,
                    message = pauseMsg,
                    currentUrl = preSnapshot.url,
                    currentTitle = preSnapshot.title,
                    interventionKind = UserInterventionKind.CAPTCHA_REQUIRED
                )
                _pendingIntervention.value = interventionResult
                return BrowserExecutionResult(
                    isSuccess = false,
                    summary = pauseMsg,
                    finalUrl = preSnapshot.url,
                    pageTitle = preSnapshot.title
                )
            }

            // STEP 2: DECIDE (using LLM with compact world model state)
            val legacyContext = browserController.getPageContext().copy(
                taskId = taskId,
                userGoal = intent.goal,
                stepCount = currentStepNum,
                perceptionGenerationId = preSnapshot.generationId,
                interactiveElements = preSnapshot.semanticElements.map { it.toInteractiveElement() },
                extractedCandidates = preSnapshot.candidateLinks,
                candidatePrices = preSnapshot.candidatePrices
            )

            val pageSnippet = preSnapshot.visibleTextSnippet
            val decision = llmClient.decideNextAction(legacyContext, pageSnippet)

            // Handle Direct Answer
            if (decision.action == "ANSWER" && !decision.answer.isNullOrBlank()) {
                finalExtractedAnswer = decision.answer
                actionLog.completeAction("step_$currentStepNum", decision.summary.ifBlank { "Answer extracted." })
                eventBus.emit(BrowserEvent.TaskCompleted(taskId, finalExtractedAnswer))
                isGoalAchieved = true
                return BrowserExecutionResult(
                    isSuccess = true,
                    answer = finalExtractedAnswer,
                    summary = decision.summary.ifBlank { finalExtractedAnswer },
                    extractedContext = pageSnippet.takeIf { it.isNotBlank() },
                    finalUrl = preSnapshot.url,
                    pageTitle = preSnapshot.title
                )
            }

            // Handle Goal Stop with explicit verification check
            if (decision.action == "STOP") {
                val goalVerification = verifyGoal(intent, preSnapshot)
                val isVerified = goalVerification.status == GoalVerificationStatus.VERIFIED || goalVerification.status == GoalVerificationStatus.PARTIALLY_VERIFIED
                val msg = decision.summary.ifBlank {
                    if (isVerified) "Opened and verified '${preSnapshot.title.ifBlank { preSnapshot.url }}'." else "Browser stopped on '${preSnapshot.title.ifBlank { preSnapshot.url }}'."
                }
                actionLog.completeAction("step_$currentStepNum", msg)
                eventBus.emit(BrowserEvent.TaskCompleted(taskId, msg))
                isGoalAchieved = isVerified
                return BrowserExecutionResult(
                    isSuccess = isVerified,
                    answer = decision.answer,
                    summary = msg,
                    extractedContext = pageSnippet.takeIf { it.isNotBlank() },
                    finalUrl = preSnapshot.url,
                    pageTitle = preSnapshot.title
                )
            }

            // STEP 3: GROUND TARGET & RESOLVE TYPED ACTION
            val typedAction = mapDecisionToTypedAction(decision, intent, preSnapshot)
            if (typedAction == null) {
                // Phase 15: Never return fake success when typed action is null
                val errorMsg = "Unrecognized or invalid browser action: '${decision.action}'"
                actionLog.failAction("step_$currentStepNum", errorMsg)
                if (recoveryCount < MAX_RECOVERY_ATTEMPTS) {
                    recoveryCount++
                    continue
                }
                return BrowserExecutionResult(
                    isSuccess = false,
                    summary = errorMsg,
                    finalUrl = preSnapshot.url,
                    pageTitle = preSnapshot.title
                )
            }

            // STEP 4: ACTION LOOP GUARD (Phase 16)
            val signature = "${decision.action}_${typedAction}_${preSnapshot.url}"
            val duplicateCount = actionSignatures.count { it == signature }
            if (duplicateCount >= 2) {
                // Phase 16: When loop detected, trigger recovery or terminate with failure
                if (recoveryCount < MAX_RECOVERY_ATTEMPTS) {
                    recoveryCount++
                    actionLog.startAction("step_${currentStepNum}_recovery", "Loop detected: attempting page scroll/recovery...")
                    actionEngine.executeAction(TypedBrowserAction.Scroll(ScrollDirection.DOWN, 1))
                    BrowserConditionWaiter.waitForDomStable(getEngine = { browserController.activeEngine.value })
                    continue
                } else {
                    val loopMsg = "Action loop detected: repeated action without state progression."
                    actionLog.failAction("step_$currentStepNum", loopMsg)
                    return BrowserExecutionResult(
                        isSuccess = false,
                        summary = loopMsg,
                        finalUrl = preSnapshot.url,
                        pageTitle = preSnapshot.title
                    )
                }
            }
            actionSignatures.add(signature)

            onProgress?.invoke(currentStepNum, MAX_STEPS, decision.summary.ifBlank { "Executing ${typedAction::class.simpleName}..." })

            // STEP 5: SENSITIVE FIELD CHECK (Phase 9)
            if (typedAction is TypedBrowserAction.TypeText) {
                val resolvedTarget = targetResolver.resolveTarget(typedAction.targetIdOrIndex, preSnapshot)
                if (resolvedTarget is TargetResolutionResult.Resolved && formEngine.isSensitiveField(resolvedTarget.element)) {
                    val sensitiveMsg = "Sensitive credential or security input required (${resolvedTarget.element.semanticId}). Pausing for user interaction."
                    actionLog.completeAction("step_$currentStepNum", sensitiveMsg)
                    val intervention = BrowserActionResult(
                        status = ActionExecutionStatus.PAUSED_FOR_USER,
                        actionName = "SensitiveFieldProtection",
                        isSuccess = false,
                        message = sensitiveMsg,
                        currentUrl = preSnapshot.url,
                        currentTitle = preSnapshot.title,
                        interventionKind = UserInterventionKind.LOGIN_REQUIRED
                    )
                    _pendingIntervention.value = intervention
                    return BrowserExecutionResult(
                        isSuccess = false,
                        summary = sensitiveMsg,
                        finalUrl = preSnapshot.url,
                        pageTitle = preSnapshot.title
                    )
                }
            }

            // STEP 6: EXECUTE ACTION
            var actionResult = actionEngine.executeAction(typedAction)
            lastStepSummary = actionResult.message

            // If action paused for human intervention (e.g. password, OTP, payment confirmation)
            if (actionResult.status == ActionExecutionStatus.PAUSED_FOR_USER || actionResult.status == ActionExecutionStatus.REQUIRES_CONFIRMATION) {
                _pendingIntervention.value = actionResult
                return BrowserExecutionResult(
                    isSuccess = false,
                    summary = actionResult.message,
                    finalUrl = preSnapshot.url,
                    pageTitle = preSnapshot.title
                )
            }

            // STEP 7: CONDITION-BASED WAIT & OBSERVE POST-ACTION STATE (Phase 10)
            BrowserConditionWaiter.waitForDomStable(settleMs = 250L, timeoutMs = 2500L) { browserController.activeEngine.value }
            val postSnapshot = perceptionLayer.observePage(browserController.activeEngine.value)
            updateWorldModel(postSnapshot, lastStepSummary)

            // STEP 8: DETERMINISTIC ACTION VERIFICATION
            val isVerified = verifyActionOutcome(typedAction, preSnapshot, postSnapshot, actionResult)

            // STEP 9: RECOVERY (if verification failed or target was stale)
            if (!isVerified || actionResult.status == ActionExecutionStatus.STALE_ELEMENT || actionResult.status == ActionExecutionStatus.STALE_TARGET_GENERATION || !actionResult.isSuccess) {
                if (recoveryCount < MAX_RECOVERY_ATTEMPTS) {
                    recoveryCount++
                    onProgress?.invoke(currentStepNum, MAX_STEPS, "Target stale or action unverified; re-observing and attempting recovery...")
                    val freshSnapshot = perceptionLayer.observePage(browserController.activeEngine.value)
                    val recoveryAction = formulateRecovery(typedAction, freshSnapshot)
                    if (recoveryAction != null) {
                        val recoveredRes = actionEngine.executeAction(recoveryAction)
                        BrowserConditionWaiter.waitForDomStable(settleMs = 200L, timeoutMs = 2000L) { browserController.activeEngine.value }
                        val recSnapshot = perceptionLayer.observePage(browserController.activeEngine.value)
                        if (recoveredRes.isSuccess && recSnapshot.url.isNotBlank()) {
                            actionResult = recoveredRes
                            lastStepSummary = recoveredRes.message
                        }
                    }
                }
            }

            // STEP 10: CHECK HIGH-LEVEL GOAL VERIFICATION (Phase 14)
            val goalVerification = verifyGoal(intent, postSnapshot)
            if (goalVerification.status == GoalVerificationStatus.VERIFIED && (decision.action == "CLICK_CANDIDATE" || decision.action == "CLICK_ELEMENT" || decision.action == "NAVIGATE")) {
                isGoalAchieved = true
                val finalMsg = "Opened and verified '${postSnapshot.title.ifBlank { postSnapshot.url }}'."
                actionLog.completeAction("step_$currentStepNum", finalMsg)
                eventBus.emit(BrowserEvent.TaskCompleted(taskId, finalMsg))
                return BrowserExecutionResult(
                    isSuccess = true,
                    summary = finalMsg,
                    extractedContext = postSnapshot.visibleTextSnippet.takeIf { it.isNotBlank() },
                    finalUrl = postSnapshot.url,
                    pageTitle = postSnapshot.title
                )
            }
        }

        val finalSnapshot = perceptionLayer.observePage(browserController.activeEngine.value)
        val finalGoalCheck = verifyGoal(intent, finalSnapshot)
        val finalSuccess = isGoalAchieved || finalGoalCheck.status == GoalVerificationStatus.VERIFIED
        val finalMsg = if (finalSuccess) lastStepSummary.ifBlank { "Browser goal completed." } else "Browser task ended without verifying goal completion."
        eventBus.emit(BrowserEvent.TaskCompleted(taskId, finalMsg))

        return BrowserExecutionResult(
            isSuccess = finalSuccess,
            answer = finalExtractedAnswer,
            summary = finalMsg,
            extractedContext = finalSnapshot.visibleTextSnippet.takeIf { it.isNotBlank() },
            finalUrl = finalSnapshot.url,
            pageTitle = finalSnapshot.title
        )
    }

    /**
     * Explicit goal verification returning structured GoalVerificationResult (Phase 14).
     */
    fun verifyGoal(intent: BrowserTaskIntent, snapshot: PagePerceptionSnapshot): GoalVerificationResult {
        val currentUrl = snapshot.url
        val currentTitle = snapshot.title
        val lowerGoal = intent.goal.lowercase()

        if (currentUrl.isBlank() || currentUrl == "about:blank") {
            return GoalVerificationResult(
                status = GoalVerificationStatus.NOT_VERIFIED,
                expectedState = "Loaded web page",
                observedState = "about:blank",
                verificationEvidence = "No page loaded in browser",
                confidence = 0.0f,
                reasonCode = "BLANK_PAGE"
            )
        }

        // 1. Specific domain or website goal verification (e.g. PUBG, YouTube, Amazon)
        val expectedDomain = when {
            lowerGoal.contains("pubg") -> "pubg.com"
            lowerGoal.contains("youtube") -> "youtube.com"
            lowerGoal.contains("google") -> "google.com"
            lowerGoal.contains("amazon") -> "amazon."
            lowerGoal.contains("github") -> "github.com"
            lowerGoal.contains("wikipedia") -> "wikipedia.org"
            else -> null
        }

        if (expectedDomain != null) {
            val matchesDomain = currentUrl.lowercase().contains(expectedDomain)
            val isSearchPage = BrowserVerifier.isSearchResultsPage(currentUrl)

            if (matchesDomain && !isSearchPage) {
                return GoalVerificationResult(
                    status = GoalVerificationStatus.VERIFIED,
                    expectedState = "Domain matching $expectedDomain",
                    observedState = currentUrl,
                    verificationEvidence = "Active URL contains expected domain $expectedDomain and title '$currentTitle'",
                    confidence = 0.95f,
                    reasonCode = "DOMAIN_MATCHED"
                )
            } else if (isSearchPage && (lowerGoal.contains("open") || lowerGoal.contains("kholo") || lowerGoal.contains("play"))) {
                return GoalVerificationResult(
                    status = GoalVerificationStatus.PARTIALLY_VERIFIED,
                    expectedState = "Destination website $expectedDomain",
                    observedState = "Search results page: $currentUrl",
                    verificationEvidence = "Still on search results page; candidate link selection required",
                    confidence = 0.5f,
                    reasonCode = "SEARCH_RESULTS_ONLY"
                )
            }
        }

        // 2. Search query verification
        if (intent.taskType == BrowserTaskType.SEARCH && BrowserVerifier.isSearchResultsPage(currentUrl)) {
            return GoalVerificationResult(
                status = GoalVerificationStatus.VERIFIED,
                expectedState = "Search results page for \"${intent.goal}\"",
                observedState = currentUrl,
                verificationEvidence = "Search results page loaded with candidates",
                confidence = 0.9f,
                reasonCode = "SEARCH_COMPLETED"
            )
        }

        // 3. General non-blank loaded page
        val isLoaded = snapshot.loadingState.isLoaded
        return GoalVerificationResult(
            status = if (isLoaded) GoalVerificationStatus.VERIFIED else GoalVerificationStatus.PARTIALLY_VERIFIED,
            expectedState = "Loaded page",
            observedState = "$currentUrl ('$currentTitle')",
            verificationEvidence = "Page loaded successfully",
            confidence = 0.8f,
            reasonCode = "PAGE_LOADED"
        )
    }

    private fun mapDecisionToTypedAction(
        decision: BrowserAgentDecision,
        intent: BrowserTaskIntent,
        snapshot: PagePerceptionSnapshot
    ): TypedBrowserAction? {
        val genId = snapshot.generationId
        return when (decision.action) {
            "SEARCH" -> {
                val q = decision.query?.takeIf { it.isNotBlank() } ?: intent.goal
                val engineUrl = storageManager.settings.value.searchEngineUrl
                val searchUrl = "${engineUrl.trimEnd('/')}/search?q=${java.net.URLEncoder.encode(q, "UTF-8")}"
                TypedBrowserAction.OpenURL(searchUrl, generationId = genId)
            }
            "CLICK_CANDIDATE" -> {
                val idx = decision.index ?: 1
                val candidate = snapshot.candidateLinks.firstOrNull { it.index == idx }
                if (candidate != null && candidate.url.isNotBlank()) {
                    TypedBrowserAction.OpenURL(candidate.url, generationId = genId)
                } else {
                    TypedBrowserAction.TapElement(idx.toString(), generationId = genId)
                }
            }
            "CLICK_ELEMENT" -> {
                val targetQuery = decision.text ?: decision.selector ?: decision.index?.toString() ?: "1"
                val resolved = targetResolver.resolveTarget(targetQuery, snapshot)
                when (resolved) {
                    is TargetResolutionResult.Resolved -> TypedBrowserAction.TapElement(resolved.element.semanticId, generationId = genId)
                    else -> TypedBrowserAction.TapElement(targetQuery, generationId = genId)
                }
            }
            "CLICK_SELECTOR" -> {
                val targetQuery = decision.text ?: decision.selector ?: ""
                TypedBrowserAction.TapElement(targetQuery, generationId = genId)
            }
            "TYPE_TEXT" -> {
                val text = decision.text ?: ""
                val targetQuery = decision.selector ?: decision.index?.toString() ?: "search_input_1"
                TypedBrowserAction.TypeText(targetQuery, text, submit = decision.submit, generationId = genId)
            }
            "NAVIGATE" -> {
                val url = decision.url ?: "https://www.google.com"
                TypedBrowserAction.OpenURL(url, generationId = genId)
            }
            "SCROLL" -> {
                val dir = if (decision.direction?.equals("UP", ignoreCase = true) == true) ScrollDirection.UP else ScrollDirection.DOWN
                TypedBrowserAction.Scroll(dir, amount = 1, generationId = genId)
            }
            "GO_BACK" -> TypedBrowserAction.Back(generationId = genId)
            "GO_FORWARD" -> TypedBrowserAction.Forward(generationId = genId)
            "RELOAD" -> TypedBrowserAction.Reload(generationId = genId)
            "EXTRACT_TABLE" -> TypedBrowserAction.ExtractTable(generationId = genId)
            "SWITCH_TAB" -> TypedBrowserAction.SwitchTab(decision.tabId ?: "", generationId = genId)
            else -> null
        }
    }

    private fun verifyActionOutcome(
        action: TypedBrowserAction,
        pre: PagePerceptionSnapshot,
        post: PagePerceptionSnapshot,
        result: BrowserActionResult
    ): Boolean {
        if (!result.isSuccess) return false
        return when (action) {
            is TypedBrowserAction.OpenURL -> {
                post.url.isNotBlank() && post.url != "about:blank"
            }
            is TypedBrowserAction.Back -> {
                post.url != pre.url || post.title != pre.title
            }
            is TypedBrowserAction.Reload -> {
                post.url.isNotBlank()
            }
            is TypedBrowserAction.TapElement -> {
                post.url != pre.url || post.title != pre.title || post.generationId != pre.generationId
            }
            is TypedBrowserAction.TypeText -> {
                result.isSuccess
            }
            is TypedBrowserAction.Scroll -> true
            else -> result.isSuccess
        }
    }

    private fun formulateRecovery(
        failedAction: TypedBrowserAction,
        postSnapshot: PagePerceptionSnapshot
    ): TypedBrowserAction? {
        return when (failedAction) {
            is TypedBrowserAction.TapElement -> {
                // If candidate links exist, try opening the first one
                val candidate = postSnapshot.candidateLinks.firstOrNull()
                if (candidate != null && candidate.url.isNotBlank()) {
                    TypedBrowserAction.OpenURL(candidate.url)
                } else {
                    TypedBrowserAction.Scroll(ScrollDirection.DOWN, 1)
                }
            }
            is TypedBrowserAction.OpenURL -> {
                TypedBrowserAction.Reload()
            }
            else -> null
        }
    }

    private fun updateWorldModel(snapshot: PagePerceptionSnapshot, lastSummary: String? = null): BrowserWorldModel {
        val model = BrowserWorldModel.fromSnapshot(
            snapshot = snapshot,
            activeTabId = browserController.tabManager.activeTabId.value,
            tabsCount = browserController.tabManager.tabs.value.size,
            lastActionResult = lastSummary
        )
        _currentWorldModel.value = model
        return model
    }

    fun cancel(reason: String = "User requested stop") {
        activeJob?.cancel()
        activeJob = null
        _pendingIntervention.value = null
        actionLog.cancelActive(reason)
        _isBusy.value = false
        eventBus.emit(BrowserEvent.TaskCancelled(lastTaskId, reason))
    }

    private fun com.lichiai.browser.perception.SemanticElement.toInteractiveElement(): com.lichiai.browser.context.BrowserInteractiveElement {
        return com.lichiai.browser.context.BrowserInteractiveElement(
            index = this.originalIndex,
            tag = this.tag,
            type = this.type,
            text = this.labelOrText,
            id = this.semanticId,
            name = this.semanticId,
            placeholder = this.placeholder,
            ariaLabel = this.labelOrText,
            href = this.href,
            isClickable = this.isClickable,
            isInput = this.isInput,
            bounds = this.bounds,
            value = this.value
        )
    }
}
