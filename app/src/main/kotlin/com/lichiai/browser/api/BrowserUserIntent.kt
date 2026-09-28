package com.lichiai.browser.api

/**
 * Structured User Intent representations for deterministic local interpretation.
 */
sealed class TargetCriterion {
    data object Official : TargetCriterion()
    data class Relevant(val subject: String) : TargetCriterion()
    data object Price : TargetCriterion()
    data object Download : TargetCriterion()
    data class Ordinal(val index: Int) : TargetCriterion()
    data class Custom(val description: String) : TargetCriterion()
}

sealed class BrowserUserIntent {
    data object OpenBrowser : BrowserUserIntent()
    data class Search(val query: String, val searchEngine: String? = null) : BrowserUserIntent()
    data class SearchAndOpen(
        val searchQuery: String,
        val targetCriterion: TargetCriterion = TargetCriterion.Official,
        val searchEngine: String? = null
    ) : BrowserUserIntent()
    data class NavigateUrl(val url: String) : BrowserUserIntent()
    data class ClickCandidate(val index: Int? = null, val semanticDescription: String? = null) : BrowserUserIntent()
    data class Scroll(val direction: ScrollDirection, val amount: Int = 1) : BrowserUserIntent()
    data object GoBack : BrowserUserIntent()
    data object GoForward : BrowserUserIntent()
    data object Reload : BrowserUserIntent()
    data class OpenNewTab(val url: String? = null) : BrowserUserIntent()
    data object CloseCurrentTab : BrowserUserIntent()
    data class SwitchTab(val tabIndex: Int) : BrowserUserIntent()
    data class FindOnPage(val text: String) : BrowserUserIntent()
    data class ExtractInformation(val query: String, val category: String? = null) : BrowserUserIntent()
    data class DownloadTarget(val targetDescription: String? = null) : BrowserUserIntent()
    data object StopTask : BrowserUserIntent()
    data class SemanticGoal(val naturalLanguageGoal: String) : BrowserUserIntent()
}

enum class ScrollDirection {
    DOWN,
    UP,
    TOP,
    BOTTOM
}

