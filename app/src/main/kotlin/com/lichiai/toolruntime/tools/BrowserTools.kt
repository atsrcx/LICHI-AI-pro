package com.lichiai.toolruntime.tools

import com.lichiai.browser.BrowserController
import com.lichiai.intent.model.LichiCapability
import com.lichiai.toolruntime.core.LichiTool
import com.lichiai.toolruntime.model.ToolCall
import com.lichiai.toolruntime.model.ToolCategory
import com.lichiai.toolruntime.model.ToolDefinition
import com.lichiai.toolruntime.model.ToolExecutionContext
import com.lichiai.toolruntime.model.ToolExecutionOutcome
import com.lichiai.toolruntime.model.ToolParameter
import com.lichiai.toolruntime.model.ToolResult
import com.lichiai.toolruntime.model.ToolRiskLevel
import com.lichiai.toolruntime.model.VerificationResult
import kotlinx.coroutines.delay

/**
 * Real Browser Navigation Tool.
 * Uses real Chromium engine state (URL, title, loading status) instead of arbitrary delays.
 */
class BrowserOpenTool(
    private val browserController: BrowserController,
    private val onNavigateToBrowser: () -> Unit = {}
) : LichiTool {

    override val definition = ToolDefinition(
        id = "browser.open",
        name = "Open Web Page",
        description = "Navigates the visible Chromium browser to a specific URL.",
        purpose = "Visibly load a web page for the user or for page inspection.",
        category = ToolCategory.BROWSER,
        mappedCapability = LichiCapability.BROWSER,
        parameters = listOf(
            ToolParameter("url", "string", "The HTTP or HTTPS URL to load", required = true)
        ),
        riskLevel = ToolRiskLevel.LOW_RISK_STATE_CHANGE,
        requiresConfirmation = false,
        idempotent = true,
        timeoutMs = 15_000L,
        requiresNetwork = true,
        changesWorldState = true
    )

    override suspend fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        var url = call.arguments["url"]?.trim() ?: ""
        if (url.isBlank()) {
            return ToolResult.failure(call.callId, definition.id, "URL is empty.")
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://$url"
        }

        context.onProgress?.invoke(1, 2, "Opening $url in browser...")
        onNavigateToBrowser()

        return try {
            val navSuccess = browserController.navigate(url)
            if (!navSuccess) {
                return ToolResult.failure(call.callId, definition.id, "Browser could not initiate navigation to $url.")
            }

            // Real State Observation: Wait up to 3.5s for page state to reflect navigation
            var attempts = 0
            var finalCtx = browserController.getPageContext()
            while (attempts < 7) {
                delay(500)
                finalCtx = browserController.getPageContext()
                val activeTab = browserController.tabManager.activeTab
                if (activeTab != null && !activeTab.isLoading && finalCtx.currentUrl.isNotBlank()) {
                    break
                }
                attempts++
            }

            val summary = "Navigated to $url. Current page title: '${finalCtx.currentTitle.ifBlank { "Loaded" }}'."

            ToolResult.success(
                callId = call.callId,
                toolId = definition.id,
                summary = summary,
                data = mapOf(
                    "requested_url" to url,
                    "current_url" to finalCtx.currentUrl,
                    "title" to finalCtx.currentTitle
                ),
                outcome = ToolExecutionOutcome.EXECUTION_SUCCEEDED_UNVERIFIED
            )
        } catch (e: Exception) {
            ToolResult.failure(call.callId, definition.id, "Browser navigation failed: ${e.message}")
        }
    }

    override suspend fun verify(call: ToolCall, result: ToolResult, context: ToolExecutionContext): VerificationResult {
        val currentCtx = browserController.getPageContext()
        val requested = result.data["requested_url"] ?: ""
        val actual = currentCtx.currentUrl
        val isVerified = result.isSuccess && (actual.isNotBlank() || currentCtx.currentTitle.isNotBlank())
        return VerificationResult(
            isVerified = isVerified,
            verifiedState = "Active URL: $actual, Title: ${currentCtx.currentTitle}",
            notes = "Inspected Chromium tab manager state",
            outcome = if (isVerified) ToolExecutionOutcome.EXECUTION_SUCCEEDED_VERIFIED else ToolExecutionOutcome.VERIFICATION_FAILED
        )
    }
}

/**
 * Real Browser Content Extraction Tool.
 */
