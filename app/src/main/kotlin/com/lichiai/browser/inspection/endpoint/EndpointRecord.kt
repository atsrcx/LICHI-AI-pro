package com.lichiai.browser.inspection.endpoint

import kotlinx.serialization.Serializable

@Serializable
data class EndpointParameter(
    val name: String,
    val sourceLocation: String, // "QUERY", "PATH", "BODY", "HEADER"
    val sampleValue: String = "",
    val isRequired: Boolean = false
)

@Serializable
data class EndpointRecord(
    val id: String,
    val url: String,
    val path: String,
    val domain: String,
    val method: String,
    val category: String, // REST, GRAPHQL, AUTH, SEARCH, ANALYTICS, RPC, CONFIG, DATA
    val parameters: List<EndpointParameter> = emptyList(),
    val requestHeadersSample: Map<String, String> = emptyMap(),
    val responseHeadersSample: Map<String, String> = emptyMap(),
    val responseStatusCode: Int = 200,
    val responseContentType: String = "application/json",
    val requestPayloadPreview: String? = null,
    val responseBodyPreview: String? = null,
    val graphQlOperationName: String? = null,
    val graphQlOperationType: String? = null, // "query", "mutation", "subscription"
    val occurrences: Int = 1,
    val averageLatencyMs: Long = 0L,
    val isThirdParty: Boolean = false
)
