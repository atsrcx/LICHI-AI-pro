package com.lichiai.web.model

import com.lichiai.web.planner.SearchPlan
import com.lichiai.web.verifier.FactEvidence
import kotlinx.serialization.Serializable

enum class WebProviderType(val id: String, val displayName: String) {
    EXA("exa", "Exa AI"),
    TAVILY("tavily", "Tavily"),
    BRAVE("brave", "Brave Search"),
    SERPER("serper", "Serper (Google)")
}

enum class WebSearchMode(val displayName: String) {
    SINGLE_PROVIDER("Single Provider"),
    AUTOMATIC_FALLBACK("Automatic Fallback"),
    MULTI_PROVIDER_RESEARCH("Multi-Provider Research")
}

enum class WebCapability(val label: String) {
    WEB_SEARCH("Web Search"),
    NEWS_SEARCH("News"),
    IMAGE_SEARCH("Images"),
    VIDEO_SEARCH("Videos"),
    CONTENT_EXTRACTION("Extraction"),
    DEEP_RESEARCH("Deep Research"),
    DIRECT_ANSWER("Direct Answer")
}

@Serializable
data class WebResult(
    val title: String,
    val url: String,
    val domain: String,
    val snippet: String,
    val content: String? = null,
    val publishedAt: String? = null,
    val imageUrl: String? = null,
    val thumbnailUrl: String? = null,
    val faviconUrl: String? = null,
    val sourceName: String? = null,
    val sourceProvider: String = "",
    val relevance: Float? = null,
    val citationId: Int = 1,
    val resultType: String = "web"
)

@Serializable
data class ImageResult(
    val title: String,
    val imageUrl: String,
    val thumbnailUrl: String? = null,
    val sourceUrl: String,
    val sourceDomain: String,
    val width: Int? = null,
    val height: Int? = null,
    val sourceName: String? = null,
    val provider: String
)

data class WebSearchQuery(
    val query: String,
    val maxResults: Int = 8,
    val language: String? = null,
    val country: String? = null,
    val freshness: String? = null, // "latest", "today", "week", "month", "year"
    val includeContent: Boolean = true,
    val includeImages: Boolean = false,
    val isDeep: Boolean = false
)

@Serializable
data class WebSearchResponse(
    val query: String,
    val providerUsed: String,
    val results: List<WebResult> = emptyList(),
    val images: List<ImageResult> = emptyList(),
    val directAnswer: String? = null,
    val rawSummary: String? = null,
    val isMultiProvider: Boolean = false,
    val executionTimeMs: Long = 0L,
    val searchPlan: SearchPlan? = null,
    val factEvidence: FactEvidence? = null
)

enum class WebActivityStatus {
    IDLE,
    STARTING,
    BUILDING_QUERY,
    SEARCHING,
    RESULTS_RECEIVED,
    READING_SOURCES,
    EXTRACTING,
    VERIFYING_FACTS,
    ANALYZING,
    GENERATING,
    COMPLETED,
    FAILED,
    CANCELLED
}

@Serializable
data class WebActivityState(
    val status: WebActivityStatus = WebActivityStatus.IDLE,
    val query: String = "",
    val providerName: String = "",
    val activeDomains: List<String> = emptyList(),
    val completedSources: List<WebResult> = emptyList(),
    val images: List<ImageResult> = emptyList(),
    val message: String = "",
    val error: String? = null,
    val intentLabel: String? = null,
    val strategyQueries: List<String> = emptyList(),
    val verifiedBadge: String? = null,
    val searchPlan: SearchPlan? = null,
    val factEvidence: FactEvidence? = null
) {
    val isActive: Boolean
        get() = status != WebActivityStatus.IDLE &&
                status != WebActivityStatus.COMPLETED &&
                status != WebActivityStatus.FAILED &&
                status != WebActivityStatus.CANCELLED
}

@Serializable
data class WebResearchClaim(
    val statement: String,
    val supportingSources: List<String> = emptyList(),
    val conflictingSources: List<String> = emptyList(),
    val confidence: String = "high"
)

@Serializable
data class WebResearchResult(
    val query: String,
    val providersConsulted: List<String>,
    val sources: List<WebResult>,
    val synthesisText: String,
    val claims: List<WebResearchClaim> = emptyList(),
    val discrepanciesFound: List<String> = emptyList()
)
