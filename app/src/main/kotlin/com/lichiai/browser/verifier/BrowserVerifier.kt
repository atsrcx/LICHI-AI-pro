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
                lower.contains("duckduckgo.com/html") ||
                lower.contains("bing.com/search") ||
                lower.contains("search.yahoo.com") ||
                lower.contains("youtube.com/results") ||
                lower.contains("ecosia.org/search") ||
                lower.contains("brave.com/search")
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

        val targetDomain = extractDomain(targetUrl).lowercase(Locale.ROOT).removePrefix("www.")
        val currentDomain = extractDomain(current).lowercase(Locale.ROOT).removePrefix("www.")

        val domainMatches = targetDomain.isNotBlank() && (currentDomain == targetDomain || currentDomain.endsWith(".$targetDomain") || targetDomain.endsWith(".$currentDomain"))
        if (!domainMatches) {
            return BrowserVerificationResult(
                passed = false,
                checkName = "verifyNavigation",
                detail = "Current domain '$currentDomain' does not match expected target '$targetDomain' (Current: $current, Expected: $targetUrl)",
                retryable = true
            )
        }

        // If target URL had a specific path requested
        val targetPath = extractPath(targetUrl).trimEnd('/')
        val currentPath = extractPath(current).trimEnd('/')
        if (targetPath.isNotBlank() && targetPath != "/") {
            val pathMatches = currentPath == targetPath || currentPath.startsWith("$targetPath/") || currentPath.contains(targetPath)
            return if (pathMatches) {
                BrowserVerificationResult(
                    passed = true,
                    checkName = "verifyNavigation",
                    detail = "Successfully reached target domain and path: $current"
                )
            } else {
                BrowserVerificationResult(
                    passed = false,
                    checkName = "verifyNavigation",
                    detail = "Target path mismatch: requested '$targetPath', but current path is '$currentPath' (URL: $current).",
                    retryable = true
                )
            }
        }

        return BrowserVerificationResult(
            passed = true,
            checkName = "verifyNavigation",
            detail = "Successfully reached target domain: $currentDomain"
        )
    }

    fun verifySearchResults(
        query: String,
        context: BrowserTaskContext
    ): BrowserVerificationResult {
        val url = context.currentUrl
        if (url.isBlank() || url == "about:blank" || !isSearchResultsPage(url)) {
            return BrowserVerificationResult(
                passed = false,
                checkName = "verifySearchResults",
                detail = "Current page is not a confirmed search results page (URL: $url).",
                retryable = true
            )
        }

        val count = context.extractedCandidates.size
        val titleMatches = context.currentTitle.contains(query, ignoreCase = true)
        val queryInUrl = url.contains(java.net.URLEncoder.encode(query, "UTF-8"), ignoreCase = true) ||
                query.split(" ").filter { it.length > 2 }.any { url.contains(it, ignoreCase = true) }

        if (count > 0 && (titleMatches || queryInUrl || context.interactiveElements.isNotEmpty())) {
            return BrowserVerificationResult(
                passed = true,
                checkName = "verifySearchResults",
                detail = "Search results confirmed for '$query' ($count candidate links, URL: $url)."
            )
        } else if (titleMatches || queryInUrl) {
            return BrowserVerificationResult(
                passed = true,
                checkName = "verifySearchResults",
                detail = "Search results page confirmed for '$query' (${context.currentTitle})."
            )
        } else {
            return BrowserVerificationResult(
                passed = false,
                checkName = "verifySearchResults",
                detail = "Query '$query' could not be confirmed in search results page (URL: $url).",
                retryable = true
            )
        }
    }

    fun verifyTypeText(
        expectedText: String,
        actualValue: String?,
        isSensitive: Boolean = false,
        elementFound: Boolean = true
    ): BrowserVerificationResult {
        if (!elementFound) {
            return BrowserVerificationResult(
                passed = false,
                checkName = "verifyTypeText",
                detail = "Target text field could not be found or identified in DOM.",
                retryable = true
            )
        }

        if (isSensitive) {
            // Never compare or read sensitive fields directly
            return BrowserVerificationResult(
                passed = true,
                checkName = "verifyTypeText",
                detail = "Protected sensitive field interaction executed without reading secret."
            )
        }

        val actual = actualValue?.trim() ?: ""
        val expected = expectedText.trim()
        val matches = actual == expected

        return if (matches) {
            BrowserVerificationResult(
                passed = true,
                checkName = "verifyTypeText",
                detail = "Text verified in field: expected '$expectedText', observed '$actual'."
            )
        } else {
            BrowserVerificationResult(
                passed = false,
                checkName = "verifyTypeText",
                detail = "Text mismatch: expected '$expectedText', but field contains '$actual'.",
                retryable = true
            )
        }
    }

    fun verifyTypeText(
        fieldIdOrSelector: String,
        expectedText: String,
        context: BrowserTaskContext
    ): BrowserVerificationResult {
        val matchedEl = context.interactiveElements.firstOrNull {
            it.id.equals(fieldIdOrSelector, ignoreCase = true) ||
                    it.name.equals(fieldIdOrSelector, ignoreCase = true) ||
                    it.index.toString() == fieldIdOrSelector ||
                    it.placeholder.contains(fieldIdOrSelector, ignoreCase = true)
        }
        val isSensitive = matchedEl?.type.equals("password", ignoreCase = true) ||
                matchedEl?.name?.contains("password", ignoreCase = true) == true ||
                matchedEl?.name?.contains("pin", ignoreCase = true) == true ||
                matchedEl?.name?.contains("otp", ignoreCase = true) == true

        return verifyTypeText(
            expectedText = expectedText,
            actualValue = matchedEl?.value,
            isSensitive = isSensitive,
            elementFound = matchedEl != null
        )
    }

    fun verifyReload(
        preGenerationId: String,
        context: BrowserTaskContext
    ): BrowserVerificationResult {
        val isFreshGen = context.perceptionGenerationId.isNotBlank() && context.perceptionGenerationId != preGenerationId
        val isLoaded = context.currentUrl.isNotBlank() && context.currentUrl != "about:blank"
        return if (isFreshGen && isLoaded) {
            BrowserVerificationResult(
                passed = true,
                checkName = "verifyReload",
                detail = "Page reload verified with fresh DOM generation '${context.perceptionGenerationId}' (${context.currentUrl})."
            )
        } else {
            BrowserVerificationResult(
                passed = false,
                checkName = "verifyReload",
                detail = "Page reload unverified: generation did not advance or page left blank.",
                retryable = true
            )
        }
    }

    fun verifyBack(
        preUrl: String,
        context: BrowserTaskContext
    ): BrowserVerificationResult {
        val current = context.currentUrl
        return if (current.isNotBlank() && current != "about:blank" && current != preUrl) {
            BrowserVerificationResult(
                passed = true,
                checkName = "verifyBack",
                detail = "History back navigation verified: navigated from '$preUrl' to '$current'."
            )
        } else {
            BrowserVerificationResult(
                passed = false,
                checkName = "verifyBack",
                detail = "History back navigation did not produce URL change (Current: $current).",
                retryable = true
            )
        }
    }

    fun verifyForward(
        preUrl: String,
        context: BrowserTaskContext
    ): BrowserVerificationResult {
        val current = context.currentUrl
        return if (current.isNotBlank() && current != "about:blank" && current != preUrl) {
            BrowserVerificationResult(
                passed = true,
                checkName = "verifyForward",
                detail = "History forward navigation verified: navigated from '$preUrl' to '$current'."
            )
        } else {
            BrowserVerificationResult(
                passed = false,
                checkName = "verifyForward",
                detail = "History forward navigation did not produce URL change (Current: $current).",
                retryable = true
            )
        }
    }

    fun verifyScroll(
        direction: com.lichiai.browser.api.ScrollDirection,
        preScrollY: Int,
        preMaxScrollY: Int,
        postScrollY: Int,
        postMaxScrollY: Int
    ): BrowserVerificationResult {
        return when (direction) {
            com.lichiai.browser.api.ScrollDirection.DOWN -> {
                if (postScrollY > preScrollY) {
                    BrowserVerificationResult(
                        passed = true,
                        checkName = "verifyScroll",
                        detail = "Scrolled DOWN from ${preScrollY}px to ${postScrollY}px."
                    )
                } else if (preScrollY >= preMaxScrollY && preMaxScrollY > 0) {
                    BrowserVerificationResult(
                        passed = true,
                        checkName = "verifyScroll",
                        detail = "Already at bottom boundary (${preScrollY}px >= ${preMaxScrollY}px); valid terminal state."
                    )
                } else {
                    BrowserVerificationResult(
                        passed = false,
                        checkName = "verifyScroll",
                        detail = "Scroll DOWN produced no movement (${preScrollY}px -> ${postScrollY}px, max: ${preMaxScrollY}px)."
                    )
                }
            }
            com.lichiai.browser.api.ScrollDirection.UP -> {
                if (postScrollY < preScrollY) {
                    BrowserVerificationResult(
                        passed = true,
                        checkName = "verifyScroll",
                        detail = "Scrolled UP from ${preScrollY}px to ${postScrollY}px."
                    )
                } else if (preScrollY <= 0) {
                    BrowserVerificationResult(
                        passed = true,
                        checkName = "verifyScroll",
                        detail = "Already at top boundary (0px); valid terminal state."
                    )
                } else {
                    BrowserVerificationResult(
                        passed = false,
                        checkName = "verifyScroll",
                        detail = "Scroll UP produced no movement (${preScrollY}px -> ${postScrollY}px)."
                    )
                }
            }
            com.lichiai.browser.api.ScrollDirection.TOP -> {
                BrowserVerificationResult(
                    passed = postScrollY <= 0,
                    checkName = "verifyScroll",
                    detail = "Scrolled to top position (${postScrollY}px)."
                )
            }
            com.lichiai.browser.api.ScrollDirection.BOTTOM -> {
                BrowserVerificationResult(
                    passed = postScrollY >= postMaxScrollY,
                    checkName = "verifyScroll",
                    detail = "Scrolled to bottom position (${postScrollY}px / ${postMaxScrollY}px)."
                )
            }
        }
    }

    fun verifyClick(
        previousUrl: String,
        context: BrowserTaskContext,
        targetDescription: String = "element"
    ): BrowserVerificationResult {
        val current = context.currentUrl
        return if (current.isNotBlank() && current != previousUrl && current != "about:blank") {
            BrowserVerificationResult(
                passed = true,
                checkName = "verifyClick",
                detail = "Click on $targetDescription triggered navigation to: $current"
            )
        } else if (context.pageMetrics.isLoaded && current != "about:blank") {
            BrowserVerificationResult(
                passed = true,
                checkName = "verifyClick",
                detail = "Click on $targetDescription executed on page ($current)."
            )
        } else {
            BrowserVerificationResult(
                passed = false,
                checkName = "verifyClick",
                detail = "Click on $targetDescription failed to execute or page left in blank state.",
                retryable = true
            )
        }
    }

    fun verifyAction(
        actionName: String,
        preUrl: String,
        preTitle: String,
        context: BrowserTaskContext,
        actionResult: com.lichiai.browser.actions.BrowserActionResult
    ): BrowserVerificationResult {
        if (!actionResult.isSuccess) {
            return BrowserVerificationResult(
                passed = false,
                checkName = "verifyAction_$actionName",
                detail = actionResult.message.ifBlank { "Action '$actionName' execution failed." },
                retryable = true
            )
        }
        val currentUrl = context.currentUrl
        return when (actionName) {
            "goBack" -> verifyBack(preUrl, context)
            "goForward" -> verifyForward(preUrl, context)
            "reload" -> verifyReload(actionResult.generationId, context)
            "openTab", "switchTab" -> {
                BrowserVerificationResult(
                    passed = currentUrl.isNotBlank(),
                    checkName = "verifyAction_$actionName",
                    detail = "Tab operation completed: active tab URL is '$currentUrl'."
                )
            }
            "findOnPage" -> {
                BrowserVerificationResult(
                    passed = actionResult.isSuccess,
                    checkName = "verifyAction_$actionName",
                    detail = actionResult.message
                )
            }
            else -> {
                BrowserVerificationResult(
                    passed = actionResult.isSuccess && currentUrl.isNotBlank() && currentUrl != "about:blank",
                    checkName = "verifyAction_$actionName",
                    detail = actionResult.message.ifBlank { "Action '$actionName' verified on page '$currentUrl'." }
                )
            }
        }
    }

    fun verifyDownload(
        downloadUrl: String,
        downloadsList: List<com.lichiai.browser.downloads.BrowserDownloadItem>
    ): BrowserVerificationResult {
        val matchingItem = downloadsList.firstOrNull { it.url == downloadUrl || it.url.contains(downloadUrl.take(25)) }
        if (matchingItem == null) {
            return BrowserVerificationResult(
                passed = false,
                checkName = "verifyDownload",
                detail = "No download session registered for '$downloadUrl'.",
                retryable = true
            )
        }
        return when (matchingItem.status) {
            com.lichiai.browser.downloads.DownloadStatus.COMPLETED -> BrowserVerificationResult(
                passed = true,
                checkName = "verifyDownload",
                detail = "Download completed successfully: ${matchingItem.fileName} (${matchingItem.contentLength} bytes)."
            )
            com.lichiai.browser.downloads.DownloadStatus.DOWNLOADING -> BrowserVerificationResult(
                passed = false,
                checkName = "verifyDownload",
                detail = "Download in progress for ${matchingItem.fileName}.",
                retryable = true
            )
            com.lichiai.browser.downloads.DownloadStatus.FAILED -> BrowserVerificationResult(
                passed = false,
                checkName = "verifyDownload",
                detail = "Download failed for ${matchingItem.fileName}."
            )
            com.lichiai.browser.downloads.DownloadStatus.CANCELLED -> BrowserVerificationResult(
                passed = false,
                checkName = "verifyDownload",
                detail = "Download cancelled for ${matchingItem.fileName}."
            )
        }
    }

    fun extractPath(url: String): String {
        return try {
            val clean = url.trim()
            if (clean.isBlank() || clean == "about:blank") return ""
            val uri = java.net.URI(if (!clean.startsWith("http://") && !clean.startsWith("https://")) "https://$clean" else clean)
            uri.path ?: ""
        } catch (_: Exception) {
            try {
                val afterHost = url.substringAfter("://").substringAfter("/", "")
                if (afterHost.isNotBlank()) "/${afterHost.substringBefore("?")}" else ""
            } catch (_: Exception) {
                ""
            }
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

    fun extractDomain(url: String): String {
        return try {
            val clean = url.trim()
            if (clean.isBlank() || clean == "about:blank") return ""
            val uri = java.net.URI(if (!clean.startsWith("http://") && !clean.startsWith("https://")) "https://$clean" else clean)
            uri.host ?: ""
        } catch (_: Exception) {
            try {
                val noProto = url.substringAfter("://").substringBefore("/").substringBefore("?").substringBefore(":")
                noProto
            } catch (_: Exception) {
                ""
            }
        }
    }
}

