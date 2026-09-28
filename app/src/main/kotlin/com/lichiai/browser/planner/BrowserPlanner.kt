package com.lichiai.browser.planner

import com.lichiai.browser.api.BrowserCommandParser
import com.lichiai.browser.api.BrowserUserIntent
import com.lichiai.browser.api.ScrollDirection
import com.lichiai.browser.context.BrowserTaskContext

data class BrowserActionStep(
    val id: String,
    val toolName: String,
    val arguments: Map<String, String>,
    val userSummary: String,
    val requiresVerification: Boolean = true
)

data class BrowserPlan(
    val goal: String,
    val steps: List<BrowserActionStep>,
    val zeroLlmExecuted: Boolean = true,
    val explanation: String? = null
)

/**
 * Isolated Browser Planner.
 * Strictly prioritizes deterministic zero-LLM local planning for routine browser operations.
 */
object BrowserPlanner {

    fun planFromIntent(
        intent: BrowserUserIntent,
        context: BrowserTaskContext,
        defaultSearchEngineUrl: String
    ): BrowserPlan {
        return when (intent) {
            is BrowserUserIntent.OpenBrowser -> {
                BrowserPlan(
                    goal = "Open Browser",
                    steps = listOf(
                        BrowserActionStep(
                            id = "open_browser_1",
                            toolName = "navigate",
                            arguments = mapOf("url" to "https://www.google.com"),
                            userSummary = "Opening browser",
                            requiresVerification = false
                        )
                    ),
                    zeroLlmExecuted = true
                )
            }

            is BrowserUserIntent.Search -> {
                BrowserPlan(
                    goal = "Search for \"${intent.query}\"",
                    steps = listOf(
                        BrowserActionStep(
                            id = "search_1",
                            toolName = "search",
                            arguments = mapOf(
                                "query" to intent.query,
                                "engine" to (intent.searchEngine ?: "")
                            ),
                            userSummary = "Searching for \"${intent.query}\"",
                            requiresVerification = true
                        )
                    ),
                    zeroLlmExecuted = true
                )
            }

            is BrowserUserIntent.SearchAndOpen -> {
                BrowserPlan(
                    goal = "Search \"${intent.searchQuery}\" and open target",
                    steps = listOf(
                        BrowserActionStep(
                            id = "search_step_1",
                            toolName = "search",
                            arguments = mapOf(
                                "query" to intent.searchQuery,
                                "engine" to (intent.searchEngine ?: "")
                            ),
                            userSummary = "Searching for \"${intent.searchQuery}\"",
                            requiresVerification = true
                        )
                    ),
                    zeroLlmExecuted = true
                )
            }

            is BrowserUserIntent.NavigateUrl -> {
                BrowserPlan(
                    goal = "Open ${intent.url}",
                    steps = listOf(
                        BrowserActionStep(
                            id = "nav_1",
                            toolName = "navigate",
                            arguments = mapOf("url" to intent.url),
                            userSummary = "Navigating to ${intent.url}",
                            requiresVerification = true
                        )
                    ),
                    zeroLlmExecuted = true
                )
            }

            is BrowserUserIntent.ClickCandidate -> {
                val index = intent.index ?: run {
                    // If semantic description like "official website", look in extracted candidates
                    if (intent.semanticDescription != null && context.extractedCandidates.isNotEmpty()) {
                        val official = context.extractedCandidates.firstOrNull { it.isOfficial }
                        official?.index ?: 1
                    } else 1
                }
                BrowserPlan(
                    goal = "Open result #$index",
                    steps = listOf(
                        BrowserActionStep(
                            id = "click_cand_1",
                            toolName = "clickCandidate",
                            arguments = mapOf("index" to index.toString()),
                            userSummary = "Opening result #$index",
                            requiresVerification = true
                        )
                    ),
                    zeroLlmExecuted = true
                )
            }

            is BrowserUserIntent.Scroll -> {
                val dirName = intent.direction.name
                val desc = when (intent.direction) {
                    ScrollDirection.DOWN -> "Scrolling down"
                    ScrollDirection.UP -> "Scrolling up"
                    ScrollDirection.TOP -> "Scrolling to top"
                    ScrollDirection.BOTTOM -> "Scrolling to bottom"
                }
                BrowserPlan(
                    goal = desc,
                    steps = listOf(
                        BrowserActionStep(
                            id = "scroll_1",
                            toolName = "scroll",
                            arguments = mapOf(
                                "direction" to dirName,
                                "amount" to intent.amount.toString()
                            ),
                            userSummary = desc,
                            requiresVerification = false
                        )
                    ),
                    zeroLlmExecuted = true
                )
            }

            is BrowserUserIntent.GoBack -> {
                BrowserPlan(
                    goal = "Go back",
                    steps = listOf(
                        BrowserActionStep(
                            id = "back_1",
                            toolName = "goBack",
                            arguments = emptyMap(),
                            userSummary = "Going back",
                            requiresVerification = true
                        )
                    ),
                    zeroLlmExecuted = true
                )
            }

            is BrowserUserIntent.GoForward -> {
                BrowserPlan(
                    goal = "Go forward",
                    steps = listOf(
                        BrowserActionStep(
                            id = "fwd_1",
                            toolName = "goForward",
                            arguments = emptyMap(),
                            userSummary = "Going forward",
                            requiresVerification = true
                        )
                    ),
                    zeroLlmExecuted = true
                )
            }

            is BrowserUserIntent.Reload -> {
                BrowserPlan(
                    goal = "Reload page",
                    steps = listOf(
                        BrowserActionStep(
                            id = "reload_1",
                            toolName = "reload",
                            arguments = emptyMap(),
                            userSummary = "Reloading page",
                            requiresVerification = false
                        )
                    ),
                    zeroLlmExecuted = true
                )
            }

            is BrowserUserIntent.OpenNewTab -> {
                BrowserPlan(
                    goal = "Open new tab",
                    steps = listOf(
                        BrowserActionStep(
                            id = "new_tab_1",
                            toolName = "openTab",
                            arguments = mapOf("url" to (intent.url ?: "about:blank")),
                            userSummary = "Opening new tab",
                            requiresVerification = false
                        )
                    ),
                    zeroLlmExecuted = true
                )
            }

            is BrowserUserIntent.ExtractInformation -> {
                val tool = if (intent.category == "price") "extractPrices" else "extractPageSummary"
                val summary = if (intent.category == "price") "Checking prices on page" else "Reading page text"
                BrowserPlan(
                    goal = "Extract information from current page",
                    steps = listOf(
                        BrowserActionStep(
                            id = "extract_1",
                            toolName = tool,
                            arguments = emptyMap(),
                            userSummary = summary,
                            requiresVerification = false
                        )
                    ),
                    zeroLlmExecuted = true
                )
            }

            is BrowserUserIntent.DownloadTarget -> {
                BrowserPlan(
                    goal = "Find download link",
                    steps = listOf(
                        BrowserActionStep(
                            id = "dl_1",
                            toolName = "clickSelector",
                            arguments = mapOf("text" to "download"),
                            userSummary = "Looking for download button",
                            requiresVerification = true
                        )
                    ),
                    zeroLlmExecuted = true
                )
            }

            is BrowserUserIntent.StopTask -> {
                BrowserPlan(
                    goal = "Stop browser task",
                    steps = listOf(
                        BrowserActionStep(
                            id = "stop_1",
                            toolName = "stopTask",
                            arguments = emptyMap(),
                            userSummary = "Task stopped",
                            requiresVerification = false
                        )
                    ),
                    zeroLlmExecuted = true
                )
            }

            is BrowserUserIntent.CloseCurrentTab -> {
                BrowserPlan(
                    goal = "Close current tab",
                    steps = listOf(
                        BrowserActionStep(
                            id = "close_tab_1",
                            toolName = "closeTab",
                            arguments = emptyMap(),
                            userSummary = "Closing tab",
                            requiresVerification = false
                        )
                    ),
                    zeroLlmExecuted = true
                )
            }

            is BrowserUserIntent.FindOnPage -> {
                BrowserPlan(
                    goal = "Find \"${intent.text}\" on page",
                    steps = listOf(
                        BrowserActionStep(
                            id = "find_1",
                            toolName = "extractPageSummary",
                            arguments = emptyMap(),
                            userSummary = "Searching for \"${intent.text}\"",
                            requiresVerification = false
                        )
                    ),
                    zeroLlmExecuted = true
                )
            }

            is BrowserUserIntent.SwitchTab -> {
                BrowserPlan(
                    goal = "Switch to tab #${intent.tabIndex}",
                    steps = emptyList(),
                    zeroLlmExecuted = true
                )
            }

            is BrowserUserIntent.SemanticGoal -> {
                // Return null plan indicating fallback to LLM is needed for complex goal
                BrowserPlan(
                    goal = intent.naturalLanguageGoal,
                    steps = emptyList(),
                    zeroLlmExecuted = false,
                    explanation = "Requires semantic interpretation"
                )
            }
        }
    }
}
