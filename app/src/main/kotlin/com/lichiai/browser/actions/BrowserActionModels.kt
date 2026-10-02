package com.lichiai.browser.actions

import com.lichiai.browser.api.ScrollDirection
import com.lichiai.browser.context.BrowserTableData
import kotlinx.serialization.Serializable

/**
 * Structured typed actions supported by the Autonomous Browser Intelligence Engine.
 * Supports end-to-end generationId tracking to prevent executing on stale DOM generations.
 */
sealed class TypedBrowserAction {
    abstract val generationId: String

    data class OpenURL(val url: String, override val generationId: String) : TypedBrowserAction()
    data class Back(override val generationId: String) : TypedBrowserAction()
    data class Forward(override val generationId: String) : TypedBrowserAction()
    data class Reload(override val generationId: String) : TypedBrowserAction()
    data class TapElement(val targetIdOrIndex: String, override val generationId: String) : TypedBrowserAction()
    data class LongPress(val targetIdOrIndex: String, override val generationId: String) : TypedBrowserAction()
    data class TypeText(val targetIdOrIndex: String, val text: String, val submit: Boolean = false, override val generationId: String) : TypedBrowserAction()
    data class ClearText(val targetIdOrIndex: String, override val generationId: String) : TypedBrowserAction()
    data class SelectOption(val targetIdOrIndex: String, val value: String, override val generationId: String) : TypedBrowserAction()
    data class Scroll(val direction: ScrollDirection, val amount: Int = 1, override val generationId: String) : TypedBrowserAction()
    data class Swipe(val direction: String, override val generationId: String) : TypedBrowserAction()
    data class PressEnter(val targetIdOrIndex: String? = null, override val generationId: String) : TypedBrowserAction()
    data class SubmitForm(val targetIdOrIndex: String? = null, override val generationId: String) : TypedBrowserAction()
    data class OpenNewTab(val url: String? = null, val isIncognito: Boolean = false, override val generationId: String) : TypedBrowserAction()
    data class CloseTab(val tabId: String, override val generationId: String) : TypedBrowserAction()
    data class SwitchTab(val tabId: String, override val generationId: String) : TypedBrowserAction()
    data class FindOnPage(val keyword: String, override val generationId: String) : TypedBrowserAction()
    data class ExtractText(override val generationId: String) : TypedBrowserAction()
    data class ExtractTable(override val generationId: String) : TypedBrowserAction()
    data class ExtractLinks(override val generationId: String) : TypedBrowserAction()
    data class WaitForElement(val selectorOrText: String, val timeoutMs: Long = 3000L, override val generationId: String) : TypedBrowserAction()
    data class Download(val url: String, val fileName: String? = null, override val generationId: String) : TypedBrowserAction()
    data class Upload(val targetIdOrIndex: String, val filePath: String, override val generationId: String) : TypedBrowserAction()
    data class AskUser(val question: String, override val generationId: String) : TypedBrowserAction()
    data class Confirm(val prompt: String, val actionToConfirm: String, override val generationId: String) : TypedBrowserAction()
    data class Done(val summary: String, override val generationId: String) : TypedBrowserAction()
    data class Failed(val reason: String, override val generationId: String) : TypedBrowserAction()
}

enum class ActionExecutionStatus {
    SUCCESS,
    FAILED,
    STALE_ELEMENT,
    STALE_TARGET_GENERATION,
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
    val generationId: String = "",
    val interventionKind: UserInterventionKind = UserInterventionKind.NONE,
    val interventionPrompt: String? = null,
    val extractedText: String? = null,
    val extractedLinksCount: Int = 0,
    val extractedTables: List<BrowserTableData> = emptyList(),
    val error: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)
