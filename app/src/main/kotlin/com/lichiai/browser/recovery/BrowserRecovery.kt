package com.lichiai.browser.recovery

import com.lichiai.browser.api.BrowserCapabilityAPI
import com.lichiai.browser.context.BrowserTaskContext
import com.lichiai.browser.events.BrowserEvent
import com.lichiai.browser.events.BrowserEventBus
import com.lichiai.browser.runtime.BrowserConditionWaiter
import com.lichiai.browser.verifier.BrowserVerifier

data class RecoveryAttemptResult(
    val recovered: Boolean,
    val summary: String
)

/**
 * Authoritative Isolated recovery engine for Browser Agent.
 * Handles transient network dropouts, unclickable DOM candidates, and timeouts with condition-based waiters.
 */
class BrowserRecovery(
    private val capabilityApi: BrowserCapabilityAPI,
    private val eventBus: BrowserEventBus
) {

    suspend fun attemptRecovery(
        failedAction: String,
        arguments: Map<String, String>,
        context: BrowserTaskContext,
        attemptNumber: Int
    ): RecoveryAttemptResult {
        if (attemptNumber > 2) {
            return RecoveryAttemptResult(false, "Exceeded maximum recovery attempts.")
        }

        eventBus.emit(BrowserEvent.RecoveryStarted(attemptNumber, "Retrying action '$failedAction'"))

        return when (failedAction) {
            "navigate" -> {
                val url = arguments["url"] ?: return RecoveryAttemptResult(false, "No URL to reload")
                val ok = capabilityApi.navigate(url)
                if (ok) {
                    BrowserConditionWaiter.waitForUrlChange(context.currentUrl) { (capabilityApi as? com.lichiai.browser.BrowserController)?.activeEngine?.value }
                    val ctx = capabilityApi.getPageContext()
                    val verified = BrowserVerifier.verifyNavigation(url, ctx)
                    if (verified.passed) {
                        RecoveryAttemptResult(true, "Recovered by reloading URL: $url")
                    } else {
                        RecoveryAttemptResult(false, "Reload navigation unconfirmed: ${verified.detail}")
                    }
                } else {
                    RecoveryAttemptResult(false, "Reload request failed")
                }
            }

            "search" -> {
                val q = arguments["query"] ?: return RecoveryAttemptResult(false, "No query")
                val ok = capabilityApi.search(q, "duckduckgo")
                if (ok) {
                    BrowserConditionWaiter.waitForUrlChange(context.currentUrl) { (capabilityApi as? com.lichiai.browser.BrowserController)?.activeEngine?.value }
                    val ctx = capabilityApi.getPageContext()
                    val verified = BrowserVerifier.verifySearchResults(q, ctx)
                    if (verified.passed) {
                        RecoveryAttemptResult(true, "Recovered using alternate search engine (DuckDuckGo)")
                    } else {
                        RecoveryAttemptResult(false, "Search recovery failed: search results page not confirmed")
                    }
                } else {
                    RecoveryAttemptResult(false, "Alternate search engine request failed")
                }
            }

            "clickCandidate", "clickElement" -> {
                val idx = arguments["index"]?.toIntOrNull() ?: 1
                val candidate = context.extractedCandidates.firstOrNull { it.index == idx }
                if (candidate != null && candidate.title.isNotBlank()) {
                    val ok = capabilityApi.clickSelector(candidate.title.take(30))
                    if (ok) {
                        BrowserConditionWaiter.waitForDomStable { (capabilityApi as? com.lichiai.browser.BrowserController)?.activeEngine?.value }
                        val ctx = capabilityApi.getPageContext()
                        val verified = BrowserVerifier.verifyClick(context.currentUrl, ctx, candidate.title)
                        if (verified.passed) {
                            RecoveryAttemptResult(true, "Recovered by clicking candidate text: '${candidate.title}'")
                        } else {
                            RecoveryAttemptResult(false, "Candidate click unverified: ${verified.detail}")
                        }
                    } else {
                        RecoveryAttemptResult(false, "Candidate text click failed")
                    }
                } else {
                    capabilityApi.reload()
                    BrowserConditionWaiter.waitForPageReady { (capabilityApi as? com.lichiai.browser.BrowserController)?.activeEngine?.value }
                    val ok = capabilityApi.clickCandidate(idx)
                    if (ok) {
                        BrowserConditionWaiter.waitForDomStable { (capabilityApi as? com.lichiai.browser.BrowserController)?.activeEngine?.value }
                        val ctx = capabilityApi.getPageContext()
                        val verified = BrowserVerifier.verifyClick(context.currentUrl, ctx)
                        if (verified.passed) {
                            RecoveryAttemptResult(true, "Recovered after page reload")
                        } else {
                            RecoveryAttemptResult(false, "Retry after reload unconfirmed: ${verified.detail}")
                        }
                    } else {
                        RecoveryAttemptResult(false, "Retry failed")
                    }
                }
            }

            else -> {
                RecoveryAttemptResult(false, "No deterministic recovery strategy available for '$failedAction'")
            }
        }
    }
}
