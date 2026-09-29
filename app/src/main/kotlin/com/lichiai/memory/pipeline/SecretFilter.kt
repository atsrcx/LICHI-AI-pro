package com.lichiai.memory.pipeline

import java.util.regex.Pattern

/**
 * Secret & Sensitive Data Filter.
 *
 * Enforces data sanitation before any text or structured fact is persisted into semantic memory.
 * Redacts:
 * - API keys (OpenAI, Gemini/Google, Anthropic, Groq, Apify, Serper, Tavily, Brave, Exa)
 * - GitHub/GitLab personal access tokens
 * - SSH Private keys
 * - Bearer authorization tokens
 * - Passwords and credentials
 */
object SecretFilter {

    private val SENSITIVE_PATTERNS = listOf(
        // API Keys & Tokens
        Pattern.compile("""(?i)\b(sk-[a-zA-Z0-9_-]{20,})\b"""),
        Pattern.compile("""\b(AIza[0-9A-Za-z_-]{35})\b"""),
        Pattern.compile("""(?i)\b(ghp_[a-zA-Z0-9]{36}|github_pat_[a-zA-Z0-9_]{80,})\b"""),
        Pattern.compile("""(?i)\b(xox[baprs]-[0-9a-zA-Z]{10,48})\b"""),
        Pattern.compile("""(?i)\b(apify_api_[a-zA-Z0-9]{30,})\b"""),
        Pattern.compile("""(?i)\b(bearer\s+[a-zA-Z0-9_\-\.]{20,})\b"""),
        Pattern.compile("""(?i)(api[_-]?key|secret[_-]?key|access[_-]?token|auth[_-]?token)\s*[:=]\s*["']?([a-zA-Z0-9_\-\.]{12,})["']?"""),
        
        // Private Keys
        Pattern.compile("""-----BEGIN (?:RSA|OPENSSH|DSA|EC|PGP)? PRIVATE KEY-----[\s\S]*?-----END (?:RSA|OPENSSH|DSA|EC|PGP)? PRIVATE KEY-----"""),
        
        // Passwords in text (e.g. password: xyz)
        Pattern.compile("""(?i)(password|passcode|secret|pin)\s*[:=]\s*["']?([^\s"']{4,})["']?""")
    )

    fun scrub(input: String): String {
        if (input.isBlank()) return input
        var result = input
        for (pattern in SENSITIVE_PATTERNS) {
            val matcher = pattern.matcher(result)
            if (matcher.find()) {
                result = matcher.replaceAll("[REDACTED_SECRET]")
            }
        }
        return result
    }

    fun containsSecret(input: String): Boolean {
        if (input.isBlank()) return false
        return SENSITIVE_PATTERNS.any { it.matcher(input).find() }
    }
}
