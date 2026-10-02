package com.lichiai.spy.interpreter

import com.lichiai.calling.contacts.PhoneNumberNormalizer
import com.lichiai.spy.core.PlatformType
import java.net.URI
import java.util.Locale

/**
 * Robust, language-aware target and entity extractor for #Spy Platform Intelligence.
 * Extracts and normalizes handles, usernames, phone numbers, emails, URLs, subreddits,
 * and search queries from multi-lingual natural language sentences (English, Hindi, Hinglish, Roman Hindi).
 *
 * Implements deterministic contextual candidate scoring to avoid noise-word collisions (e.g. Hindi "ma", "par", "mein").
 */
object TargetExtractor {

    // Linguistic noise words and connectors (Hindi / Hinglish / Roman Hindi / English)
    private val LINGUISTIC_CONNECTORS_AND_NOISE = setOf(
        // Hindi / Hinglish pronouns & demonstratives
        "is", "iss", "iska", "iski", "iske", "isko", "us", "uss", "uska", "uski", "uske", "usko",
        "kisi", "kiska", "kiski", "kiske", "yeh", "woh", "ye", "wo", "in", "inka", "inke", "un", "unka", "unke",
        "mera", "meri", "mere", "tera", "teri", "tere", "apna", "apni", "apne",
        // CLI flags and modifiers
        "-u", "-u:", "-u=", "-full", "full", "-f",
        // Hindi / Hinglish connectors & prepositions
        "ma", "mein", "me", "mai", "par", "per", "pe", "pa", "se", "ko", "k", "ka", "ki", "ke", "kay", "aur", "ya",
        "ne", "tak", "bhi", "toh", "to", "hi", "hai", "hain", "tha", "thi", "the", "hoga", "hogi", "hoge",
        // Action verbs & commands (Hindi / Hinglish / English)
        "dhundo", "dhoondo", "khojo", "batao", "bataye", "batana", "dikhao", "dikhaye", "dikhana",
        "nikalo", "nikal", "nikaliye", "lao", "laao", "check", "karo", "kariye", "krdo", "kardo",
        "search", "find", "fetch", "get", "lookup", "look", "up", "show", "tell", "give", "please", "kripya",
        "chahiye", "kare", "karna", "dekhna", "dekh", "dekho", "bhejo",
        // Subject nouns & entity descriptors
        "account", "khata", "profile", "user", "handle", "channel", "page", "sub", "subreddit",
        "post", "posts", "tweet", "tweets", "feed", "video", "videos", "reel", "reels",
        "phone", "mobile", "number", "contact", "sampark", "preview", "card", "overview",
        // Field keywords
        "detail", "details", "info", "information", "data", "bio", "biography", "about",
        "follower", "followers", "following", "subscriber", "subscribers", "sub", "subs",
        "view", "views", "like", "likes", "comment", "comments", "stat", "stats", "statistics",
        "analytics", "metric", "metrics", "public", "private", "latest", "top", "new", "all", "business",
        // Platform tokens
        "instagram", "insta", "ig", "youtube", "yt", "reddit", "tiktok", "twitter", "x",
        "linkedin", "github", "facebook", "fb", "snapchat", "snap", "threads", "pinterest",
        "spotify", "soundcloud", "telegram", "discord", "gitlab", "quora", "medium"
    )

    private val SEMANTIC_ENTITY_MARKERS = setOf(
        "profile", "account", "handle", "user", "username", "id", "channel", "page",
        "ka", "ki", "ke", "ko", "par", "per", "pe", "mein", "me", "ma", "se"
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
     * Uses deterministic contextual scoring.
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

        // 4. Explicit -u / -U flag syntax (e.g. "-U axeel_dubin", "-u axeel_dubin")
        val uFlagMatch = Regex("(?:^|\\s)-[uU][:|=]?\\s*([@a-zA-Z0-9._-]+)").find(trimmed)
        if (uFlagMatch != null) {
            val handle = uFlagMatch.groupValues[1].removePrefix("@")
            return normalizeUsername(handle, platform)
        }

        // 5. Explicit @handle syntax (e.g. "@axeel_dubin", "@nattykamal", "@ma", "@x")
        // Explicit @handle ALWAYS takes highest priority and preserves short usernames.
        val handleMatch = Regex("@[a-zA-Z0-9._-]+").find(trimmed)
        if (handleMatch != null) {
            val handle = handleMatch.value.removePrefix("@")
            return normalizeUsername(handle, platform)
        }

        // 6. Explicit Subreddit syntax (e.g. "r/android" or "/r/android")
        val subMatch = Regex("(?:^|\\s)r/([a-zA-Z0-9_]+)", RegexOption.IGNORE_CASE).find(trimmed)
        if (subMatch != null) {
            return subMatch.groupValues[1].trim()
        }

        // 7. Contextual Token Scoring for Natural Language (Hindi / Hinglish / English)
        return extractBestTargetCandidate(trimmed, platform)
    }

