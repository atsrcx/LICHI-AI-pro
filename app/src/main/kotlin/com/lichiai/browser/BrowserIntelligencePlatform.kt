package com.lichiai.browser

import android.content.Context
import com.lichiai.agentvision.telemetry.AgentVisionTelemetryHub
import com.lichiai.browser.actions.ActionExecutionStatus
import com.lichiai.browser.actions.BrowserActionEngine
import com.lichiai.browser.actions.BrowserActionResult
import com.lichiai.browser.actions.TypedBrowserAction
import com.lichiai.browser.autonomy.AutonomousBrowserLoop
import com.lichiai.browser.autonomy.AutonomyLoopState
import com.lichiai.browser.extraction.BrowserContentExtractor
import com.lichiai.browser.extraction.ExtractedStructuredContent
import com.lichiai.browser.perception.BrowserPerceptionLayer
import com.lichiai.browser.perception.PagePerceptionSnapshot
import com.lichiai.browser.recovery.BrowserRecovery
import com.lichiai.browser.security.WebSecuritySanitizer
import com.lichiai.memory.manager.LichiMemoryEngine
import com.lichiai.ui.activity.ActivityKind
import com.lichiai.ui.activity.AssistantActivityState
import com.lichiai.web.WebIntelligenceManager
import com.lichiai.web.model.WebResult
import com.lichiai.web.model.WebSearchResponse
import com.lichiai.web.search.ComparisonEngine
import com.lichiai.web.search.ComparisonMatrixReport
import com.lichiai.web.search.DeepSearchEngine
import com.lichiai.web.search.DeepSearchReport
import com.lichiai.web.search.DorkSearchEngine
import com.lichiai.web.search.ResearchEngine
import com.lichiai.web.search.SiteSearchEngine
import com.lichiai.web.search.SiteSearchResult
import com.lichiai.web.search.StructuredResearchReport
import com.lichiai.web.strategy.SelfUpgradingSearchStrategy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Unified Browser Intelligence Platform.
 * Acts as the single coordination point for:
 * - 15 First-Class Browser & Search Capabilities
 * - Multi-layer DOM/Accessibility perception
 * - Typed Browser action engine
 * - Closed-loop autonomy & bounded recovery
 * - Web Intelligence reuse & provider fallback
 * - Memory OS persistence
 * - Real Task Activity state streaming
 */
