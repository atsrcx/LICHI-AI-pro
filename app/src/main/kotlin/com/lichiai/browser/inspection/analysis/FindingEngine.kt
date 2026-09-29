package com.lichiai.browser.inspection.analysis

import com.lichiai.browser.inspection.dom.DomSnapshot
import com.lichiai.browser.inspection.download.DownloadCandidate
import com.lichiai.browser.inspection.endpoint.EndpointRecord
import com.lichiai.browser.inspection.model.InspectionFinding
import com.lichiai.browser.inspection.network.NetworkRequestRecord
import com.lichiai.browser.inspection.performance.PerformanceAuditReport
import com.lichiai.browser.inspection.resources.ResourceRecord
import com.lichiai.browser.inspection.runtime.ConsoleMessageRecord
import com.lichiai.browser.inspection.security.SecurityAuditReport
import java.util.UUID

object FindingEngine {

    fun generateFindings(
        dom: DomSnapshot,
        endpoints: List<EndpointRecord>,
        requests: List<NetworkRequestRecord>,
        resources: List<ResourceRecord>,
        downloads: List<DownloadCandidate>,
        security: SecurityAuditReport?,
        performance: PerformanceAuditReport?,
        consoleLogs: List<ConsoleMessageRecord>
    ): List<InspectionFinding> {
        val findings = mutableListOf<InspectionFinding>()

        // 1. API Findings
        if (endpoints.isNotEmpty()) {
            val restCount = endpoints.count { it.category == "REST" }
            val gqlCount = endpoints.count { it.category == "GRAPHQL" }
            findings.add(
                InspectionFinding(
                    id = UUID.randomUUID().toString().take(8),
                    title = "Discovered ${endpoints.size} Active API Endpoints",
                    category = "API",
                    severity = "INFO",
                    description = "Identified $restCount REST endpoints, $gqlCount GraphQL endpoints across ${endpoints.map { it.domain }.distinct().size} domains.",
                    evidenceDetails = endpoints.take(5).joinToString(", ") { "${it.method} ${it.path}" }
                )
            )
        }

        // 2. Download Findings
        if (downloads.isNotEmpty()) {
            findings.add(
                InspectionFinding(
                    id = UUID.randomUUID().toString().take(8),
                    title = "Detected ${downloads.size} Direct Download Assets",
                    category = "DOWNLOAD",
                    severity = "INFO",
                    description = "Direct download targets found with verified file extensions: ${downloads.map { it.extension }.distinct().joinToString(", ")}.",
                    evidenceDetails = downloads.take(3).joinToString("; ") { "${it.filename} (${it.url})" }
                )
            )
        }

        // 3. Security Findings
        if (security != null) {
            if (!security.isHttps) {
                findings.add(
                    InspectionFinding(
                        id = UUID.randomUUID().toString().take(8),
                        title = "Insecure HTTP Transport",
                        category = "SECURITY",
                        severity = "HIGH",
                        description = "The target webpage is served over unencrypted HTTP. All transmitted credentials and data are visible in plain text.",
                        evidenceDetails = "Scheme: HTTP, Risk: HIGH"
                    )
                )
            } else if (security.hasMixedContent) {
                findings.add(
                    InspectionFinding(
                        id = UUID.randomUUID().toString().take(8),
                        title = "Mixed Content Detected",
                        category = "SECURITY",
                        severity = "WARNING",
                        description = "Webpage is loaded over HTTPS but requests insecure HTTP subresources.",
                        evidenceDetails = security.mixedContentUrls.joinToString(", ")
                    )
                )
            }

            if (security.suspectedLeakedSecrets.isNotEmpty()) {
                findings.add(
                    InspectionFinding(
                        id = UUID.randomUUID().toString().take(8),
                        title = "Potential Secret/Token Exposure",
                        category = "SECURITY",
                        severity = "HIGH",
                        description = "Observed request payloads or URLs contain patterns resembling API tokens or private keys.",
                        evidenceDetails = security.suspectedLeakedSecrets.joinToString(", ")
                    )
                )
            }
        }

        // 4. Performance Findings
        if (performance != null && performance.fullPageLoadMs > 0) {
            val severity = if (performance.performanceRating in listOf("POOR", "NEEDS_IMPROVEMENT")) "WARNING" else "INFO"
            findings.add(
                InspectionFinding(
                    id = UUID.randomUUID().toString().take(8),
                    title = "Page Load Performance: ${performance.performanceRating}",
                    category = "PERFORMANCE",
                    severity = severity,
                    description = "DOM Content Loaded in ${performance.domContentLoadedMs}ms, Full Load in ${performance.fullPageLoadMs}ms (TTFB: ${performance.ttfbMs}ms, FCP: ${performance.firstContentfulPaintMs}ms).",
                    evidenceDetails = "Rating: ${performance.performanceRating}, Total time: ${performance.fullPageLoadMs}ms"
                )
            )
        }

        // 5. Console & Runtime Errors
        val errors = consoleLogs.filter { it.level == "ERROR" }
        if (errors.isNotEmpty()) {
            findings.add(
                InspectionFinding(
                    id = UUID.randomUUID().toString().take(8),
                    title = "Observed ${errors.size} JavaScript Runtime Errors",
                    category = "RUNTIME",
                    severity = "WARNING",
                    description = "Unhandled exceptions or console.error calls detected during page execution.",
                    evidenceDetails = errors.take(3).joinToString("; ") { "${it.message} (${it.sourceId}:${it.lineNumber})" }
                )
            )
        }

        // 6. Architecture & Third-party dependencies
        val thirdPartyDomains = resources.filter { it.isThirdParty }.map { it.domain }.distinct()
        if (thirdPartyDomains.isNotEmpty()) {
            findings.add(
                InspectionFinding(
                    id = UUID.randomUUID().toString().take(8),
                    title = "Observed ${thirdPartyDomains.size} External Service Domains",
                    category = "ARCHITECTURE",
                    severity = "INFO",
                    description = "The page loads external dependencies or integrations from: ${thirdPartyDomains.take(6).joinToString(", ")}.",
                    evidenceDetails = "External domains: ${thirdPartyDomains.size}"
                )
            )
        }

        return findings
    }
}
