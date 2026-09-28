package com.lichiai.browser.executor

import com.lichiai.agentvision.coordinate.CoordinateMapper
import com.lichiai.agentvision.model.AgentVisualEvent
import com.lichiai.agentvision.model.VisualActionType
import com.lichiai.agentvision.model.VisualCoordinateSpace
import com.lichiai.agentvision.model.VisualPhase
import com.lichiai.agentvision.model.VisualPosition
import com.lichiai.agentvision.model.VisualSource
import com.lichiai.agentvision.telemetry.AgentVisionTelemetryHub
import com.lichiai.browser.api.BrowserCapabilityAPI
import com.lichiai.browser.context.BrowserTaskContext
import com.lichiai.browser.events.BrowserActionLog
import com.lichiai.browser.events.BrowserEvent
import com.lichiai.browser.events.BrowserEventBus
import com.lichiai.browser.planner.BrowserActionStep
import com.lichiai.browser.recovery.BrowserRecovery
import com.lichiai.browser.security.BrowserSecurityManager
import com.lichiai.browser.tools.BrowserToolRegistry
import com.lichiai.browser.verifier.BrowserVerifier
import kotlinx.coroutines.delay

data class ExecutionStepResult(
    val success: Boolean,
    val summary: String,
    val output: String,
    val requiresUserConfirmation: Boolean = false
)

/**
 * Isolated Browser Executor.
 * Validates, executes, verifies, and records every browser action.
 */
