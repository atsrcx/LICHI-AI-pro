package com.lichiai.browser.inspection

import android.content.Context
import com.lichiai.browser.engine.ChromiumWebViewEngine
import com.lichiai.browser.events.BrowserEventBus
import com.lichiai.browser.inspection.analysis.WebsiteAnalysisEngine
import com.lichiai.browser.inspection.dom.DomInspector
import com.lichiai.browser.inspection.dom.DomSnapshot
import com.lichiai.browser.inspection.download.DownloadCandidate
import com.lichiai.browser.inspection.endpoint.EndpointDiscoveryEngine
import com.lichiai.browser.inspection.endpoint.EndpointRecord
import com.lichiai.browser.inspection.model.BrowserInspectionSession
import com.lichiai.browser.inspection.model.ComprehensiveInspectionReport
import com.lichiai.browser.inspection.model.InspectionMode
import com.lichiai.browser.inspection.network.NetworkAnalysisReport
import com.lichiai.browser.inspection.performance.BrowserPerformanceInspector
import com.lichiai.browser.inspection.resources.DiscoveredUrlRecord
import com.lichiai.browser.inspection.resources.ResourceDiscoveryEngine
import com.lichiai.browser.inspection.resources.ResourceRecord
import com.lichiai.browser.inspection.security.BrowserSecurityInspector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.UUID

