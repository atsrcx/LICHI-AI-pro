package com.lichiai.browser.runtime

import kotlinx.serialization.Serializable

/**
 * High-level structured intent categories for Autonomous Browser Intelligence.
 */
@Serializable
enum class BrowserTaskType {
    OPEN_BROWSER,
    NAVIGATE,
    SEARCH,
    SEARCH_AND_OPEN,
    FIND_INFORMATION,
    COMPARE,
    FILL_FORM,
    DOWNLOAD,
    READ_PAGE,
    CLICK_TARGET,
    SCROLL_AND_FIND,
    MULTI_STEP_WEB_TASK,
    RESEARCH,
    MONITOR_PAGE
}

/**
 * Structured Browser Task Intent contract between LichiCentralBrain and BrowserAgentRuntime.
 * The Intent Handler & Central Brain output WHAT high-level goal to achieve;
 * BrowserAgentRuntime decides HOW to achieve it.
 */
@Serializable
data class BrowserTaskIntent(
    val domain: String = "BROWSER",
    val goal: String,
    val taskType: BrowserTaskType = BrowserTaskType.MULTI_STEP_WEB_TASK,
    val target: String? = null,
    val constraints: Map<String, String> = emptyMap(),
    val expectedOutcome: String? = null,
    val requiresCurrentPage: Boolean = false,
    val searchEngine: String? = null
) {
    companion object {
        fun fromGoal(goal: String, expectedOutcome: String? = null): BrowserTaskIntent {
            val lower = goal.lowercase()
            val type = when {
                lower.startsWith("open ") && (lower.contains("http://") || lower.contains("https://") || lower.contains(".com")) -> BrowserTaskType.NAVIGATE
                lower.contains("research") || lower.contains("compare") -> BrowserTaskType.RESEARCH
                lower.contains("download") -> BrowserTaskType.DOWNLOAD
                lower.contains("fill") || lower.contains("form") || lower.contains("login") || lower.contains("signup") -> BrowserTaskType.FILL_FORM
                (lower.contains("search") || lower.contains("google") || lower.contains("dhundo") || lower.contains("find")) && (lower.contains("open") || lower.contains("kholo") || lower.contains("play") || lower.contains("visit")) -> BrowserTaskType.SEARCH_AND_OPEN
                lower.contains("search") || lower.contains("google") || lower.contains("dhundo") -> BrowserTaskType.SEARCH
                lower.contains("read") || lower.contains("extract") || lower.contains("summary") -> BrowserTaskType.READ_PAGE
                lower.contains("click") || lower.contains("tap") -> BrowserTaskType.CLICK_TARGET
                else -> BrowserTaskType.MULTI_STEP_WEB_TASK
            }
            return BrowserTaskIntent(
                goal = goal,
                taskType = type,
                expectedOutcome = expectedOutcome
            )
        }
    }
}
