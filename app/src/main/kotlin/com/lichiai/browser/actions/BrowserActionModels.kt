package com.lichiai.browser.actions

import com.lichiai.browser.api.ScrollDirection
import com.lichiai.browser.context.BrowserTableData
import kotlinx.serialization.Serializable

/**
 * Structured typed actions supported by the Autonomous Browser Intelligence Engine.
 * Conforms to Section 10 requirements.
 */
sealed class TypedBrowserAction {
    data class OpenURL(val url: String) : TypedBrowserAction()
    object Back : TypedBrowserAction()
    object Forward : TypedBrowserAction()
    object Reload : TypedBrowserAction()
    data class TapElement(val targetIdOrIndex: String) : TypedBrowserAction()
    data class LongPress(val targetIdOrIndex: String) : TypedBrowserAction()
    data class TypeText(val targetIdOrIndex: String, val text: String, val submit: Boolean = false) : TypedBrowserAction()
    data class ClearText(val targetIdOrIndex: String) : TypedBrowserAction()
    data class SelectOption(val targetIdOrIndex: String, val value: String) : TypedBrowserAction()
    data class Scroll(val direction: ScrollDirection, val amount: Int = 1) : TypedBrowserAction()
    data class Swipe(val direction: String) : TypedBrowserAction()
    data class PressEnter(val targetIdOrIndex: String? = null) : TypedBrowserAction()
    data class SubmitForm(val targetIdOrIndex: String? = null) : TypedBrowserAction()
    data class OpenNewTab(val url: String? = null, val isIncognito: Boolean = false) : TypedBrowserAction()
    data class CloseTab(val tabId: String) : TypedBrowserAction()
    data class SwitchTab(val tabId: String) : TypedBrowserAction()
    data class FindOnPage(val keyword: String) : TypedBrowserAction()
    object ExtractText : TypedBrowserAction()
    object ExtractTable : TypedBrowserAction()
    object ExtractLinks : TypedBrowserAction()
    data class WaitForElement(val selectorOrText: String, val timeoutMs: Long = 3000L) : TypedBrowserAction()
    data class Download(val url: String, val fileName: String? = null) : TypedBrowserAction()
    data class Upload(val targetIdOrIndex: String, val filePath: String) : TypedBrowserAction()
    data class AskUser(val question: String) : TypedBrowserAction()
    data class Confirm(val prompt: String, val actionToConfirm: String) : TypedBrowserAction()
    data class Done(val summary: String) : TypedBrowserAction()
    data class Failed(val reason: String) : TypedBrowserAction()
}

enum class ActionExecutionStatus {
    SUCCESS,
    FAILED,
    STALE_ELEMENT,
    PAUSED_FOR_USER,
    REQUIRES_CONFIRMATION,
    TIMEOUT,
    CANCELLED
}

enum class UserInterventionKind {
    NONE,
    LOGIN_REQUIRED,
    CAPTCHA_REQUIRED,
    OTP_REQUIRED,
    HIGH_RISK_CONFIRMATION
}

/**
 * Structured execution result returned after every browser action.
 */
data class BrowserActionResult(
    val status: ActionExecutionStatus,
    val actionName: String,
    val isSuccess: Boolean,
    val message: String,
    val currentUrl: String = "",
    val currentTitle: String = "",
    val interventionKind: UserInterventionKind = UserInterventionKind.NONE,
    val interventionPrompt: String? = null,
    val extractedText: String? = null,
    val extractedLinksCount: Int = 0,
    val extractedTables: List<BrowserTableData> = emptyList(),
    val error: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)
