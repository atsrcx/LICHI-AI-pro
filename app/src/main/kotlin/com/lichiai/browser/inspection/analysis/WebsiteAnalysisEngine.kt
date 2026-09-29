package com.lichiai.browser.inspection.analysis

import com.lichiai.browser.inspection.cdp.OptionalCdpAdapter
import com.lichiai.browser.inspection.dom.DomSnapshot
import com.lichiai.browser.inspection.download.DownloadCandidate
import com.lichiai.browser.inspection.endpoint.EndpointRecord
import com.lichiai.browser.inspection.model.BrowserInspectionSession
import com.lichiai.browser.inspection.model.ComprehensiveInspectionReport
import com.lichiai.browser.inspection.model.InspectionSummary
import com.lichiai.browser.inspection.network.NetworkRequestRecord
import com.lichiai.browser.inspection.performance.PerformanceAuditReport
import com.lichiai.browser.inspection.resources.ResourceRecord
import com.lichiai.browser.inspection.runtime.ConsoleMessageRecord
import com.lichiai.browser.inspection.security.SecurityAuditReport

object WebsiteAnalysisEngine {

    fun generateComprehensiveReport(
        session: BrowserInspectionSession,
        dom: DomSnapshot,
        endpoints: List<EndpointRecord>,
        requests: List<NetworkRequestRecord>,
        resources: List<ResourceRecord>,
        downloads: List<DownloadCandidate>,
        security: SecurityAuditReport?,
        performance: PerformanceAuditReport?,
        consoleLogs: List<ConsoleMessageRecord>
    ): ComprehensiveInspectionReport {

        val findings = FindingEngine.generateFindings(
            dom = dom,
            endpoints = endpoints,
            requests = requests,
            resources = resources,
            downloads = downloads,
            security = security,
            performance = performance,
            consoleLogs = consoleLogs
        )

        val externalDomains = resources.filter { it.isThirdParty }.map { it.domain }.distinct()
        val scriptsCount = resources.count { it.type == "Script" }
        val stylesCount = resources.count { it.type == "Stylesheet" }
        val imagesCount = resources.count { it.type == "Image" }
        val errorCount = consoleLogs.count { it.level == "ERROR" }

        val summary = InspectionSummary(
            totalRequests = requests.size,
            totalEndpointsDiscovered = endpoints.size,
            totalExternalDomains = externalDomains.size,
            totalDownloadsDetected = downloads.size,
            totalFormsDetected = dom.forms.size,
            totalScripts = scriptsCount,
            totalStylesheets = stylesCount,
            totalImages = imagesCount,
            totalErrorsCount = errorCount,
            hasMixedContent = security?.hasMixedContent ?: false,
            isHttps = security?.isHttps ?: session.targetUrl.startsWith("https://", ignoreCase = true),
            domLoadTimeMs = performance?.domContentLoadedMs ?: 0L,
            fullLoadTimeMs = performance?.fullPageLoadMs ?: 0L
        )

        val explanation = buildTechnicalExplanation(
            session = session,
            dom = dom,
            endpoints = endpoints,
            resources = resources,
            security = security,
            performance = performance,
            externalDomains = externalDomains
        )

        return ComprehensiveInspectionReport(
            session = session.copy(completedAt = System.currentTimeMillis()),
            summary = summary,
            endpoints = endpoints,
            networkRequests = requests,
            resources = resources,
            downloads = downloads,
            forms = dom.forms,
            securitySignals = security,
            performanceMetrics = performance,
            consoleLogs = consoleLogs,
            findings = findings,
            technicalStructureExplanation = explanation,
            cdpStatus = OptionalCdpAdapter.probeCdpTransport().diagnosticReason
        )
    }

