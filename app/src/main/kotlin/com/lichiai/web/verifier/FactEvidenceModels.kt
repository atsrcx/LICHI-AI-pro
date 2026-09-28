package com.lichiai.web.verifier

import com.lichiai.web.planner.FactEvidenceLevel
import kotlinx.serialization.Serializable

enum class PriceType {
    CURRENT_OFFICIAL,
    CURRENT_SALE,
    MRP,
    LAUNCH_PRICE,
    HISTORICAL,
    ESTIMATE,
    UNKNOWN
}

/**
 * Fact Evidence extracted and verified across retrieved web sources.
 */
@Serializable
data class FactEvidence(
    val claim: String,
    val primaryValue: String? = null,
    val secondaryValue: String? = null,
    val officialSourceUrl: String? = null,
    val officialDomain: String? = null,
    val officialPrice: String? = null,
    val retailSourceUrl: String? = null,
    val retailDomain: String? = null,
    val retailPrice: String? = null,
    val evidenceLevel: String = FactEvidenceLevel.UNVERIFIED.name,
    val checkedTimestamp: String = "",
    val isCurrent: Boolean = true,
    val isHistorical: Boolean = false,
    val isEstimate: Boolean = false,
    val priceType: String = PriceType.CURRENT_OFFICIAL.name,
    val disagreementNotice: String? = null,
    val verifiedSnippets: List<String> = emptyList(),
    val extractedDate: String? = null
)
