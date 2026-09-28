package com.lichiai.browser.api

import java.util.Locale

/**
 * Fast deterministic local parser for hash commands and natural language browser instructions.
 * Requires 0 LLM calls for standard browser actions and navigation.
 */
object BrowserCommandParser {

    private val STOP_KEYWORDS = listOf(
        "ruko", "ruk jao", "stop", "cancel", "bas", "rok do", "thahar jao", "abort"
    )

    private val GO_BACK_KEYWORDS = listOf(
        "pehle wali website par wapas jao", "wapas jao", "go back", "back jao",
        "previous page", "pichhe jao", "peeche jao", "wapis jao"
    )

    private val GO_FORWARD_KEYWORDS = listOf(
        "aage jao", "go forward", "forward jao", "next page"
    )

    private val RELOAD_KEYWORDS = listOf(
        "reload", "refresh", "page refresh karo", "dobara load karo"
    )

    private val SCROLL_DOWN_KEYWORDS = listOf(
        "neeche jao", "niche jao", "thoda aur neeche scroll karo", "neeche scroll karo",
        "scroll down", "down jao", "niche scroll karo", "scroll karo neeche"
    )

    private val SCROLL_UP_KEYWORDS = listOf(
        "upar jao", "ooper jao", "scroll up", "upar scroll karo", "thoda upar jao"
    )

    private val NEW_TAB_KEYWORDS = listOf(
        "new tab", "naya tab", "naye tab mein kholo", "open in new tab", "isko new tab mein kholo"
    )

    /**
     * Parse raw text into structured BrowserUserIntent.
     */
    fun parse(input: String): BrowserUserIntent {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return BrowserUserIntent.OpenBrowser

        // 1. Check for immediate STOP / Cancel
        val lower = trimmed.lowercase(Locale.ROOT)
        if (STOP_KEYWORDS.any { lower == it || lower == "$it." }) {
            return BrowserUserIntent.StopTask
        }

        // 2. Fast-path Hash (#) Commands
        if (trimmed.startsWith("#")) {
            return parseHashCommand(trimmed)
        }

        // 3. Simple Browser Open
        if (lower in listOf("browser kholo", "open browser", "browser open karo", "browser", "internet kholo", "browser kholo.")) {
            return BrowserUserIntent.OpenBrowser
        }

        // 4. Navigation controls
        if (GO_BACK_KEYWORDS.any { lower.contains(it) }) return BrowserUserIntent.GoBack
        if (GO_FORWARD_KEYWORDS.any { lower.contains(it) }) return BrowserUserIntent.GoForward
        if (RELOAD_KEYWORDS.any { lower.contains(it) }) return BrowserUserIntent.Reload
        if (SCROLL_DOWN_KEYWORDS.any { lower.contains(it) }) return BrowserUserIntent.Scroll(ScrollDirection.DOWN)
        if (SCROLL_UP_KEYWORDS.any { lower.contains(it) }) return BrowserUserIntent.Scroll(ScrollDirection.UP)

        // 5. Ordinal result selection on current results: "doosra result kholo", "pehla result", "third result", etc.
        val ordinalIntent = parseOrdinalSelection(lower)
        if (ordinalIntent != null && !lower.contains("search") && !lower.contains("dhundho") && !lower.contains("google")) {
            return ordinalIntent
        }

        // 6. Direct URL navigation pattern: e.g. "github.com kholo", "open https://apple.com"
        val directUrl = extractUrlIfPresent(trimmed)
        if (directUrl != null && !lower.contains("search") && !lower.contains("dhundho") && !lower.contains("google")) {
            return BrowserUserIntent.NavigateUrl(directUrl)
        }

        // 7. Compound Multi-Step: Search + Open (e.g. Search on Google and open official/relevant website)
        val compoundIntent = parseCompoundSearchAndOpen(trimmed)
        if (compoundIntent != null) {
            return compoundIntent
        }

        // 8. Search-Only engine pattern: e.g. "Google par PUBG game search karo", "YouTube par Arijit Singh search karo"
        val searchIntent = parseSearchIntent(trimmed)
        if (searchIntent != null) return searchIntent

        // 9. In-page Price / Download / Summary extraction
        if (lower.contains("price") || lower.contains("rate") || lower.contains("keemat") || lower.contains("cost")) {
            return BrowserUserIntent.ExtractInformation(query = trimmed, category = "price")
        }
        if (lower.contains("download") || lower.contains("download option") || lower.contains("download karo")) {
            return BrowserUserIntent.DownloadTarget(targetDescription = trimmed)
        }
        if (lower.contains("summary") || lower.contains("short summary") || lower.contains("kya likha hai")) {
            return BrowserUserIntent.ExtractInformation(query = trimmed, category = "summary")
        }

        // 10. Semantic link selection on current page: e.g. "official website kholo", "official link open karo"
        if (lower.contains("official website") || lower.contains("main website") || lower.contains("official link")) {
            return BrowserUserIntent.ClickCandidate(semanticDescription = "official website")
        }

        // 11. Fallback: Semantic goal
        return BrowserUserIntent.SemanticGoal(naturalLanguageGoal = trimmed)
    }

