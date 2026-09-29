package com.lichiai.web.search

import com.lichiai.web.WebIntelligenceManager
import com.lichiai.web.model.WebResult
import com.lichiai.web.model.WebSearchResponse
import com.lichiai.web.planner.QueryPlanner
import com.lichiai.web.verifier.FactEvidence
import com.lichiai.web.verifier.FactVerificationEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.util.Locale

data class DeepSearchReport(
    val originalQuery: String,
    val searchQueriesExecuted: List<String>,
    val verifiedSources: List<WebResult>,
    val extractedEvidence: Map<String, String>,
    val factEvidence: FactEvidence?,
    val synthesisText: String,
    val hasDisagreement: Boolean,
    val disagreementNotice: String? = null,
    val isBudgetExhausted: Boolean = false
)

/**
 * Autonomous Multi-Step DeepSearch Pipeline.
 * Enforces Section 6:
 * QUERY -> UNDERSTANDING -> SEARCH PLAN -> MULTI-QUERY SEARCH ->
 * RESULT RANKING -> DEDUPLICATION -> EXTRACTION -> CROSS-SOURCE COMPARISON ->
 * VERIFICATION -> FINAL SYNTHESIS.
 */
class DeepSearchEngine(
    private val webIntelligenceManager: WebIntelligenceManager
) {

    private val WebResult.isOfficialSource: Boolean
        get() = domain.contains("official") || title.contains("official", ignoreCase = true) || domain.contains("apple.com") || domain.contains("google.com")

    suspend fun executeDeepSearch(
        query: String,
        maxBudgetQueries: Int = 3,
        onStageUpdate: ((stage: String, current: Int, total: Int) -> Unit)? = null
    ): DeepSearchReport = withContext(Dispatchers.IO) {
        // 1. QUERY UNDERSTANDING & PLAN
        onStageUpdate?.invoke("Query understanding & plan formulation", 1, 6)
        val plan = QueryPlanner.plan(query)
        val executedQueries = mutableListOf<String>()
        val aggregatedResults = mutableListOf<WebResult>()
        val seenUrls = mutableSetOf<String>()

        val queriesToRun = if (plan.queries.isNotEmpty()) {
            plan.queries.map { it.query }.take(maxBudgetQueries)
        } else {
            listOf(query, "$query official site", "$query latest review")
        }.take(maxBudgetQueries)

        // 2. MULTI-QUERY SEARCH ACROSS PROVIDERS
        onStageUpdate?.invoke("Multi-query search execution", 2, 6)
        for ((idx, q) in queriesToRun.withIndex()) {
            executedQueries.add(q)
            val resp: WebSearchResponse = webIntelligenceManager.executeSearch(q)
            for (res in resp.results) {
                val cleanUrl = sanitize(res.url)
                if (cleanUrl.isNotBlank() && seenUrls.add(cleanUrl)) {
                    aggregatedResults.add(res.copy(url = cleanUrl))
                }
            }
        }

        // 3. RESULT RANKING & SOURCE DEDUPLICATION
        onStageUpdate?.invoke("Ranking & source deduplication", 3, 6)
        val rankedSources = aggregatedResults.sortedWith(
            compareByDescending<WebResult> { it.isOfficialSource }
                .thenByDescending { it.relevance ?: 0.5f }
                .thenByDescending { it.snippet.length }
        ).take(6)

        // 4. CONTENT EXTRACTION FROM TOP SOURCES
        onStageUpdate?.invoke("Deep content extraction", 4, 6)
        val topUrls = rankedSources.take(3).map { it.url }
        val extractedContent = if (topUrls.isNotEmpty()) {
            webIntelligenceManager.extractContent(topUrls)
        } else {
            emptyMap()
        }

        // 5. CROSS-SOURCE COMPARISON & FACT VERIFICATION
        onStageUpdate?.invoke("Fact verification & contradiction detection", 5, 6)
        val evidence = FactVerificationEngine.verify(plan, rankedSources)

        // 6. FINAL SYNTHESIS
        onStageUpdate?.invoke("Synthesizing verified findings", 6, 6)
        val synthesis = buildString {
            append("DeepSearch findings for \"$query\":\n\n")
            if (evidence.primaryValue != null) {
                append("• Primary Verified Finding: ${evidence.primaryValue}\n")
                if (evidence.officialPrice != null) append("  - Official Listed: ${evidence.officialPrice}\n")
                if (evidence.retailPrice != null) append("  - Retail/Current: ${evidence.retailPrice}\n")
            }
            if (evidence.disagreementNotice != null) {
                append("• Source Disagreement: ${evidence.disagreementNotice}\n")
            }
            append("\nVerified Sources Consulted (${rankedSources.size}):\n")
            rankedSources.take(5).forEachIndexed { index, webResult ->
                append("  [${index + 1}] ${webResult.title} (${webResult.domain})\n      ${webResult.url}\n")
            }
        }

        DeepSearchReport(
            originalQuery = query,
            searchQueriesExecuted = executedQueries,
            verifiedSources = rankedSources,
            extractedEvidence = extractedContent,
            factEvidence = evidence,
            synthesisText = synthesis,
            hasDisagreement = evidence.disagreementNotice != null,
            disagreementNotice = evidence.disagreementNotice,
            isBudgetExhausted = executedQueries.size >= maxBudgetQueries
        )
    }

    private fun sanitize(url: String): String {
        return runCatching {
            val uri = URI(url)
            val query = uri.query?.split("&")?.filterNot { it.startsWith("utm_") || it.startsWith("ref=") }?.joinToString("&")
            val cleanQuery = if (query.isNullOrEmpty()) "" else "?$query"
            "${uri.scheme}://${uri.host}${uri.path ?: ""}$cleanQuery"
        }.getOrDefault(url)
    }
}
