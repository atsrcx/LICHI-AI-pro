package com.lichiai.web.search

import com.lichiai.web.WebIntelligenceManager
import com.lichiai.web.model.WebResult
import com.lichiai.web.model.WebSearchResponse
import java.util.Locale

data class DorkOperators(
    val site: String? = null,
    val inTitle: String? = null,
    val inUrl: String? = null,
    val fileType: String? = null,
    val exactPhrase: String? = null,
    val exclusions: List<String> = emptyList(),
    val terms: List<String> = emptyList()
)

data class DorkValidationResult(
    val isValid: Boolean,
    val reason: String? = null
)

/**
 * Public-Web Advanced DorkSearch Engine.
 * Enforces Section 4 guidelines:
 * - Supports legitimate search operators: site:, intitle:, inurl:, filetype:, exact phrases, OR, exclusions.
 * - Strictly rejects credential harvesting, exploit finding, authorization bypass, and malware queries.
 */
class DorkSearchEngine(
    private val webIntelligenceManager: WebIntelligenceManager? = null
) {

    companion object {
        private val DISALLOWED_PATTERNS = listOf(
            "password", "passwd", "credential", "apikey", "secret", "private_key",
            "id_rsa", "shadow", "wp-config", ".env", "dump.sql", "database.sql",
            "admin/login", "cpanel", "auth_token", "exploit", "sqli", "xss", "payload"
        )
    }

    /**
     * Validates whether a dork query contains forbidden malicious or credential hunting tokens.
     */
    fun validateDorkSafety(query: String): DorkValidationResult {
        val lower = query.lowercase(Locale.ROOT)
        for (pattern in DISALLOWED_PATTERNS) {
            if (lower.contains(pattern)) {
                return DorkValidationResult(
                    isValid = false,
                    reason = "DorkSearch security policy disallows credential hunting or exploit terms ('$pattern')."
                )
            }
        }
        return DorkValidationResult(isValid = true)
    }

    /**
     * Parses structured dork operators from a raw user prompt or command.
     */
    fun parseDorkOperators(raw: String): DorkOperators {
        var remaining = raw
        var site: String? = null
        var inTitle: String? = null
        var inUrl: String? = null
        var fileType: String? = null
        var exactPhrase: String? = null
        val exclusions = mutableListOf<String>()

        val siteRegex = Regex("(?i)site:(?:\"([^\"]+)\"|([\\w\\.-]+))")
        siteRegex.find(remaining)?.let {
            site = if (it.groupValues[1].isNotEmpty()) it.groupValues[1] else it.groupValues[2]
            remaining = remaining.replace(it.value, " ")
        }

        val inTitleRegex = Regex("(?i)intitle:(?:\"([^\"]+)\"|(\\S+))")
        inTitleRegex.find(remaining)?.let {
            inTitle = if (it.groupValues[1].isNotEmpty()) it.groupValues[1] else it.groupValues[2]
            remaining = remaining.replace(it.value, " ")
        }

        val inUrlRegex = Regex("(?i)inurl:(?:\"([^\"]+)\"|(\\S+))")
        inUrlRegex.find(remaining)?.let {
            inUrl = if (it.groupValues[1].isNotEmpty()) it.groupValues[1] else it.groupValues[2]
            remaining = remaining.replace(it.value, " ")
        }

        val fileTypeRegex = Regex("(?i)filetype:([a-zA-Z0-9]+)")
        fileTypeRegex.find(remaining)?.let {
            fileType = it.groupValues[1]
            remaining = remaining.replace(it.value, " ")
        }

        val exactRegex = Regex("\"([^\"]+)\"")
        exactRegex.find(remaining)?.let {
            exactPhrase = it.groupValues[1]
            remaining = remaining.replace(it.value, " ")
        }

        val exclusionRegex = Regex("-([a-zA-Z0-9]+)")
        exclusionRegex.findAll(remaining).forEach {
            exclusions.add(it.groupValues[1])
            remaining = remaining.replace(it.value, " ")
        }

        val terms = remaining.trim().split(Regex("\\s+")).filter { it.isNotBlank() }

        return DorkOperators(
            site = site,
            inTitle = inTitle,
            inUrl = inUrl,
            fileType = fileType,
            exactPhrase = exactPhrase,
            exclusions = exclusions,
            terms = terms
        )
    }

    /**
     * Reconstructs a clean, normalized public advanced query for web intelligence providers.
     */
    fun buildNormalizedDorkQuery(operators: DorkOperators): String {
        return buildString {
            operators.site?.let { append("site:$it ") }
            operators.inTitle?.let { append("intitle:\"$it\" ") }
            operators.inUrl?.let { append("inurl:\"$it\" ") }
            operators.fileType?.let { append("filetype:$it ") }
            operators.exactPhrase?.let { append("\"$it\" ") }
            operators.exclusions.forEach { append("-$it ") }
            if (operators.terms.isNotEmpty()) {
                append(operators.terms.joinToString(" "))
            }
        }.trim()
    }

    /**
     * Executes the validated advanced dork search across existing search providers.
     */
    suspend fun executeDorkSearch(rawQuery: String): WebSearchResponse {
        val validation = validateDorkSafety(rawQuery)
        if (!validation.isValid) {
            return WebSearchResponse(
                query = rawQuery,
                providerUsed = "DorkSecurityGuard",
                results = emptyList(),
                directAnswer = "⚠️ ${validation.reason}"
            )
        }

        val operators = parseDorkOperators(rawQuery)
        val compiledQuery = buildNormalizedDorkQuery(operators).ifBlank { rawQuery }

        return webIntelligenceManager?.executeSearch(compiledQuery) ?: WebSearchResponse(
            query = compiledQuery,
            providerUsed = "DorkSearchEngine",
            results = emptyList()
        )
    }
}