    private fun parseHashCommand(text: String): BrowserUserIntent {
        val raw = text.removePrefix("#").trim()
        val parts = raw.split(Regex("\\s+"), limit = 2)
        val cmd = parts[0].lowercase(Locale.ROOT)
        val args = if (parts.size > 1) parts[1].trim() else ""

        return when (cmd) {
            "open", "goto", "url" -> {
                val url = if (!args.startsWith("http://") && !args.startsWith("https://")) "https://$args" else args
                BrowserUserIntent.NavigateUrl(url)
            }
            "google" -> BrowserUserIntent.Search(query = args, searchEngine = "google")
            "youtube" -> BrowserUserIntent.Search(query = args, searchEngine = "youtube")
            "search", "find" -> BrowserUserIntent.Search(query = args)
            "back" -> BrowserUserIntent.GoBack
            "forward" -> BrowserUserIntent.GoForward
            "refresh", "reload" -> BrowserUserIntent.Reload
            "down" -> BrowserUserIntent.Scroll(ScrollDirection.DOWN)
            "up" -> BrowserUserIntent.Scroll(ScrollDirection.UP)
            "newtab" -> BrowserUserIntent.OpenNewTab(args.takeIf { it.isNotBlank() })
            "stop" -> BrowserUserIntent.StopTask
            else -> {
                BrowserUserIntent.Search(query = raw)
            }
        }
    }

    private fun parseOrdinalSelection(lower: String): BrowserUserIntent? {
        val patterns = mapOf(
            1 to listOf("pehla result", "first result", "1st result", "pehli link", "result 1", "first link", "pehla wala"),
            2 to listOf("doosra result", "dusra result", "second result", "2nd result", "doosri link", "dusri link", "result 2", "second link", "doosra wala", "dusra wala"),
            3 to listOf("teesra result", "tisra result", "third result", "3rd result", "teesri link", "result 3", "teesra wala"),
            4 to listOf("chautha result", "fourth result", "4th result", "result 4"),
            5 to listOf("panchwa result", "fifth result", "5th result", "result 5")
        )

        for ((index, keywords) in patterns) {
            if (keywords.any { lower.contains(it) }) {
                return BrowserUserIntent.ClickCandidate(index = index)
            }
        }
        return null
    }

