package com.lichiai.browser.context

import kotlinx.serialization.Serializable

@Serializable
data class BrowserPageCandidate(
    val index: Int,
    val title: String,
    val url: String,
    val snippet: String? = null,
    val isOfficial: Boolean = false,
    val isDownload: Boolean = false,
    val isPrice: Boolean = false
)

@Serializable
data class BrowserInteractiveElement(
    val index: Int,
    val tag: String, // "a", "button", "input", "textarea", "select", "summary"
    val type: String = "", // "text", "password", "submit", "checkbox", "radio", "button"
    val text: String = "",
    val placeholder: String = "",
    val href: String = "",
    val name: String = "",
    val id: String = "",
    val ariaLabel: String = "",
    val isVisible: Boolean = true,
    val isClickable: Boolean = true,
    val isInput: Boolean = false,
    val value: String = "",
    val bounds: String = "" // "top,left,width,height"
)

@Serializable
data class BrowserTableData(
    val title: String = "",
    val headers: List<String> = emptyList(),
    val rows: List<List<String>> = emptyList()
)

@Serializable
data class BrowserPageState(
    val readyState: String = "complete", // "loading", "interactive", "complete"
    val scrollY: Int = 0,
    val maxScrollY: Int = 0,
    val textLength: Int = 0,
    val interactiveCount: Int = 0,
    val isLoaded: Boolean = true
)

@Serializable
data class BrowserTabSummary(
    val tabId: String,
    val title: String,
    val url: String,
    val isIncognito: Boolean = false,
    val isActive: Boolean = false,
    val category: String = "General"
)

enum class BrowserExecutionStatus {
    IDLE,
    OBSERVING,
    PLANNING,
    VALIDATING,
    EXECUTING,
    VERIFYING,
    RECOVERING,
    WAITING_CONFIRMATION,
    COMPLETED,
    FAILED,
    CANCELLED
}

data class BrowserConfirmationRequest(
    val confirmationId: String,
    val actionType: String,
    val description: String,
    val amount: String? = null,
    val riskLevel: String = "HIGH",
    val onConfirm: suspend () -> Unit,
    val onCancel: suspend () -> Unit
)

/**
 * Compact, isolated context containing strictly browser-specific state.
 * Does NOT contain external device states, other apps' accessibility nodes, or main chat history.
 */
data class BrowserTaskContext(
    val taskId: String = "",
    val perceptionGenerationId: String = "",
    val perceptionTimestamp: Long = System.currentTimeMillis(),
    val userGoal: String = "",
    val currentTabId: String = "",
    val currentUrl: String = "",
    val currentTitle: String = "",
    val previousUrl: String? = null,
    val lastAction: String? = null,
    val lastActionResult: String? = null,
    val pageState: String = "IDLE", // LOADING, READY, ERROR
    val activeElement: String? = null,
    val searchQuery: String? = null,
    val extractedCandidates: List<BrowserPageCandidate> = emptyList(),
    val interactiveElements: List<BrowserInteractiveElement> = emptyList(),
    val pageMetrics: BrowserPageState = BrowserPageState(),
    val candidatePrices: List<String> = emptyList(),
    val extractedTables: List<BrowserTableData> = emptyList(),
    val openTabs: List<BrowserTabSummary> = emptyList(),
    val targetTopic: String? = null,
    val requiresWebsiteOpen: Boolean = false,
    val attemptedCandidateIndices: List<Int> = emptyList(),
    val stepCount: Int = 0,
    val executionStatus: BrowserExecutionStatus = BrowserExecutionStatus.IDLE,
    val errorNotice: String? = null,
    val pendingConfirmation: BrowserConfirmationRequest? = null
)

