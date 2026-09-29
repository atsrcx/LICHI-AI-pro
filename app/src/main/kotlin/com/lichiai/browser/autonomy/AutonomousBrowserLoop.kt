package com.lichiai.browser.autonomy

import com.lichiai.browser.BrowserController
import com.lichiai.browser.actions.ActionExecutionStatus
import com.lichiai.browser.actions.BrowserActionEngine
import com.lichiai.browser.actions.BrowserActionResult
import com.lichiai.browser.actions.TypedBrowserAction
import com.lichiai.browser.actions.UserInterventionKind
import com.lichiai.browser.perception.BrowserPerceptionLayer
import com.lichiai.browser.perception.PagePerceptionSnapshot
import com.lichiai.browser.recovery.BrowserRecovery
import com.lichiai.browser.verifier.BrowserVerifier
import com.lichiai.orchestrator.loop.OrchestratorLoopGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * State of the autonomous browser loop execution.
 */
data class AutonomyLoopState(
    val taskId: String,
    val userGoal: String,
    val currentStepIndex: Int = 0,
    val maxSteps: Int = 8,
    val plannedActions: MutableList<TypedBrowserAction> = mutableListOf(),
    val executedResults: MutableList<BrowserActionResult> = mutableListOf(),
    val isPaused: Boolean = false,
    val pauseReason: String? = null,
    val pauseInterventionKind: UserInterventionKind = UserInterventionKind.NONE,
    val isCompleted: Boolean = false,
    val finalSummary: String? = null,
    val error: String? = null
)

/**
 * Autonomous Browser Loop with Closed Loop Verification, Bounded Recovery,
 * and Pause/Resume for CAPTCHA/Login interventions.
 */
