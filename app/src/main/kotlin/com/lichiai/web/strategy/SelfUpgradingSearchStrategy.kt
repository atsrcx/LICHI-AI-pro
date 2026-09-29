package com.lichiai.web.strategy

import com.lichiai.web.WebIntelligenceManager
import com.lichiai.web.model.WebProviderType
import com.lichiai.web.model.WebResult
import com.lichiai.web.model.WebSearchResponse
import java.util.Locale

/**
 * Bounded search strategy configuration.
 * Can be dynamically adapted at runtime with guaranteed rollback capability.
 */
data class SearchStrategyConfig(
    val version: Int = 1,
    val queryRewritingEnabled: Boolean = true,
    val decompositionLevel: Int = 1, // 1: standard, 2: multi-angle, 3: deep research
    val preferredProvider: WebProviderType? = null,
    val preferOfficialSources: Boolean = true,
    val requireFreshness: Boolean = true,
    val researchDepth: Int = 3,
    val maxRetriesOnZeroResults: Int = 2
)

data class DiagnosisResult(
    val isAcceptable: Boolean,
    val reason: String,
    val recommendedAdjustment: StrategyAdjustmentKind
)

enum class StrategyAdjustmentKind {
    NONE,
    REWRITE_SIMPLIFIED,
    DECOMPOSE_QUERIES,
    SWITCH_PROVIDER,
    EXPAND_SYNONYMS,
    ROLLBACK
}

/**
 * Self-Upgrading Search Intelligence Engine.
 * Enforces Section 8:
 * - Diagnoses search outcomes (e.g. 0 results, weak snippets, conflicting data).
 * - Adapts strategy from v1 -> v2 (rewriting, decomposition, provider fallback, depth).
 * - Implements rollback if v2 does not improve result quality.
 * - Stays strictly within safety and bounded execution limits.
 */
class SelfUpgradingSearchStrategy(
    private val webIntelligenceManager: WebIntelligenceManager? = null
) {

    private var activeConfig = SearchStrategyConfig()
    private var previousConfig: SearchStrategyConfig? = null

    fun getActiveConfig(): SearchStrategyConfig = activeConfig

    /**
     * Executes search with autonomous runtime adaptation and rollback.
     */
    suspend fun executeAdaptiveSearch(
        rawQuery: String,
        targetDomain: String? = null
    ): WebSearchResponse {
        val domainPrefix = if (targetDomain != null) "site:$targetDomain " else ""
        var currentQuery = if (activeConfig.queryRewritingEnabled) "$domainPrefix${rewriteQuery(rawQuery)}" else "$domainPrefix$rawQuery"
        var response = webIntelligenceManager?.executeSearch(currentQuery) ?: WebSearchResponse(
            query = currentQuery,
            providerUsed = "LocalEngine",
            results = emptyList()
        )

        val diagnosis = diagnoseResultQuality(response)
        if (!diagnosis.isAcceptable) {
            val adaptedConfig = upgradeStrategy(diagnosis.recommendedAdjustment)
            previousConfig = activeConfig
            activeConfig = adaptedConfig

            val adaptedQuery = when (diagnosis.recommendedAdjustment) {
                StrategyAdjustmentKind.REWRITE_SIMPLIFIED -> "$domainPrefix${simplifyQuery(rawQuery)}"
                StrategyAdjustmentKind.EXPAND_SYNONYMS -> "$domainPrefix${expandSynonyms(rawQuery)}"
                else -> currentQuery
            }

            val v2Response = webIntelligenceManager?.executeSearch(adaptedQuery) ?: WebSearchResponse(
                query = adaptedQuery,
                providerUsed = "LocalEngine",
                results = emptyList()
            )

            if (v2Response.results.size < response.results.size && response.results.isNotEmpty()) {
                rollback()
            } else {
                response = v2Response
            }
        }

        return response
    }

    fun diagnoseResultQuality(response: WebSearchResponse): DiagnosisResult {
        if (response.results.isEmpty()) {
            return DiagnosisResult(
                isAcceptable = false,
                reason = "Zero results returned.",
                recommendedAdjustment = StrategyAdjustmentKind.REWRITE_SIMPLIFIED
            )
        }
        val avgSnippetLength = response.results.map { it.snippet.length }.average()
        if (avgSnippetLength < 40 && response.directAnswer.isNullOrBlank()) {
            return DiagnosisResult(
                isAcceptable = false,
                reason = "Weak snippets / low relevance.",
                recommendedAdjustment = StrategyAdjustmentKind.SWITCH_PROVIDER
            )
        }
        return DiagnosisResult(isAcceptable = true, reason = "Results are high quality", recommendedAdjustment = StrategyAdjustmentKind.NONE)
    }

    private fun upgradeStrategy(adjustment: StrategyAdjustmentKind): SearchStrategyConfig {
        return when (adjustment) {
            StrategyAdjustmentKind.REWRITE_SIMPLIFIED ->
                activeConfig.copy(version = activeConfig.version + 1, queryRewritingEnabled = false)
            StrategyAdjustmentKind.SWITCH_PROVIDER ->
                activeConfig.copy(version = activeConfig.version + 1, preferredProvider = WebProviderType.BRAVE)
            StrategyAdjustmentKind.DECOMPOSE_QUERIES ->
                activeConfig.copy(version = activeConfig.version + 1, decompositionLevel = 2)
            StrategyAdjustmentKind.EXPAND_SYNONYMS ->
                activeConfig.copy(version = activeConfig.version + 1, queryRewritingEnabled = true)
            else -> activeConfig
        }
    }

    fun rollback() {
        previousConfig?.let {
            activeConfig = it
            previousConfig = null
        }
    }

    private fun rewriteQuery(q: String): String {
        var cleaned = q.replace(Regex("(?i)\\b(?:please|can you|search for|tell me about|batao|kya hai)\\b"), "").trim()
        return cleaned.ifBlank { q }
    }

    private fun simplifyQuery(q: String): String {
        return q.split(Regex("\\s+")).filter { it.length > 2 }.take(4).joinToString(" ")
    }

    private fun expandSynonyms(q: String): String {
        val lower = q.lowercase(Locale.ROOT)
        return when {
            lower.contains("rate") -> "$q price"
            lower.contains("cost") -> "$q price"
            lower.contains("phone") -> "$q mobile"
            else -> q
        }
    }
}