    /**
     * Parses compound instructions that require Searching AND Opening a result (e.g. Official, Relevant, Price, Download, Ordinal).
     */
    private fun parseCompoundSearchAndOpen(text: String): BrowserUserIntent.SearchAndOpen? {
        var normalized = text.trim()
            .replace(Regex("(?i)^(?:browser\\s+(?:kholo|open\\s+karo)\\s+(?:aur|and|,)?\\s*)"), "")
            .replace(Regex("(?i)^(?:apna\\s+browser\\s+(?:kholo|open\\s+karo)\\s+(?:aur|and|,)?\\s*)"), "")
            .replace(Regex("(?i)^(?:browser\\s+mein\\s+)"), "")
            .replace(Regex("(?i)^(?:kripya\\s+|please\\s+)"), "")
            .trim()

        val lower = normalized.lowercase(Locale.ROOT)

        // Check if there is an action to OPEN / KHOLO after search or as part of compound instruction
        val hasOpenAction = lower.contains("kholo") || lower.contains("open karo") || lower.contains("open") ||
                lower.contains("khol do") || lower.contains("chalao")

        if (!hasOpenAction) return null

        // Check for compound connectives: "aur", "and", "then", "karke", "mein jo ... mile usko"
        val isCompound = lower.contains(" aur ") || lower.contains(" and ") || lower.contains(" then ") ||
                lower.contains("search results mein") || lower.contains("search result mein") ||
                lower.contains("wali relevant") || lower.contains("wala result") || lower.contains("wali website")

        if (!isCompound && !lower.contains("official website") && !lower.contains("relevant website")) {
            return null
        }

        // Determine Search Engine
        val searchEngine = if (lower.contains("youtube")) "youtube" else "google"

        // 1. Check for Official Website goal
        if (lower.contains("official website") || lower.contains("official site") || lower.contains("official link") || lower.contains("main website")) {
            val query = extractSubjectForSearch(normalized, "official website")
            val effectiveQuery = if (!query.contains("official", ignoreCase = true)) "$query official website" else query
            return BrowserUserIntent.SearchAndOpen(
                searchQuery = effectiveQuery,
                targetCriterion = TargetCriterion.Official,
                searchEngine = searchEngine
            )
        }

        // 2. Check for Relevant Website / Subject goal
        if (lower.contains("relevant website") || lower.contains("relevant site") || lower.contains("relevant result") || lower.contains("ke baare mein")) {
            val subject = extractSubjectForSearch(normalized, "relevant")
            return BrowserUserIntent.SearchAndOpen(
                searchQuery = subject,
                targetCriterion = TargetCriterion.Relevant(subject),
                searchEngine = searchEngine
            )
        }

        // 3. Check for Price result goal
        if (lower.contains("price") || lower.contains("keemat") || lower.contains("rate")) {
            val subject = extractSubjectForSearch(normalized, "price")
            val effectiveQuery = if (!subject.contains("price", ignoreCase = true)) "$subject price" else subject
            return BrowserUserIntent.SearchAndOpen(
                searchQuery = effectiveQuery,
                targetCriterion = TargetCriterion.Price,
                searchEngine = searchEngine
            )
        }

        // 4. Check for Download goal
        if (lower.contains("download")) {
            val subject = extractSubjectForSearch(normalized, "download")
            val effectiveQuery = if (!subject.contains("download", ignoreCase = true)) "$subject download" else subject
            return BrowserUserIntent.SearchAndOpen(
                searchQuery = effectiveQuery,
                targetCriterion = TargetCriterion.Download,
                searchEngine = searchEngine
            )
        }

        // 5. Check for Ordinal result in compound: e.g. "Google par X search karo aur doosra result kholo"
        val ordinal = parseOrdinalSelection(lower)
        if (ordinal is BrowserUserIntent.ClickCandidate && ordinal.index != null) {
            val subject = extractSubjectForSearch(normalized, "result")
            return BrowserUserIntent.SearchAndOpen(
                searchQuery = subject,
                targetCriterion = TargetCriterion.Ordinal(ordinal.index),
                searchEngine = searchEngine
            )
        }

        return null
    }

