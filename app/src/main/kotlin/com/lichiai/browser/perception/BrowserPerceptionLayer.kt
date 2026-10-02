package com.lichiai.browser.perception

import android.graphics.Bitmap
import com.lichiai.browser.context.BrowserInteractiveElement
import com.lichiai.browser.context.BrowserPageCandidate
import com.lichiai.browser.context.BrowserPageState
import com.lichiai.browser.context.BrowserTableData
import com.lichiai.browser.context.BrowserTaskContext
import com.lichiai.browser.engine.ChromiumWebViewEngine
import com.lichiai.browser.security.WebSecuritySanitizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Temporary semantic representation of an interactive DOM/Accessibility element.
 * Generated uniquely per perception snapshot; invalidated on page navigation or reload.
 */
data class SemanticElement(
    val semanticId: String,          // e.g. "search_input_1", "button_submit_2", "link_source_5"
    val originalIndex: Int,
    val generationId: String,        // Snapshot UUID to prevent stale element execution
    val tag: String,
    val type: String,
    val labelOrText: String,
    val placeholder: String,
    val href: String,
    val isClickable: Boolean,
    val isInput: Boolean,
    val value: String,
    val bounds: String,
    val inputValue: String? = null,
    val inputValueAvailable: Boolean = false,
    val sensitive: Boolean = false
)

/**
 * Multi-layer perception snapshot of the visible browser page.
 */
data class PagePerceptionSnapshot(
    val generationId: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val url: String,
    val title: String,
    val loadingState: BrowserPageState,
    val visibleTextSnippet: String,
    val semanticElements: List<SemanticElement>,
    val candidateLinks: List<BrowserPageCandidate>,
    val candidatePrices: List<String>,
    val extractedTables: List<BrowserTableData>,
    val screenshot: Bitmap? = null,
    val hasCaptchaOrLogin: Boolean = false,
    val isStale: Boolean = false
)

/**
 * Perception Layer for Browser Intelligence.
 * Observes DOM, accessibility semantics, layout, and visual state.
 * Maintains generation tracking to automatically detect and invalidate stale elements.
 */
class BrowserPerceptionLayer {

    private var activeSnapshot: PagePerceptionSnapshot? = null
    private var lastUrl: String = ""

    /**
     * Captures a complete multi-layer perception of the active WebView engine.
     */
    suspend fun observePage(
        engine: ChromiumWebViewEngine?,
        includeScreenshot: Boolean = false
    ): PagePerceptionSnapshot = withContext(Dispatchers.Main) {
        if (engine == null) {
            val empty = PagePerceptionSnapshot(
                url = "about:blank",
                title = "",
                loadingState = BrowserPageState(),
                visibleTextSnippet = "",
                semanticElements = emptyList(),
                candidateLinks = emptyList(),
                candidatePrices = emptyList(),
                extractedTables = emptyList()
            )
            activeSnapshot = empty
            return@withContext empty
        }

        val generationId = UUID.randomUUID().toString()
        val currentUrl = engine.getUrl()
        val title = engine.getTitle()
        val pageState = engine.getPageState()

        val rawInteractive = engine.extractInteractiveElements()
        val rawCandidates = engine.extractCandidateLinks()
        val rawPrices = engine.extractPrices()
        val rawTables = engine.extractTables()
        val rawText = WebSecuritySanitizer.sanitizeWebContent(engine.extractTextSnippet())

        // Map to semantic identities
        val semanticEls = rawInteractive.map { el ->
            val role = when {
                el.tag == "input" && (el.type == "search" || el.name.contains("q") || el.placeholder.contains("search", ignoreCase = true)) -> "search_input"
                el.tag == "input" && el.type == "password" -> "password_input"
                el.tag == "input" && (el.type == "submit" || el.type == "button") -> "button_submit"
                el.tag == "button" -> "button"
                el.tag == "a" -> "link"
                el.isInput -> "input"
                else -> el.tag
            }
            val idBase = if (el.id.isNotBlank()) el.id else "${role}_${el.index}"
            val isSensitive = el.type.equals("password", ignoreCase = true) ||
                    el.name.contains("password", ignoreCase = true) ||
                    el.name.contains("otp", ignoreCase = true) ||
                    el.placeholder.contains("password", ignoreCase = true) ||
                    el.placeholder.contains("otp", ignoreCase = true) ||
                    el.placeholder.contains("cvv", ignoreCase = true) ||
                    el.ariaLabel.contains("password", ignoreCase = true) ||
                    el.ariaLabel.contains("otp", ignoreCase = true)

            SemanticElement(
                semanticId = idBase,
                originalIndex = el.index,
                generationId = generationId,
                tag = el.tag,
                type = el.type,
                labelOrText = el.text.ifBlank { el.ariaLabel },
                placeholder = el.placeholder,
                href = el.href,
                isClickable = el.isClickable,
                isInput = el.isInput,
                value = if (isSensitive) "" else el.value,
                bounds = el.bounds,
                inputValue = if (isSensitive) null else (if (el.isInput) el.value else null),
                inputValueAvailable = !isSensitive && el.isInput,
                sensitive = isSensitive
            )
        }

        val hasCaptcha = rawInteractive.any {
            it.id.contains("recaptcha", ignoreCase = true) ||
            it.ariaLabel.contains("captcha", ignoreCase = true) ||
            it.name.contains("captcha", ignoreCase = true)
        } || rawText.contains("Verify you are human", ignoreCase = true) ||
             rawText.contains("Cloudflare", ignoreCase = true) && rawText.contains("challenge", ignoreCase = true)

        val screenshot = if (includeScreenshot) engine.captureScreenshot() else null

        val snapshot = PagePerceptionSnapshot(
            generationId = generationId,
            timestamp = System.currentTimeMillis(),
            url = currentUrl,
            title = title,
            loadingState = pageState,
            visibleTextSnippet = rawText,
            semanticElements = semanticEls,
            candidateLinks = rawCandidates,
            candidatePrices = rawPrices,
            extractedTables = rawTables,
            screenshot = screenshot,
            hasCaptchaOrLogin = hasCaptcha,
            isStale = false
        )

        lastUrl = currentUrl
        activeSnapshot = snapshot
        snapshot
    }

    /**
     * Resolves a target semantic element. Verifies whether the reference belongs to the
     * current active perception generation. Returns null if stale or not found.
     */
    fun resolveElement(semanticIdOrIndex: String): SemanticElement? {
        val snap = activeSnapshot ?: return null
        if (snap.isStale) return null

        // Try exact semanticId
        snap.semanticElements.firstOrNull { it.semanticId == semanticIdOrIndex }?.let { return it }

        // Try index lookup
        val idx = semanticIdOrIndex.toIntOrNull()
        if (idx != null) {
            return snap.semanticElements.firstOrNull { it.originalIndex == idx }
        }

        // Fuzzy match on label/text or id
        return snap.semanticElements.firstOrNull {
            it.labelOrText.contains(semanticIdOrIndex, ignoreCase = true) ||
            it.placeholder.contains(semanticIdOrIndex, ignoreCase = true)
        }
    }

    /**
     * Explicitly invalidates current perception cache (e.g. following navigation or submit).
     */
    fun invalidatePerception() {
        activeSnapshot = activeSnapshot?.copy(isStale = true)
    }

    fun getActiveSnapshot(): PagePerceptionSnapshot? = activeSnapshot
}
