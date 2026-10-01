package com.lichiai.browser.runtime

import com.lichiai.browser.context.BrowserPageCandidate
import com.lichiai.browser.perception.PagePerceptionSnapshot
import com.lichiai.browser.perception.SemanticElement

/**
 * Compact authoritative browser state model.
 * Captures real observed world state without injecting massive raw DOM into reasoning prompts.
 */
data class BrowserWorldModel(
    val currentUrl: String = "about:blank",
    val title: String = "",
    val loadingState: String = "idle",
    val viewport: String = "mobile",
    val scrollPosition: Int = 0,
    val activeTabId: String = "",
    val tabsCount: Int = 1,
    val dialogs: List<String> = emptyList(),
    val hasCookieBanner: Boolean = false,
    val hasLoginState: Boolean = false,
    val hasCaptchaState: Boolean = false,
    val formsCount: Int = 0,
    val interactiveElements: List<SemanticElement> = emptyList(),
    val candidateLinks: List<BrowserPageCandidate> = emptyList(),
    val candidatePrices: List<String> = emptyList(),
    val tablesCount: Int = 0,
    val lastAction: String? = null,
    val lastActionResult: String? = null,
    val lastVerifiedState: String? = null,
    val perceptionGenerationId: String = ""
) {
    /**
     * Converts world model to a token-efficient prompt summary for LLM reasoning.
     */
    fun toCompactPromptSummary(): String = buildString {
        appendLine("BROWSER WORLD STATE (Perception ID: $perceptionGenerationId):")
        appendLine("• URL: ${currentUrl.ifBlank { "about:blank" }}")
        appendLine("• Title: \"${title.ifBlank { "New Tab" }}\"")
        appendLine("• State: $loadingState | Scroll: ${scrollPosition}px | Active Tabs: $tabsCount")

        if (hasCaptchaState) {
            appendLine("⚠️ NOTICE: CAPTCHA / Bot Challenge detected on page.")
        }
        if (hasLoginState) {
            appendLine("🔒 NOTICE: Login / Authentication credentials form detected.")
        }
        if (hasCookieBanner) {
            appendLine("🍪 NOTICE: Cookie consent banner detected.")
        }

        if (!lastAction.isNullOrBlank()) {
            appendLine("• Last Action: $lastAction -> ${lastActionResult ?: "COMPLETED"}")
        }
        if (!lastVerifiedState.isNullOrBlank()) {
            appendLine("• Verified State: $lastVerifiedState")
        }

        if (candidateLinks.isNotEmpty()) {
            appendLine("\nTOP SEARCH CANDIDATES:")
            candidateLinks.take(8).forEach { c ->
                val flags = buildString {
                    if (c.isOfficial) append(" [OFFICIAL]")
                    if (c.isDownload) append(" [DOWNLOAD]")
                    if (c.isPrice) append(" [PRICE]")
                }
                appendLine("  #${c.index}: \"${c.title}\"$flags -> ${c.url}")
            }
        }

        if (interactiveElements.isNotEmpty()) {
            appendLine("\nINTERACTIVE ELEMENTS (Generation $perceptionGenerationId):")
            interactiveElements.take(12).forEach { el ->
                val label = el.labelOrText.ifBlank { el.placeholder.ifBlank { el.semanticId } }
                appendLine("  [ID: ${el.semanticId}] [${el.tag.uppercase()}${if (el.type.isNotBlank()) ":${el.type}" else ""}]: \"$label\"")
            }
        }

        if (candidatePrices.isNotEmpty()) {
            appendLine("\nDETECTED PRICES: ${candidatePrices.joinToString(", ")}")
        }
    }

    companion object {
        fun fromSnapshot(
            snapshot: PagePerceptionSnapshot,
            activeTabId: String = "",
            tabsCount: Int = 1,
            lastAction: String? = null,
            lastActionResult: String? = null,
            lastVerified: String? = null
        ): BrowserWorldModel {
            val hasCookie = snapshot.semanticElements.any {
                it.labelOrText.contains("cookie", ignoreCase = true) ||
                it.labelOrText.contains("accept all", ignoreCase = true) ||
                it.labelOrText.contains("agree", ignoreCase = true) && it.isClickable
            }
            val hasLogin = snapshot.semanticElements.any { it.type == "password" || it.labelOrText.contains("password", ignoreCase = true) }

            return BrowserWorldModel(
                currentUrl = snapshot.url,
                title = snapshot.title,
                loadingState = if (snapshot.loadingState.isLoaded) "ready" else snapshot.loadingState.readyState,
                scrollPosition = 0,
                activeTabId = activeTabId,
                tabsCount = tabsCount,
                hasCookieBanner = hasCookie,
                hasLoginState = hasLogin,
                hasCaptchaState = snapshot.hasCaptchaOrLogin,
                formsCount = snapshot.semanticElements.count { it.isInput },
                interactiveElements = snapshot.semanticElements,
                candidateLinks = snapshot.candidateLinks,
                candidatePrices = snapshot.candidatePrices,
                tablesCount = snapshot.extractedTables.size,
                lastAction = lastAction,
                lastActionResult = lastActionResult,
                lastVerifiedState = lastVerified,
                perceptionGenerationId = snapshot.generationId
            )
        }
    }
}
