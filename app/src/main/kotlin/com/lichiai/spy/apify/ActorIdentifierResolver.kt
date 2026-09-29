package com.lichiai.spy.apify

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Canonical resolver and normalizer for Apify Actor identifiers.
 *
 * Apify REST API v2 documentation (https://docs.apify.com/api/v2):
 * An Actor can be identified either by:
 * 1. Its unique 17-char ID (e.g. "HToNmwg25vD9h2532", "shu8hvrXbJbY3Eb9W")
 * 2. Its username~actor-name combination (e.g. "apify~instagram-profile-scraper", "streamers~youtube-scraper")
 *
 * Notice that in HTTP URL paths, passing a slash like "apify/instagram-profile-scraper"
 * splits the path into separate segments causing HTTP 404.
 * This resolver converts all variations (slashes, tildes, store URLs, URL-encoded slashes)
 * into the canonical API identifier representation.
 */
object ActorIdentifierResolver {

    /**
     * Converts any raw Actor identifier representation to the canonical Apify REST API identifier
     * formatted as `username~actor-name` or `actorId`.
     *
     * Examples:
     * - "apify/instagram-profile-scraper" -> "apify~instagram-profile-scraper"
     * - "apify~instagram-profile-scraper" -> "apify~instagram-profile-scraper"
     * - "apify%2Finstagram-profile-scraper" -> "apify~instagram-profile-scraper"
     * - "https://apify.com/apify/instagram-profile-scraper" -> "apify~instagram-profile-scraper"
     * - "https://console.apify.com/actors/shu8hvrXbJbY3Eb9W" -> "shu8hvrXbJbY3Eb9W"
     * - "shu8hvrXbJbY3Eb9W" -> "shu8hvrXbJbY3Eb9W"
     */
    fun toCanonicalApiId(rawIdentifier: String): String {
        val cleaned = cleanRawInput(rawIdentifier)
        if (cleaned.isBlank()) return ""

        // Check if it is a full URL
        if (cleaned.startsWith("http://", ignoreCase = true) || cleaned.startsWith("https://", ignoreCase = true)) {
            return fromUrl(cleaned)
        }

        // Decode any URL encoding first (e.g. %2F -> /, %7E -> ~)
        val decoded = try {
            URLDecoder.decode(cleaned, StandardCharsets.UTF_8.name())
        } catch (_: Exception) {
            cleaned
        }

        // If it already uses tilde notation
        if (decoded.contains("~")) {
            val parts = decoded.split("~").filter { it.isNotBlank() }
            return if (parts.size == 2) {
                "${parts[0].trim()}~${parts[1].trim()}"
            } else {
                decoded.trim()
            }
        }

        // If it uses slash notation (e.g. "apify/instagram-profile-scraper")
        if (decoded.contains("/")) {
            val parts = decoded.split("/").filter { it.isNotBlank() }
            return if (parts.size == 2) {
                "${parts[0].trim()}~${parts[1].trim()}"
            } else if (parts.size > 2) {
                // If extra segments were passed, take the last two representing user/name
                val user = parts[parts.size - 2].trim()
                val name = parts[parts.size - 1].trim()
                "$user~$name"
            } else {
                parts.firstOrNull()?.trim() ?: decoded.trim()
            }
        }

        // Direct actor alphanumeric ID or single name
        return decoded.trim()
    }

    /**
     * Converts any Actor identifier to the human-readable Store format `username/actor-name`.
     * If only an ID is present, returns the ID.
     */
    fun toStoreName(rawIdentifier: String): String {
        val canonical = toCanonicalApiId(rawIdentifier)
        return if (canonical.contains("~")) {
            canonical.replace("~", "/")
        } else {
            canonical
        }
    }

    /**
     * Extracts username and actor name pair if available.
     */
    fun extractOwnerAndName(rawIdentifier: String): Pair<String, String>? {
        val canonical = toCanonicalApiId(rawIdentifier)
        if (canonical.contains("~")) {
            val parts = canonical.split("~")
            if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                return Pair(parts[0], parts[1])
            }
        }
        return null
    }

    private fun cleanRawInput(raw: String): String {
        return raw.trim().trim('"', '\'', '`', '<', '>')
    }

    private fun fromUrl(url: String): String {
        val cleanUrl = url.substringBefore("?").substringBefore("#").trimEnd('/')
        var segments = cleanUrl.split("/").filter { it.isNotBlank() }
        if (segments.isEmpty()) return ""

        // Filter out known subpage endpoints like "api", "runs", "integrations", "settings", "input-schema"
        val knownSubpages = setOf("api", "runs", "integrations", "settings", "input-schema", "readme", "changelog", "builds")
        if (segments.size > 2 && segments.last().lowercase() in knownSubpages) {
            segments = segments.dropLast(1)
        }

        // e.g. https://apify.com/apify/instagram-profile-scraper -> segments: [https:, apify.com, apify, instagram-profile-scraper]
        // or https://console.apify.com/actors/shu8hvrXbJbY3Eb9W -> segments: [..., actors, shu8hvrXbJbY3Eb9W]
        val last = segments.last()
        val secondLast = segments.getOrNull(segments.size - 2)

        if (secondLast != null && secondLast != "actors" && secondLast != "act" && !secondLast.contains(".")) {
            return "$secondLast~$last"
        }

        return last
    }
}