    /**
     * Extracts the concise entity/subject to be searched from a compound natural language instruction.
     */
    private fun extractSubjectForSearch(text: String, targetType: String): String {
        var s = text.trim()
            .replace(Regex("(?i)^(?:browser\\s+(?:kholo|open\\s+karo)\\s+(?:aur|and|,)?\\s*)"), "")
            .replace(Regex("(?i)^(?:apna\\s+browser\\s+(?:kholo|open\\s+karo)\\s+(?:aur|and|,)?\\s*)"), "")
            .replace(Regex("(?i)^(?:kripya\\s+|please\\s+)"), "")
            .trim()

        // Split by compound connectives if present
        if (s.contains(Regex("(?i)\\s+(?:aur|and|then)\\s+"))) {
            val parts = s.split(Regex("(?i)\\s+(?:aur|and|then)\\s+"), limit = 2)
            s = parts[0].trim()
        }

        // Remove Google/search prefixes
        s = s.replace(Regex("(?i)^(?:google|youtube|bing|duckduckgo)\\s+(?:par|mein|pe|pr)\\s+"), "")
            .replace(Regex("(?i)^(?:google|youtube|bing|duckduckgo)\\s+(?:kholo|open\\s+karo)\\s+(?:aur|and)?\\s*"), "")
            .replace(Regex("(?i)^(?:search(?:\\s+karo)?|find|dhundho|khojo)\\s+"), "")

        // Remove trailing search/open instructions from the clause
        s = s.replace(Regex("(?i)\\s+(?:search\\s+karo|dhundho|khojo|dekho|search|kholo|open\\s+karo|open|chalao)\\.?$"), "")
            .replace(Regex("(?i)\\s+(?:wali|wala|ke\\s+liye)?\\s+(?:relevant\\s+website|official\\s+website|website|site|result|link)$"), "")
            .replace(Regex("(?i)\\s+ke\\s+baare\\s+mein\\s+(?:koi\\s+)?(?:relevant\\s+website|website|jaankari)$"), "")
            .replace(Regex("(?i)\\s+ke\\s+baare\\s+mein$"), "")
            .replace(Regex("(?i)\\s+ki\\s+official\\s+website$"), " official website")
            .replace(Regex("(?i)\\s+ka\\s+price\\s+wala\\s+result$"), " price")
            .replace(Regex("(?i)\\s+ka\\s+price$"), " price")
            .trim()

        val cleaned = cleanQuery(s)
        return if (cleaned.isNotBlank()) cleaned else text.trim()
    }

