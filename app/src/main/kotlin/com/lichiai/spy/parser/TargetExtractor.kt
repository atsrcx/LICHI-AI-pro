package com.lichiai.spy.parser

import java.util.Locale

object TargetExtractor {
    // International E.164 and local phone pattern with optional spaces/dashes
    private val PHONE_REGEX = Regex("""(?:\+?\d{1,4}[-.\s]?)?\(?\d{2,5}\)?[-.\s]?\d{3,5}[-.\s]?\d{2,6}""")
    private val EMAIL_REGEX = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,64}""")
    private val USERNAME_REGEX = Regex("""@([A-Za-z0-9_.-]+)""")

    data class ExtractedTarget(
        val cleanTarget: String,
        val detectedType: TargetType
    )

    enum class TargetType {
        PHONE, EMAIL, USERNAME, URL, SEARCH_QUERY
    }

    fun extract(payload: String): ExtractedTarget {
        val raw = payload.trim()
        
        // 1. Direct Email Check
        val emailMatch = EMAIL_REGEX.find(raw)
        if (emailMatch != null && (raw.contains("@") && raw.contains("."))) {
            return ExtractedTarget(emailMatch.value.trim().lowercase(Locale.ROOT), TargetType.EMAIL)
        }

        // 2. Direct Phone Number Check
        val phoneMatch = PHONE_REGEX.find(raw)
        if (phoneMatch != null) {
            val candidate = phoneMatch.value.trim()
            val digitCount = candidate.count { it.isDigit() }
            if (digitCount in 7..15) {
                // Normalize by stripping unnecessary brackets, spaces, and dashes, preserving leading '+'
                val normalizedPhone = if (candidate.startsWith("+")) {
                    "+" + candidate.filter { it.isDigit() }
                } else {
                    candidate.filter { it.isDigit() }
                }
                return ExtractedTarget(normalizedPhone, TargetType.PHONE)
            }
        }

        // 3. Username Handle Check (@handle)
        val handleMatch = USERNAME_REGEX.find(raw)
        if (handleMatch != null) {
            return ExtractedTarget(handleMatch.groupValues[1].trim(), TargetType.USERNAME)
        }

        // 4. URL Check
        if (raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true)) {
            val urlToken = raw.split("\\s+".toRegex()).firstOrNull() ?: raw
            return ExtractedTarget(urlToken.trim(), TargetType.URL)
        }

        // 5. Fallback to clean alphanumeric target or search phrase
        val tokens = raw.split("\\s+".toRegex()).filterNot { token ->
            token.lowercase(Locale.ROOT) in listOf("instagram", "insta", "twitter", "x", "youtube", "yt", "github", "linkedin", "profile", "lookup", "check", "find", "search", "details", "info")
        }
        val clean = tokens.joinToString(" ").trim().removePrefix("@")
        return ExtractedTarget(clean.ifBlank { raw }, TargetType.SEARCH_QUERY)
    }
}
