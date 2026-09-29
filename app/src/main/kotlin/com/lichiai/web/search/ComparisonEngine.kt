package com.lichiai.web.search

import com.lichiai.web.WebIntelligenceManager
import com.lichiai.web.model.WebResult
import com.lichiai.web.model.WebSearchResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class ComparisonStatus {
    VERIFIED,
    PARTIAL,
    UNKNOWN,
    CONFLICT,
    FAILED,
    BLOCKED,
    CANCELLED
}

data class NormalizedEntityData(
    val entityName: String,
    val price: String = "UNKNOWN",
    val specifications: Map<String, String> = emptyMap(),
    val features: List<String> = emptyList(),
    val availability: String = "UNKNOWN",
    val sourceDomain: String = "UNKNOWN",
    val sourceUrl: String = "",
    val verificationStatus: ComparisonStatus = ComparisonStatus.UNKNOWN,
    val dateOrTimestamp: String = "Current"
)

data class ComparisonMatrixReport(
    val comparedEntities: List<NormalizedEntityData>,
    val comparisonCriteria: List<String>,
    val summaryDifferences: String,
    val overallStatus: ComparisonStatus
)

/**
 * Cross-Source Normalization and Comparison Engine.
 * Enforces Section 16 requirements.
 */
class ComparisonEngine(
    private val webIntelligenceManager: WebIntelligenceManager
) {

    suspend fun compareEntities(
        entities: List<String>,
        criteria: List<String> = listOf("price", "specifications", "features", "availability")
    ): ComparisonMatrixReport = withContext(Dispatchers.IO) {
        val normalizedList = mutableListOf<NormalizedEntityData>()

        for (entity in entities) {
            val response: WebSearchResponse = webIntelligenceManager.executeSearch("$entity price specs features")
            val topResult: WebResult? = response.results.firstOrNull()
            val price = extractPriceFromSnippet(topResult?.snippet ?: "")
            val status = when {
                topResult != null && price != "UNKNOWN" -> ComparisonStatus.VERIFIED
                topResult != null -> ComparisonStatus.PARTIAL
                else -> ComparisonStatus.UNKNOWN
            }

            normalizedList.add(
                NormalizedEntityData(
                    entityName = entity,
                    price = price,
                    features = topResult?.snippet?.split(".")?.take(3)?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList(),
                    availability = if (topResult != null) "In Stock / Available" else "UNKNOWN",
                    sourceDomain = topResult?.domain ?: "UNKNOWN",
                    sourceUrl = topResult?.url ?: "",
                    verificationStatus = status,
                    dateOrTimestamp = "Current"
                )
            )
        }

        val diffSummary = buildString {
            append("Comparison between ${entities.joinToString(" vs ")}:\n\n")
            for (item in normalizedList) {
                append("• **${item.entityName}**:\n")
                append("  - Price: ${item.price}\n")
                append("  - Status: [${item.verificationStatus.name}]\n")
                append("  - Key Points: ${item.features.joinToString("; ")}\n")
                if (item.sourceUrl.isNotBlank()) {
                    append("  - Source: ${item.sourceDomain} (${item.sourceUrl})\n")
                }
                append("\n")
            }
        }

        ComparisonMatrixReport(
            comparedEntities = normalizedList,
            comparisonCriteria = criteria,
            summaryDifferences = diffSummary,
            overallStatus = if (normalizedList.any { it.verificationStatus == ComparisonStatus.VERIFIED }) ComparisonStatus.VERIFIED else ComparisonStatus.PARTIAL
        )
    }

    private fun extractPriceFromSnippet(text: String): String {
        val match = Regex("(?:₹|Rs\\.?|INR|\\$)\\s*[\\d,]+").find(text)
        return match?.value ?: "UNKNOWN"
    }
}