    /**
     * Contextually scores all tokens in natural language sentences to accurately
     * resolve the entity username while eliminating linguistic noise words ("ma", "par", "mein", etc.).
     */
    private fun extractBestTargetCandidate(query: String, platform: PlatformType): String {
        val rawTokens = query.split(Regex("[\\s,;!?]+")).filter { it.isNotBlank() }
        if (rawTokens.isEmpty()) return ""

        data class ScoredCandidate(
            val token: String,
            val normalized: String,
            val score: Int
        )

        val scoredList = mutableListOf<ScoredCandidate>()

        for (index in rawTokens.indices) {
            val raw = rawTokens[index]
            val clean = raw.trim('"', '\'', '`', ':', ',', '.', ';', '(', ')', '[', ']', '{', '}')
            if (clean.isBlank()) continue

            val lower = clean.lowercase(Locale.ROOT)
            val alphanumericOnly = lower.replace(Regex("[^a-z0-9_.]"), "")

            var score = 0
            val isNoise = lower in LINGUISTIC_CONNECTORS_AND_NOISE || alphanumericOnly in LINGUISTIC_CONNECTORS_AND_NOISE
            val isPlatform = isPlatformKeyword(alphanumericOnly)

            if (isPlatform) {
                // Platform name itself cannot be the target
                continue
            }

            if (isNoise) {
                // Heavy penalty for linguistic connectors and common action verbs
                score -= 100
            }

            // Syntax validity
            val validSyntax = isValidUsername(clean)
            if (validSyntax) {
                score += 30
            }

            // Length heuristics for un-prefixed tokens
            if (clean.length in 3..30) {
                score += 15
            } else if (clean.length in 1..2 && isNoise) {
                score -= 50
            }

            // Characters bonus: usernames often have underscores, digits, or dots
            if (clean.contains('_') || clean.any { it.isDigit() }) {
                score += 15
            }

            // Contextual adjacency scoring
            val prevToken = rawTokens.getOrNull(index - 1)?.lowercase(Locale.ROOT)?.replace(Regex("[^a-z0-9]"), "")
            val prevPrevToken = rawTokens.getOrNull(index - 2)?.lowercase(Locale.ROOT)?.replace(Regex("[^a-z0-9]"), "")
            val nextToken = rawTokens.getOrNull(index + 1)?.lowercase(Locale.ROOT)?.replace(Regex("[^a-z0-9]"), "")

            // Bonus: follows platform name + connector (e.g. "Instagram ma nattykamal", "Instagram par nattykamal", "Instagram mein nattykamal")
            if (prevPrevToken != null && isPlatformKeyword(prevPrevToken) && prevToken in setOf("ma", "par", "per", "pe", "mein", "me", "ka", "ki", "ke", "se", "ko")) {
                score += 50
            }

            // Bonus: immediately follows platform name (e.g. "Instagram nattykamal")
            if (prevToken != null && isPlatformKeyword(prevToken)) {
                score += 45
            }

            // Bonus: adjacent to semantic entity markers ("profile", "account", "handle", "user", "id", "ka profile")
            if (nextToken in SEMANTIC_ENTITY_MARKERS || prevToken in SEMANTIC_ENTITY_MARKERS) {
                score += 35
            }

            scoredList.add(ScoredCandidate(token = raw, normalized = clean, score = score))
        }

        // Sort by highest score
        val best = scoredList.maxByOrNull { it.score }
        if (best != null && best.score > 0) {
            return normalizeUsername(best.normalized, platform)
        }

        // Fallback: non-noise tokens joined if no single candidate scored positively
        val nonNoise = scoredList.filter { it.score >= 0 }.map { it.normalized }
        if (nonNoise.isNotEmpty()) {
            return normalizeUsername(nonNoise.first(), platform)
        }

        return ""
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
     * Validates whether a token matches standard social username syntax.
     * 1-30 chars, letters, numbers, periods, underscores.
     */
    fun isValidUsername(username: String): Boolean {
        val clean = username.removePrefix("@").trim()
        if (clean.length !in 1..30) return false
        return clean.matches(Regex("^[a-zA-Z0-9._]+$")) && !clean.startsWith(".") && !clean.endsWith(".")
    }

    private fun isPlatformKeyword(word: String): Boolean {
        return word in setOf(
            "instagram", "insta", "ig", "youtube", "yt", "reddit",
            "tiktok", "twitter", "x", "linkedin", "github", "facebook", "fb",
            "snapchat", "snap", "threads", "pinterest", "spotify", "soundcloud",
            "telegram", "discord", "gitlab", "quora", "medium", "maps", "imdb"
        )
    }
}
