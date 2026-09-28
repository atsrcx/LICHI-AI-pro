package com.lichiai.web.adapter

import com.lichiai.web.model.ImageResult
import com.lichiai.web.model.WebCapability
import com.lichiai.web.model.WebProviderType
import com.lichiai.web.model.WebResult
import com.lichiai.web.model.WebSearchQuery
import com.lichiai.web.model.WebSearchResponse

/**
 * Normalized interface for all Web Intelligence Provider Adapters.
 *
 * Each adapter converts provider-specific REST schemas to/from normalized Lichi models
 * and declares its verified capabilities truthfully.
 */
interface WebSearchProvider {
    val providerType: WebProviderType
    val supportedCapabilities: Set<WebCapability>

    fun supports(capability: WebCapability): Boolean = supportedCapabilities.contains(capability)

    /**
     * Executes standard or deep web search.
     * @param onProgress Callback invoked as live search progress events occur (e.g., status description, visited domains)
     */
    suspend fun search(
        query: WebSearchQuery,
        apiKey: String,
        onProgress: suspend (statusMsg: String, domains: List<String>) -> Unit = { _, _ -> }
    ): WebSearchResponse

    suspend fun searchNews(
        query: String,
        count: Int,
        apiKey: String
    ): List<WebResult> {
        throw UnsupportedOperationException("${providerType.displayName} does not support dedicated news search.")
    }

    suspend fun searchImages(
        query: String,
        count: Int,
        apiKey: String
    ): List<ImageResult> {
        throw UnsupportedOperationException("${providerType.displayName} does not support image search.")
    }

    suspend fun extractContent(
        urls: List<String>,
        apiKey: String
    ): Map<String, String> {
        throw UnsupportedOperationException("${providerType.displayName} does not support direct URL extraction.")
    }

    suspend fun testConnection(apiKey: String): Result<String>
}
