package com.lichiai.browser.inspection.endpoint

import android.net.Uri
import com.lichiai.browser.inspection.network.NetworkObserver
import com.lichiai.browser.inspection.network.NetworkRequestRecord
import org.json.JSONObject

class EndpointDiscoveryEngine(private val networkObserver: NetworkObserver) {

    fun discoverEndpoints(): List<EndpointRecord> {
        val requests = networkObserver.requests.value
        val apiRequests = requests.filter { it.isApiEndpoint }

        val grouped = apiRequests.groupBy { "${it.method}:${normalizeUrlPath(it.url)}" }

        val discovered = mutableListOf<EndpointRecord>()

        for ((key, records) in grouped) {
            val sample = records.first()
            val uri = try { Uri.parse(sample.url) } catch (_: Exception) { null }
            val path = uri?.path ?: sample.url
            val domain = uri?.host ?: sample.domain

            val graphQlInfo = GraphQlDetector.detect(sample.url, sample.requestBodyPreview)
            val category = EndpointClassifier.classify(sample.url, sample.method, graphQlInfo.isGraphQl)

            val params = extractParameters(uri, sample.requestBodyPreview)

            val avgLatency = records.map { it.durationMs }.average().toLong()

            discovered.add(
                EndpointRecord(
                    id = key.hashCode().toString(),
                    url = sample.url,
                    path = path,
                    domain = domain,
                    method = sample.method,
                    category = category,
                    parameters = params,
                    requestHeadersSample = sample.headers,
                    responseHeadersSample = sample.responseHeaders,
                    responseStatusCode = sample.statusCode,
                    responseContentType = sample.mimeType,
                    requestPayloadPreview = sample.requestBodyPreview,
                    responseBodyPreview = sample.responseBodyPreview,
                    graphQlOperationName = graphQlInfo.operationName,
                    graphQlOperationType = graphQlInfo.operationType,
                    occurrences = records.size,
                    averageLatencyMs = avgLatency,
                    isThirdParty = sample.isThirdParty
                )
            )
        }

        return discovered.sortedByDescending { it.occurrences }
    }

    private fun normalizeUrlPath(urlStr: String): String {
        return try {
            val uri = Uri.parse(urlStr)
            val path = uri.path ?: "/"
            "${uri.host ?: ""}$path"
        } catch (_: Exception) {
            urlStr.substringBefore('?')
        }
    }

    private fun extractParameters(uri: Uri?, payload: String?): List<EndpointParameter> {
        val params = mutableListOf<EndpointParameter>()

        // 1. Query parameters
        if (uri != null && uri.isHierarchical) {
            try {
                uri.queryParameterNames.forEach { qName ->
                    val sampleVal = uri.getQueryParameter(qName) ?: ""
                    params.add(
                        EndpointParameter(
                            name = qName,
                            sourceLocation = "QUERY",
                            sampleValue = sampleVal.take(50),
                            isRequired = true
                        )
                    )
                }
            } catch (_: Exception) {}
        }

        // 2. Body parameters if JSON
        if (!payload.isNullOrBlank()) {
            try {
                val json = JSONObject(payload)
                val keys = json.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = json.opt(k)?.toString() ?: ""
                    params.add(
                        EndpointParameter(
                            name = k,
                            sourceLocation = "BODY",
                            sampleValue = v.take(50),
                            isRequired = false
                        )
                    )
                }
            } catch (_: Exception) {}
        }

        return params
    }
}
