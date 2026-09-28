package com.lichiai.browser.events

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * High-level user-safe browser event representation for live agent observability and UI.
 * Never contains private tokens, chain-of-thought, or raw prompt deliberations.
 */
sealed class BrowserEvent(val timestamp: Long = System.currentTimeMillis()) {
    data object BrowserOpened : BrowserEvent()
    data class NavigationStarted(val url: String) : BrowserEvent()
    data class NavigationCompleted(val url: String, val title: String) : BrowserEvent()
    data class PageLoadProgress(val progress: Int) : BrowserEvent()
    data class PageLoaded(val url: String, val title: String) : BrowserEvent()
    data class SearchStarted(val query: String, val engine: String) : BrowserEvent()
    data class SearchCompleted(val query: String, val resultCount: Int) : BrowserEvent()
    data class TabCreated(val tabId: String, val isIncognito: Boolean) : BrowserEvent()
    data class TabClosed(val tabId: String) : BrowserEvent()
    data class TabChanged(val tabId: String, val url: String) : BrowserEvent()
    data class UserInteracted(val action: String) : BrowserEvent()
    data class AgentActionStarted(val actionName: String, val description: String) : BrowserEvent()
    data class AgentActionCompleted(val actionName: String, val summary: String) : BrowserEvent()
    data class VerificationPassed(val check: String) : BrowserEvent()
    data class VerificationFailed(val check: String, val reason: String) : BrowserEvent()
    data class RecoveryStarted(val attempt: Int, val strategy: String) : BrowserEvent()
    data class TaskStarted(val taskId: String, val goal: String) : BrowserEvent()
    data class TaskCompleted(val taskId: String, val result: String) : BrowserEvent()
    data class TaskCancelled(val taskId: String, val reason: String) : BrowserEvent()
    data class BrowserError(val message: String) : BrowserEvent()
}

/**
 * Production-grade reactive event bus for the Lichi Browser subsystem.
 * Isolated from core Lichi agent events.
 */
class BrowserEventBus {
    private val _events = MutableSharedFlow<BrowserEvent>(replay = 20, extraBufferCapacity = 64)
    val events: SharedFlow<BrowserEvent> = _events.asSharedFlow()

    fun emit(event: BrowserEvent) {
        _events.tryEmit(event)
    }
}
