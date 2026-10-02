package com.lichiai.browser.actions

import com.lichiai.agentvision.model.AgentVisualEvent
import com.lichiai.agentvision.model.VisualActionType
import com.lichiai.agentvision.model.VisualPhase
import com.lichiai.agentvision.model.VisualPosition
import com.lichiai.agentvision.model.VisualSource
import com.lichiai.agentvision.telemetry.AgentVisionTelemetryHub
import com.lichiai.browser.BrowserController
import com.lichiai.browser.api.ScrollDirection
import com.lichiai.browser.perception.BrowserPerceptionLayer
import com.lichiai.browser.perception.SemanticElement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Action Engine for Autonomous Browser Intelligence.
 * Safely executes typed actions against the active Chromium WebView,
 * enforces generationId validation, verifies text entry, handles stale element detection,
 * and streams visual telemetry to Agent Vision.
 */
class BrowserActionEngine(
    private val browserController: BrowserController,
    private val perceptionLayer: BrowserPerceptionLayer,
    private val telemetryHub: AgentVisionTelemetryHub = AgentVisionTelemetryHub
) {

    suspend fun executeAction(action: TypedBrowserAction): BrowserActionResult = withContext(Dispatchers.Main) {
        val activeEngine = browserController.activeEngine.value
        val url = activeEngine?.getUrl() ?: "about:blank"
        val title = activeEngine?.getTitle() ?: ""

        // Phase 4: Strict Action Engine Generation Gate (Mandatory)
        val activeSnap = perceptionLayer.getActiveSnapshot()
        if (activeSnap == null || activeSnap.isStale || action.generationId != activeSnap.generationId) {
            return@withContext BrowserActionResult(
                status = ActionExecutionStatus.STALE_TARGET_GENERATION,
                actionName = action::class.simpleName ?: "BrowserAction",
                isSuccess = false,
                message = "STALE_TARGET_GENERATION: Target generation '${action.generationId}' does not match active snapshot generation '${activeSnap?.generationId}' (stale=${activeSnap?.isStale}).",
                currentUrl = url,
                currentTitle = title,
                generationId = action.generationId,
                error = "STALE_TARGET_GENERATION"
            )
        }

        when (action) {
            is TypedBrowserAction.OpenURL -> {
                val ok = browserController.navigate(action.url)
                perceptionLayer.invalidatePerception()
                BrowserActionResult(
                    status = if (ok) ActionExecutionStatus.SUCCESS else ActionExecutionStatus.FAILED,
                    actionName = "OpenURL",
                    isSuccess = ok,
                    message = if (ok) "Navigated to ${action.url}" else "Failed to navigate to ${action.url}",
                    currentUrl = action.url,
                    currentTitle = title,
                    generationId = action.generationId
                )
            }
            is TypedBrowserAction.Back -> {
                val ok = browserController.goBack()
                perceptionLayer.invalidatePerception()
                BrowserActionResult(
                    status = if (ok) ActionExecutionStatus.SUCCESS else ActionExecutionStatus.FAILED,
                    actionName = "Back",
                    isSuccess = ok,
                    message = if (ok) "Went back" else "Cannot go back",
                    currentUrl = activeEngine?.getUrl() ?: url,
                    currentTitle = activeEngine?.getTitle() ?: title,
                    generationId = action.generationId
                )
            }
            is TypedBrowserAction.Forward -> {
                val ok = browserController.goForward()
                perceptionLayer.invalidatePerception()
                BrowserActionResult(
                    status = if (ok) ActionExecutionStatus.SUCCESS else ActionExecutionStatus.FAILED,
                    actionName = "Forward",
                    isSuccess = ok,
                    message = if (ok) "Went forward" else "Cannot go forward",
                    currentUrl = activeEngine?.getUrl() ?: url,
                    currentTitle = activeEngine?.getTitle() ?: title,
                    generationId = action.generationId
                )
            }
            is TypedBrowserAction.Reload -> {
                val ok = browserController.reload()
                perceptionLayer.invalidatePerception()
                BrowserActionResult(
                    status = ActionExecutionStatus.SUCCESS,
                    actionName = "Reload",
                    isSuccess = ok,
                    message = "Reloading page",
                    currentUrl = url,
                    currentTitle = title,
                    generationId = action.generationId
                )
            }
            is TypedBrowserAction.TapElement -> {
                val target = perceptionLayer.resolveElement(action.targetIdOrIndex)
                if (target == null) {
                    return@withContext BrowserActionResult(
                        status = ActionExecutionStatus.STALE_ELEMENT,
                        actionName = "TapElement",
                        isSuccess = false,
                        message = "Element '${action.targetIdOrIndex}' not found or stale in current perception snapshot.",
                        currentUrl = url,
                        currentTitle = title,
                        generationId = action.generationId
                    )
                }

                emitVisualClick(target)

                val clicked = browserController.clickElement(target.originalIndex)
                perceptionLayer.invalidatePerception()
                BrowserActionResult(
                    status = if (clicked) ActionExecutionStatus.SUCCESS else ActionExecutionStatus.FAILED,
                    actionName = "TapElement",
                    isSuccess = clicked,
                    message = if (clicked) "Tapped element '${target.labelOrText.ifBlank { target.semanticId }}'" else "Failed to click element",
                    currentUrl = url,
                    currentTitle = title,
                    generationId = action.generationId
                )
            }
            is TypedBrowserAction.LongPress -> {
                val target = perceptionLayer.resolveElement(action.targetIdOrIndex)
                if (target == null) {
                    return@withContext BrowserActionResult(
                        status = ActionExecutionStatus.STALE_ELEMENT,
                        actionName = "LongPress",
                        isSuccess = false,
                        message = "Element '${action.targetIdOrIndex}' not found or stale.",
                        currentUrl = url,
                        currentTitle = title,
                        generationId = action.generationId
                    )
                }
                val clicked = browserController.clickElement(target.originalIndex)
                BrowserActionResult(
                    status = if (clicked) ActionExecutionStatus.SUCCESS else ActionExecutionStatus.FAILED,
                    actionName = "LongPress",
                    isSuccess = clicked,
                    message = "Long-pressed element '${target.semanticId}'",
                    currentUrl = url,
                    currentTitle = title,
                    generationId = action.generationId
                )
            }
            is TypedBrowserAction.TypeText -> {
                val target = perceptionLayer.resolveElement(action.targetIdOrIndex)
                val idx = target?.originalIndex ?: action.targetIdOrIndex.toIntOrNull()
                val selector = if (target == null && idx == null) action.targetIdOrIndex else null

                // Phase 6 & 25: Protected Sensitive Field Interception (Passwords, OTPs, CVVs)
                val isSensitive = target?.sensitive == true ||
                        target?.type == "password" ||
                        target?.labelOrText?.contains("password", ignoreCase = true) == true ||
                        target?.labelOrText?.contains("otp", ignoreCase = true) == true ||
                        target?.placeholder?.contains("password", ignoreCase = true) == true ||
                        target?.placeholder?.contains("otp", ignoreCase = true) == true

                if (isSensitive) {
                    val kind = if (target?.labelOrText?.contains("otp", ignoreCase = true) == true) {
                        UserInterventionKind.OTP_REQUIRED
                    } else {
                        UserInterventionKind.LOGIN_REQUIRED
                    }
                    return@withContext BrowserActionResult(
                        status = ActionExecutionStatus.PAUSED_FOR_USER,
                        actionName = "TypeText",
                        isSuccess = false,
                        message = "Authentication or security credential field encountered. Pausing for user entry.",
                        interventionKind = kind,
                        interventionPrompt = "Please enter your ${if (kind == UserInterventionKind.OTP_REQUIRED) "OTP" else "password"} manually on screen.",
                        currentUrl = url,
                        currentTitle = title,
                        generationId = action.generationId
                    )
                }

                val typed = browserController.typeText(
                    index = idx,
                    selector = selector,
                    text = action.text,
                    submit = action.submit
                )

                if (action.submit) {
                    perceptionLayer.invalidatePerception()
                    BrowserActionResult(
                        status = if (typed) ActionExecutionStatus.SUCCESS else ActionExecutionStatus.FAILED,
                        actionName = "TypeText",
                        isSuccess = typed,
                        message = if (typed) "Submitted text into ${target?.semanticId ?: "field"}" else "Failed to type and submit text",
                        currentUrl = url,
                        currentTitle = title,
                        generationId = action.generationId
                    )
                } else {
                    // Phase 6: Verify actual field state from post-action DOM
                    com.lichiai.browser.runtime.BrowserConditionWaiter.waitForDomStable(settleMs = 150L, timeoutMs = 1500L) { activeEngine }
                    val postSnap = perceptionLayer.observePage(activeEngine)
                    val freshTarget = postSnap.semanticElements.firstOrNull {
                        it.semanticId == (target?.semanticId ?: "") || it.originalIndex == idx
                    }
                    val verifyResult = com.lichiai.browser.verifier.BrowserVerifier.verifyTypeText(
                        expectedText = action.text,
                        actualValue = freshTarget?.value?.ifBlank { freshTarget.inputValue },
                        isSensitive = false,
                        elementFound = freshTarget != null
                    )

                    BrowserActionResult(
                        status = if (verifyResult.passed) ActionExecutionStatus.SUCCESS else ActionExecutionStatus.FAILED,
                        actionName = "TypeText",
                        isSuccess = verifyResult.passed,
                        message = verifyResult.detail,
                        currentUrl = postSnap.url,
                        currentTitle = postSnap.title,
                        generationId = action.generationId
                    )
                }
            }
            is TypedBrowserAction.ClearText -> {
                val target = perceptionLayer.resolveElement(action.targetIdOrIndex)
                val idx = target?.originalIndex ?: action.targetIdOrIndex.toIntOrNull()
                val cleared = browserController.typeText(index = idx, selector = null, text = "", submit = false)
                BrowserActionResult(
                    status = if (cleared) ActionExecutionStatus.SUCCESS else ActionExecutionStatus.FAILED,
                    actionName = "ClearText",
                    isSuccess = cleared,
                    message = "Cleared text",
                    currentUrl = url,
                    currentTitle = title,
                    generationId = action.generationId
                )
            }
            is TypedBrowserAction.SelectOption -> {
                val target = perceptionLayer.resolveElement(action.targetIdOrIndex)
                val typed = browserController.typeText(index = target?.originalIndex, selector = null, text = action.value, submit = true)
                BrowserActionResult(
                    status = if (typed) ActionExecutionStatus.SUCCESS else ActionExecutionStatus.FAILED,
                    actionName = "SelectOption",
                    isSuccess = typed,
                    message = "Selected option '${action.value}'",
                    currentUrl = url,
                    currentTitle = title,
                    generationId = action.generationId
                )
            }
            is TypedBrowserAction.Scroll -> {
                // Phase 8: Real Scroll Verification
                val preScrollY = activeSnap.loadingState.scrollY
                val preMaxY = activeSnap.loadingState.maxScrollY
                val ok = browserController.scroll(action.direction, action.amount)

                com.lichiai.browser.runtime.BrowserConditionWaiter.waitForDomStable(settleMs = 150L, timeoutMs = 1500L) { activeEngine }
                val postSnap = perceptionLayer.observePage(activeEngine)
                val verifyResult = com.lichiai.browser.verifier.BrowserVerifier.verifyScroll(
                    direction = action.direction,
                    preScrollY = preScrollY,
                    preMaxScrollY = preMaxY,
                    postScrollY = postSnap.loadingState.scrollY,
                    postMaxScrollY = postSnap.loadingState.maxScrollY
                )

                BrowserActionResult(
                    status = if (verifyResult.passed) ActionExecutionStatus.SUCCESS else ActionExecutionStatus.FAILED,
                    actionName = "Scroll",
                    isSuccess = verifyResult.passed,
                    message = verifyResult.detail,
                    currentUrl = postSnap.url,
                    currentTitle = postSnap.title,
                    generationId = action.generationId
                )
            }
            is TypedBrowserAction.Swipe -> {
                val dir = if (action.direction.equals("UP", ignoreCase = true)) ScrollDirection.DOWN else ScrollDirection.UP
                val ok = browserController.scroll(dir, 1)
                BrowserActionResult(
                    status = ActionExecutionStatus.SUCCESS,
                    actionName = "Swipe",
                    isSuccess = ok,
                    message = "Swiped ${action.direction}",
                    currentUrl = url,
                    currentTitle = title,
                    generationId = action.generationId
                )
            }
            is TypedBrowserAction.PressEnter -> {
                val target = action.targetIdOrIndex?.let { perceptionLayer.resolveElement(it) }
                val ok = browserController.typeText(index = target?.originalIndex, selector = null, text = "", submit = true)
                perceptionLayer.invalidatePerception()
                BrowserActionResult(
                    status = ActionExecutionStatus.SUCCESS,
                    actionName = "PressEnter",
                    isSuccess = ok,
                    message = "Pressed Enter",
                    currentUrl = url,
                    currentTitle = title,
                    generationId = action.generationId
                )
            }
            is TypedBrowserAction.SubmitForm -> {
                val target = action.targetIdOrIndex?.let { perceptionLayer.resolveElement(it) }
                val ok = browserController.typeText(index = target?.originalIndex, selector = null, text = "", submit = true)
                perceptionLayer.invalidatePerception()
                com.lichiai.browser.runtime.BrowserConditionWaiter.waitForDomStable(settleMs = 150L, timeoutMs = 2000L) { activeEngine }
                val postSnap = perceptionLayer.observePage(activeEngine)
                val submitted = ok && (postSnap.url != url || postSnap.title != title || postSnap.generationId != activeSnap.generationId)
                BrowserActionResult(
                    status = if (submitted) ActionExecutionStatus.SUCCESS else ActionExecutionStatus.FAILED,
                    actionName = "SubmitForm",
                    isSuccess = submitted,
                    message = if (submitted) "Submitted form successfully" else "Form submission did not produce observable state change",
                    currentUrl = postSnap.url,
                    currentTitle = postSnap.title,
                    generationId = action.generationId
                )
            }
            is TypedBrowserAction.OpenNewTab -> {
                val newId = browserController.openTab(action.url, action.isIncognito)
                perceptionLayer.invalidatePerception()
                BrowserActionResult(
                    status = ActionExecutionStatus.SUCCESS,
                    actionName = "OpenNewTab",
                    isSuccess = true,
                    message = "Opened new tab ($newId)",
                    currentUrl = action.url ?: "about:blank",
                    currentTitle = "New Tab",
                    generationId = action.generationId
                )
            }
            is TypedBrowserAction.CloseTab -> {
                val ok = browserController.closeTab(action.tabId)
                perceptionLayer.invalidatePerception()
                BrowserActionResult(
                    status = if (ok) ActionExecutionStatus.SUCCESS else ActionExecutionStatus.FAILED,
                    actionName = "CloseTab",
                    isSuccess = ok,
                    message = "Closed tab ${action.tabId}",
                    currentUrl = url,
                    currentTitle = title,
                    generationId = action.generationId
                )
            }
            is TypedBrowserAction.SwitchTab -> {
                val ok = browserController.switchTab(action.tabId)
                perceptionLayer.invalidatePerception()
                BrowserActionResult(
                    status = if (ok) ActionExecutionStatus.SUCCESS else ActionExecutionStatus.FAILED,
                    actionName = "SwitchTab",
                    isSuccess = ok,
                    message = "Switched to tab ${action.tabId}",
                    currentUrl = url,
                    currentTitle = title,
                    generationId = action.generationId
                )
            }
            is TypedBrowserAction.FindOnPage -> {
                val found = activeSnap.visibleTextSnippet.contains(action.keyword, ignoreCase = true) ||
                        activeSnap.semanticElements.any { it.labelOrText.contains(action.keyword, ignoreCase = true) }
                BrowserActionResult(
                    status = if (found) ActionExecutionStatus.SUCCESS else ActionExecutionStatus.FAILED,
                    actionName = "FindOnPage",
                    isSuccess = found,
                    message = if (found) "Found '${action.keyword}' on page" else "'${action.keyword}' not found on page",
                    currentUrl = url,
                    currentTitle = title,
                    generationId = action.generationId
                )
            }
            is TypedBrowserAction.ExtractText -> {
                val text = activeEngine?.extractTextSnippet() ?: ""
                BrowserActionResult(
                    status = ActionExecutionStatus.SUCCESS,
                    actionName = "ExtractText",
                    isSuccess = text.isNotBlank(),
                    message = "Extracted ${text.length} characters of text",
                    extractedText = text,
                    currentUrl = url,
                    currentTitle = title,
                    generationId = action.generationId
                )
            }
            is TypedBrowserAction.ExtractTable -> {
                val tables = browserController.extractTables()
                BrowserActionResult(
                    status = ActionExecutionStatus.SUCCESS,
                    actionName = "ExtractTable",
                    isSuccess = tables.isNotEmpty(),
                    message = "Extracted ${tables.size} tables from page",
                    extractedTables = tables,
                    currentUrl = url,
                    currentTitle = title,
                    generationId = action.generationId
                )
            }
            is TypedBrowserAction.ExtractLinks -> {
                val ctx = browserController.getPageContext()
                BrowserActionResult(
                    status = ActionExecutionStatus.SUCCESS,
                    actionName = "ExtractLinks",
                    isSuccess = ctx.extractedCandidates.isNotEmpty(),
                    message = "Extracted ${ctx.extractedCandidates.size} candidate links",
                    extractedLinksCount = ctx.extractedCandidates.size,
                    currentUrl = url,
                    currentTitle = title,
                    generationId = action.generationId
                )
            }
            is TypedBrowserAction.WaitForElement -> {
                val found = com.lichiai.browser.runtime.BrowserConditionWaiter.waitForElementVisible(
                    targetQuery = action.selectorOrText,
                    timeoutMs = action.timeoutMs
                ) { perceptionLayer.observePage(activeEngine) }

                BrowserActionResult(
                    status = if (found) ActionExecutionStatus.SUCCESS else ActionExecutionStatus.TIMEOUT,
                    actionName = "WaitForElement",
                    isSuccess = found,
                    message = if (found) "Element '${action.selectorOrText}' is visible" else "Timed out waiting for element",
                    currentUrl = url,
                    currentTitle = title,
                    generationId = action.generationId
                )
            }
            is TypedBrowserAction.Download -> {
                val ok = if (action.url.startsWith("http://") || action.url.startsWith("https://")) {
                    browserController.downloadManager.startDownload(action.url, "LichiBrowser", "", "", 0L)
                    true
                } else false
                BrowserActionResult(
                    status = if (ok) ActionExecutionStatus.SUCCESS else ActionExecutionStatus.FAILED,
                    actionName = "Download",
                    isSuccess = ok,
                    message = if (ok) "Initiated download for ${action.url}" else "Failed to start download for ${action.url}",
                    currentUrl = url,
                    currentTitle = title,
                    generationId = action.generationId
                )
            }
            is TypedBrowserAction.Upload -> {
                BrowserActionResult(
                    status = ActionExecutionStatus.PAUSED_FOR_USER,
                    actionName = "Upload",
                    isSuccess = false,
                    message = "File upload requested for ${action.filePath}. Waiting for user confirmation.",
                    interventionKind = UserInterventionKind.HIGH_RISK_CONFIRMATION,
                    interventionPrompt = "Do you want to upload file '${action.filePath}'?",
                    currentUrl = url,
                    currentTitle = title,
                    generationId = action.generationId
                )
            }
            is TypedBrowserAction.AskUser -> {
                BrowserActionResult(
                    status = ActionExecutionStatus.PAUSED_FOR_USER,
                    actionName = "AskUser",
                    isSuccess = true,
                    message = action.question,
                    interventionKind = UserInterventionKind.NONE,
                    interventionPrompt = action.question,
                    currentUrl = url,
                    currentTitle = title,
                    generationId = action.generationId
                )
            }
            is TypedBrowserAction.Confirm -> {
                BrowserActionResult(
                    status = ActionExecutionStatus.REQUIRES_CONFIRMATION,
                    actionName = "Confirm",
                    isSuccess = true,
                    message = action.prompt,
                    interventionKind = UserInterventionKind.HIGH_RISK_CONFIRMATION,
                    interventionPrompt = action.prompt,
                    currentUrl = url,
                    currentTitle = title,
                    generationId = action.generationId
                )
            }
            is TypedBrowserAction.Done -> {
                BrowserActionResult(
                    status = ActionExecutionStatus.SUCCESS,
                    actionName = "Done",
                    isSuccess = true,
                    message = action.summary,
                    currentUrl = url,
                    currentTitle = title,
                    generationId = action.generationId
                )
            }
            is TypedBrowserAction.Failed -> {
                BrowserActionResult(
                    status = ActionExecutionStatus.FAILED,
                    actionName = "Failed",
                    isSuccess = false,
                    message = action.reason,
                    error = action.reason,
                    currentUrl = url,
                    currentTitle = title,
                    generationId = action.generationId
                )
            }
        }
    }

    private fun emitVisualClick(target: SemanticElement) {
        runCatching {
            val parts = target.bounds.split(",")
            if (parts.size == 4) {
                val top = parts[0].toFloatOrNull() ?: 0f
                val left = parts[1].toFloatOrNull() ?: 0f
                val w = parts[2].toFloatOrNull() ?: 0f
                val h = parts[3].toFloatOrNull() ?: 0f
                val centerX = left + (w / 2f)
                val centerY = top + (h / 2f)
                telemetryHub.emitEvent(
                    AgentVisualEvent(
                        source = VisualSource.BROWSER_AGENT,
                        actionType = VisualActionType.TAP,
                        phase = VisualPhase.COMPLETED,
                        targetPosition = VisualPosition(centerX, centerY),
                        targetIdentifier = target.semanticId,
                        operationalDescription = "Clicked ${target.labelOrText.take(30)}"
                    )
                )
            }
        }
    }
}
