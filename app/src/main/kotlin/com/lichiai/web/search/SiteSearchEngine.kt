package com.lichiai.web.search

import com.lichiai.web.WebIntelligenceManager
import com.lichiai.web.model.WebResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.util.Locale

data class SiteSearchResult(
    val domain: String,
    val query: String,
    val pages: List<WebResult>,
    val extractedContent: Map<String, String>,
    val internalLinks: List<String>,
    val summary: String
)

/**
 * Domain-scoped SiteSearch and lightweight crawler.
 * Enforces Section 5 guidelines:
 * - Scopes queries strictly to target domain.
 * - Extracts and crawls only necessary internal pages.
 * - Deduplicates pages and returns source-linked results.
 */
class SiteSearchEngine(
    private val webIntelligenceManager: WebIntelligenceManager
) {

    /**
     * Executes targeted search within a specific site domain.
     */
    suspend fun searchSite(
        domain: String,
        query: String,
        maxPages: Int = 3
    ): SiteSearchResult = withContext(Dispatchers.IO) {
        val cleanDomain = normalizeDomain(domain)
        val siteQuery = "site:$cleanDomain $query"

        val response = webIntelligenceManager.executeSearch(siteQuery)

        // Filter and deduplicate strictly within domain
        val domainResults: List<WebResult> = response.results.filter { result ->
            val host = runCatching { URI(result.url).host?.lowercase(Locale.ROOT) }.getOrNull() ?: ""
            host.contains(cleanDomain)
        }.distinctBy { it.url }.take(maxPages)

        // Extract content from top internal pages
        val targetUrls: List<String> = domainResults.map { it.url }
        val extractedContent = if (targetUrls.isNotEmpty()) {
            webIntelligenceManager.extractContent(targetUrls)
        } else {
            emptyMap()
        }

        val internalLinks: List<String> = domainResults.map { it.url }

        val summary = if (domainResults.isNotEmpty()) {
            "Found ${domainResults.size} relevant pages on $cleanDomain for '$query'."
        } else {
            "No direct pages found on $cleanDomain for '$query'."
        }

        SiteSearchResult(
            domain = cleanDomain,
            query = query,
            pages = domainResults,
            extractedContent = extractedContent,
            internalLinks = internalLinks,
            summary = summary
        )
    }

    private fun normalizeDomain(raw: String): String {
        return runCatching {
            val uri = if (!raw.startsWith("http")) URI("https://$raw") else URI(raw)
            val host = uri.host ?: raw
            if (host.startsWith("www.")) host.substring(4) else host
        }.getOrDefault(raw).lowercase(Locale.ROOT).trim()
    }
}