class AutonomousBrowserLoop(
    private val browserController: BrowserController,
    private val perceptionLayer: BrowserPerceptionLayer,
    private val actionEngine: BrowserActionEngine,
    private val recovery: BrowserRecovery,
    private val loopGuard: OrchestratorLoopGuard = OrchestratorLoopGuard()
) {

    private var activeLoopState: AutonomyLoopState? = null

    /**
     * Executes a planned autonomous task using the closed-loop cycle:
     * OBSERVE -> UNDERSTAND -> PLAN -> ACT -> OBSERVE -> VERIFY -> RECOVER -> CONTINUE / DONE
     */
    suspend fun runLoop(
        taskId: String,
        goal: String,
        initialActions: List<TypedBrowserAction>,
        onProgress: ((step: Int, total: Int, statusText: String) -> Unit)? = null
    ): AutonomyLoopState = withContext(Dispatchers.Main) {
        val state = AutonomyLoopState(
            taskId = taskId,
            userGoal = goal,
            plannedActions = initialActions.toMutableList()
        )
        activeLoopState = state

        while (state.currentStepIndex < state.plannedActions.size && state.currentStepIndex < state.maxSteps) {
            val currentAction = state.plannedActions[state.currentStepIndex]
            val stepNum = state.currentStepIndex + 1
            val totalSteps = state.plannedActions.size

            onProgress?.invoke(stepNum, totalSteps, "Executing action: ${currentAction::class.simpleName}")

            // 1. OBSERVE (Pre-action)
            val preObservation = perceptionLayer.observePage(browserController.activeEngine.value)

            // Check for CAPTCHA / Login blocking before action
            if (preObservation.hasCaptchaOrLogin) {
                state.copy(
                    isPaused = true,
                    pauseReason = "Authentication or CAPTCHA detected on page. Pausing for user interaction.",
                    pauseInterventionKind = UserInterventionKind.CAPTCHA_REQUIRED
                ).also {
                    activeLoopState = it
                    return@withContext it
                }
            }

            // 2. ACT
            var result = actionEngine.executeAction(currentAction)

            // Check if action paused for user (e.g. Password entry / Confirmation)
            if (result.status == ActionExecutionStatus.PAUSED_FOR_USER || result.status == ActionExecutionStatus.REQUIRES_CONFIRMATION) {
                return@withContext state.copy(
                    isPaused = true,
                    pauseReason = result.message,
                    pauseInterventionKind = result.interventionKind
                ).also { activeLoopState = it }
            }

            // 3. OBSERVE (Post-action)
            delay(600) // Allow DOM mutations to settle
            val postObservation = perceptionLayer.observePage(browserController.activeEngine.value)

            // 4. VERIFY
            val isVerified = verifyStepOutcome(currentAction, preObservation, postObservation, result)

            // 5. RECOVER (if step failed or element stale)
            if (!isVerified || result.status == ActionExecutionStatus.STALE_ELEMENT || !result.isSuccess) {
                onProgress?.invoke(stepNum, totalSteps, "Attempting recovery for step $stepNum...")
                val recoveryAction = formulateRecovery(currentAction, postObservation)
                if (recoveryAction != null) {
                    val recoveredResult = actionEngine.executeAction(recoveryAction)
                    delay(500)
                    val recObservation = perceptionLayer.observePage(browserController.activeEngine.value)
                    if (recoveredResult.isSuccess && recObservation.url.isNotBlank()) {
                        result = recoveredResult
                    }
                }
            }

            state.executedResults.add(result)
            val nextIndex = state.currentStepIndex + 1
            activeLoopState = state.copy(currentStepIndex = nextIndex)

            // Check if Done
            if (currentAction is TypedBrowserAction.Done || result.actionName == "Done") {
                val completed = state.copy(
                    isCompleted = true,
                    finalSummary = result.message
                )
                activeLoopState = completed
                return@withContext completed
            }
        }

        val finalState = state.copy(
            isCompleted = true,
            finalSummary = state.executedResults.lastOrNull()?.message ?: "Autonomous browsing completed."
        )
        activeLoopState = finalState
        finalState
    }

    /**
     * Resumes an existing paused task without resetting the step state.
     */
    suspend fun resumeTask(
        onProgress: ((step: Int, total: Int, statusText: String) -> Unit)? = null
    ): AutonomyLoopState? = withContext(Dispatchers.Main) {
        val paused = activeLoopState ?: return@withContext null
        if (!paused.isPaused) return@withContext paused

        // Unpause and continue from the paused step
        val unpaused = paused.copy(
            isPaused = false,
            pauseReason = null,
            pauseInterventionKind = UserInterventionKind.NONE
        )
        activeLoopState = unpaused
        runLoop(
            taskId = unpaused.taskId,
            goal = unpaused.userGoal,
            initialActions = unpaused.plannedActions.drop(unpaused.currentStepIndex),
            onProgress = onProgress
        )
    }

    private fun verifyStepOutcome(
        action: TypedBrowserAction,
        pre: PagePerceptionSnapshot,
        post: PagePerceptionSnapshot,
        result: BrowserActionResult
    ): Boolean {
        if (!result.isSuccess) return false
        return when (action) {
            is TypedBrowserAction.OpenURL -> post.url.contains(action.url.take(15)) || post.url != "about:blank"
            is TypedBrowserAction.TapElement -> post.url != pre.url || post.loadingState.scrollY != pre.loadingState.scrollY || result.isSuccess
            is TypedBrowserAction.TypeText -> result.isSuccess
            is TypedBrowserAction.Scroll -> true
            else -> result.isSuccess
        }
    }

    private fun formulateRecovery(
        failedAction: TypedBrowserAction,
        currentObservation: PagePerceptionSnapshot
    ): TypedBrowserAction? {
        return when (failedAction) {
            is TypedBrowserAction.TapElement -> {
                // If specific element was stale, try tapping first clickable candidate link or scrolling to make it visible
                val firstCandidate = currentObservation.candidateLinks.firstOrNull()
                if (firstCandidate != null) {
                    TypedBrowserAction.OpenURL(firstCandidate.url)
                } else {
                    TypedBrowserAction.Scroll(com.lichiai.browser.api.ScrollDirection.DOWN, 1)
                }
            }
            is TypedBrowserAction.OpenURL -> {
                TypedBrowserAction.Reload
            }
            else -> null
        }
    }

    fun getActiveState(): AutonomyLoopState? = activeLoopState
}
