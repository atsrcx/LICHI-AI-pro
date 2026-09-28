package com.lichiai.web.planner

import kotlinx.serialization.Serializable

/**
 * High-level intent classification for web intelligence queries.
 */
enum class WebSearchIntent(val displayName: String) {
    GENERAL_WEB_SEARCH("General Search"),
    CURRENT_INFORMATION("Current Information"),
    CURRENT_PRICE("Current Price"),
    PRODUCT_RESEARCH("Product Research"),
    PRODUCT_COMPARISON("Product Comparison"),
    PRICE_COMPARISON("Price Comparison"),
    AVAILABILITY("Availability Check"),
    NEWS("News"),
    LATEST_NEWS("Latest News"),
    TECHNICAL_INFORMATION("Technical Specs"),
    OFFICIAL_DOCUMENTATION("Official Documentation"),
    SPECIFICATIONS("Specifications"),
    REVIEWS("Reviews & Impressions"),
    IMAGE_SEARCH("Image Search"),
    VIDEO_SEARCH("Video Search"),
    WEBSITE_READING("Website Reading"),
    ARTICLE_SUMMARY("Article Summary"),
    DEEP_RESEARCH("Deep Research"),
    FACT_CHECK("Fact Check"),
    SOURCE_VERIFICATION("Source Verification"),
    LOCATION_SPECIFIC_SEARCH("Local Search"),
    LOCAL_SEARCH("Local Search"),
    TIME_SENSITIVE_SEARCH("Time Sensitive Search")
}

/**
 * Grounding evidence levels for extracted facts.
 */
enum class FactEvidenceLevel(val displayName: String) {
    VERIFIED_EXACT("Verified Exact"),
    VERIFIED_APPROXIMATE("Verified Approximate"),
    MULTI_SOURCE_CONFIRMED("Multi-Source Confirmed"),
    SINGLE_SOURCE("Single Source"),
    HISTORICAL("Historical / Outdated"),
    ESTIMATE("Estimate"),
    UNVERIFIED("Unverified")
}

/**
 * Extracted entities from user input (English, Hindi, Hinglish).
 */
@Serializable
data class ExtractedEntities(
    val brand: String? = null,
    val product: String? = null,
    val modelVariant: String? = null, // e.g. "Pro", "Ultra", "Base", "Plus"
    val storage: String? = null,      // e.g. "256GB", "128GB"
    val color: String? = null,
    val location: String? = null,     // e.g. "India", "Delhi"
    val currency: String = "INR",
    val rawTopic: String = ""
)

/**
 * Category of generated sub-query.
 */
enum class QueryCategory {
    PRIMARY,
    OFFICIAL_DOMAIN,
    RETAILER,
    VERIFICATION,
    FRESHNESS,
    SPECIFIC_SITE
}

/**
 * Individual planned search query.
 */
@Serializable
data class PlannedQuery(
    val query: String,
    val category: String, // String representation of QueryCategory for serialization
    val siteConstraint: String? = null
)

/**
 * Structured Search Plan produced by QueryPlanner.
 */
@Serializable
data class SearchPlan(
    val intent: String, // WebSearchIntent.name
    val originalQuery: String,
    val normalizedQuery: String,
    val entities: ExtractedEntities,
    val location: String? = null,
    val freshness: String? = null,
    val requiredEvidence: String? = null,
    val preferredDomains: List<String> = emptyList(),
    val excludedDomains: List<String> = emptyList(),
    val exactFactRequired: Boolean = false,
    val verificationRequired: Boolean = false,
    val multiSourceRequired: Boolean = false,
    val imageRequired: Boolean = false,
    val newsRequired: Boolean = false,
    val deepResearch: Boolean = false,
    val userExplicitSiteConstraint: String? = null,
    val queries: List<PlannedQuery> = emptyList(),
    val isAmbiguous: Boolean = false,
    val ambiguityReason: String? = null
)
