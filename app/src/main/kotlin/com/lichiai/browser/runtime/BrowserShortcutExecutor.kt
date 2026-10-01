package com.lichiai.browser.runtime

import com.lichiai.browser.BrowserController
import com.lichiai.browser.actions.BrowserActionEngine
import com.lichiai.browser.actions.TypedBrowserAction
import com.lichiai.browser.agent.BrowserExecutionResult
import com.lichiai.browser.api.BrowserCommandParser
import com.lichiai.browser.api.BrowserUserIntent
import com.lichiai.browser.api.ScrollDirection
import com.lichiai.browser.events.BrowserEvent
import com.lichiai.browser.events.BrowserEventBus
import com.lichiai.browser.perception.BrowserPerceptionLayer
import com.lichiai.browser.storage.BrowserStorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder

/**
 * Deterministic Browser Shortcut Compatibility Executor.
 * Executes explicit hash commands (#open, #google, #back, #forward, #reload, #scroll)
 * without invoking autonomous LLM planning loops.
 */
class BrowserShortcutExecutor(
    private val browserController: BrowserController,
    private val actionEngine: BrowserActionEngine,
    private val perceptionLayer: BrowserPerceptionLayer,
    private val storageManager: BrowserStorageManager,
    private val eventBus: BrowserEventBus
) {

    suspend fun executeShortcut(
        taskId: String,
        rawInput: String,
        onProgress: ((step: Int, total: Int, text: String) -> Unit)? = null
    ): BrowserExecutionResult = withContext(Dispatchers.Main) {
        val parsed = BrowserCommandParser.parse(rawInput)
        val defaultEngineUrl = storageManager.settings.value.searchEngineUrl

        val actions = mutableListOf<TypedBrowserAction>()
        when (parsed) {
            is BrowserUserIntent.NavigateUrl -> actions.add(TypedBrowserAction.OpenURL(parsed.url))
            is BrowserUserIntent.Search -> {
                val searchUrl = if (!parsed.searchEngine.isNullOrBlank() && parsed.searchEngine.equals("youtube", ignoreCase = true)) {
                    "https://www.youtube.com/results?search_query=${URLEncoder.encode(parsed.query, "UTF-8")}"
                } else {
                    "${defaultEngineUrl.trimEnd('/')}/search?q=${URLEncoder.encode(parsed.query, "UTF-8")}"
                }
                actions.add(TypedBrowserAction.OpenURL(searchUrl))
            }
            is BrowserUserIntent.SearchAndOpen -> {
                val searchUrl = "${defaultEngineUrl.trimEnd('/')}/search?q=${URLEncoder.encode(parsed.searchQuery, "UTF-8")}"
                actions.add(TypedBrowserAction.OpenURL(searchUrl))
            }
            is BrowserUserIntent.GoBack -> actions.add(TypedBrowserAction.Back())
            is BrowserUserIntent.GoForward -> actions.add(TypedBrowserAction.Forward())
            is BrowserUserIntent.Reload -> actions.add(TypedBrowserAction.Reload())
            is BrowserUserIntent.Scroll -> actions.add(TypedBrowserAction.Scroll(parsed.direction, parsed.amount))
            is BrowserUserIntent.OpenNewTab -> actions.add(TypedBrowserAction.OpenNewTab(parsed.url))
            is BrowserUserIntent.CloseCurrentTab -> actions.add(TypedBrowserAction.CloseTab(browserController.tabManager.activeTabId.value ?: ""))
            is BrowserUserIntent.SwitchTab -> actions.add(TypedBrowserAction.SwitchTab(parsed.tabIndex.toString()))
            is BrowserUserIntent.ClickCandidate -> {
                parsed.index?.let { actions.add(TypedBrowserAction.TapElement(it.toString())) }
            }
            is BrowserUserIntent.StopTask -> {
                return@withContext BrowserExecutionResult(isSuccess = true, summary = "Browser task stopped.")
            }
            else -> {
                // Fallback direct URL or search
                val clean = rawInput.removePrefix("#").trim()
                if (clean.startsWith("http://") || clean.startsWith("https://")) {
                    actions.add(TypedBrowserAction.OpenURL(clean))
                } else {
                    val searchUrl = "${defaultEngineUrl.trimEnd('/')}/search?q=${URLEncoder.encode(clean, "UTF-8")}"
                    actions.add(TypedBrowserAction.OpenURL(searchUrl))
                }
            }
        }

        var finalSummary = ""
        var allSuccess = true
        for ((idx, action) in actions.withIndex()) {
            onProgress?.invoke(idx + 1, actions.size, "Executing shortcut: ${action::class.simpleName}")
            val res = actionEngine.executeAction(action)
            finalSummary = res.message
            if (!res.isSuccess) {
                allSuccess = false
                break
            }
        }

        // Await page ready condition
        BrowserConditionWaiter.waitForPageReady(3000L) { browserController.activeEngine.value }
        val freshSnapshot = perceptionLayer.observePage(browserController.activeEngine.value)

        eventBus.emit(BrowserEvent.TaskCompleted(taskId, finalSummary))

        BrowserExecutionResult(
            isSuccess = allSuccess,
            summary = finalSummary,
            extractedContext = freshSnapshot.visibleTextSnippet.takeIf { it.isNotBlank() },
            finalUrl = freshSnapshot.url,
            pageTitle = freshSnapshot.title
        )
    }
}
