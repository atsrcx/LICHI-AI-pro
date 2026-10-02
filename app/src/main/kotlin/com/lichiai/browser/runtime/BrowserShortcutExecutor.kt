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

        if (parsed is BrowserUserIntent.StopTask) {
            return@withContext BrowserExecutionResult(isSuccess = true, summary = "Browser task stopped.")
        }

        // 1. Initial snapshot observation to bind generationId
        var currentSnapshot = perceptionLayer.observePage(browserController.activeEngine.value)
        var genId = currentSnapshot.generationId

        val actionBuilders = mutableListOf<(String) -> TypedBrowserAction>()
        when (parsed) {
            is BrowserUserIntent.NavigateUrl -> actionBuilders.add { g -> TypedBrowserAction.OpenURL(parsed.url, generationId = g) }
            is BrowserUserIntent.Search -> {
                val searchUrl = if (!parsed.searchEngine.isNullOrBlank() && parsed.searchEngine.equals("youtube", ignoreCase = true)) {
                    "https://www.youtube.com/results?search_query=${URLEncoder.encode(parsed.query, "UTF-8")}"
                } else {
                    "${defaultEngineUrl.trimEnd('/')}/search?q=${URLEncoder.encode(parsed.query, "UTF-8")}"
                }
                actionBuilders.add { g -> TypedBrowserAction.OpenURL(searchUrl, generationId = g) }
            }
            is BrowserUserIntent.SearchAndOpen -> {
                val searchUrl = "${defaultEngineUrl.trimEnd('/')}/search?q=${URLEncoder.encode(parsed.searchQuery, "UTF-8")}"
                actionBuilders.add { g -> TypedBrowserAction.OpenURL(searchUrl, generationId = g) }
            }
            is BrowserUserIntent.GoBack -> actionBuilders.add { g -> TypedBrowserAction.Back(generationId = g) }
            is BrowserUserIntent.GoForward -> actionBuilders.add { g -> TypedBrowserAction.Forward(generationId = g) }
            is BrowserUserIntent.Reload -> actionBuilders.add { g -> TypedBrowserAction.Reload(generationId = g) }
            is BrowserUserIntent.Scroll -> actionBuilders.add { g -> TypedBrowserAction.Scroll(parsed.direction, parsed.amount, generationId = g) }
            is BrowserUserIntent.OpenNewTab -> actionBuilders.add { g -> TypedBrowserAction.OpenNewTab(parsed.url, generationId = g) }
            is BrowserUserIntent.CloseCurrentTab -> actionBuilders.add { g -> TypedBrowserAction.CloseTab(browserController.tabManager.activeTabId.value ?: "", generationId = g) }
            is BrowserUserIntent.SwitchTab -> actionBuilders.add { g -> TypedBrowserAction.SwitchTab(parsed.tabIndex.toString(), generationId = g) }
            is BrowserUserIntent.ClickCandidate -> {
                parsed.index?.let { idx ->
                    actionBuilders.add { g -> TypedBrowserAction.TapElement(idx.toString(), generationId = g) }
                }
            }
            else -> {
                // Fallback direct URL or search
                val clean = rawInput.removePrefix("#").trim()
                if (clean.startsWith("http://") || clean.startsWith("https://")) {
                    actionBuilders.add { g -> TypedBrowserAction.OpenURL(clean, generationId = g) }
                } else {
                    val searchUrl = "${defaultEngineUrl.trimEnd('/')}/search?q=${URLEncoder.encode(clean, "UTF-8")}"
                    actionBuilders.add { g -> TypedBrowserAction.OpenURL(searchUrl, generationId = g) }
                }
            }
        }

        var finalSummary = ""
        var allSuccess = true
        for ((idx, actionBuilder) in actionBuilders.withIndex()) {
            if (idx > 0) {
                // Multi-step sequence: re-observe fresh snapshot before subsequent action
                BrowserConditionWaiter.waitForDomStable(settleMs = 150L, timeoutMs = 1500L) { browserController.activeEngine.value }
                currentSnapshot = perceptionLayer.observePage(browserController.activeEngine.value)
                genId = currentSnapshot.generationId
            }

            val action = actionBuilder(genId)
            onProgress?.invoke(idx + 1, actionBuilders.size, "Executing shortcut: ${action::class.simpleName}")
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
