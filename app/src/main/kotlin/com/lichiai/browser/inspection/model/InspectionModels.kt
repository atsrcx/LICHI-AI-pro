package com.lichiai.browser.inspection.model

import kotlinx.serialization.Serializable

/**
 * Inspection Mode defining the depth and target domain of the inspection session.
 */
enum class InspectionMode {
    PAGE_INSPECTION,
    SITE_INSPECTION,
    NETWORK_INSPECTION,
    ENDPOINT_INSPECTION,
    DOWNLOAD_INSPECTION,
    RESOURCE_INSPECTION,
    SECURITY_INSPECTION,
    PERFORMANCE_INSPECTION,
    FULL_INSPECTION;

    companion object {
        fun fromString(value: String?): InspectionMode {
            return when (value?.uppercase()?.trim()) {
                "PAGE", "PAGE_INSPECTION" -> PAGE_INSPECTION
                "SITE", "SITE_INSPECTION" -> SITE_INSPECTION
                "NETWORK", "NETWORK_INSPECTION" -> NETWORK_INSPECTION
                "ENDPOINT", "API", "ENDPOINT_INSPECTION" -> ENDPOINT_INSPECTION
                "DOWNLOAD", "DOWNLOADS", "DOWNLOAD_INSPECTION" -> DOWNLOAD_INSPECTION
                "RESOURCE", "RESOURCES", "RESOURCE_INSPECTION" -> RESOURCE_INSPECTION
                "SECURITY", "SECURITY_INSPECTION" -> SECURITY_INSPECTION
                "PERFORMANCE", "PERF", "PERFORMANCE_INSPECTION" -> PERFORMANCE_INSPECTION
                else -> FULL_INSPECTION
            }
        }
    }
}

/**
 * Source of inspection evidence.
 */
enum class EvidenceScope {
    OBSERVED_ONLY,
    STATIC_REFERENCES,
    HYBRID
}

/**
 * Scope and identity context for an inspection session.
 * Never relies on global variables. Explicitly scoped.
 */
@Serializable
data class BrowserInspectionSession(
    val inspectionSessionId: String,
    val taskId: String = "",
    val messageId: String = "",
    val conversationId: String = "",
    val browserTabId: String,
    val pageId: String,
    val navigationId: String = "",
    val targetUrl: String,
    val pageTitle: String = "",
    val mode: String = InspectionMode.FULL_INSPECTION.name,
    val evidenceScope: String = EvidenceScope.HYBRID.name,
    val startedAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val isLive: Boolean = true
)

/**
 * High-level finding or technical insight produced by FindingEngine.
 */
@Serializable
data class InspectionFinding(
    val id: String,
    val title: String,
    val category: String, // "API", "SECURITY", "PERFORMANCE", "DOWNLOAD", "ARCHITECTURE", "RESOURCE"
    val severity: String, // "INFO", "NOTICE", "WARNING", "HIGH"
    val description: String,
    val evidenceDetails: String,
    val affectedUrl: String? = null
)

/**
 * Comprehensive technical report of a page/website inspection.
 */
@Serializable
data class ComprehensiveInspectionReport(
    val session: BrowserInspectionSession,
    val summary: InspectionSummary,
    val endpoints: List<com.lichiai.browser.inspection.endpoint.EndpointRecord> = emptyList(),
    val networkRequests: List<com.lichiai.browser.inspection.network.NetworkRequestRecord> = emptyList(),
    val resources: List<com.lichiai.browser.inspection.resources.ResourceRecord> = emptyList(),
    val downloads: List<com.lichiai.browser.inspection.download.DownloadCandidate> = emptyList(),
    val forms: List<com.lichiai.browser.inspection.dom.FormRecord> = emptyList(),
    val securitySignals: com.lichiai.browser.inspection.security.SecurityAuditReport? = null,
    val performanceMetrics: com.lichiai.browser.inspection.performance.PerformanceAuditReport? = null,
    val consoleLogs: List<com.lichiai.browser.inspection.runtime.ConsoleMessageRecord> = emptyList(),
    val findings: List<InspectionFinding> = emptyList(),
    val technicalStructureExplanation: String = "",
    val cdpStatus: String = "UNAVAILABLE"
)

@Serializable
data class InspectionSummary(
    val totalRequests: Int,
    val totalEndpointsDiscovered: Int,
    val totalExternalDomains: Int,
    val totalDownloadsDetected: Int,
    val totalFormsDetected: Int,
    val totalScripts: Int,
    val totalStylesheets: Int,
    val totalImages: Int,
    val totalErrorsCount: Int,
    val hasMixedContent: Boolean,
    val isHttps: Boolean,
    val domLoadTimeMs: Long,
    val fullLoadTimeMs: Long
)
