package com.lichiai.agent

import android.content.Context
import android.util.Log
import com.lichiai.agent.accessibility.LichiAccessibilityService
import com.lichiai.agent.client.AgentGeminiClient
import com.lichiai.agent.data.AgentSettingsRepository
import com.lichiai.agent.executor.AgentActionExecutor
import com.lichiai.agent.fs.AgentFileSystem
import com.lichiai.agent.history.AgentHistoryManager
import com.lichiai.agent.loop.AgentLoop
import com.lichiai.agent.model.AgentRunResult
import com.lichiai.agent.model.AgentRunStatus
import com.lichiai.agent.perception.PerceptionEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Autonomous Agent V2 Coordinator.
 *
 * Exposes single, clean, encapsulated entry point for executing autonomous tasks.
 */
class Agent private constructor(context: Context) {

    companion object {
        private const val TAG = "AutonomousAgent"

        @Volatile
        private var instance: Agent? = null

        fun getInstance(context: Context): Agent {
            return instance ?: synchronized(this) {
                instance ?: Agent(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settingsRepository = AgentSettingsRepository.getInstance(appContext)
    val fileSystem = AgentFileSystem(appContext)
    val historyManager = AgentHistoryManager()
    val geminiClient = AgentGeminiClient()
    val llmProvider = com.lichiai.agent.client.AgentLlmProvider(appContext, geminiClient)
    val perceptionEngine = PerceptionEngine(appContext)
    val actionExecutor = AgentActionExecutor(appContext, fileSystem)

    val loop = AgentLoop(
        context = appContext,
        perceptionEngine = perceptionEngine,
        llmProvider = llmProvider,
        actionExecutor = actionExecutor,
        fileSystem = fileSystem,
        historyManager = historyManager
    )

    val status: StateFlow<AgentRunStatus> get() = loop.status
    val currentGoal: StateFlow<String> get() = loop.currentGoal
    val liveStatus: StateFlow<com.lichiai.agent.model.AgentLiveStatus> get() = loop.liveStatus

    private var activeJob: Job? = null

    /**
     * Executes the autonomous phone automation loop for [userGoal].
     *
     * Validates:
     * 1. Agent is enabled in settings.
     * 2. Reasoning provider is configured (either Current Provider or Dedicated Gemini).
     * 3. Accessibility service is connected.
     */
    suspend fun executeTask(
        userGoal: String,
        matchedSkills: List<com.lichiai.skill.model.Skill> = emptyList(),
        onProgress: ((step: Int, total: Int, statusText: String) -> Unit)? = null
    ): AgentRunResult {
        val settings = settingsRepository.settings.value

        // 1. Gate: Enabled check
        if (!settings.enabled) {
            return AgentRunResult(
                isSuccess = false,
                summary = "Autonomous Agent is currently disabled in Settings.",
                totalSteps = 0
            )
        }

        // 2. Gate: Backend configuration check
        if (!settings.useCurrentProvider && settings.apiKey.isBlank()) {
            return AgentRunResult(
                isSuccess = false,
                summary = "Agent Gemini API key is missing. Please configure it in Settings under Autonomous Agent V2 or enable 'Use Current Provider'.",
                totalSteps = 0
            )
        }

        // 3. Gate: Accessibility service check
        if (!LichiAccessibilityService.isServiceRunning()) {
            return AgentRunResult(
                isSuccess = false,
                summary = "Lichi Accessibility Service is not enabled. Please enable it in Android Accessibility Settings to allow UI automation.",
                totalSteps = 0
            )
        }

        activeJob?.cancel()
        return loop.run(userGoal, settings, matchedSkills, onProgress)
    }

    fun cancelTask() {
        Log.i(TAG, "Cancelling active Autonomous Agent task")
        activeJob?.cancel()
        activeJob = null
    }
}