    private fun parseSearchIntent(text: String): BrowserUserIntent? {
        var normalized = text.trim()
            .replace(Regex("(?i)^(?:browser\\s+(?:kholo|open\\s+karo)\\s+(?:aur|and|,)?\\s*)"), "")
            .replace(Regex("(?i)^(?:browser\\s+mein\\s+)"), "")
            .replace(Regex("(?i)^(?:kripya\\s+|please\\s+)"), "")
            .trim()

        val lower = normalized.lowercase(Locale.ROOT)

        // 1. Google search pattern
        val googlePatterns = listOf(
            Regex("(?i)(?:google\\s+(?:par|mein|pe|pr)\\s+)(.+?)(?:\\s+(?:search\\s+karo|dhundho|khojo|dekho|search))?\\.?$"),
            Regex("(?i)(?:google\\s+(?:kholo|open\\s+karo)\\s+(?:aur|and)?\\s*)(.+?)(?:\\s+(?:search\\s+karo|dhundho|khojo|dekho|search))?\\.?$"),
            Regex("(?i)(.+?)(?:\\s+google\\s+(?:par|mein|pe|pr)\\s+(?:search\\s+karo|dhundho|khojo))\\.?$")
        )
        for (pattern in googlePatterns) {
            pattern.find(normalized)?.let { match ->
                val q = cleanQuery(match.groupValues[1])
                if (q.isNotBlank()) return BrowserUserIntent.Search(query = q, searchEngine = "google")
            }
        }

        // 2. YouTube search pattern
        val youtubePatterns = listOf(
            Regex("(?i)(?:youtube\\s+(?:par|mein|pe|pr)\\s+)(.+?)(?:\\s+(?:search\\s+karo|dhundho|khojo|dekho|chalao|play\\s+karo|search))?\\.?$"),
            Regex("(?i)(?:youtube\\s+(?:kholo|open\\s+karo)\\s+(?:aur|and)?\\s*)(.+?)(?:\\s+(?:search\\s+karo|dhundho|khojo|dekho|chalao|play\\s+karo|search))?\\.?$"),
            Regex("(?i)(.+?)(?:\\s+youtube\\s+(?:par|mein|pe|pr)\\s+(?:search\\s+karo|dhundho|khojo))\\.?$")
        )
        for (pattern in youtubePatterns) {
            pattern.find(normalized)?.let { match ->
                val q = cleanQuery(match.groupValues[1])
                if (q.isNotBlank()) return BrowserUserIntent.Search(query = q, searchEngine = "youtube")
            }
        }

        // 3. Web search pattern: "Web search karo Aaj news kya bata raha hai"
        val webSearchPrefixRegex = Regex("(?i)^(?:web\\s+search(?:\\s+karo|\\s+kijiye)?\\s+)(.+)$")
        webSearchPrefixRegex.find(normalized)?.let { match ->
            val q = cleanQuery(match.groupValues[1])
            if (q.isNotBlank()) return BrowserUserIntent.Search(query = q)
        }

        // 4. General search prefixes and suffixes
        val generalSearchPrefixRegex = Regex("(?i)^(?:search(?:\\s+karo)?|find|dhundho|khojo)\\s+(.+)$")
        generalSearchPrefixRegex.find(normalized)?.let { match ->
            val q = cleanQuery(match.groupValues[1])
            if (q.isNotBlank()) return BrowserUserIntent.Search(query = q)
        }

        val generalSearchSuffixRegex = Regex("(?i)^(.+?)(?:\\s+(?:web\\s+search\\s+karo|search\\s+karo|dhundho|khojo|search|dekh\\s+kar\\s+batao))\\.?$")
        generalSearchSuffixRegex.find(normalized)?.let { match ->
            val q = cleanQuery(match.groupValues[1])
            if (q.isNotBlank()) return BrowserUserIntent.Search(query = q)
        }

        if (lower.contains("search karo") || lower.contains("dhundho") || lower.contains("khojo")) {
            val cleaned = cleanQuery(
                normalized
                    .replace(Regex("(?i)^(web\\s+search|search|find)\\s+"), "")
                    .replace(Regex("(?i)(web\\s+search\\s+karo|search\\s+karo|dhundho|khojo|search|dekh\\s+kar\\s+batao|dekho)"), "")
            )
            if (cleaned.isNotBlank()) {
                return BrowserUserIntent.Search(query = cleaned)
            }
        }

        return null
    }

    private fun cleanQuery(raw: String): String {
        return raw.trim()
            .replace(Regex("(?i)^(?:web\\s+search(?:\\s+karo)?|search(?:\\s+karo)?|find)\\s+"), "")
            .replace(Regex("(?i)^(?:par|pe|pr|mein|in|on|for)\\s+"), "")
            .replace(Regex("(?i)\\s+(?:web\\s+search\\s+karo|search\\s+karo|dhundho|khojo|dekho|kholo|open\\s+karo)\\.?$"), "")
            .removePrefix("\"").removeSuffix("\"")
            .removePrefix("'").removeSuffix("'")
            .removeSuffix(".").removeSuffix("?").removeSuffix("!")
            .trim()
    }

    private fun extractUrlIfPresent(text: String): String? {
        val words = text.split(Regex("\\s+"))
        for (w in words) {
            val clean = w.removeSuffix(".").removeSuffix(",").trim()
            if (clean.startsWith("http://") || clean.startsWith("https://")) {
                return clean
            }
            if (clean.contains(".") && (clean.endsWith(".com") || clean.endsWith(".org") || clean.endsWith(".net") ||
                        clean.endsWith(".in") || clean.endsWith(".io") || clean.endsWith(".ai") || clean.endsWith(".co"))
            ) {
                return "https://$clean"
            }
        }
        return null
    }
}

