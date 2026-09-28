package com.lichiai.browser.events

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class BrowserActionStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    FAILED,
    CANCELLED
}

data class BrowserActionEntry(
    val id: String,
    val title: String,
    val detail: String? = null,
    val status: BrowserActionStatus = BrowserActionStatus.RUNNING,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Observable log of user-safe actions for live Browser Agent visualization.
 * No raw reasoning, system prompts, or private model context are stored here.
 */
class BrowserActionLog {
    private val _entries = MutableStateFlow<List<BrowserActionEntry>>(emptyList())
    val entries: StateFlow<List<BrowserActionEntry>> = _entries.asStateFlow()

    private val _currentGoal = MutableStateFlow<String?>(null)
    val currentGoal: StateFlow<String?> = _currentGoal.asStateFlow()

    fun setGoal(goal: String?) {
        _currentGoal.value = goal
    }

    fun startAction(id: String, title: String, detail: String? = null) {
        _entries.update { list ->
            // Mark any previous running action as completed if not finished
            val updated = list.map {
                if (it.status == BrowserActionStatus.RUNNING) it.copy(status = BrowserActionStatus.SUCCESS)
                else it
            }
            (updated + BrowserActionEntry(id, title, detail, BrowserActionStatus.RUNNING)).takeLast(15)
        }
    }

    fun completeAction(id: String, detail: String? = null) {
        _entries.update { list ->
            list.map {
                if (it.id == id) it.copy(status = BrowserActionStatus.SUCCESS, detail = detail ?: it.detail)
                else it
            }
        }
    }

    fun failAction(id: String, reason: String) {
        _entries.update { list ->
            list.map {
                if (it.id == id) it.copy(status = BrowserActionStatus.FAILED, detail = reason)
                else it
            }
        }
    }

    fun cancelActive(reason: String = "User interrupted") {
        _entries.update { list ->
            list.map {
                if (it.status == BrowserActionStatus.RUNNING) it.copy(status = BrowserActionStatus.CANCELLED, detail = reason)
                else it
            }
        }
    }

    fun clear() {
        _entries.value = emptyList()
        _currentGoal.value = null
    }
}
