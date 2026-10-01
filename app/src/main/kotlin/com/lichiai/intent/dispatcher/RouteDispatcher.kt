package com.lichiai.intent.dispatcher

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.lichiai.agent.bridge.AutonomousAgentTool
import com.lichiai.browser.BrowserController
import com.lichiai.browser.api.ScrollDirection
import com.lichiai.calling.engine.UniversalCallEngine
import com.lichiai.intent.context.ContextBuilder
import com.lichiai.intent.model.BrowserActionType
import com.lichiai.intent.model.LichiCapability
import com.lichiai.intent.model.ResolvedIntent
import com.lichiai.web.WebIntelligenceManager
import com.lichiai.web.model.WebSearchResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed class DispatchExecutionResult {
    data class BrowserExecuted(
        val message: String,
        val isSuccess: Boolean = true,
        val extractedAnswer: String? = null,
        val extractedContext: String? = null
    ) : DispatchExecutionResult()
    data class WebSearchExecuted(val response: WebSearchResponse, val contextPrompt: String, val message: String) : DispatchExecutionResult()
    data class AndroidAgentExecuted(val summary: String, val isSuccess: Boolean, val steps: Int) : DispatchExecutionResult()
    data class CallExecuted(val message: String, val isSuccess: Boolean) : DispatchExecutionResult()
    data class MediaExecuted(val message: String) : DispatchExecutionResult()
    data class DeviceControlExecuted(val message: String) : DispatchExecutionResult()
    data class TimeReminderExecuted(val message: String, val isSuccess: Boolean, val requiresScreenNavigation: Boolean) : DispatchExecutionResult()
    data class TerminalExecuted(val message: String, val rawOutput: String = "", val isSuccess: Boolean = true, val requiresScreenNavigation: Boolean = false) : DispatchExecutionResult()
    data class ClarificationNeeded(val question: String) : DispatchExecutionResult()
    data class FallbackChat(val prompt: String) : DispatchExecutionResult()
    data class ExecutionFailed(val error: String) : DispatchExecutionResult()
}

/**
 * Execution Dispatcher for Lichi AI.
 * ARCHITECTURAL INVARIANT: RouteDispatcher is an execution dispatcher and does NOT classify raw user language.
 * It strictly dispatches structured requests (ResolvedIntent) to verified isolated peer capability executors.
 */
