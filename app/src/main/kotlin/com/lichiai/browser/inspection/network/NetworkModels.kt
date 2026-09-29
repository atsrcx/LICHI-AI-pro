package com.lichiai.browser.inspection.network

import kotlinx.serialization.Serializable

@Serializable
data class NetworkRequestRecord(
    val requestId: String,
    val url: String,
    val method: String,
    val headers: Map<String, String> = emptyMap(),
    val isForMainFrame: Boolean = false,
    val hasUserGesture: Boolean = false,
    val resourceType: String = "Other",
    val timestamp: Long = System.currentTimeMillis(),
    val durationMs: Long = 0L,
    val statusCode: Int = 200,
    val statusText: String = "OK",
    val mimeType: String = "",
    val responseHeaders: Map<String, String> = emptyMap(),
    val requestBodyPreview: String? = null,
    val responseBodyPreview: String? = null,
    val initiator: String = "Network",
    val isApiEndpoint: Boolean = false,
    val domain: String = "",
    val isThirdParty: Boolean = false
)

@Serializable
data class NetworkTimeline(
    val totalRequests: Int,
    val totalFailed: Int,
    val totalSlowRequests: Int,
    val averageLatencyMs: Long,
    val domainDistribution: Map<String, Int> = emptyMap(),
    val resourceTypeDistribution: Map<String, Int> = emptyMap()
)

@Serializable
data class NetworkAnalysisReport(
    val totalRequests: Int,
    val timeline: NetworkTimeline,
    val observedEndpointsCount: Int,
    val failedRequests: List<NetworkRequestRecord> = emptyList(),
    val slowRequests: List<NetworkRequestRecord> = emptyList(),
    val topDomains: List<Pair<String, Int>> = emptyList(),
    val allRequests: List<NetworkRequestRecord> = emptyList()
)
