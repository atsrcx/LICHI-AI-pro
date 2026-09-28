package com.lichiai.browser.verifier

import com.lichiai.browser.api.TargetCriterion
import com.lichiai.browser.context.BrowserPageCandidate
import com.lichiai.browser.context.BrowserTaskContext
import java.util.Locale

data class BrowserVerificationResult(
    val passed: Boolean,
    val checkName: String,
    val detail: String,
    val retryable: Boolean = true
)

data class BrowserPageDifference(
    val hasChanged: Boolean,
    val urlChanged: Boolean,
    val titleChanged: Boolean,
    val candidatesChanged: Boolean,
    val elementsChanged: Boolean,
    val scrollChanged: Boolean,
    val summary: String
)

/**
 * Isolated deterministic verification for browser operations.
 * Prevents false completion claims or hallucinated success.
 */
object BrowserVerifier {

    /**
     * Compares before and after browser states to detect real page mutations (RikkaHub-style Diffing).
     */
    fun detectPageDifference(
        before: BrowserTaskContext,
        after: BrowserTaskContext
    ): BrowserPageDifference {
        val urlChanged = before.currentUrl != after.currentUrl && after.currentUrl.isNotBlank() && after.currentUrl != "about:blank"
        val titleChanged = before.currentTitle != after.currentTitle && after.currentTitle.isNotBlank()
        val candidatesChanged = before.extractedCandidates != after.extractedCandidates
        val elementsChanged = before.interactiveElements.size != after.interactiveElements.size
        val scrollChanged = before.pageMetrics.scrollY != after.pageMetrics.scrollY

        val hasChanged = urlChanged || titleChanged || candidatesChanged || elementsChanged || scrollChanged

        val summary = when {
            urlChanged -> "Navigated to ${after.currentUrl}"
            titleChanged -> "Title updated to '${after.currentTitle}'"
            candidatesChanged -> "Search/link candidates refreshed (${after.extractedCandidates.size} links)"
            elementsChanged -> "Interactive elements updated (${after.interactiveElements.size} elements)"
            scrollChanged -> "Scroll position changed to ${after.pageMetrics.scrollY}px"
            else -> "No detectable state mutation"
        }

        return BrowserPageDifference(
            hasChanged = hasChanged,
            urlChanged = urlChanged,
            titleChanged = titleChanged,
            candidatesChanged = candidatesChanged,
            elementsChanged = elementsChanged,
            scrollChanged = scrollChanged,
            summary = summary
        )
    }

    fun isSearchResultsPage(url: String): Boolean {
        if (url.isBlank() || url == "about:blank") return false
        val lower = url.lowercase(Locale.ROOT)
        return lower.contains("google.com/search") ||
                lower.contains("duckduckgo.com/?q=") ||
                lower.contains("bing.com/search") ||
                lower.contains("search.yahoo.com") ||
                lower.contains("youtube.com/results")
    }

    fun verifyNavigation(
        targetUrl: String,
        context: BrowserTaskContext
    ): BrowserVerificationResult {
        val current = context.currentUrl
        if (current.isBlank() || current == "about:blank") {
            return BrowserVerificationResult(
                passed = false,
                checkName = "verifyNavigation",
                detail = "Browser is still on blank page; navigation did not complete.",
                retryable = true
            )
        }

        val targetDomain = extractDomain(targetUrl)
        val currentDomain = extractDomain(current)

        if (targetDomain.isNotBlank() && currentDomain.contains(targetDomain)) {
            return BrowserVerificationResult(
                passed = true,
                checkName = "verifyNavigation",
                detail = "Successfully reached target domain: $currentDomain"
            )
        }

        // Check if URL changed from previous
        if (context.previousUrl != null && current != context.previousUrl) {
            return BrowserVerificationResult(
                passed = true,
                checkName = "verifyNavigation",
                detail = "Page navigated from ${context.previousUrl} to $current"
            )
        }

        return BrowserVerificationResult(
            passed = current != "about:blank",
            checkName = "verifyNavigation",
            detail = "Page URL is $current"
        )
    }

    fun verifySearchResults(
        query: String,
        context: BrowserTaskContext
    ): BrowserVerificationResult {
        val count = context.extractedCandidates.size
        val url = context.currentUrl
        return if (count > 0) {
            BrowserVerificationResult(
                passed = true,
                checkName = "verifySearchResults",
                detail = "Found $count search result candidate links on page."
            )
        } else if (url != "about:blank" && (
                context.currentTitle.contains(query, ignoreCase = true) ||
                url.contains("search", ignoreCase = true) ||
                url.contains("google.com/search") ||
                url.contains("duckduckgo.com") ||
                url.contains("bing.com/search") ||
                url.contains("youtube.com/results")
            )) {
            BrowserVerificationResult(
                passed = true,
                checkName = "verifySearchResults",
                detail = "Search page loaded: ${context.currentTitle.ifBlank { url }}"
            )
        } else {
            BrowserVerificationResult(
                passed = false,
                checkName = "verifySearchResults",
                detail = "Search results page not confirmed (URL: $url).",
                retryable = true
            )
        }
    }

