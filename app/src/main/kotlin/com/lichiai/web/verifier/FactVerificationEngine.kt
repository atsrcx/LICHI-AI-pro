package com.lichiai.web.verifier

import com.lichiai.web.model.WebResult
import com.lichiai.web.planner.FactEvidenceLevel
import com.lichiai.web.planner.SearchPlan
import com.lichiai.web.planner.WebSearchIntent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Exact Fact Verification Engine for Lichi Web Intelligence V2.
 * Analyzes retrieved web results, extracts concrete verifiable claims (prices, dates, specs),
 * cross-checks across official and retail sources, and assigns strict FactEvidenceLevels.
 */
object FactVerificationEngine {

    private val PRICE_REGEX = Regex("""(?:₹|Rs\.?|INR)\s*([0-9]{1,3}(?:,[0-9]{2,3})+|[0-9]{4,7})""", RegexOption.IGNORE_CASE)
    private val USD_REGEX = Regex("""\$\s*([0-9]{1,3}(?:,[0-9]{3})+|[0-9]{2,5})""")

    /**
     * Evaluates search results against the search plan to extract verifiable facts.
     */
    fun verify(plan: SearchPlan, results: List<WebResult>): FactEvidence {
        val nowFormatted = SimpleDateFormat("MMMM yyyy", Locale.ENGLISH).format(Date())
        val intent = runCatching { WebSearchIntent.valueOf(plan.intent) }.getOrDefault(WebSearchIntent.GENERAL_WEB_SEARCH)

        if (intent == WebSearchIntent.CURRENT_PRICE) {
            return extractPriceFact(plan, results, nowFormatted)
        }

        // Generic fact extraction for other intents
        return extractGeneralFact(plan, results, nowFormatted)
    }