class BrowserExtractTool(
    private val browserController: BrowserController
) : LichiTool {

    override val definition = ToolDefinition(
        id = "browser.extract",
        name = "Extract Page Content",
        description = "Extracts text content, articles, or headings from the active browser page.",
        purpose = "Read and extract information from the currently opened website.",
        category = ToolCategory.BROWSER,
        mappedCapability = LichiCapability.EXTRACT,
        parameters = emptyList(),
        riskLevel = ToolRiskLevel.READ_ONLY,
        requiresConfirmation = false,
        idempotent = true,
        timeoutMs = 15_000L,
        requiresNetwork = false,
        changesWorldState = false
    )

    override suspend fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        context.onProgress?.invoke(1, 2, "Extracting page content...")
        return try {
            val summary = browserController.extractPageSummary()
            val pageCtx = browserController.getPageContext()

            if (summary.isBlank() && pageCtx.currentUrl.isBlank()) {
                return ToolResult.failure(call.callId, definition.id, "No active web page loaded in browser to extract.")
            }

            val resultText = if (summary.isNotBlank()) summary else "Loaded page at ${pageCtx.currentUrl} with title '${pageCtx.currentTitle}'."
            ToolResult.success(
                callId = call.callId,
                toolId = definition.id,
                summary = "Extracted content from ${pageCtx.currentUrl.ifBlank { "browser" }}.",
                data = mapOf(
                    "url" to pageCtx.currentUrl,
                    "title" to pageCtx.currentTitle,
                    "content" to resultText.take(1500)
                ),
                rawOutput = resultText,
                outcome = ToolExecutionOutcome.EXECUTION_SUCCEEDED_VERIFIED
            )
        } catch (e: Exception) {
            ToolResult.failure(call.callId, definition.id, "Extraction failed: ${e.message}")
        }
    }

    override suspend fun verify(call: ToolCall, result: ToolResult, context: ToolExecutionContext): VerificationResult {
        val hasContent = !result.data["content"].isNullOrBlank()
        return VerificationResult(
            isVerified = result.isSuccess && hasContent,
            verifiedState = if (hasContent) "Extracted ${result.data["content"]?.length} chars" else "Empty extraction",
            notes = "DOM content extraction check",
            outcome = if (hasContent) ToolExecutionOutcome.EXECUTION_SUCCEEDED_VERIFIED else ToolExecutionOutcome.VERIFICATION_FAILED
        )
    }
}

/**
 * Real Multi-Step Browser Action Tool.
 * Delegates high-level web actions to BrowserAgent (click, search, form filling, scroll).
 */
class BrowserActionTool(
    private val browserController: BrowserController,
    private val onNavigateToBrowser: () -> Unit = {}
) : LichiTool {

    override val definition = ToolDefinition(
        id = "browser.action",
        name = "Browser Interaction Action",
        description = "Performs multi-step interaction on the web page: click elements, search forms, or scroll.",
        purpose = "Execute actions within the Chromium browser.",
        category = ToolCategory.BROWSER,
        mappedCapability = LichiCapability.BROWSER,
        parameters = listOf(
            ToolParameter("instruction", "string", "What action to perform (e.g. 'Click on the first download link', 'Scroll down to pricing')", required = true)
        ),
        riskLevel = ToolRiskLevel.LOW_RISK_STATE_CHANGE,
        requiresConfirmation = false,
        idempotent = false,
        timeoutMs = 30_000L,
        requiresNetwork = true,
        changesWorldState = true
    )

    override suspend fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val instruction = call.arguments["instruction"]?.trim() ?: context.userGoal
        if (instruction.isBlank()) {
            return ToolResult.failure(call.callId, definition.id, "Instruction is empty.")
        }

        context.onProgress?.invoke(1, 3, "Performing browser action: $instruction")
        onNavigateToBrowser()

        return try {
            val agentResult = browserController.agent.executeInstruction(instruction)
            if (agentResult.isSuccess) {
                ToolResult.success(
                    callId = call.callId,
                    toolId = definition.id,
                    summary = agentResult.summary.ifBlank { "Browser action executed successfully." },
                    data = mapOf("instruction" to instruction),
                    rawOutput = agentResult.summary,
                    outcome = ToolExecutionOutcome.EXECUTION_SUCCEEDED_UNVERIFIED
                )
            } else {
                ToolResult.failure(call.callId, definition.id, agentResult.summary.ifBlank { "Browser action failed." })
            }
        } catch (e: Exception) {
            ToolResult.failure(call.callId, definition.id, "Error during browser action: ${e.message}")
        }
    }

    override suspend fun verify(call: ToolCall, result: ToolResult, context: ToolExecutionContext): VerificationResult {
        val pageCtx = browserController.getPageContext()
        val isVerified = result.isSuccess && pageCtx.currentUrl.isNotBlank()
        return VerificationResult(
            isVerified = isVerified,
            verifiedState = "Browser URL: ${pageCtx.currentUrl}",
            notes = "Verified against active browser tab state",
            outcome = if (isVerified) ToolExecutionOutcome.EXECUTION_SUCCEEDED_VERIFIED else ToolExecutionOutcome.VERIFICATION_FAILED
        )
    }
}
