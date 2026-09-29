package com.lichiai.browser.inspection.network

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class NetworkObserver {

    private val _requests = MutableStateFlow<List<NetworkRequestRecord>>(emptyList())
    val requests: StateFlow<List<NetworkRequestRecord>> = _requests.asStateFlow()

    private val activeRecords = ConcurrentHashMap<String, NetworkRequestRecord>()

    fun recordRequest(
        url: String,
        method: String,
        headers: Map<String, String>,
        isForMainFrame: Boolean,
        hasUserGesture: Boolean,
        pageUrl: String? = null
    ): String {
        val reqId = UUID.randomUUID().toString().take(8)
        val domain = extractDomain(url)
        val mainDomain = pageUrl?.let { extractDomain(it) } ?: ""
        val isThirdParty = mainDomain.isNotBlank() && domain.isNotBlank() && !domain.endsWith(mainDomain) && !mainDomain.endsWith(domain)

        val resType = classifyResourceType(url, headers)
        val isApi = isApiEndpoint(url, headers, method)

        val record = NetworkRequestRecord(
            requestId = reqId,
            url = url,
            method = method.uppercase(),
            headers = headers,
            isForMainFrame = isForMainFrame,
            hasUserGesture = hasUserGesture,
            resourceType = resType,
            timestamp = System.currentTimeMillis(),
            domain = domain,
            isThirdParty = isThirdParty,
            isApiEndpoint = isApi,
            initiator = if (isForMainFrame) "Document" else "Network"
        )
        activeRecords[reqId] = record
        updateState()
        return reqId
    }

    fun recordJsFetchOrXhr(
        url: String,
        method: String,
        headers: Map<String, String>,
        requestBody: String?,
        statusCode: Int,
        statusText: String,
        durationMs: Long,
        responseHeaders: Map<String, String>,
        responseBody: String?,
        initiator: String = "Fetch",
        pageUrl: String? = null
    ) {
        val reqId = UUID.randomUUID().toString().take(8)
        val domain = extractDomain(url)
        val mainDomain = pageUrl?.let { extractDomain(it) } ?: ""
        val isThirdParty = mainDomain.isNotBlank() && domain.isNotBlank() && !domain.endsWith(mainDomain) && !mainDomain.endsWith(domain)
        val mime = responseHeaders["content-type"] ?: responseHeaders["Content-Type"] ?: "application/json"

        val record = NetworkRequestRecord(
            requestId = reqId,
            url = url,
            method = method.uppercase(),
            headers = headers,
            isForMainFrame = false,
            hasUserGesture = false,
            resourceType = "XHR/Fetch",
            timestamp = System.currentTimeMillis(),
            durationMs = durationMs,
            statusCode = if (statusCode > 0) statusCode else 200,
            statusText = if (statusText.isNotBlank()) statusText else "OK",
            mimeType = mime,
            responseHeaders = responseHeaders,
            requestBodyPreview = requestBody?.take(500),
            responseBodyPreview = responseBody?.take(1500),
            initiator = initiator,
            isApiEndpoint = true,
            domain = domain,
            isThirdParty = isThirdParty
        )
        activeRecords[reqId] = record
        updateState()
    }

    fun completeRequest(
        requestId: String,
        statusCode: Int,
        mimeType: String?,
        responseHeaders: Map<String, String> = emptyMap()
    ) {
        val existing = activeRecords[requestId] ?: return
        val duration = System.currentTimeMillis() - existing.timestamp
        val updated = existing.copy(
            statusCode = statusCode,
            durationMs = duration,
            mimeType = mimeType ?: existing.mimeType,
            responseHeaders = responseHeaders
        )
        activeRecords[requestId] = updated
        updateState()
    }

    fun markFailed(requestId: String, errorText: String) {
        val existing = activeRecords[requestId] ?: return
        val duration = System.currentTimeMillis() - existing.timestamp
        val updated = existing.copy(
            statusCode = 0,
            statusText = errorText,
            durationMs = duration
        )
        activeRecords[requestId] = updated
        updateState()
    }

    fun clear() {
        activeRecords.clear()
        _requests.value = emptyList()
    }

    private fun updateState() {
        _requests.value = activeRecords.values.sortedByDescending { it.timestamp }.take(200)
    }

    fun generateAnalysisReport(): NetworkAnalysisReport {
        val list = activeRecords.values.toList()
        val total = list.size
        val failed = list.filter { it.statusCode == 0 || it.statusCode >= 400 }
        val slow = list.filter { it.durationMs > 1000L }
        val avgLatency = if (list.isNotEmpty()) list.map { it.durationMs }.average().toLong() else 0L

        val domainDist = list.groupingBy { it.domain.ifBlank { "unknown" } }.eachCount()
        val typeDist = list.groupingBy { it.resourceType }.eachCount()
        val apiCount = list.count { it.isApiEndpoint }

        val timeline = NetworkTimeline(
            totalRequests = total,
            totalFailed = failed.size,
            totalSlowRequests = slow.size,
            averageLatencyMs = avgLatency,
            domainDistribution = domainDist,
            resourceTypeDistribution = typeDist
        )

        val topDomains = domainDist.entries.sortedByDescending { it.value }.map { it.key to it.value }.take(10)

        return NetworkAnalysisReport(
            totalRequests = total,
            timeline = timeline,
            observedEndpointsCount = apiCount,
            failedRequests = failed.take(15),
            slowRequests = slow.take(15),
            topDomains = topDomains,
            allRequests = list.sortedByDescending { it.timestamp }
        )
    }

    private fun extractDomain(urlStr: String): String {
        return try {
            val uri = Uri.parse(urlStr)
            uri.host ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun classifyResourceType(url: String, headers: Map<String, String>): String {
        val lower = url.lowercase()
        val accept = headers["accept"]?.lowercase() ?: headers["Accept"]?.lowercase() ?: ""

        return when {
            lower.contains(".js") || lower.contains("/js/") || accept.contains("javascript") -> "Script"
            lower.contains(".css") || lower.contains("/css/") || accept.contains("text/css") -> "Stylesheet"
            lower.contains(".png") || lower.contains(".jpg") || lower.contains(".jpeg") ||
                lower.contains(".webp") || lower.contains(".gif") || lower.contains(".svg") ||
                lower.contains(".ico") || accept.contains("image/") -> "Image"
            lower.contains(".woff") || lower.contains(".woff2") || lower.contains(".ttf") || lower.contains(".otf") -> "Font"
            lower.contains(".mp4") || lower.contains(".webm") || lower.contains(".mp3") || lower.contains(".wav") -> "Media"
            lower.contains("/api/") || lower.contains("/v1/") || lower.contains("/v2/") ||
                lower.contains("/graphql") || accept.contains("application/json") -> "XHR/Fetch"
            lower.startsWith("ws://") || lower.startsWith("wss://") -> "WebSocket"
            else -> "Document"
        }
    }

    private fun isApiEndpoint(url: String, headers: Map<String, String>, method: String): Boolean {
        val lower = url.lowercase()
        val accept = headers["accept"]?.lowercase() ?: headers["Accept"]?.lowercase() ?: ""
        val contentType = headers["content-type"]?.lowercase() ?: headers["Content-Type"]?.lowercase() ?: ""

        if (method.uppercase() in listOf("POST", "PUT", "DELETE", "PATCH")) return true
        if (accept.contains("application/json") || contentType.contains("application/json")) return true
        if (lower.contains("/api/") || lower.contains("/v1/") || lower.contains("/v2/") ||
            lower.contains("/v3/") || lower.contains("/rest/") || lower.contains("/graphql") ||
            lower.contains(".json") || lower.contains("/query") || lower.contains("/rpc") ||
            lower.contains("/endpoints")) return true

        return false
    }
}
