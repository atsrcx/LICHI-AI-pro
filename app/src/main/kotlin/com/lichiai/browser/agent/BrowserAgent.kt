package com.lichiai.browser.agent

import com.lichiai.browser.BrowserController
import com.lichiai.browser.actions.BrowserActionEngine
import com.lichiai.browser.api.BrowserCapabilityAPI
import com.lichiai.browser.context.BrowserMemory
import com.lichiai.browser.context.BrowserTaskContext
import com.lichiai.browser.events.BrowserActionLog
import com.lichiai.browser.events.BrowserEvent
import com.lichiai.browser.events.BrowserEventBus
import com.lichiai.browser.executor.BrowserExecutor
import com.lichiai.browser.llm.BrowserLLMClient
import com.lichiai.browser.perception.BrowserPerceptionLayer
import com.lichiai.browser.runtime.BrowserAgentRuntime
import com.lichiai.browser.runtime.BrowserTaskIntent
import com.lichiai.browser.storage.BrowserStorageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class BrowserInteractionMode {
    MANUAL,
    AGENT,
    HYBRID
}

data class BrowserAgentTelemetry(
    val totalTasks: Int = 0,
    val zeroLlmTasks: Int = 0,
    val llmCalls: Int = 0,
    val lastTaskDurationMs: Long = 0,
    val lastAction: String = ""
)

data class BrowserExecutionResult(
    val isSuccess: Boolean,
    val answer: String? = null,
    val summary: String = "",
    val extractedContext: String? = null,
    val finalUrl: String = "",
    val pageTitle: String = ""
)

/**
 * Public Facade for the Autonomous Browser Operating System.
 * Delegates all cognitive reasoning, closed-loop observation, grounding,
 * execution, verification, and recovery to [BrowserAgentRuntime].
 */
class BrowserAgent(
    private val capabilityApi: BrowserCapabilityAPI,
    private val executor: BrowserExecutor,
    private val llmClient: BrowserLLMClient,
    private val storageManager: BrowserStorageManager,
    private val memory: BrowserMemory,
    private val actionLog: BrowserActionLog,
    private val eventBus: BrowserEventBus,
    private val scope: CoroutineScope
) {

    private val _interactionMode = MutableStateFlow(BrowserInteractionMode.HYBRID)
    val interactionMode: StateFlow<BrowserInteractionMode> = _interactionMode.asStateFlow()

    private val _telemetry = MutableStateFlow(BrowserAgentTelemetry())
    val telemetry: StateFlow<BrowserAgentTelemetry> = _telemetry.asStateFlow()

    private val _pendingConfirmation = MutableStateFlow<com.lichiai.browser.context.BrowserConfirmationRequest?>(null)
    val pendingConfirmation: StateFlow<com.lichiai.browser.context.BrowserConfirmationRequest?> = _pendingConfirmation.asStateFlow()

    private val perceptionLayer = BrowserPerceptionLayer()

    val runtime: BrowserAgentRuntime = if (capabilityApi is BrowserController) {
        val actionEngine = BrowserActionEngine(capabilityApi, perceptionLayer)
        BrowserAgentRuntime(
            browserController = capabilityApi,
            perceptionLayer = perceptionLayer,
            actionEngine = actionEngine,
            llmClient = llmClient,
            storageManager = storageManager,
            memory = memory,
            actionLog = actionLog,
            eventBus = eventBus,
            scope = scope
        )
    } else {
        // Fallback for mock/test capability API
        throw IllegalStateException("BrowserAgent requires a BrowserController instance")
    }

    val isBusy: StateFlow<Boolean> = runtime.isBusy

    private var currentTaskJob: Job? = null

    fun setInteractionMode(mode: BrowserInteractionMode) {
        _interactionMode.value = mode
    }

    fun dismissConfirmation() {
        _pendingConfirmation.value?.let { req ->
            scope.launch { req.onCancel() }
        }
        _pendingConfirmation.value = null
    }

    fun approveConfirmation() {
        _pendingConfirmation.value?.let { req ->
            scope.launch { req.onConfirm() }
        }
        _pendingConfirmation.value = null
    }

    fun stopActiveTask(reason: String = "User requested stop") {
        currentTaskJob?.cancel()
        currentTaskJob = null
        _pendingConfirmation.value = null
        runtime.cancel(reason)
    }

    /**
     * Submit natural language instruction or # shortcut asynchronously to the Browser Agent.
     */
    fun submitInstruction(instruction: String, onResult: ((String) -> Unit)? = null) {
        currentTaskJob?.cancel()
        currentTaskJob = scope.launch(Dispatchers.Default) {
            val res = executeInstruction(instruction)
            onResult?.invoke(res.answer ?: res.summary)
        }
    }

    /**
     * Synchronously/suspendingly executes an instruction via the unified runtime.
     */
    suspend fun executeInstruction(
        instruction: String,
        onProgress: ((step: Int, total: Int, text: String) -> Unit)? = null
    ): BrowserExecutionResult {
        val startTime = System.currentTimeMillis()
        val res = runtime.executeInstruction(instruction, onProgress)
        val duration = System.currentTimeMillis() - startTime
        _telemetry.value = _telemetry.value.copy(
            totalTasks = _telemetry.value.totalTasks + 1,
            lastTaskDurationMs = duration,
            lastAction = res.summary
        )
        return res
    }

    /**
     * Executes a high-level structured browser task intent via the unified runtime.
     */
    suspend fun executeGoal(
        intent: BrowserTaskIntent,
        onProgress: ((step: Int, total: Int, text: String) -> Unit)? = null
    ): BrowserExecutionResult {
        val startTime = System.currentTimeMillis()
        val res = runtime.executeGoal(intent, onProgress)
        val duration = System.currentTimeMillis() - startTime
        _telemetry.value = _telemetry.value.copy(
            totalTasks = _telemetry.value.totalTasks + 1,
            lastTaskDurationMs = duration,
            lastAction = res.summary
        )
        return res
    }
}