    private fun buildTechnicalExplanation(
        session: BrowserInspectionSession,
        dom: DomSnapshot,
        endpoints: List<EndpointRecord>,
        resources: List<ResourceRecord>,
        security: SecurityAuditReport?,
        performance: PerformanceAuditReport?,
        externalDomains: List<String>
    ): String {
        return buildString {
            append("### Technical Structure & Architecture Analysis\n\n")
            append("**Target:** ${session.targetUrl}\n")
            append("**Title:** ${dom.title.ifBlank { "Untitled" }}\n\n")

            // 1. Frontend & DOM
            append("#### 1. Frontend & Document Layout\n")
            append("• **Doctype:** ${dom.doctype.uppercase()} (Charset: ${dom.charset})\n")
            append("• **DOM Elements:** ${dom.totalElementsCount} total nodes, ${dom.totalLinksCount} hyperlinks.\n")
            if (dom.forms.isNotEmpty()) {
                append("• **Forms Detected:** ${dom.forms.size} form(s) found.\n")
                dom.forms.forEach { f ->
                    append("  - Form [${f.method}] action=\"${f.action}\" with ${f.fields.size} inputs (${f.fields.joinToString(", ") { it.name.ifBlank { it.type } }}).\n")
                }
            }
            if (dom.shadowRoots.isNotEmpty()) {
                append("• **Web Components / Shadow DOM:** ${dom.shadowRoots.size} shadow root(s) detected.\n")
            }
            if (dom.iframes.isNotEmpty()) {
                append("• **Embedded Frames:** ${dom.iframes.size} iframe(s) (${dom.iframes.count { it.isCrossDomain }} cross-domain).\n")
            }

            // 2. API & Network Architecture
            append("\n#### 2. API & Data Flow Architecture\n")
            if (endpoints.isNotEmpty()) {
                val rest = endpoints.filter { it.category == "REST" }
                val gql = endpoints.filter { it.category == "GRAPHQL" }
                val auth = endpoints.filter { it.category == "AUTH" }
                append("• **Endpoints Observed:** ${endpoints.size} distinct APIs.\n")
                if (rest.isNotEmpty()) append("  - **REST APIs:** ${rest.size} endpoints (${rest.take(3).joinToString(", ") { "${it.method} ${it.path}" }}).\n")
                if (gql.isNotEmpty()) append("  - **GraphQL APIs:** ${gql.size} operations detected.\n")
                if (auth.isNotEmpty()) append("  - **Auth/Session Endpoints:** ${auth.take(2).joinToString(", ") { it.path }}.\n")
            } else {
                append("• No asynchronous XHR/Fetch API endpoints triggered yet (page may be statically rendered or hydration has completed).\n")
            }

            // 3. Infrastructure, CDNs & Scripts
            append("\n#### 3. Scripts & External Ecosystem\n")
            val scripts = resources.filter { it.type == "Script" }
            append("• **Scripts:** ${scripts.size} scripts (${scripts.count { it.isThirdParty }} third-party).\n")
            if (externalDomains.isNotEmpty()) {
                append("• **Connected External Domains:** ${externalDomains.take(8).joinToString(", ")}\n")
            }

            // 4. Security & Privacy
            append("\n#### 4. Security Posture\n")
            append("• **Transport Security:** ${if (security?.isHttps == true) "HTTPS (TLS encrypted)" else "HTTP (Insecure plain text)"}\n")
            if (security?.hasMixedContent == true) {
                append("• **WARNING:** Mixed content detected (${security.mixedContentUrls.size} insecure requests).\n")
            }
            if (security?.cspDetected == true) {
                append("• **Content Security Policy:** Configured.\n")
            } else {
                append("• **Content Security Policy:** Not enforced by response headers.\n")
            }

            // 5. Performance Metrics
            if (performance != null && performance.fullPageLoadMs > 0) {
                append("\n#### 5. Performance Signals\n")
                append("• **TTFB:** ${performance.ttfbMs}ms | **DOM Ready:** ${performance.domContentLoadedMs}ms | **Full Load:** ${performance.fullPageLoadMs}ms | **Rating:** ${performance.performanceRating}\n")
            }
        }
    }
}