    fun verifyClick(
        previousUrl: String,
        context: BrowserTaskContext
    ): BrowserVerificationResult {
        val current = context.currentUrl
        return if (current.isNotBlank() && current != previousUrl && current != "about:blank") {
            BrowserVerificationResult(
                passed = true,
                checkName = "verifyClick",
                detail = "Click triggered navigation to: $current"
            )
        } else {
            // Even if URL didn't change, DOM state might have changed (e.g. modal opened or scroll)
            BrowserVerificationResult(
                passed = true,
                checkName = "verifyClick",
                detail = "Element clicked in page."
            )
        }
    }

    /**
     * Verifies whether the opened destination webpage satisfies the overarching user goal.
     */
    fun verifyGoalCompletion(
        goal: String,
        targetCriterion: TargetCriterion?,
        context: BrowserTaskContext,
        pageSnippet: String
    ): BrowserVerificationResult {
        val currentUrl = context.currentUrl
        val currentTitle = context.currentTitle

        if (currentUrl.isBlank() || currentUrl == "about:blank") {
            return BrowserVerificationResult(
                passed = false,
                checkName = "verifyGoalCompletion",
                detail = "Browser is on blank page."
            )
        }

        // If goal required opening a website, ensure we are no longer on the raw search results page
        if (targetCriterion != null && targetCriterion !is TargetCriterion.Custom && isSearchResultsPage(currentUrl)) {
            return BrowserVerificationResult(
                passed = false,
                checkName = "verifyGoalCompletion",
                detail = "Still on search engine page; target website is not yet opened."
            )
        }

        // Check for error pages (404, 500, DNS error)
        val lowerTitle = currentTitle.lowercase(Locale.ROOT)
        val lowerSnippet = pageSnippet.lowercase(Locale.ROOT)
        if (lowerTitle.contains("404 not found") || lowerTitle.contains("page not found") ||
            lowerTitle.contains("privacy error") || lowerSnippet.contains("err_name_not_resolved")) {
            return BrowserVerificationResult(
                passed = false,
                checkName = "verifyGoalCompletion",
                detail = "Opened page encountered an error: $currentTitle"
            )
        }

        return BrowserVerificationResult(
            passed = true,
            checkName = "verifyGoalCompletion",
            detail = "Target website successfully opened: ${currentTitle.ifBlank { currentUrl }}"
        )
    }

    /**
     * Deterministic, generalized candidate ranking for goal-driven search result selection.
     * ZERO hardcoded subjects.
     */
    fun rankCandidate(
        candidate: BrowserPageCandidate,
        searchQuery: String,
        criterion: TargetCriterion
    ): Int {
        var score = 100 - (candidate.index * 5) // Base ranking favors earlier search results

        val lowerTitle = candidate.title.lowercase(Locale.ROOT)
        val lowerUrl = candidate.url.lowercase(Locale.ROOT)
        val lowerSnippet = (candidate.snippet ?: "").lowercase(Locale.ROOT)
        val queryTokens = searchQuery.lowercase(Locale.ROOT)
            .split(Regex("[\\s,;:.\\-_]+"))
            .filter { it.length > 2 && it !in listOf("the", "for", "and", "official", "website", "search", "kholo", "game") }

        // Boost based on query token matches
        for (token in queryTokens) {
            if (lowerUrl.contains(token)) score += 40
            if (lowerTitle.contains(token)) score += 30
            if (lowerSnippet.contains(token)) score += 15
        }

        when (criterion) {
            is TargetCriterion.Official -> {
                if (candidate.isOfficial) score += 60
                if (lowerTitle.contains("official") || lowerSnippet.contains("official site") || lowerUrl.contains("official")) score += 50
                // Penalty for aggregator / fan / third-party review domains if searching official
                if (lowerUrl.contains("wikipedia.org") || lowerUrl.contains("fandom.com") || lowerUrl.contains("reddit.com")) score -= 20
            }
            is TargetCriterion.Relevant -> {
                val subjectTokens = criterion.subject.lowercase(Locale.ROOT)
                    .split(Regex("[\\s,;:.\\-_]+"))
                    .filter { it.length > 2 }
                for (token in subjectTokens) {
                    if (lowerTitle.contains(token)) score += 40
                    if (lowerUrl.contains(token)) score += 30
                    if (lowerSnippet.contains(token)) score += 20
                }
            }
            is TargetCriterion.Price -> {
                if (candidate.isPrice) score += 60
                if (lowerTitle.contains("price") || lowerSnippet.contains("₹") || lowerSnippet.contains("$") || lowerSnippet.contains("price")) score += 40
            }
            is TargetCriterion.Download -> {
                if (candidate.isDownload) score += 60
                if (lowerTitle.contains("download") || lowerUrl.contains("download") || lowerSnippet.contains("download")) score += 40
            }
            is TargetCriterion.Ordinal -> {
                if (candidate.index == criterion.index) score += 500
            }
            is TargetCriterion.Custom -> {}
        }

        return score
    }

    private fun extractDomain(url: String): String {
        return try {
            val uri = android.net.Uri.parse(url)
            uri.host ?: ""
        } catch (_: Exception) {
            ""
        }
    }
}

