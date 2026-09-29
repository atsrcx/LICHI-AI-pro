package com.lichiai.browser.inspection.security

import kotlinx.serialization.Serializable

@Serializable
data class SecurityAuditReport(
    val isHttps: Boolean,
    val protocol: String = "HTTPS",
    val certificateStatus: String = "VALID",
    val hasMixedContent: Boolean = false,
    val mixedContentUrls: List<String> = emptyList(),
    val totalCookies: Int = 0,
    val secureCookiesCount: Int = 0,
    val cspDetected: Boolean = false,
    val cspDirectives: String = "",
    val suspectedLeakedSecrets: List<String> = emptyList(),
    val thirdPartyTrackers: List<String> = emptyList(),
    val securityHeadersPresent: List<String> = emptyList(),
    val securityHeadersMissing: List<String> = emptyList(),
    val riskScore: String = "LOW"
)

object SecretRedactor {

    private val KEY_PATTERNS = listOf(
        Regex("(?i)(api[_-]?key|apikey|secret|token|password|auth|bearer)\\s*[:=]\\s*['\"]?([a-zA-Z0-9_\\-\\.~]{8,})['\"]?"),
        Regex("(?i)AIza[0-9A-Za-z\\-_]{35}"), // Google API key
        Regex("(?i)sk_live_[0-9a-zA-Z]{24}"), // Stripe live key
        Regex("(?i)AKIA[0-9A-Z]{16}") // AWS Access Key
    )

    fun redact(text: String?): String {
        if (text.isNullOrBlank()) return ""
        var current: String = text
        KEY_PATTERNS.forEach { pattern ->
            current = pattern.replace(current) { match: kotlin.text.MatchResult ->
                val full = match.value
                val prefix = full.take(4)
                val suffix = full.takeLast(3)
                "$prefix****[REDACTED]****$suffix"
            }
        }
        return current
    }

    fun findSuspectedSecrets(text: String?): List<String> {
        if (text.isNullOrBlank()) return emptyList()
        val found = mutableListOf<String>()
        KEY_PATTERNS.forEach { pattern ->
            pattern.findAll(text).forEach { match ->
                found.add("Pattern match: ${match.value.take(6)}... (Redacted)")
            }
        }
        return found
    }
}
