package com.lichiai.spy.interpreter

import com.lichiai.calling.contacts.PhoneNumberNormalizer
import com.lichiai.spy.core.PlatformType
import java.net.URI
import java.util.Locale

/**
 * Robust, language-aware target and entity extractor for #Spy Platform Intelligence.
 * Extracts and normalizes handles, usernames, phone numbers, emails, URLs, subreddits,
 * and search queries from multi-lingual natural language sentences (English, Hindi, Hinglish, Roman Hindi).
 */
object TargetExtractor {

    // Linguistic noise words and connectors that must never be part of a target identifier
    private val NOISE_WORDS = setOf(
        // Hindi / Hinglish pronouns & demonstratives
        "is", "iss", "iska", "iski", "iske", "isko", "us", "uss", "uska", "uski", "uske", "usko",
        "kisi", "kiska", "kiski", "kiske", "yeh", "woh", "in", "inka", "inke", "un", "unka", "unke",
        // Hindi / Hinglish connectors & prepositions
        "par", "per", "pe", "pa", "mein", "me", "se", "ko", "k", "ka", "ki", "ke", "kay", "aur", "ya",
        // Action verbs & commands (Hindi / Hinglish / English)
        "dhundo", "dhoondo", "khojo", "batao", "bataye", "batana", "dikhao", "dikhaye", "dikhana",
        "nikalo", "nikal", "nikaliye", "lao", "laao", "check", "karo", "kariye", "search", "find",
        "fetch", "get", "lookup", "look", "up", "show", "tell", "give", "please", "kripya", "krdo", "kardo",
        // Subject nouns & entity descriptors
        "account", "khata", "profile", "user", "handle", "channel", "page", "sub", "subreddit",
        "post", "posts", "tweet", "tweets", "feed", "video", "videos", "reel", "reels",
        "phone", "mobile", "number", "contact", "sampark", "preview", "card",
        // Field keywords
        "detail", "details", "info", "information", "data", "bio", "biography", "about",
        "follower", "followers", "following", "subscriber", "subscribers", "sub", "subs",
        "view", "views", "like", "likes", "comment", "comments", "stat", "stats", "statistics",
        "analytics", "metric", "metrics", "public", "private", "latest", "top", "new", "all", "business",
        // Platform tokens
        "instagram", "insta", "ig", "youtube", "yt", "reddit", "tiktok", "twitter", "x",
        "linkedin", "github", "facebook", "fb"
    )

    private val PHONE_PATTERN = Regex("(?:\\+?\\d{1,4}[-\\s.]?)?\\(?\\d{2,5}\\)?[-.\\s]?\\d{3,5}[-.\\s]?\\d{3,6}")
    private val EMAIL_PATTERN = Regex("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}")

    /**
     * Attempts to extract a valid phone number from the query.
     */
    fun extractPhoneNumber(query: String): String? {
        val matches = PHONE_PATTERN.findAll(query)
        for (match in matches) {
            val candidate = match.value.trim()
            val digitsOnly = candidate.filter { it.isDigit() }
            if (digitsOnly.length in 7..15) {
                return PhoneNumberNormalizer.normalize(candidate)
            }
        }
        return null
    }

    /**
     * Attempts to extract an email address from the query.
     */
    fun extractEmail(query: String): String? {
        val match = EMAIL_PATTERN.find(query) ?: return null
        return match.value.trim().lowercase(Locale.ROOT)
    }

    /**
     * Extracts and normalizes the target from user input according to the platform and operation.
     */
    fun extract(cleanQuery: String, platform: PlatformType): String {
        val trimmed = cleanQuery.trim()
        if (trimmed.isBlank()) return ""

        // 1. Direct Phone Number Check
        val phoneTarget = extractPhoneNumber(trimmed)
        if (phoneTarget != null && isPhoneQuery(trimmed)) {
            return phoneTarget
        }

        // 2. Direct Email Check
        val emailTarget = extractEmail(trimmed)
        if (emailTarget != null) {
            return emailTarget
        }

        // 3. Direct Web URLs
        val urlTarget = extractFromUrl(trimmed, platform)
        if (urlTarget.isNotBlank()) {
            return normalizeUsername(urlTarget, platform)
        }

        // 4. Explicit @handle syntax (e.g. "@axeel_dubin")
        val handleMatch = Regex("@[a-zA-Z0-9._-]+").find(trimmed)
        if (handleMatch != null) {
            val handle = handleMatch.value.removePrefix("@")
            return normalizeUsername(handle, platform)
        }

        // 5. Explicit Subreddit syntax (e.g. "r/android" or "/r/android")
        val subMatch = Regex("(?:^|\\s)r/([a-zA-Z0-9_]+)", RegexOption.IGNORE_CASE).find(trimmed)
        if (subMatch != null) {
            return subMatch.groupValues[1].trim()
        }

        // 6. Linguistic Token Filtering for Hindi / Hinglish / English
        // Example: "Instagram per axeel_dubin account ka detail nikalo"
        val rawTokens = trimmed.split(Regex("[\\s,;!?]+")).filter { it.isNotBlank() }
        val candidateTokens = mutableListOf<String>()

        for (token in rawTokens) {
            val lower = token.lowercase(Locale.ROOT)
            val alphanumericOnly = lower.replace(Regex("[^a-z0-9_.]"), "")

            if (alphanumericOnly.isBlank()) continue
            if (lower in NOISE_WORDS || alphanumericOnly in NOISE_WORDS) continue

            // If token is a platform keyword with punctuation, skip it
            if (isPlatformKeyword(alphanumericOnly)) continue

            candidateTokens.add(token.trim('"', '\'', '`', ':', ',', '.', ';', '(', ')'))
        }

        if (candidateTokens.isNotEmpty()) {
            // For username-centric platforms (Instagram, TikTok, Twitter, GitHub),
            // find the token that best conforms to platform username syntax
            if (platform == PlatformType.INSTAGRAM || platform == PlatformType.TIKTOK || platform == PlatformType.TWITTER_X || platform == PlatformType.GITHUB) {
                val bestUsername = candidateTokens.firstOrNull { isValidUsername(it) }
                if (bestUsername != null) {
                    return normalizeUsername(bestUsername, platform)
                }
            }

            return candidateTokens.joinToString(" ").trim()
        }

        return trimmed
    }

