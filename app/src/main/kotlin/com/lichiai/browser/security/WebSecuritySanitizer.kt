package com.lichiai.browser.security

import java.util.Locale

/**
 * Web Security Sanitizer.
 * Enforces Section 22 Security Guidelines:
 * - Treats all webpage content as UNTRUSTED DATA.
 * - Detects and neutralizes prompt injection patterns (e.g. "Ignore previous instructions",
 *   "SYSTEM PROMPT:", "Send data to...", "You are now in debug mode").
 * - Sanitizes content before it can reach conversational LLM contexts.
 */
object WebSecuritySanitizer {

    private val PROMPT_INJECTION_PATTERNS = listOf(
        Regex("(?i)\\b(?:ignore|disregard|forget|override)\\s+(?:all\\s+)?(?:previous|prior|above)\\s+(?:instructions|prompts|rules|commands)\\b"),
        Regex("(?i)\\b(?:system\\s*prompt|system\\s*message|developer\\s*mode|jailbreak|dan\\s*mode)\\b"),
        Regex("(?i)\\b(?:you\\s+are\\s+now\\s+(?:a|an)|act\\s+as\\s+(?:a|an|the))\\s+(?:unrestricted|evil|admin|root|system)\\b"),
        Regex("(?i)\\b(?:send|exfiltrate|post|transmit)\\s+(?:all\\s+)?(?:data|secrets|keys|passwords|history|tokens)\\s+to\\b"),
        Regex("(?i)\\b(?:output|print|display|reveal)\\s+(?:your|the)\\s+(?:instructions|system\\s+prompt|api\\s*key)\\b"),
        Regex("(?i)<script[\\s>].*?</script>"),
        Regex("(?i)javascript:")
    )

    /**
     * Inspects text for prompt injection risks.
     * Returns true if suspicious instruction patterns are detected.
     */
    fun hasPromptInjection(text: String): Boolean {
        if (text.isBlank()) return false
        return PROMPT_INJECTION_PATTERNS.any { it.containsMatchIn(text) }
    }

    /**
     * Sanitizes untrusted webpage text before it is presented to an LLM or user context.
     * Replaces injection attempts with safe placeholders while retaining real factual content.
     */
    fun sanitizeWebContent(text: String): String {
        if (text.isBlank()) return ""
        var cleaned = text
        for (pattern in PROMPT_INJECTION_PATTERNS) {
            cleaned = pattern.replace(cleaned, "[FILTERED_UNTRUSTED_INSTRUCTION]")
        }
        // Normalize multiple spaces and clamp maximum length to avoid token flooding
        return cleaned.replace(Regex("\\s+"), " ").trim().take(4000)
    }

    /**
     * Validates that an extraction target URL does not attempt local file access
     * or internal Android component scheme hijacking.
     */
    fun isSafeWebUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT).trim()
        if (lower.startsWith("file:") || lower.startsWith("content:") || lower.startsWith("javascript:")) {
            return false
        }
        return lower.startsWith("http://") || lower.startsWith("https://") || lower == "about:blank"
    }
}