class RouteDispatcher(
    val context: Context,
    val browserController: BrowserController,
    val webIntelligenceManager: WebIntelligenceManager,
    val autonomousAgentTool: AutonomousAgentTool,
    val universalCallEngine: UniversalCallEngine,
    val contextBuilder: ContextBuilder,
    val onNavigateToBrowser: () -> Unit,
    val onNavigateToTerminal: () -> Unit = {}
) {
    val browserPlatform = com.lichiai.browser.BrowserIntelligencePlatform(
        context = context,
        browserController = browserController,
        webIntelligenceManager = webIntelligenceManager
    )

    suspend fun dispatch(
        intent: ResolvedIntent,
        onProgress: ((step: Int, total: Int, text: String) -> Unit)? = null
    ): DispatchExecutionResult = withContext(Dispatchers.Main) {
        when (intent) {
            is ResolvedIntent.BrowserTask -> executeBrowserTask(intent, onProgress)
            is ResolvedIntent.WebSearchTask -> executeWebSearchTask(intent)
            is ResolvedIntent.DorkSearchTask -> executeDorkSearchTask(intent)
            is ResolvedIntent.SiteSearchTask -> executeSiteSearchTask(intent)
            is ResolvedIntent.DeepSearchTask -> executeDeepSearchTask(intent, onProgress)
            is ResolvedIntent.ResearchTask -> executeResearchTask(intent)
            is ResolvedIntent.NavigateTask -> executeNavigateTask(intent)
            is ResolvedIntent.ExtractTask -> executeExtractTask(intent)
            is ResolvedIntent.FindOnPageTask -> executeFindOnPageTask(intent)
            is ResolvedIntent.CompareTask -> executeCompareTask(intent)
            is ResolvedIntent.VerifyTask -> executeVerifyTask(intent)
            is ResolvedIntent.FormsTask -> executeFormsTask(intent)
            is ResolvedIntent.DownloadTask -> executeDownloadTask(intent)
            is ResolvedIntent.UploadTask -> executeUploadTask(intent)
            is ResolvedIntent.MultiTabTask -> executeMultiTabTask(intent)
            is ResolvedIntent.PageSummaryTask -> executePageSummaryTask(intent)
            is ResolvedIntent.InspectTask -> executeInspectTask(intent)
            is ResolvedIntent.AndroidAgentTask -> executeAndroidAgentTask(intent, onProgress)
            is ResolvedIntent.CallTask -> executeCallTask(intent)
            is ResolvedIntent.MediaTask -> executeMediaTask(intent)
            is ResolvedIntent.DeviceControlTask -> executeDeviceControlTask(intent)
            is ResolvedIntent.TimeReminderTask -> executeTimeReminderTask(intent)
            is ResolvedIntent.TerminalTask -> executeTerminalTask(intent, onProgress)
            is ResolvedIntent.MultiStepTask -> executeMultiStepTask(intent, onProgress)
            is ResolvedIntent.Clarification -> DispatchExecutionResult.ClarificationNeeded(intent.question)
            is ResolvedIntent.Cancellation -> {
                contextBuilder.reset()
                DispatchExecutionResult.FallbackChat(intent.naturalAcknowledgment)
            }
            is ResolvedIntent.NormalChat -> DispatchExecutionResult.FallbackChat(intent.prompt)
            is ResolvedIntent.ContextualQuestion -> DispatchExecutionResult.FallbackChat(intent.question)
            is ResolvedIntent.ResumeTask -> DispatchExecutionResult.FallbackChat(intent.naturalAcknowledgment)
            is ResolvedIntent.TaskInterruption -> DispatchExecutionResult.FallbackChat(intent.naturalAcknowledgment)
            is ResolvedIntent.SkillManagementTask -> DispatchExecutionResult.FallbackChat(intent.naturalAcknowledgment)
        }
    }

    private suspend fun executeBrowserTask(
        task: ResolvedIntent.BrowserTask,
        onProgress: ((step: Int, total: Int, text: String) -> Unit)? = null
    ): DispatchExecutionResult {
        onNavigateToBrowser()

        return when (task.action) {
            BrowserActionType.SEARCH -> {
                val query = task.query?.takeIf { it.isNotBlank() } ?: task.rawPrompt
                val agentRes = browserController.agent.executeInstruction(query) { s, t, txt ->
                    onProgress?.invoke(s, t, txt)
                }
                val summary = agentRes.answer ?: agentRes.summary
                contextBuilder.recordExecution(
                    capability = LichiCapability.BROWSER,
                    userGoal = task.rawPrompt,
                    assistantResponse = summary,
                    searchQuery = query,
                    actionType = "BROWSER_SEARCH"
                )
                DispatchExecutionResult.BrowserExecuted(
                    message = summary,
                    isSuccess = agentRes.isSuccess,
                    extractedAnswer = agentRes.answer,
                    extractedContext = agentRes.extractedContext
                )
            }
            BrowserActionType.NAVIGATE -> {
                val url = task.url ?: "https://www.google.com"
                val ok = browserController.navigate(url)
                val summary = "Navigated to $url"
                contextBuilder.recordExecution(
                    capability = LichiCapability.BROWSER,
                    userGoal = task.rawPrompt,
                    assistantResponse = summary,
                    actionType = "BROWSER_NAVIGATE"
                )
                DispatchExecutionResult.BrowserExecuted(summary, isSuccess = ok)
            }
            BrowserActionType.CLICK_CANDIDATE -> {
                val instruction = task.rawPrompt.ifBlank { "click candidate ${task.candidateIndex ?: 0}" }
                val agentRes = browserController.agent.executeInstruction(instruction) { s, t, txt ->
                    onProgress?.invoke(s, t, txt)
                }
                val summary = agentRes.answer ?: agentRes.summary
                contextBuilder.recordExecution(
                    capability = LichiCapability.BROWSER,
                    userGoal = task.rawPrompt,
                    assistantResponse = summary,
                    actionType = "BROWSER_CLICK"
                )
                DispatchExecutionResult.BrowserExecuted(
                    message = summary,
                    isSuccess = agentRes.isSuccess,
                    extractedAnswer = agentRes.answer,
                    extractedContext = agentRes.extractedContext
                )
            }
            BrowserActionType.SCROLL_DOWN -> {
                val ok = browserController.scroll(ScrollDirection.DOWN)
                DispatchExecutionResult.BrowserExecuted("Scrolled down.", isSuccess = ok)
            }
            BrowserActionType.SCROLL_UP -> {
                val ok = browserController.scroll(ScrollDirection.UP)
                DispatchExecutionResult.BrowserExecuted("Scrolled up.", isSuccess = ok)
            }
            BrowserActionType.BACK -> {
                val ok = browserController.goBack()
                DispatchExecutionResult.BrowserExecuted("Navigated back.", isSuccess = ok)
            }
            BrowserActionType.FORWARD -> {
                val ok = browserController.goForward()
                DispatchExecutionResult.BrowserExecuted("Navigated forward.", isSuccess = ok)
            }
            BrowserActionType.RELOAD -> {
                val ok = browserController.reload()
                DispatchExecutionResult.BrowserExecuted("Page reloaded.", isSuccess = ok)
            }
            BrowserActionType.NEW_TAB -> {
                browserController.tabManager.createTab()
                DispatchExecutionResult.BrowserExecuted("New tab opened.", isSuccess = true)
            }
            BrowserActionType.CLOSE_TAB -> {
                val ok = browserController.closeTab(browserController.tabManager.activeTabId.value)
                DispatchExecutionResult.BrowserExecuted("Tab closed.", isSuccess = ok)
            }
            BrowserActionType.FIND_ON_PAGE -> {
                val instruction = task.findTarget?.let { "find on page $it" } ?: task.rawPrompt
                val agentRes = browserController.agent.executeInstruction(instruction) { s, t, txt ->
                    onProgress?.invoke(s, t, txt)
                }
                val summary = agentRes.answer ?: agentRes.summary
                DispatchExecutionResult.BrowserExecuted(
                    message = summary,
                    isSuccess = agentRes.isSuccess,
                    extractedAnswer = agentRes.answer,
                    extractedContext = agentRes.extractedContext
                )
            }
        }
    }

    private suspend fun executeWebSearchTask(task: ResolvedIntent.WebSearchTask): DispatchExecutionResult = withContext(Dispatchers.IO) {
        try {
            val response = webIntelligenceManager.executeSearch(
                query = task.query,
                isImageSearch = task.isImageSearch,
                isNewsSearch = task.isNewsSearch
            )
            val contextPrompt = webIntelligenceManager.buildWebContextPrompt(response)
            val summary = response.directAnswer ?: if (response.results.isNotEmpty()) {
                "Found ${response.results.size} search results for \"${task.query}\"."
            } else {
                "Search completed with no direct results."
            }
            contextBuilder.recordExecution(
                capability = LichiCapability.WEB_SEARCH,
                userGoal = task.query,
                assistantResponse = summary,
                searchQuery = task.query,
                results = response.results.map { it.title },
                actionType = "WEB_SEARCH"
            )
            DispatchExecutionResult.WebSearchExecuted(response, contextPrompt, summary)
        } catch (e: Exception) {
            DispatchExecutionResult.ExecutionFailed("Web search failed: ${e.message}")
        }
    }

    private suspend fun executeDorkSearchTask(task: ResolvedIntent.DorkSearchTask): DispatchExecutionResult = withContext(Dispatchers.IO) {
        try {
            val response = browserPlatform.executeDorkSearch(task.query)
            val contextPrompt = webIntelligenceManager.buildWebContextPrompt(response)
            val summary = response.directAnswer ?: "Dork search completed with ${response.results.size} results."
            contextBuilder.recordExecution(
                capability = LichiCapability.DORK_SEARCH,
                userGoal = task.query,
                assistantResponse = summary,
                searchQuery = task.query,
                results = response.results.map { it.title },
                actionType = "DORK_SEARCH"
            )
            DispatchExecutionResult.WebSearchExecuted(response, contextPrompt, summary)
        } catch (e: Exception) {
            DispatchExecutionResult.ExecutionFailed("Dork search failed: ${e.message}")
        }
    }

    private suspend fun executeSiteSearchTask(task: ResolvedIntent.SiteSearchTask): DispatchExecutionResult = withContext(Dispatchers.IO) {
        try {
            val result = browserPlatform.executeSiteSearch(task.domain, task.query, task.maxPages)
            val fakeResponse = WebSearchResponse(
                query = task.query,
                providerUsed = "SiteSearch (${task.domain})",
                results = result.pages,
                directAnswer = result.summary
            )
            val contextPrompt = webIntelligenceManager.buildWebContextPrompt(fakeResponse)
            contextBuilder.recordExecution(
                capability = LichiCapability.SITE_SEARCH,
                userGoal = task.query,
                assistantResponse = result.summary,
                searchQuery = task.query,
                results = result.pages.map { it.title },
                actionType = "SITE_SEARCH"
            )
            DispatchExecutionResult.WebSearchExecuted(fakeResponse, contextPrompt, result.summary)
        } catch (e: Exception) {
            DispatchExecutionResult.ExecutionFailed("Site search failed: ${e.message}")
        }
    }

    private suspend fun executeDeepSearchTask(
        task: ResolvedIntent.DeepSearchTask,
        onProgress: ((step: Int, total: Int, text: String) -> Unit)?
    ): DispatchExecutionResult = withContext(Dispatchers.IO) {
        try {
            val report = browserPlatform.executeDeepSearch(
                query = task.query,
                conversationId = contextBuilder.buildContext().conversationId ?: ""
            )
            val fakeResponse = WebSearchResponse(
                query = task.query,
                providerUsed = "DeepSearch Intelligence",
                results = report.verifiedSources,
                directAnswer = report.synthesisText,
                factEvidence = report.factEvidence
            )
            val contextPrompt = webIntelligenceManager.buildWebContextPrompt(fakeResponse)
            contextBuilder.recordExecution(
                capability = LichiCapability.DEEP_SEARCH,
                userGoal = task.query,
                assistantResponse = report.synthesisText,
                searchQuery = task.query,
                results = report.verifiedSources.map { it.title },
                actionType = "DEEP_SEARCH"
            )
            DispatchExecutionResult.WebSearchExecuted(fakeResponse, contextPrompt, report.synthesisText)
        } catch (e: Exception) {
            DispatchExecutionResult.ExecutionFailed("DeepSearch failed: ${e.message}")
        }
    }

    private suspend fun executeResearchTask(task: ResolvedIntent.ResearchTask): DispatchExecutionResult = withContext(Dispatchers.IO) {
        try {
            val report = browserPlatform.executeResearch(
                topic = task.topic,
                queries = task.queries,
                conversationId = contextBuilder.buildContext().conversationId ?: ""
            )
            val fakeResponse = WebSearchResponse(
                query = task.topic,
                providerUsed = "Autonomous Research",
                results = report.sources,
                directAnswer = report.finalAnswer
            )
            val contextPrompt = webIntelligenceManager.buildWebContextPrompt(fakeResponse)
            contextBuilder.recordExecution(
                capability = LichiCapability.RESEARCH,
                userGoal = task.topic,
                assistantResponse = report.finalAnswer,
                searchQuery = task.topic,
                results = report.sources.map { it.title },
                actionType = "RESEARCH"
            )
            DispatchExecutionResult.WebSearchExecuted(fakeResponse, contextPrompt, report.finalAnswer)
        } catch (e: Exception) {
            DispatchExecutionResult.ExecutionFailed("Research failed: ${e.message}")
        }
    }

    private suspend fun executeNavigateTask(task: ResolvedIntent.NavigateTask): DispatchExecutionResult = withContext(Dispatchers.Main) {
        onNavigateToBrowser()
        val result = browserPlatform.executeNavigate(task.url)
        contextBuilder.recordExecution(
            capability = LichiCapability.NAVIGATE,
            userGoal = task.url,
            assistantResponse = result.message,
            actionType = "NAVIGATE"
        )
        DispatchExecutionResult.BrowserExecuted(result.message)
    }

    private suspend fun executeExtractTask(task: ResolvedIntent.ExtractTask): DispatchExecutionResult = withContext(Dispatchers.Main) {
        onNavigateToBrowser()
        val extracted = browserPlatform.executeExtract()
        val summary = "Extracted from ${extracted.title}: ${extracted.headings.size} headings, ${extracted.tables.size} tables, ${extracted.prices.size} price points."
        contextBuilder.recordExecution(
            capability = LichiCapability.EXTRACT,
            userGoal = "Extract content",
            assistantResponse = summary,
            actionType = "EXTRACT"
        )
        DispatchExecutionResult.BrowserExecuted(summary)
    }

    private suspend fun executeFindOnPageTask(task: ResolvedIntent.FindOnPageTask): DispatchExecutionResult = withContext(Dispatchers.Main) {
        onNavigateToBrowser()
        val result = browserPlatform.executeFindOnPage(task.keyword)
        contextBuilder.recordExecution(
            capability = LichiCapability.FIND_ON_PAGE,
            userGoal = task.keyword,
            assistantResponse = result.message,
            actionType = "FIND_ON_PAGE"
        )
        DispatchExecutionResult.BrowserExecuted(result.message)
    }

    private suspend fun executeCompareTask(task: ResolvedIntent.CompareTask): DispatchExecutionResult = withContext(Dispatchers.IO) {
        try {
            val report = browserPlatform.executeCompare(task.entities, task.criteria)
            val fakeResponse = WebSearchResponse(
                query = task.entities.joinToString(" vs "),
                providerUsed = "Comparison Intelligence",
                results = report.comparedEntities.map {
                    com.lichiai.web.model.WebResult(
                        title = it.entityName,
                        url = it.sourceUrl,
                        domain = it.sourceDomain,
                        snippet = "${it.price} | ${it.features.joinToString()}"
                    )
                },
                directAnswer = report.summaryDifferences
            )
            val contextPrompt = webIntelligenceManager.buildWebContextPrompt(fakeResponse)
            contextBuilder.recordExecution(
                capability = LichiCapability.COMPARE,
                userGoal = task.entities.joinToString(" vs "),
                assistantResponse = report.summaryDifferences,
                actionType = "COMPARE"
            )
            DispatchExecutionResult.WebSearchExecuted(fakeResponse, contextPrompt, report.summaryDifferences)
        } catch (e: Exception) {
            DispatchExecutionResult.ExecutionFailed("Comparison failed: ${e.message}")
        }
    }

    private suspend fun executeVerifyTask(task: ResolvedIntent.VerifyTask): DispatchExecutionResult = withContext(Dispatchers.IO) {
        try {
            val response = browserPlatform.executeVerify(task.claim, task.domain)
            val contextPrompt = webIntelligenceManager.buildWebContextPrompt(response)
            contextBuilder.recordExecution(
                capability = LichiCapability.VERIFY,
                userGoal = task.claim,
                assistantResponse = task.naturalAcknowledgment,
                searchQuery = task.claim,
                results = response.results.map { it.title },
                actionType = "VERIFY"
            )
            DispatchExecutionResult.WebSearchExecuted(response, contextPrompt, task.naturalAcknowledgment)
        } catch (e: Exception) {
            DispatchExecutionResult.ExecutionFailed("Verification failed: ${e.message}")
        }
    }

    private suspend fun executeFormsTask(task: ResolvedIntent.FormsTask): DispatchExecutionResult = withContext(Dispatchers.Main) {
        onNavigateToBrowser()
        val results = browserPlatform.executeForms(task.fieldValues, task.submit)
        val successCount = results.count { it.isSuccess }
        val msg = "Filled ${task.fieldValues.size} form fields ($successCount successful)${if (task.submit) " and submitted" else ""}."
        contextBuilder.recordExecution(
            capability = LichiCapability.FORMS,
            userGoal = "Fill form",
            assistantResponse = msg,
            actionType = "FORMS"
        )
        DispatchExecutionResult.BrowserExecuted(msg)
    }

    private suspend fun executeDownloadTask(task: ResolvedIntent.DownloadTask): DispatchExecutionResult = withContext(Dispatchers.Main) {
        val result = browserPlatform.executeDownload(task.url)
        contextBuilder.recordExecution(
            capability = LichiCapability.DOWNLOAD,
            userGoal = task.url,
            assistantResponse = result.message,
            actionType = "DOWNLOAD"
        )
        DispatchExecutionResult.BrowserExecuted(result.message)
    }

    private suspend fun executeUploadTask(task: ResolvedIntent.UploadTask): DispatchExecutionResult = withContext(Dispatchers.Main) {
        val result = browserPlatform.executeUpload(task.targetIdOrIndex, task.filePath)
        contextBuilder.recordExecution(
            capability = LichiCapability.UPLOAD,
            userGoal = task.filePath,
            assistantResponse = result.message,
            actionType = "UPLOAD"
        )
        DispatchExecutionResult.BrowserExecuted(result.message)
    }

    private suspend fun executeMultiTabTask(task: ResolvedIntent.MultiTabTask): DispatchExecutionResult = withContext(Dispatchers.Main) {
        onNavigateToBrowser()
        val result = browserPlatform.executeMultiTab(task.action, task.tabId, task.url)
        contextBuilder.recordExecution(
            capability = LichiCapability.MULTI_TAB,
            userGoal = task.action,
            assistantResponse = result.message,
            actionType = "MULTI_TAB"
        )
        DispatchExecutionResult.BrowserExecuted(result.message)
    }

    private suspend fun executePageSummaryTask(task: ResolvedIntent.PageSummaryTask): DispatchExecutionResult = withContext(Dispatchers.Main) {
        onNavigateToBrowser()
        val summary = browserPlatform.executePageSummary()
        contextBuilder.recordExecution(
            capability = LichiCapability.PAGE_SUMMARY,
            userGoal = "Page summary",
            assistantResponse = summary,
            actionType = "PAGE_SUMMARY"
        )
        DispatchExecutionResult.BrowserExecuted(summary)
    }

    private suspend fun executeInspectTask(task: ResolvedIntent.InspectTask): DispatchExecutionResult = withContext(Dispatchers.Main) {
        if (task.showUi) {
            onNavigateToBrowser()
        }
        val queryLower = task.query.lowercase()
        val resultMessage = when {
            queryLower.contains("link") || queryLower.contains("url") -> {
                val links = browserPlatform.browserController.discoverLinks()
                val byCat = links.groupBy { it.category }
                buildString {
                    append("Discovered ${links.size} total links on this page:\n")
                    byCat.forEach { (cat, list) ->
                        append("\n• Category: $cat (${list.size})\n")
                        list.take(6).forEach { l ->
                            append("  - \"${l.text.ifBlank { l.url }}\" -> ${l.url}\n")
                        }
                    }
                }
            }
            queryLower.contains("endpoint") || queryLower.contains("api") -> {
                val eps = browserPlatform.executeEndpointDiscovery()
                if (eps.isEmpty()) "Is website par abhi koi active asynchronous API endpoint trigger nahi hua hai."
                else buildString {
                    append("Observed ${eps.size} API endpoints:\n")
                    eps.forEachIndexed { i, ep ->
                        append("${i + 1}. [${ep.method}] ${ep.path} (${ep.domain})\n")
                        append("   Category: ${ep.category}, Status: ${ep.responseStatusCode}, Content-Type: ${ep.responseContentType}\n")
                        if (ep.parameters.isNotEmpty()) {
                            append("   Parameters: ${ep.parameters.joinToString(", ") { "${it.name}=${it.sampleValue}" }}\n")
                        }
                    }
                }
            }
            queryLower.contains("download") -> {
                val dls = browserPlatform.executeDownloadDiscovery()
                if (dls.isEmpty()) "Is page par koi direct download link identify nahi hua."
                else buildString {
                    append("Discovered ${dls.size} direct download links:\n")
                    dls.forEachIndexed { i, dl ->
                        append("${i + 1}. ${dl.filename} (.${dl.extension})\n   URL: ${dl.url}\n   Source: ${dl.detectedVia}\n")
                    }
                }
            }
            queryLower.contains("resource") || queryLower.contains("script") || queryLower.contains("domain") -> {
                val res = browserPlatform.executeResourceDiscovery()
                val externalDomains = res.filter { it.isThirdParty }.map { it.domain }.distinct()
                buildString {
                    append("Discovered ${res.size} resources across page:\n")
                    append("• Scripts: ${res.count { it.type == "Script" }}\n")
                    append("• Stylesheets: ${res.count { it.type == "Stylesheet" }}\n")
                    append("• Images: ${res.count { it.type == "Image" }}\n")
                    if (externalDomains.isNotEmpty()) {
                        append("\nExternal Connected Domains (${externalDomains.size}):\n")
                        externalDomains.forEach { append("• $it\n") }
                    }
                }
            }
            queryLower.contains("form") -> {
                val mode = com.lichiai.browser.inspection.model.InspectionMode.PAGE_INSPECTION
                val report = browserPlatform.executeInspectPage(mode)
                if (report.forms.isEmpty()) "Is page par koi HTML form detect nahi hua."
                else buildString {
                    append("Detected ${report.forms.size} forms on this page:\n")
                    report.forms.forEachIndexed { i, f ->
                        append("${i + 1}. ${f.formName} [${f.method}] -> Action: ${f.action}\n")
                        append("   Fields (${f.fields.size}): ${f.fields.joinToString(", ") { "${it.name} (${it.type})" }}\n")
                    }
                }
            }
            queryLower.contains("network") || queryLower.contains("request") -> {
                val analysis = browserPlatform.executeNetworkAnalysis()
                buildString {
                    append("=== NETWORK REQUESTS ANALYSIS ===\n")
                    append("Total Requests: ${analysis.totalRequests}\n")
                    append("Failed Requests: ${analysis.timeline.totalFailed}\n")
                    append("Slow Requests (>1s): ${analysis.timeline.totalSlowRequests}\n")
                    append("Average Latency: ${analysis.timeline.averageLatencyMs}ms\n\n")
                    append("Top Domains Connected:\n")
                    analysis.topDomains.take(6).forEach { (d, c) ->
                        append("• $d: $c requests\n")
                    }
                }
            }
            else -> {
                val mode = com.lichiai.browser.inspection.model.InspectionMode.fromString(task.mode)
                val report = browserPlatform.executeInspectPage(mode)
                buildString {
                    append(report.technicalStructureExplanation)
                    append("\n\n**Findings:**\n")
                    report.findings.forEach { f ->
                        append("• [${f.category}] ${f.title}: ${f.description}\n")
                    }
                }
            }
        }

        contextBuilder.recordExecution(
            capability = LichiCapability.INSPECT_PAGE,
            userGoal = task.query.ifBlank { "Deep inspect website" },
            assistantResponse = resultMessage,
            actionType = "INSPECT"
        )
        DispatchExecutionResult.BrowserExecuted(resultMessage)
    }

    private suspend fun executeAndroidAgentTask(
        task: ResolvedIntent.AndroidAgentTask,
        onProgress: ((step: Int, total: Int, text: String) -> Unit)?
    ): DispatchExecutionResult {
        if (!autonomousAgentTool.isEnabled()) {
            return DispatchExecutionResult.ExecutionFailed(
                "Autonomous Agent is currently disabled. Please enable it in Settings under Autonomous Agent V2."
            )
        }

        val result = autonomousAgentTool.execute(
            taskDescription = task.goal,
            matchedSkills = task.matchedSkills,
            onProgress = onProgress
        )

        val appEntity = task.targetApp?.let { mapOf("app" to it) } ?: emptyMap()
        contextBuilder.recordExecution(
            capability = LichiCapability.ANDROID_AGENT,
            userGoal = task.goal,
            assistantResponse = result.summary,
            entities = appEntity,
            actionType = "ANDROID_AGENT"
        )

        return DispatchExecutionResult.AndroidAgentExecuted(
            summary = result.summary,
            isSuccess = result.isSuccess,
            steps = result.totalSteps
        )
    }

    private suspend fun executeCallTask(task: ResolvedIntent.CallTask): DispatchExecutionResult {
        val outcome = universalCallEngine.executeIntent(task.callIntent, sourceMode = "TEXT")
        val contactTarget = task.callIntent.targetText ?: task.callIntent.phoneNumber ?: ""
        val callEntities = if (contactTarget.isNotBlank()) mapOf("contact" to contactTarget) else emptyMap()
        contextBuilder.recordExecution(
            capability = LichiCapability.CALLS,
            userGoal = task.callIntent.originalText,
            assistantResponse = outcome.message,
            entities = callEntities,
            actionType = "CALL"
        )
        val isSuccess = outcome.status == com.lichiai.calling.intent.CallResultStatus.SUCCESS_STARTED
        return DispatchExecutionResult.CallExecuted(outcome.message, isSuccess)
    }

    private suspend fun executeMediaTask(task: ResolvedIntent.MediaTask): DispatchExecutionResult {
        return try {
            val pm = context.packageManager
            if (task.targetApp == "youtube") {
                val appIntent = Intent(Intent.ACTION_SEARCH).apply {
                    setPackage("com.google.android.youtube")
                    putExtra("query", task.query)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                val canLaunchApp = pm.queryIntentActivities(appIntent, 0).isNotEmpty() ||
                    pm.getLaunchIntentForPackage("com.google.android.youtube") != null
                if (canLaunchApp) {
                    try {
                        context.startActivity(appIntent)
                        contextBuilder.recordExecution(
                            capability = LichiCapability.MEDIA_YOUTUBE,
                            userGoal = task.query,
                            assistantResponse = task.naturalAcknowledgment
                        )
                        return DispatchExecutionResult.MediaExecuted(task.naturalAcknowledgment)
                    } catch (_: Exception) {
                        // Fallback to browser
                    }
                }
            } else if (task.targetApp == "spotify") {
                val spotifyIntent = Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:" + Uri.encode(task.query))).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                val canLaunchSpotify = pm.queryIntentActivities(spotifyIntent, 0).isNotEmpty() ||
                    pm.getLaunchIntentForPackage("com.spotify.music") != null
                if (canLaunchSpotify) {
                    try {
                        context.startActivity(spotifyIntent)
                        contextBuilder.recordExecution(
                            capability = LichiCapability.MEDIA_YOUTUBE,
                            userGoal = task.query,
                            assistantResponse = task.naturalAcknowledgment
                        )
                        return DispatchExecutionResult.MediaExecuted(task.naturalAcknowledgment)
                    } catch (_: Exception) {
                        // Fallback to browser
                    }
                }
            }

            // Fallback: Visible Browser
            val youtubeUrl = "https://www.youtube.com/results?search_query=" + Uri.encode(task.query)
            onNavigateToBrowser()
            browserController.navigate(youtubeUrl)
            contextBuilder.recordExecution(
                capability = LichiCapability.MEDIA_YOUTUBE,
                userGoal = task.query,
                assistantResponse = task.naturalAcknowledgment
            )
            DispatchExecutionResult.MediaExecuted(task.naturalAcknowledgment)
        } catch (e: Exception) {
            DispatchExecutionResult.ExecutionFailed("Media launch failed: ${e.message}")
        }
    }

    private suspend fun executeDeviceControlTask(task: ResolvedIntent.DeviceControlTask): DispatchExecutionResult {
        return DispatchExecutionResult.DeviceControlExecuted(task.naturalAcknowledgment)
    }

    private suspend fun executeTimeReminderTask(task: ResolvedIntent.TimeReminderTask): DispatchExecutionResult {
        val adapter = com.lichiai.time.adapter.TimeCapabilityAdapter(context)
        val outcome = if (task.action.isNotBlank() && (task.title != null || task.timeMs != null || task.action != "CREATE")) {
            adapter.executeStructured(
                action = task.action,
                title = task.title,
                timeMs = task.timeMs,
                isAlarm = task.isAlarm,
                recurrenceRule = task.recurrence,
                idOrQuery = task.id ?: task.title ?: task.rawInput,
                snoozeMinutes = task.minutes,
                rawInput = task.rawInput
            )
        } else {
            adapter.handleQuery(task.rawInput)
        }
        contextBuilder.recordExecution(
            capability = LichiCapability.TIME_REMINDER,
            userGoal = task.rawInput,
            assistantResponse = outcome.naturalSpeech
        )
        return DispatchExecutionResult.TimeReminderExecuted(
            message = outcome.naturalSpeech,
            isSuccess = outcome.isSuccess,
            requiresScreenNavigation = outcome.requiresScreenNavigation
        )
    }

    private suspend fun executeMultiStepTask(
        task: ResolvedIntent.MultiStepTask,
        onProgress: ((step: Int, total: Int, text: String) -> Unit)?
    ): DispatchExecutionResult {
        var lastResult: DispatchExecutionResult = DispatchExecutionResult.BrowserExecuted(task.naturalAcknowledgment)
        val total = task.steps.size

        for ((index, step) in task.steps.withIndex()) {
            onProgress?.invoke(index + 1, total, "Executing step ${index + 1}: ${step.naturalAcknowledgment}")
            lastResult = dispatch(step, onProgress)
        }
        return lastResult
    }

    private suspend fun executeTerminalTask(
        task: ResolvedIntent.TerminalTask,
        onProgress: ((step: Int, total: Int, text: String) -> Unit)?
    ): DispatchExecutionResult = withContext(Dispatchers.IO) {
        if (task.action.equals("OPEN", ignoreCase = true) || task.command.isBlank()) {
            withContext(Dispatchers.Main) { onNavigateToTerminal() }
            return@withContext DispatchExecutionResult.TerminalExecuted(
                message = "Lichi Terminal V3 open kar diya hai.",
                rawOutput = "",
                isSuccess = true,
                requiresScreenNavigation = true
            )
        }

        val taskManager = com.lichiai.terminal.task.TerminalTaskManager.getInstance(context)
        val termManager = com.lichiai.terminal.core.TerminalManager.getInstance(context)
        val agentAdapter = com.lichiai.terminal.agent.TerminalAgentAdapter(termManager)

        val backendType = if (task.backendType.contains("SSH", ignoreCase = true)) {
            com.lichiai.terminal.model.TerminalBackendType.SSH
        } else {
            com.lichiai.terminal.model.TerminalBackendType.LOCAL_TERMUX
        }

        val record = taskManager.createTask(
            command = task.command,
            requestId = "req_${System.currentTimeMillis()}",
            backendType = backendType,
            targetHost = task.host,
            username = task.user
        )

        onProgress?.invoke(1, 3, "Connecting to ${if (backendType == com.lichiai.terminal.model.TerminalBackendType.SSH) task.host ?: "remote" else "Local Termux"}...")
        taskManager.updateTask(
            taskId = record.taskId,
            status = com.lichiai.terminal.task.TerminalTaskStatus.EXECUTING,
            eventSummary = "Executing: ${task.command.take(30)}"
        )

        val agentResult = agentAdapter.executeTask(task.command)

        val finalStatus = if (agentResult.isSuccess) {
            com.lichiai.terminal.task.TerminalTaskStatus.COMPLETED
        } else {
            com.lichiai.terminal.task.TerminalTaskStatus.FAILED
        }

        taskManager.updateTask(
            taskId = record.taskId,
            status = finalStatus,
            eventSummary = if (agentResult.isSuccess) "Command completed successfully" else "Command returned non-zero or error output",
            rawOutput = agentResult.output,
            outputSummary = agentResult.summary,
            error = if (!agentResult.isSuccess) agentResult.summary else null
        )

        onProgress?.invoke(3, 3, if (agentResult.isSuccess) "Completed" else "Failed")

        val summaryForChat = buildString {
            if (agentResult.isSuccess) {
                append("Command `${task.command}` execute ho gaya.")
                if (agentResult.output.isNotBlank()) {
                    append("\n\n```\n")
                    append(agentResult.output.take(400).trimEnd())
                    append("\n```")
                }
            } else {
                append("Command `${task.command}` me error aaya:\n${agentResult.summary}")
            }
        }

        DispatchExecutionResult.TerminalExecuted(
            message = summaryForChat,
            rawOutput = agentResult.output,
            isSuccess = agentResult.isSuccess,
            requiresScreenNavigation = false
        )
    }
}
