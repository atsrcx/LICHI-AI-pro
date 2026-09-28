package com.lichiai.browser.api

import java.util.Locale

/**
 * Deterministic Address Bar URL & Search Resolver.
 * Follows the Address Bar Contract:
 * User enters text -> Resolver -> if valid URL navigate directly, else search using configured engine.
 * Requires 0 LLM.
 */
object BrowserUrlResolver {

    sealed class ResolvedTarget {
        data class DirectUrl(val url: String) : ResolvedTarget()
        data class SearchQuery(val query: String, val explicitEngine: String? = null) : ResolvedTarget()
    }

    private val COMMON_TLDS = setOf(
        "com", "org", "net", "edu", "gov", "mil", "int",
        "in", "io", "ai", "co", "me", "app", "dev", "tech",
        "info", "biz", "uk", "us", "ca", "de", "jp", "fr", "au",
        "ru", "ch", "it", "nl", "se", "no", "es", "br", "xyz"
    )

    fun resolve(input: String): ResolvedTarget {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) {
            return ResolvedTarget.DirectUrl("about:blank")
        }

        // Direct schema check: http://, https://, about:
        if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true) ||
            trimmed.startsWith("about:", ignoreCase = true)) {
            return ResolvedTarget.DirectUrl(trimmed)
        }

        // Check if single token (no spaces)
        if (!trimmed.contains(" ") && !trimmed.contains("\t") && !trimmed.contains("\n")) {
            // Localhost / IP address
            if (trimmed.startsWith("localhost", ignoreCase = true) ||
                trimmed.startsWith("127.0.0.1") ||
                trimmed.matches(Regex("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}(:\\d+)?.*$"))) {
                return ResolvedTarget.DirectUrl("http://$trimmed")
            }

            // Standard domain with dot: e.g. "example.com", "google.com", "youtube.com"
            if (trimmed.contains(".")) {
                val hostPart = trimmed.split("/")[0].split(":")[0]
                val dotIndex = hostPart.lastIndexOf('.')
                if (dotIndex > 0 && dotIndex < hostPart.length - 1) {
                    val tld = hostPart.substring(dotIndex + 1).lowercase(Locale.ROOT)
                    if (COMMON_TLDS.contains(tld) || (tld.length in 2..6 && tld.all { it.isLetter() })) {
                        return ResolvedTarget.DirectUrl("https://$trimmed")
                    }
                }
            }
        }

        // Otherwise: Treat as search query
        return ResolvedTarget.SearchQuery(trimmed)
    }
}