    private fun isPhoneQuery(text: String): Boolean {
        val lower = text.lowercase(Locale.ROOT)
        return lower.contains("phone") || lower.contains("mobile") || lower.contains("number") ||
               lower.contains("contact") || lower.contains("sampark") || lower.contains("call") ||
               text.contains("+") || text.any { it.isDigit() }
    }

    /**
     * Extracts username or identifier from known social platform URLs.
     */
    private fun extractFromUrl(text: String, platform: PlatformType): String {
        val urlPattern = Regex("(https?://[^\\s]+|www\\.[^\\s]+|(?:instagram|youtube|reddit|tiktok|twitter|x|github|linkedin)\\.com/[^\\s]+)", RegexOption.IGNORE_CASE)
        val match = urlPattern.find(text) ?: return ""
        val matchedUrl = match.value.trim(')', ']', '}', '>', '.', ',')

        try {
            val cleanUrl = if (!matchedUrl.startsWith("http://") && !matchedUrl.startsWith("https://")) {
                "https://$matchedUrl"
            } else matchedUrl

            val uri = URI(cleanUrl)
            val path = uri.path.trim('/')
            val segments = path.split("/").filter { it.isNotBlank() }

            if (segments.isEmpty()) return ""

            return when (platform) {
                PlatformType.INSTAGRAM -> {
                    val first = segments[0]
                    if (first != "p" && first != "reel" && first != "stories" && first != "explore") {
                        first
                    } else segments.getOrNull(1) ?: first
                }
                PlatformType.YOUTUBE -> {
                    val first = segments[0]
                    if (first.startsWith("@")) {
                        first.removePrefix("@")
                    } else if ((first == "c" || first == "channel" || first == "user") && segments.size > 1) {
                        segments[1].removePrefix("@")
                    } else {
                        first
                    }
                }
                PlatformType.REDDIT -> {
                    if (segments.size >= 2 && (segments[0] == "r" || segments[0] == "user")) {
                        segments[1]
                    } else segments.last()
                }
                PlatformType.TIKTOK -> {
                    segments[0].removePrefix("@")
                }
                PlatformType.TWITTER_X, PlatformType.GITHUB -> {
                    segments[0].removePrefix("@")
                }
                PlatformType.LINKEDIN -> {
                    if (segments.size >= 2 && segments[0] == "in") {
                        segments[1]
                    } else segments.last()
                }
                else -> segments.last()
            }
        } catch (_: Exception) {
            return ""
        }
    }

    /**
     * Normalizes a username by trimming whitespace, removing leading '@', trailing punctuation.
     */
    fun normalizeUsername(raw: String, platform: PlatformType): String {
        var clean = raw.trim().removePrefix("@").trimEnd('/', '?', '#')
        clean = clean.substringBefore("?").substringBefore("#")
        clean = clean.trim('"', '\'', '`', '<', '>', '.', ',', ';', ':')
        return clean.trim()
    }

    /**
     * Validates whether a token matches the standard Instagram/social username format.
     * 1-30 chars, letters, numbers, periods, underscores. Cannot contain spaces or consecutive periods.
     */
    fun isValidUsername(username: String): Boolean {
        val clean = username.removePrefix("@").trim()
        if (clean.length !in 1..30) return false
        return clean.matches(Regex("^[a-zA-Z0-9._]+$")) && !clean.startsWith(".") && !clean.endsWith(".")
    }

    private fun isPlatformKeyword(word: String): Boolean {
        return word in setOf(
            "instagram", "insta", "ig", "youtube", "yt", "reddit",
            "tiktok", "twitter", "x", "linkedin", "github", "facebook", "fb"
        )
    }
}