class BrowserInspectionCoordinator(
    val context: Context,
    val coroutineScope: CoroutineScope,
    val eventBus: BrowserEventBus,
    val hub: BrowserInstrumentationHub = BrowserInstrumentationHub()
) {
    val domInspector = DomInspector()
    val endpointEngine = EndpointDiscoveryEngine(hub.networkObserver)
    val resourceEngine = ResourceDiscoveryEngine(hub.networkObserver)
    val securityInspector = BrowserSecurityInspector(hub.networkObserver)
    val performanceInspector = BrowserPerformanceInspector()

    private val _activeReport = MutableStateFlow<ComprehensiveInspectionReport?>(null)
    val activeReport: StateFlow<ComprehensiveInspectionReport?> = _activeReport.asStateFlow()

    private val _isInspecting = MutableStateFlow(false)
    val isInspecting: StateFlow<Boolean> = _isInspecting.asStateFlow()

    suspend fun inspectCurrentPage(
        engine: ChromiumWebViewEngine?,
        tabId: String,
        mode: InspectionMode = InspectionMode.FULL_INSPECTION,
        taskId: String = "",
        messageId: String = "",
        conversationId: String = ""
    ): ComprehensiveInspectionReport = withContext(Dispatchers.Main) {
        _isInspecting.value = true
        try {
            val pageUrl = try { engine?.getUrl() ?: "about:blank" } catch (_: Exception) { "about:blank" }
            val pageTitle = try { engine?.getTitle() ?: "" } catch (_: Exception) { "" }

            val session = BrowserInspectionSession(
                inspectionSessionId = UUID.randomUUID().toString(),
                taskId = taskId,
                messageId = messageId,
                conversationId = conversationId,
                browserTabId = tabId,
                pageId = UUID.randomUUID().toString().take(8),
                targetUrl = pageUrl,
                pageTitle = pageTitle,
                mode = mode.name
            )

            // 1. DOM Inspection
            val domSnapshot = if (mode in listOf(InspectionMode.FULL_INSPECTION, InspectionMode.PAGE_INSPECTION, InspectionMode.SITE_INSPECTION)) {
                try {
                    domInspector.inspectDom(engine)
                } catch (_: Exception) {
                    DomSnapshot(pageUrl = pageUrl, title = pageTitle)
                }
            } else DomSnapshot(pageUrl = pageUrl, title = pageTitle)

            // 2. DOM download candidates
            try {
                hub.downloadObserver.discoverDomDownloads(engine)
            } catch (_: Exception) {}
            val downloads = hub.downloadObserver.downloads.value

            // 3. Endpoint discovery
            val endpoints = try {
                endpointEngine.discoverEndpoints()
            } catch (_: Exception) {
                emptyList()
            }

            // 4. Resources and URLs
            val resources = try {
                resourceEngine.discoverAllResources(engine, pageUrl)
            } catch (_: Exception) {
                emptyList()
            }

            // 5. Security audit
            val securityReport = try {
                securityInspector.auditSecurity(engine, pageUrl)
            } catch (_: Exception) {
                com.lichiai.browser.inspection.security.SecurityAuditReport(
                    isHttps = pageUrl.startsWith("https://", ignoreCase = true),
                    protocol = if (pageUrl.startsWith("https://", ignoreCase = true)) "HTTPS" else "HTTP",
                    certificateStatus = if (pageUrl.startsWith("https://", ignoreCase = true)) "SECURE" else "UNENCRYPTED"
                )
            }

            // 6. Performance metrics
            val perfReport = try {
                performanceInspector.auditPerformance(engine)
            } catch (_: Exception) {
                com.lichiai.browser.inspection.performance.PerformanceAuditReport()
            }

            // 7. Network requests and console
            val networkReqs = hub.networkObserver.requests.value
            val consoleLogs = hub.consoleInspector.logs.value

            // 8. Full synthesis via WebsiteAnalysisEngine
            val report = WebsiteAnalysisEngine.generateComprehensiveReport(
                session = session,
                dom = domSnapshot,
                endpoints = endpoints,
                requests = networkReqs,
                resources = resources,
                downloads = downloads,
                security = securityReport,
                performance = perfReport,
                consoleLogs = consoleLogs
            )

            _activeReport.value = report
            report
        } catch (e: Exception) {
            android.util.Log.e("BrowserInspection", "Error during page inspection", e)
            val fallbackSession = BrowserInspectionSession(
                inspectionSessionId = UUID.randomUUID().toString(),
                taskId = taskId,
                messageId = messageId,
                conversationId = conversationId,
                browserTabId = tabId,
                pageId = UUID.randomUUID().toString().take(8),
                targetUrl = try { engine?.getUrl() ?: "about:blank" } catch (_: Exception) { "about:blank" },
                pageTitle = try { engine?.getTitle() ?: "" } catch (_: Exception) { "" },
                mode = mode.name
            )
            val fallbackReport = WebsiteAnalysisEngine.generateComprehensiveReport(
                session = fallbackSession,
                dom = DomSnapshot(pageUrl = fallbackSession.targetUrl, title = fallbackSession.pageTitle),
                endpoints = emptyList(),
                requests = hub.networkObserver.requests.value,
                resources = emptyList(),
                downloads = hub.downloadObserver.downloads.value,
                security = com.lichiai.browser.inspection.security.SecurityAuditReport(isHttps = false, protocol = "HTTP", certificateStatus = "UNKNOWN"),
                performance = com.lichiai.browser.inspection.performance.PerformanceAuditReport(),
                consoleLogs = hub.consoleInspector.logs.value
            )
            _activeReport.value = fallbackReport
            fallbackReport
        } finally {
            _isInspecting.value = false
        }
    }

    suspend fun discoverEndpoints(engine: ChromiumWebViewEngine?): List<EndpointRecord> = withContext(Dispatchers.Default) {
        endpointEngine.discoverEndpoints()
    }

    suspend fun discoverDownloads(engine: ChromiumWebViewEngine?): List<DownloadCandidate> = withContext(Dispatchers.Default) {
        hub.downloadObserver.discoverDomDownloads(engine)
        hub.downloadObserver.downloads.value
    }

    suspend fun discoverResources(engine: ChromiumWebViewEngine?): List<ResourceRecord> = withContext(Dispatchers.Default) {
        val pageUrl = engine?.getUrl() ?: ""
        resourceEngine.discoverAllResources(engine, pageUrl)
    }

    suspend fun discoverLinks(engine: ChromiumWebViewEngine?): List<DiscoveredUrlRecord> = withContext(Dispatchers.Default) {
        val pageUrl = engine?.getUrl() ?: ""
        resourceEngine.discoverAllUrls(engine, pageUrl)
    }

    suspend fun analyzeNetwork(): NetworkAnalysisReport = withContext(Dispatchers.Default) {
        hub.networkObserver.generateAnalysisReport()
    }
}
