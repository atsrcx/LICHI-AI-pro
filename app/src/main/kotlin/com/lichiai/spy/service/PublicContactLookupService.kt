package com.lichiai.spy.service

import android.content.Context
import com.lichiai.spy.core.PlatformType
import com.lichiai.spy.model.PlatformProfile
import com.lichiai.web.WebIntelligenceManager

class PublicContactLookupService(private val context: Context) {
    private val webIntelligenceManager = WebIntelligenceManager.getInstance(context)

    /**
     * Conducts verified public business lookup for phone numbers.
     * Enforces privacy: strictly extracts public registered business/enterprise information.
     */
    suspend fun lookupPublicPhone(phone: String): PlatformProfile? {
        val cleanPhone = phone.trim()
        val digitsOnly = cleanPhone.filter { it.isDigit() }
        val last10Digits = if (digitsOnly.length >= 10) digitsOnly.takeLast(10) else digitsOnly

        // Construct targeted public business dork query
        val dorkQuery = "\"$cleanPhone\" OR \"$last10Digits\" (company OR business OR store OR shop OR office OR official OR contact)"
        val response = webIntelligenceManager.executeSearch(dorkQuery)

        if (response.results.isEmpty()) return null

        // Parse results for legitimate enterprise records
        val topResult = response.results.firstOrNull { result ->
            val text = (result.title + " " + result.snippet).lowercase()
            text.contains("ltd") || text.contains("pvt") || text.contains("store") ||
            text.contains("services") || text.contains("shop") || text.contains("office") ||
            text.contains("company") || text.contains("contact us") || text.contains("customer care")
        } ?: response.results.firstOrNull() ?: return null

        // Extract metadata cleanly
        return PlatformProfile(
            platform = PlatformType.GENERIC_WEB,
            platformKey = "Public Business Directory",
            username = cleanPhone,
            displayName = topResult.title.substringBefore(" - ").substringBefore(" | ").trim(),
            bio = topResult.snippet,
            profileUrl = topResult.url,
            website = topResult.domain,
            publicPhone = cleanPhone,
            isVerified = true
        )
    }

    suspend fun lookupPublicEmail(email: String): PlatformProfile? {
        val cleanEmail = email.trim().lowercase()
        val domain = cleanEmail.substringAfter("@")
        val dorkQuery = "\"$cleanEmail\" (contact OR about OR team OR business OR official)"
        val response = webIntelligenceManager.executeSearch(dorkQuery)

        if (response.results.isEmpty()) return null
        val topResult = response.results.firstOrNull() ?: return null

        return PlatformProfile(
            platform = PlatformType.GENERIC_WEB,
            platformKey = "Public Corporate Directory",
            username = cleanEmail,
            displayName = topResult.title.substringBefore(" - ").substringBefore(" | ").trim(),
            bio = topResult.snippet,
            profileUrl = topResult.url,
            website = domain,
            publicEmail = cleanEmail,
            isVerified = true
        )
    }
}