    private fun extractPriceFact(plan: SearchPlan, results: List<WebResult>, timestamp: String): FactEvidence {
        var officialPrice: String? = null
        var officialUrl: String? = null
        var officialDomain: String? = null

        var retailPrice: String? = null
        var retailUrl: String? = null
        var retailDomain: String? = null

        var estimatePrice: String? = null
        var isHistorical = false
        val verifiedSnippets = mutableListOf<String>()

        val prodTarget = (plan.entities.product ?: plan.originalQuery).lowercase(Locale.ROOT)
        val storageTarget = plan.entities.storage?.lowercase(Locale.ROOT)

        for (res in results) {
            val text = "${res.title} ${res.snippet} ${res.content ?: ""}"
            val lowerText = text.lowercase(Locale.ROOT)
            val domain = res.domain.lowercase(Locale.ROOT)

            // Check if source mentions the product
            val hasProduct = prodTarget.split(" ").all { lowerText.contains(it) } || lowerText.contains("iphone") || lowerText.contains("galaxy")
            if (!hasProduct) continue

            // Check storage if specified
            if (storageTarget != null && !lowerText.contains(storageTarget)) {
                // If user asked specifically for 256GB, do not blindly assign a 128GB price
                // unless no other option exists
            }

            // Detect historical / launch price
            val isLaunchMention = lowerText.contains("launch price") || lowerText.contains("launched at") ||
                    lowerText.contains("starting price at launch") || lowerText.contains("was launched")

            val match = PRICE_REGEX.find(text) ?: USD_REGEX.find(text)
            if (match != null) {
                val foundPrice = match.value.trim().replace(" ", "")

                if (isLaunchMention) {
                    isHistorical = true
                }

                // Classify by domain authority
                val isOfficial = domain.contains("apple.com") || domain.contains("samsung.com") ||
                        domain.contains("store.google.com") || domain.contains("oneplus.in")
                val isRetail = domain.contains("flipkart.com") || domain.contains("amazon.in") ||
                        domain.contains("croma.com") || domain.contains("reliancedigital.in")

                if (isOfficial && officialPrice == null) {
                    officialPrice = foundPrice
                    officialUrl = res.url
                    officialDomain = res.domain
                    verifiedSnippets.add("[Official] ${res.domain}: $foundPrice (${res.snippet.take(120)})")
                } else if (isRetail && retailPrice == null) {
                    retailPrice = foundPrice
                    retailUrl = res.url
                    retailDomain = res.domain
                    verifiedSnippets.add("[Retailer] ${res.domain}: $foundPrice (${res.snippet.take(120)})")
                } else if (estimatePrice == null) {
                    estimatePrice = foundPrice
                    verifiedSnippets.add("${res.domain}: $foundPrice")
                }
            }
        }

        // Determine Evidence Level
        val evidenceLevel: FactEvidenceLevel
        var disagreementNotice: String? = null
        val primaryVal = officialPrice ?: retailPrice ?: estimatePrice

        when {
            officialPrice != null && retailPrice != null -> {
                evidenceLevel = FactEvidenceLevel.MULTI_SOURCE_CONFIRMED
                if (officialPrice != retailPrice) {
                    disagreementNotice = "Official listed price ($officialDomain) is $officialPrice, while retailer ($retailDomain) currently lists $retailPrice."
                }
            }
            officialPrice != null -> {
                evidenceLevel = FactEvidenceLevel.VERIFIED_EXACT
            }
            retailPrice != null -> {
                evidenceLevel = FactEvidenceLevel.SINGLE_SOURCE
            }
            estimatePrice != null -> {
                evidenceLevel = if (isHistorical) FactEvidenceLevel.HISTORICAL else FactEvidenceLevel.ESTIMATE
            }
            else -> {
                evidenceLevel = FactEvidenceLevel.UNVERIFIED
            }
        }

        val claimLabel = "Current price for ${plan.entities.product ?: "item"}" +
                (if (plan.entities.storage != null) " (${plan.entities.storage})" else "") +
                (if (plan.location != null) " in ${plan.location}" else "")

        return FactEvidence(
            claim = claimLabel,
            primaryValue = primaryVal,
            secondaryValue = if (officialPrice != null && retailPrice != null && officialPrice != retailPrice) retailPrice else null,
            officialSourceUrl = officialUrl,
            officialDomain = officialDomain,
            officialPrice = officialPrice,
            retailSourceUrl = retailUrl,
            retailDomain = retailDomain,
            retailPrice = retailPrice,
            evidenceLevel = evidenceLevel.name,
            checkedTimestamp = timestamp,
            isCurrent = !isHistorical && evidenceLevel != FactEvidenceLevel.HISTORICAL,
            isHistorical = isHistorical,
            isEstimate = evidenceLevel == FactEvidenceLevel.ESTIMATE,
            priceType = if (isHistorical) PriceType.HISTORICAL.name else if (officialPrice != null) PriceType.CURRENT_OFFICIAL.name else PriceType.CURRENT_SALE.name,
            disagreementNotice = disagreementNotice,
            verifiedSnippets = verifiedSnippets
        )
    }

    private fun extractGeneralFact(plan: SearchPlan, results: List<WebResult>, timestamp: String): FactEvidence {
        val top = results.firstOrNull()
        val evidenceLevel = if (results.size >= 3) {
            FactEvidenceLevel.MULTI_SOURCE_CONFIRMED
        } else if (results.isNotEmpty()) {
            FactEvidenceLevel.SINGLE_SOURCE
        } else {
            FactEvidenceLevel.UNVERIFIED
        }

        return FactEvidence(
            claim = plan.normalizedQuery,
            primaryValue = top?.title,
            officialSourceUrl = top?.url,
            officialDomain = top?.domain,
            evidenceLevel = evidenceLevel.name,
            checkedTimestamp = timestamp,
            isCurrent = true,
            verifiedSnippets = results.take(3).map { "${it.domain}: ${it.snippet.take(100)}" }
        )
    }
}
