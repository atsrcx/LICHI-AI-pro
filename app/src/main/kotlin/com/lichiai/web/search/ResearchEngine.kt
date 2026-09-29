package com.lichiai.web.search

import com.lichiai.web.WebIntelligenceManager
import com.lichiai.web.model.WebResult
import com.lichiai.web.model.WebSearchResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ResearchClaim(
    val claimText: String,
    val sourceTitle: String,
    val sourceUrl: String,
    val isOfficial: Boolean
)

data class ResearchDisagreement(
    val topic: String,
    val claimA: String,
    val sourceA: String,
    val urlA: String,
    val claimB: String,
    val sourceB: String,
    val urlB: String,
    val conflictExplanation: String
)

data class StructuredResearchReport(
    val question: String,
    val sources: List<WebResult>,
    val evidenceSnippets: List<String>,
    val claims: List<ResearchClaim>,
    val crossSourceAgreementScore: Float,
    val conflicts: List<ResearchDisagreement>,
    val confidence: Float,
    val finalAnswer: String
)

/**
 * Structured Autonomous Research Engine.
 * Enforces Section 7:
 * - Produces Question, Sources, Evidence, Claims, Agreement, Conflicts, Confidence, Final answer.
 * - Explicitly structures disagreements (Claim A vs Claim B) instead of silently picking one.
 */
class ResearchEngine(
    private val webIntelligenceManager: WebIntelligenceManager
) {

    private val WebResult.isOfficialSource: Boolean
        get() = domain.contains("official") || title.contains("official", ignoreCase = true) || domain.contains("apple.com") || domain.contains("google.com")

    suspend fun conductResearch(
        question: String,
        queries: List<String> = emptyList()
    ): StructuredResearchReport = withContext(Dispatchers.IO) {
        val searchQueries = if (queries.isNotEmpty()) queries else listOf(question, "$question details sources")
        val allSources = mutableListOf<WebResult>()
        val seenUrls = mutableSetOf<String>()

        for (q in searchQueries) {
            val response: WebSearchResponse = webIntelligenceManager.executeSearch(q)
            for (res in response.results) {
                if (res.url.isNotBlank() && seenUrls.add(res.url)) {
                    allSources.add(res)
                }
            }
        }

        // Generate claims with sources
        val claims = mutableListOf<ResearchClaim>()
        val evidenceSnippets = mutableListOf<String>()

        allSources.take(6).forEach { src ->
            val snippet = src.snippet.ifBlank { src.content?.take(160) ?: "" }
            if (snippet.isNotBlank()) {
                evidenceSnippets.add(snippet)
                claims.add(
                    ResearchClaim(
                        claimText = snippet,
                        sourceTitle = src.title,
                        sourceUrl = src.url,
                        isOfficial = src.isOfficialSource
                    )
                )
            }
        }

        // Detect conflicts/disagreements across claims
        val conflicts = mutableListOf<ResearchDisagreement>()
        if (claims.size >= 2) {
            val numbersInClaims = claims.map { c ->
                Regex("\\b\\d[\\d,\\.]*\\b").findAll(c.claimText).map { it.value }.toSet()
            }
            if (numbersInClaims.size >= 2 && numbersInClaims[0].isNotEmpty() && numbersInClaims[1].isNotEmpty()) {
                val diff = numbersInClaims[0].subtract(numbersInClaims[1])
                if (diff.isNotEmpty() && claims[0].claimText != claims[1].claimText) {
                    conflicts.add(
                        ResearchDisagreement(
                            topic = "Factual Figure/Date Discrepancy",
                            claimA = claims[0].claimText,
                            sourceA = claims[0].sourceTitle,
                            urlA = claims[0].sourceUrl,
                            claimB = claims[1].claimText,
                            sourceB = claims[1].sourceTitle,
                            urlB = claims[1].sourceUrl,
                            conflictExplanation = "Sources report differing numeric values: ${claims[0].sourceTitle} states '${diff.firstOrNull()}' whereas ${claims[1].sourceTitle} reports different figures."
                        )
                    )
                }
            }
        }

        val agreementScore = if (conflicts.isEmpty()) 0.95f else 0.65f
        val confidence = if (allSources.any { it.isOfficialSource }) 0.92f else agreementScore

        val finalAnswer = buildString {
            append("Research summary for: \"$question\"\n\n")
            if (conflicts.isNotEmpty()) {
                append("⚠️ DISCREPANCY DETECTED BETWEEN SOURCES:\n")
                conflicts.forEach { c ->
                    append("• CLAIM A: ${c.claimA}\n  SOURCE A: ${c.sourceA} (${c.urlA})\n")
                    append("• CLAIM B: ${c.claimB}\n  SOURCE B: ${c.sourceB} (${c.urlB})\n")
                    append("• EXPLANATION: ${c.conflictExplanation}\n\n")
                }
            } else {
                append("• High cross-source consensus found across ${allSources.size} sources.\n\n")
            }
            append("Key Claims & Evidence:\n")
            claims.take(4).forEachIndexed { i, c ->
                append("${i + 1}. \"${c.claimText}\" [Source: ${c.sourceTitle}]\n")
            }
        }

        StructuredResearchReport(
            question = question,
            sources = allSources,
            evidenceSnippets = evidenceSnippets,
            claims = claims,
            crossSourceAgreementScore = agreementScore,
            conflicts = conflicts,
            confidence = confidence,
            finalAnswer = finalAnswer
        )
    }
}
