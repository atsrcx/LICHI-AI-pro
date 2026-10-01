package com.lichiai.browser.runtime

import com.lichiai.browser.engine.ChromiumWebViewEngine
import com.lichiai.browser.perception.PagePerceptionSnapshot
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Condition-based synchronization for Human-Like Browser Operating System.
 * Eliminates arbitrary fixed sleeps (e.g. delay(500), delay(1500)) with polling condition waiters.
 */
object BrowserConditionWaiter {

    suspend fun waitUntil(
        timeoutMs: Long = 5000L,
        pollIntervalMs: Long = 100L,
        conditionName: String = "condition",
        predicate: suspend () -> Boolean
    ): Boolean {
        val result = withTimeoutOrNull(timeoutMs) {
            val start = System.currentTimeMillis()
            while (System.currentTimeMillis() - start < timeoutMs) {
                if (predicate()) {
                    return@withTimeoutOrNull true
                }
                delay(pollIntervalMs)
            }
            predicate()
        }
        return result ?: false
    }

    suspend fun waitForUrlChange(
        initialUrl: String,
        timeoutMs: Long = 5000L,
        getEngine: () -> ChromiumWebViewEngine?
    ): Boolean {
        return waitUntil(timeoutMs, conditionName = "URL_CHANGED") {
            val engine = getEngine()
            val current = engine?.getUrl() ?: ""
            current.isNotBlank() && current != "about:blank" && current != initialUrl
        }
    }

    suspend fun waitForPageReady(
        timeoutMs: Long = 6000L,
        getEngine: () -> ChromiumWebViewEngine?
    ): Boolean {
        return waitUntil(timeoutMs, conditionName = "PAGE_LOADED") {
            val engine = getEngine()
            val state = engine?.getPageState()
            state?.isLoaded == true || state?.readyState == "complete" || state?.readyState == "interactive"
        }
    }

    suspend fun waitForElementVisible(
        targetQuery: String,
        timeoutMs: Long = 4000L,
        getSnapshot: suspend () -> PagePerceptionSnapshot
    ): Boolean {
        val resolver = BrowserTargetResolver()
        return waitUntil(timeoutMs, pollIntervalMs = 200L, conditionName = "ELEMENT_VISIBLE") {
            val snapshot = getSnapshot()
            val res = resolver.resolveTarget(targetQuery, snapshot)
            res is TargetResolutionResult.Resolved
        }
    }

    suspend fun waitForDomStable(
        settleMs: Long = 200L,
        timeoutMs: Long = 3000L,
        getEngine: () -> ChromiumWebViewEngine?
    ): Boolean {
        var lastCount = -1
        var stableTime = 0L
        return waitUntil(timeoutMs, pollIntervalMs = 100L, conditionName = "DOM_STABLE") {
            val engine = getEngine()
            val currentCount = engine?.extractInteractiveElements()?.size ?: 0
            if (currentCount == lastCount && currentCount > 0) {
                stableTime += 100L
                if (stableTime >= settleMs) return@waitUntil true
            } else {
                lastCount = currentCount
                stableTime = 0L
            }
            false
        }
    }
}