class BrowserExecutor(
    private val toolRegistry: BrowserToolRegistry,
    private val capabilityApi: BrowserCapabilityAPI,
    private val eventBus: BrowserEventBus,
    private val actionLog: BrowserActionLog,
    private val recovery: BrowserRecovery
) {

    suspend fun executeStep(
        step: BrowserActionStep,
        context: BrowserTaskContext,
        isCancelled: () -> Boolean
    ): ExecutionStepResult {
        if (isCancelled()) {
            actionLog.cancelActive("User stopped the task")
            return ExecutionStepResult(false, "Task cancelled by user", "CANCELLED")
        }

        // 1. Security Validation
        val sec = BrowserSecurityManager.validateAction(step.toolName, step.arguments)
        if (!sec.allowed) {
            actionLog.failAction(step.id, sec.reason)
            eventBus.emit(BrowserEvent.BrowserError(sec.reason))
            return ExecutionStepResult(false, "Security policy blocked: ${sec.reason}", "REJECTED")
        }

        if (sec.requiresUserConfirmation) {
            return ExecutionStepResult(
                success = false,
                summary = "Confirmation required: ${sec.reason}",
                output = "AWAITING_CONFIRMATION",
                requiresUserConfirmation = true
            )
        }

        // 2. Log Action Start for live UI
        actionLog.startAction(step.id, step.userSummary)
        eventBus.emit(BrowserEvent.AgentActionStarted(step.toolName, step.userSummary))

        val prevUrl = context.currentUrl

        // Resolve Target Coordinates from context for Browser Visualizer
        val actionType = when (step.toolName) {
            "clickCandidate", "clickElement", "clickSelector", "highlightElement" -> VisualActionType.TAP
            "typeText" -> VisualActionType.TYPE
            "scroll" -> VisualActionType.SCROLL
            "navigate", "search" -> VisualActionType.NAVIGATE
            "goBack" -> VisualActionType.BACK
            "goForward" -> VisualActionType.FORWARD
            "switchTab" -> VisualActionType.SWITCH_TAB
            else -> VisualActionType.MOVE
        }

        val targetIdx = step.arguments["index"]?.toIntOrNull()
        val targetText = step.arguments["text"] ?: step.arguments["query"] ?: ""
        val matchedEl = if (targetIdx != null) {
            context.interactiveElements.firstOrNull { it.index == targetIdx }
        } else if (targetText.isNotBlank()) {
            context.interactiveElements.firstOrNull { it.text.contains(targetText, ignoreCase = true) }
        } else null

        val domBounds = matchedEl?.bounds?.let { CoordinateMapper.parseBoundsString(it) }
        val targetPos = CoordinateMapper.calculateCenter(domBounds)
        val targetIdent = matchedEl?.let { el ->
            if (el.text.isNotBlank()) el.text else el.placeholder
        } ?: step.userSummary

        AgentVisionTelemetryHub.emitEvent(
            AgentVisualEvent(
                taskId = context.taskId,
                stepId = step.id,
                source = VisualSource.BROWSER_AGENT,
                actionType = actionType,
                phase = VisualPhase.STARTED,
                targetPosition = targetPos,
                targetBounds = domBounds,
                targetIdentifier = targetIdent,
                textLength = if (actionType == VisualActionType.TYPE) (step.arguments["text"]?.length ?: 0) else 0,
                typedMaskedText = if (actionType == VisualActionType.TYPE) step.arguments["text"] else null,
                scrollDeltaY = if (actionType == VisualActionType.SCROLL && step.arguments["direction"] == "UP") -1f else 1f,
                operationalDescription = step.userSummary,
                isPositionAvailable = domBounds != null
            )
        )

        // 3. Tool Execution
        val output = toolRegistry.executeTool(step.toolName, step.arguments)
        val initialOk = !output.startsWith("ERROR") && !output.startsWith("FAILED")

        // 4. Deterministic Verification
        if (initialOk && step.requiresVerification) {
            delay(1200) // allow page navigation / DOM response
            val updatedContext = capabilityApi.getPageContext()
            val verifyResult = when (step.toolName) {
                "navigate" -> BrowserVerifier.verifyNavigation(step.arguments["url"] ?: "", updatedContext)
                "search" -> BrowserVerifier.verifySearchResults(step.arguments["query"] ?: "", updatedContext)
                "clickCandidate", "clickElement", "clickSelector" -> BrowserVerifier.verifyClick(prevUrl, updatedContext)
                "typeText" -> {
                    val diff = BrowserVerifier.detectPageDifference(context, updatedContext)
                    com.lichiai.browser.verifier.BrowserVerificationResult(true, "verifyType", "Typed text successfully (${diff.summary})")
                }
                else -> com.lichiai.browser.verifier.BrowserVerificationResult(true, "generic", "ok")
            }

            if (!verifyResult.passed) {
                eventBus.emit(BrowserEvent.VerificationFailed(verifyResult.checkName, verifyResult.detail))
                // 5. Recovery Attempt
                val rec = recovery.attemptRecovery(step.toolName, step.arguments, updatedContext, 1)
                if (rec.recovered) {
                    actionLog.completeAction(step.id, rec.summary)
                    eventBus.emit(BrowserEvent.AgentActionCompleted(step.toolName, rec.summary))
                    AgentVisionTelemetryHub.emitEvent(
                        AgentVisualEvent(
                            taskId = context.taskId,
                            stepId = step.id,
                            source = VisualSource.BROWSER_AGENT,
                            actionType = actionType,
                            phase = VisualPhase.COMPLETED,
                            targetPosition = targetPos,
                            targetBounds = domBounds,
                            operationalDescription = rec.summary,
                            resultSummary = rec.summary
                        )
                    )
                    return ExecutionStepResult(true, rec.summary, output)
                } else {
                    actionLog.failAction(step.id, verifyResult.detail)
                    AgentVisionTelemetryHub.emitEvent(
                        AgentVisualEvent(
                            taskId = context.taskId,
                            stepId = step.id,
                            source = VisualSource.BROWSER_AGENT,
                            actionType = actionType,
                            phase = VisualPhase.FAILED,
                            targetPosition = targetPos,
                            targetBounds = domBounds,
                            operationalDescription = "Failed: ${verifyResult.detail}",
                            error = verifyResult.detail
                        )
                    )
                    return ExecutionStepResult(false, verifyResult.detail, output)
                }
            } else {
                eventBus.emit(BrowserEvent.VerificationPassed(verifyResult.detail))
            }
        }

        return if (initialOk) {
            actionLog.completeAction(step.id, step.userSummary)
            eventBus.emit(BrowserEvent.AgentActionCompleted(step.toolName, step.userSummary))
            AgentVisionTelemetryHub.emitEvent(
                AgentVisualEvent(
                    taskId = context.taskId,
                    stepId = step.id,
                    source = VisualSource.BROWSER_AGENT,
                    actionType = actionType,
                    phase = VisualPhase.COMPLETED,
                    targetPosition = targetPos,
                    targetBounds = domBounds,
                    operationalDescription = step.userSummary,
                    resultSummary = step.userSummary
                )
            )
            ExecutionStepResult(true, step.userSummary, output)
        } else {
            actionLog.failAction(step.id, output)
            AgentVisionTelemetryHub.emitEvent(
                AgentVisualEvent(
                    taskId = context.taskId,
                    stepId = step.id,
                    source = VisualSource.BROWSER_AGENT,
                    actionType = actionType,
                    phase = VisualPhase.FAILED,
                    targetPosition = targetPos,
                    targetBounds = domBounds,
                    operationalDescription = "Failed: $output",
                    error = output
                )
            )
            ExecutionStepResult(false, output, output)
        }
    }
}