class BrowserIntelligencePlatform(
    private val context: Context,
    val browserController: BrowserController,
    val webIntelligenceManager: WebIntelligenceManager
) {
    private val memoryEngine: LichiMemoryEngine = LichiMemoryEngine.getInstance(context)
    val perceptionLayer: BrowserPerceptionLayer = BrowserPerceptionLayer()
    val actionEngine: BrowserActionEngine = BrowserActionEngine(
        browserController = browserController,
        perceptionLayer = perceptionLayer,
        telemetryHub = AgentVisionTelemetryHub
    )
    val autonomyLoop: AutonomousBrowserLoop = AutonomousBrowserLoop(
        browserController = browserController,
        perceptionLayer = perceptionLayer,
        actionEngine = actionEngine,
        recovery = BrowserRecovery(browserController, browserController.eventBus)
    )
    val dorkEngine: DorkSearchEngine = DorkSearchEngine(webIntelligenceManager)
    val siteEngine: SiteSearchEngine = SiteSearchEngine(webIntelligenceManager)
    val deepSearchEngine: DeepSearchEngine = DeepSearchEngine(webIntelligenceManager)
    val researchEngine: ResearchEngine = ResearchEngine(webIntelligenceManager)
    val comparisonEngine: ComparisonEngine = ComparisonEngine(webIntelligenceManager)
    val adaptiveStrategy: SelfUpgradingSearchStrategy = SelfUpgradingSearchStrategy(webIntelligenceManager)

    // --- 1. WebSearch Capability ---
    suspend fun executeWebSearch(
        query: String,
        targetDomain: String? = null,
        onActivityUpdate: ((AssistantActivityState) -> Unit)? = null
    ): WebSearchResponse = withContext(Dispatchers.IO) {
        onActivityUpdate?.invoke(
            AssistantActivityState(
                kind = ActivityKind.SEARCHING_WEB,
                title = "Searching web",
                subtitle = query.take(35),
                isActive = true
            )
        )
        val response = adaptiveStrategy.executeAdaptiveSearch(query, targetDomain)
        onActivityUpdate?.invoke(
            AssistantActivityState(
                kind = ActivityKind.COMPLETED,
                title = "Search completed",
                subtitle = "${response.results.size} sources retrieved",
                sources = response.results,
                isActive = false
            )
        )
        response
    }

    // --- 2. DorkSearch Capability ---
    suspend fun executeDorkSearch(
        query: String,
        onActivityUpdate: ((AssistantActivityState) -> Unit)? = null
    ): WebSearchResponse = withContext(Dispatchers.IO) {
        onActivityUpdate?.invoke(
            AssistantActivityState(
                kind = ActivityKind.SEARCHING_WEB,
                title = "Advanced public web search",
                subtitle = query.take(35),
                isActive = true
            )
        )
        val response = dorkEngine.executeDorkSearch(query)
        onActivityUpdate?.invoke(
            AssistantActivityState(
                kind = ActivityKind.COMPLETED,
                title = "DorkSearch completed",
                subtitle = "${response.results.size} sources found",
                sources = response.results,
                isActive = false
            )
        )
        response
    }

    // --- 3. SiteSearch Capability ---
    suspend fun executeSiteSearch(
        domain: String,
        query: String,
        maxPages: Int = 3,
        onActivityUpdate: ((AssistantActivityState) -> Unit)? = null
    ): SiteSearchResult = withContext(Dispatchers.IO) {
        onActivityUpdate?.invoke(
            AssistantActivityState(
                kind = ActivityKind.SEARCHING_WEB,
                title = "Searching site $domain",
                subtitle = query.take(35),
                isActive = true
            )
        )
        val result = siteEngine.searchSite(domain, query, maxPages)
        onActivityUpdate?.invoke(
            AssistantActivityState(
                kind = ActivityKind.COMPLETED,
                title = "SiteSearch completed",
                subtitle = "${result.pages.size} pages on $domain",
                sources = result.pages,
                isActive = false
            )
        )
        result
    }

    // --- 4. DeepSearch Capability ---
    suspend fun executeDeepSearch(
        query: String,
        conversationId: String = "",
        messageId: String = "",
        onActivityUpdate: ((AssistantActivityState) -> Unit)? = null
    ): DeepSearchReport = withContext(Dispatchers.IO) {
        val report = deepSearchEngine.executeDeepSearch(
            query = query,
            onStageUpdate = { stage, current, total ->
                onActivityUpdate?.invoke(
                    AssistantActivityState(
                        kind = ActivityKind.RESEARCHING,
                        title = "DeepSearch in progress",
                        subtitle = stage,
                        step = current,
                        totalSteps = total,
                        isActive = true
                    )
                )
            }
        )

        // Persist to Memory OS
        if (conversationId.isNotBlank() && messageId.isNotBlank()) {
            memoryEngine.recordTurnAsync(
                conversationId = conversationId,
                messageId = messageId,
                role = "assistant",
                content = report.synthesisText
            )
        }

        onActivityUpdate?.invoke(
            AssistantActivityState(
                kind = ActivityKind.COMPLETED,
                title = "DeepSearch completed",
                subtitle = "${report.verifiedSources.size} verified sources",
                sources = report.verifiedSources,
                disagreementNotice = report.disagreementNotice,
                isActive = false
            )
        )
        report
    }

    // --- 5. Research Capability ---
    suspend fun executeResearch(
        topic: String,
        queries: List<String> = emptyList(),
        conversationId: String = "",
        messageId: String = "",
        onActivityUpdate: ((AssistantActivityState) -> Unit)? = null
    ): StructuredResearchReport = withContext(Dispatchers.IO) {
        onActivityUpdate?.invoke(
            AssistantActivityState(
                kind = ActivityKind.RESEARCHING,
                title = "Synthesizing research",
                subtitle = topic.take(35),
                isActive = true
            )
        )
        val report = researchEngine.conductResearch(topic, queries)

        if (conversationId.isNotBlank() && messageId.isNotBlank()) {
            memoryEngine.recordTurnAsync(
                conversationId = conversationId,
                messageId = messageId,
                role = "assistant",
                content = report.finalAnswer
            )
        }

        onActivityUpdate?.invoke(
            AssistantActivityState(
                kind = ActivityKind.COMPLETED,
                title = "Research finished",
                subtitle = "${report.sources.size} sources analyzed",
                sources = report.sources,
                isActive = false
            )
        )
        report
    }

    // --- 6. Navigate Capability ---
    suspend fun executeNavigate(url: String): BrowserActionResult = withContext(Dispatchers.Main) {
        val snapshot = perceptionLayer.observePage(browserController.activeEngine.value)
        actionEngine.executeAction(TypedBrowserAction.OpenURL(url, generationId = snapshot.generationId))
    }

    // --- 7. Extract Capability ---
    suspend fun executeExtract(): ExtractedStructuredContent = withContext(Dispatchers.Main) {
        val snapshot = perceptionLayer.observePage(browserController.activeEngine.value)
        BrowserContentExtractor.extractStructured(snapshot)
    }

    // --- 8. FindOnPage Capability ---
    suspend fun executeFindOnPage(keyword: String): BrowserActionResult = withContext(Dispatchers.Main) {
        val snapshot = perceptionLayer.observePage(browserController.activeEngine.value)
        actionEngine.executeAction(TypedBrowserAction.FindOnPage(keyword, generationId = snapshot.generationId))
    }

    // --- 9. Compare Capability ---
    suspend fun executeCompare(
        entities: List<String>,
        criteria: List<String> = emptyList(),
        onActivityUpdate: ((AssistantActivityState) -> Unit)? = null
    ): ComparisonMatrixReport = withContext(Dispatchers.IO) {
        onActivityUpdate?.invoke(
            AssistantActivityState(
                kind = ActivityKind.RESEARCHING,
                title = "Comparing sources",
                subtitle = entities.joinToString(" vs ").take(35),
                isActive = true
            )
        )
        val result = comparisonEngine.compareEntities(entities, criteria)
        onActivityUpdate?.invoke(
            AssistantActivityState(
                kind = ActivityKind.COMPLETED,
                title = "Comparison finished",
                subtitle = "${result.comparedEntities.size} items compared",
                isActive = false
            )
        )
        result
    }

    // --- 10. Verify Capability ---
    suspend fun executeVerify(claim: String, domain: String? = null): WebSearchResponse = withContext(Dispatchers.IO) {
        val query = if (domain != null) "site:$domain $claim" else "$claim fact check official"
        adaptiveStrategy.executeAdaptiveSearch(query, domain)
    }

    // --- 11. Forms Capability ---
    suspend fun executeForms(
        fieldValues: Map<String, String>,
        submit: Boolean = true
    ): List<BrowserActionResult> = withContext(Dispatchers.Main) {
        val results = mutableListOf<BrowserActionResult>()
        val snapshot = perceptionLayer.observePage(browserController.activeEngine.value)
        val genId = snapshot.generationId
        for ((fieldIdOrName, value) in fieldValues) {
            val target = perceptionLayer.resolveElement(fieldIdOrName)
            val action = TypedBrowserAction.TypeText(
                targetIdOrIndex = target?.semanticId ?: fieldIdOrName,
                text = value,
                submit = false,
                generationId = genId
            )
            results.add(actionEngine.executeAction(action))
        }
        if (submit) {
            results.add(actionEngine.executeAction(TypedBrowserAction.SubmitForm(generationId = genId)))
        }
        results
    }

    // --- 12. Download Capability ---
    suspend fun executeDownload(url: String): BrowserActionResult = withContext(Dispatchers.Main) {
        val snapshot = perceptionLayer.observePage(browserController.activeEngine.value)
        actionEngine.executeAction(TypedBrowserAction.Download(url, generationId = snapshot.generationId))
    }

    // --- 13. Upload Capability ---
    suspend fun executeUpload(targetIdOrIndex: String, filePath: String): BrowserActionResult = withContext(Dispatchers.Main) {
        val snapshot = perceptionLayer.observePage(browserController.activeEngine.value)
        actionEngine.executeAction(TypedBrowserAction.Upload(targetIdOrIndex, filePath, generationId = snapshot.generationId))
    }

    // --- 14. MultiTab Capability ---
    suspend fun executeMultiTab(action: String, tabId: String? = null, url: String? = null): BrowserActionResult = withContext(Dispatchers.Main) {
        val snapshot = perceptionLayer.observePage(browserController.activeEngine.value)
        val genId = snapshot.generationId
        when (action.uppercase()) {
            "OPEN" -> actionEngine.executeAction(TypedBrowserAction.OpenNewTab(url, generationId = genId))
            "CLOSE" -> tabId?.let { actionEngine.executeAction(TypedBrowserAction.CloseTab(it, generationId = genId)) }
                ?: BrowserActionResult(ActionExecutionStatus.FAILED, "CloseTab", false, "Missing tabId")
            "SWITCH" -> tabId?.let { actionEngine.executeAction(TypedBrowserAction.SwitchTab(it, generationId = genId)) }
                ?: BrowserActionResult(ActionExecutionStatus.FAILED, "SwitchTab", false, "Missing tabId")
            else -> BrowserActionResult(
                status = ActionExecutionStatus.SUCCESS,
                actionName = "ListTabs",
                isSuccess = true,
                message = "${browserController.tabManager.tabs.value.size} active tabs."
            )
        }
    }

    // --- 15. PageSummary Capability ---
    suspend fun executePageSummary(): String = withContext(Dispatchers.Main) {
        val snapshot = perceptionLayer.observePage(browserController.activeEngine.value)
        val structured = BrowserContentExtractor.extractStructured(snapshot)
        buildString {
            append("Summary of ${structured.title} (${structured.sourceUrl}):\n\n")
            if (structured.headings.isNotEmpty()) {
                append("Key Topics:\n")
                structured.headings.take(5).forEach { append("• $it\n") }
                append("\n")
            }
            append("Content:\n${structured.mainTextSnippet}\n")
            if (structured.prices.isNotEmpty()) {
                append("\nPrices/Financial Data Detected:\n")
                structured.prices.take(4).forEach {
                    append("• ${it.rawValue} [${it.validity}] (Context: \"${it.contextSnippet.trim()}\")\n")
                }
            }
        }
    }

    // --- 16. Browser Inspection & DevTools Intelligence ---
    suspend fun executeInspectPage(
        mode: com.lichiai.browser.inspection.model.InspectionMode = com.lichiai.browser.inspection.model.InspectionMode.FULL_INSPECTION,
        onActivityUpdate: ((AssistantActivityState) -> Unit)? = null
    ): com.lichiai.browser.inspection.model.ComprehensiveInspectionReport = withContext(Dispatchers.Main) {
        onActivityUpdate?.invoke(
            AssistantActivityState(
                kind = ActivityKind.INSPECTING_PAGE,
                title = "Inspecting page & network topology",
                subtitle = mode.name,
                isActive = true
            )
        )
        val report = browserController.inspectCurrentPage(mode = mode)
        onActivityUpdate?.invoke(
            AssistantActivityState(
                kind = ActivityKind.COMPLETED,
                title = "Inspection completed",
                subtitle = "${report.summary.totalEndpointsDiscovered} APIs, ${report.summary.totalRequests} requests, ${report.summary.totalDownloadsDetected} downloads",
                isActive = false
            )
        )
        report
    }

    suspend fun executeEndpointDiscovery(
        onActivityUpdate: ((AssistantActivityState) -> Unit)? = null
    ): List<com.lichiai.browser.inspection.endpoint.EndpointRecord> = withContext(Dispatchers.Main) {
        onActivityUpdate?.invoke(
            AssistantActivityState(
                kind = ActivityKind.INSPECTING_PAGE,
                title = "Discovering API endpoints",
                subtitle = "Scanning network & payloads",
                isActive = true
            )
        )
        val endpoints = browserController.discoverEndpoints()
        onActivityUpdate?.invoke(
            AssistantActivityState(
                kind = ActivityKind.COMPLETED,
                title = "API discovery completed",
                subtitle = "${endpoints.size} endpoints identified",
                isActive = false
            )
        )
        endpoints
    }

    suspend fun executeDownloadDiscovery(
        onActivityUpdate: ((AssistantActivityState) -> Unit)? = null
    ): List<com.lichiai.browser.inspection.download.DownloadCandidate> = withContext(Dispatchers.Main) {
        val dls = browserController.discoverDownloads()
        onActivityUpdate?.invoke(
            AssistantActivityState(
                kind = ActivityKind.COMPLETED,
                title = "Download discovery completed",
                subtitle = "${dls.size} direct download links found",
                isActive = false
            )
        )
        dls
    }

    suspend fun executeResourceDiscovery(
        onActivityUpdate: ((AssistantActivityState) -> Unit)? = null
    ): List<com.lichiai.browser.inspection.resources.ResourceRecord> = withContext(Dispatchers.Main) {
        val res = browserController.discoverResources()
        onActivityUpdate?.invoke(
            AssistantActivityState(
                kind = ActivityKind.COMPLETED,
                title = "Resource discovery completed",
                subtitle = "${res.size} scripts, stylesheets, and resources identified",
                isActive = false
            )
        )
        res
    }

    suspend fun executeNetworkAnalysis(
        onActivityUpdate: ((AssistantActivityState) -> Unit)? = null
    ): com.lichiai.browser.inspection.network.NetworkAnalysisReport = withContext(Dispatchers.Main) {
        val analysis = browserController.analyzeNetwork()
        onActivityUpdate?.invoke(
            AssistantActivityState(
                kind = ActivityKind.COMPLETED,
                title = "Network analysis completed",
                subtitle = "${analysis.totalRequests} requests analyzed (${analysis.timeline.totalFailed} failed, avg ${analysis.timeline.averageLatencyMs}ms)",
                isActive = false
            )
        )
        analysis
    }
}
