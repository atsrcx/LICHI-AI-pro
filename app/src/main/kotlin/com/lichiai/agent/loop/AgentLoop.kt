package com.lichiai.agent.loop

import android.content.Context
import android.util.Log
import com.lichiai.agent.client.AgentGeminiClient
import com.lichiai.agent.data.AgentSettings
import com.lichiai.agent.executor.AgentActionExecutor
import com.lichiai.agent.fs.AgentFileSystem
import com.lichiai.agent.history.AgentHistoryManager
import com.lichiai.agent.model.AgentAction
import com.lichiai.agent.model.AgentExecutionState
import com.lichiai.agent.model.AgentLiveStatus
import com.lichiai.agent.model.AgentRunResult
import com.lichiai.agent.model.AgentRunStatus
import com.lichiai.agent.perception.PerceptionEngine
import com.lichiai.agent.prompt.AgentSystemPrompt
import com.lichiai.agentvision.model.VisualSource
import com.lichiai.agentvision.telemetry.AgentVisionTelemetryHub
import com.lichiai.dynamicisland.LichiAssistantStateHub
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

/**
 * AgentLoop coordinates the iterative Sense-Think-Act cycle for Autonomous Agent V2.
 *
 * Exact Flow:
 * SENSE -> THINK -> ACT -> OBSERVE RESULT -> UPDATE MEMORY -> RE-EVALUATE -> NEXT GOAL -> ACT AGAIN -> ... -> DONE
 */
class AgentLoop(
    private val context: Context,
    private val perceptionEngine: PerceptionEngine,
    private val llmProvider: com.lichiai.agent.client.AgentLlmProvider,
    private val actionExecutor: AgentActionExecutor,
    private val fileSystem: AgentFileSystem,
    private val historyManager: AgentHistoryManager
) {

    companion object {
        private const val TAG = "AgentLoop"
    }

    private val _status = MutableStateFlow(AgentRunStatus.IDLE)
    val status: StateFlow<AgentRunStatus> = _status.asStateFlow()

    private val _currentGoal = MutableStateFlow("")
    val currentGoal: StateFlow<String> = _currentGoal.asStateFlow()

    private val _liveStatus = MutableStateFlow(AgentLiveStatus())
    val liveStatus: StateFlow<AgentLiveStatus> = _liveStatus.asStateFlow()

    private fun formatActionDescription(action: AgentAction): String {
        return when (action.name.lowercase()) {
            "open_app" -> {
                val pkg = action.param("package_name")
                val app = when {
                    pkg.contains("instagram", ignoreCase = true) -> "Instagram"
                    pkg.contains("whatsapp", ignoreCase = true) -> "WhatsApp"
                    pkg.contains("telegram", ignoreCase = true) -> "Telegram"
                    pkg.contains("settings", ignoreCase = true) -> "Settings"
                    pkg.contains("youtube", ignoreCase = true) -> "YouTube"
                    pkg.contains("spotify", ignoreCase = true) -> "Spotify"
                    pkg.contains("chrome", ignoreCase = true) -> "Chrome"
                    else -> pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }
                }
                "Opening $app..."
            }
            "type" -> {
                val text = action.param("text")
                if (text.isNotBlank()) "Typing \"${text.take(15)}\"..." else "Typing text..."
            }
            "tap" -> "Interacting with screen..."
            "scroll" -> "Scrolling ${action.param("direction", "down").lowercase()}..."
            "wait" -> "Waiting for screen to load..."
            "back" -> "Going back..."
            "home" -> "Navigating Home..."
            "done" -> "Completing task..."
            else -> "Executing ${action.name}..."
        }
    }

    /**
     * Runs the autonomous loop for [userGoal] up to [settings.maxSteps].
     */
    suspend fun run(
        userGoal: String,
        settings: AgentSettings,
        matchedSkills: List<com.lichiai.skill.model.Skill> = emptyList(),
        onProgress: ((step: Int, total: Int, statusText: String) -> Unit)? = null
    ): AgentRunResult {
        val skillSummary = if (matchedSkills.isNotEmpty()) " [Skills: ${matchedSkills.joinToString { it.name }}]" else ""
        Log.i(TAG, "[Agent] Starting Autonomous Agent V2 loop for goal: $userGoal$skillSummary")
        _status.value = AgentRunStatus.SENSING
        _currentGoal.value = userGoal

        val skillContext = if (matchedSkills.isNotEmpty()) {
            matchedSkills.joinToString("\n\n") { it.toAgentContextString() }
        } else ""

        val initialActivity = if (matchedSkills.isNotEmpty()) {
            "Applying ${matchedSkills.first().name}..."
        } else {
            "Working..."
        }

        val maxSteps = settings.maxSteps.coerceIn(3, 30)
        val taskId = "android_agent_${System.currentTimeMillis()}"
        try {
            AgentVisionTelemetryHub.setActiveTask(taskId, userGoal, VisualSource.ANDROID_AGENT)
        } catch (_: Throwable) {}

        _liveStatus.value = AgentLiveStatus(
            state = AgentExecutionState.STARTING,
            task = userGoal,
            step = 0,
            totalSteps = maxSteps,
            activityText = initialActivity
        )

        fileSystem.initNewTask(userGoal)
        historyManager.clear()
        actionExecutor.reset()

        var totalStepsExecuted = 0

        try {
            for (step in 1..maxSteps) {
                if (!coroutineContext.isActive) {
                    _status.value = AgentRunStatus.CANCELLED
                    _liveStatus.value = AgentLiveStatus(
                        state = AgentExecutionState.CANCELLED,
                        task = userGoal,
                        step = step,
                        totalSteps = maxSteps,
                        activityText = "Cancelled"
                    )
                    return AgentRunResult(false, "Agent task was cancelled.", totalStepsExecuted)
                }

                totalStepsExecuted = step

                // 1. SENSE
                _status.value = AgentRunStatus.SENSING
                val senseText = "Perceiving device screen..."
                _liveStatus.value = AgentLiveStatus(
                    state = AgentExecutionState.PERCEIVING,
                    task = userGoal,
                    step = step,
                    totalSteps = maxSteps,
                    activityText = senseText
                )
                onProgress?.invoke(step, maxSteps, senseText)
                try {
                    LichiAssistantStateHub.onToolExecution("Agent: Sense ($step/$maxSteps)")
                } catch (_: Throwable) {}

                val androidState = perceptionEngine.perceive()

                // 2. THINK
                _status.value = AgentRunStatus.THINKING
                val thinkText = "Reasoning next step..."
                _liveStatus.value = AgentLiveStatus(
                    state = AgentExecutionState.THINKING,
                    task = userGoal,
                    step = step,
                    totalSteps = maxSteps,
                    activityText = thinkText
                )
                onProgress?.invoke(step, maxSteps, thinkText)
                val prompt = AgentSystemPrompt.buildPrompt(
                    userRequest = userGoal,
                    stepNumber = step,
                    maxSteps = maxSteps,
                    historyManager = historyManager,
                    androidState = androidState,
                    fileSystem = fileSystem,
                    skillContext = skillContext
                )

                Log.d(TAG, "[Agent] Reasoning request started for step $step (useCurrentProvider=${settings.useCurrentProvider})")
                val reasoningResult = llmProvider.generateAgentOutput(
                    settings = settings,
                    prompt = prompt
                )

                if (reasoningResult.isFailure) {
                    val err = reasoningResult.exceptionOrNull()?.message ?: "Unknown reasoning error"
                    Log.e(TAG, "[Agent] Step $step: Agent reasoning failed: $err")
                    _status.value = AgentRunStatus.ERROR
                    _liveStatus.value = AgentLiveStatus(
                        state = AgentExecutionState.FAILED,
                        task = userGoal,
                        step = step,
                        totalSteps = maxSteps,
                        activityText = "Planning error: $err"
                    )
                    return AgentRunResult(
                        isSuccess = false,
                        summary = "Agent planning failed at step $step: $err",
                        totalSteps = totalStepsExecuted,
                        error = err
                    )
                }

                val agentOutput = reasoningResult.getOrThrow()
                Log.d(TAG, "[Agent] LLM response received. Next goal: ${agentOutput.nextGoal}, actions: ${agentOutput.action.size}")
                _currentGoal.value = agentOutput.nextGoal.ifBlank { userGoal }

                // 3. ACT
                _status.value = AgentRunStatus.ACTING
                val actionDescriptions = agentOutput.action.map { formatActionDescription(it) }.joinToString("; ")
                val actStatusText = if (actionDescriptions.isNotBlank()) actionDescriptions else "Acting..."
                _liveStatus.value = AgentLiveStatus(
                    state = AgentExecutionState.EXECUTING,
                    task = userGoal,
                    step = step,
                    totalSteps = maxSteps,
                    activityText = actStatusText
                )
                onProgress?.invoke(step, maxSteps, actStatusText)
                try {
                    LichiAssistantStateHub.onToolExecution("Agent: Act ($actStatusText)")
                } catch (_: Throwable) {}

                val observations = mutableListOf<String>()
                if (agentOutput.action.isEmpty()) {
                    observations.add("No actions produced by agent for this turn.")
                } else {
                    for (action in agentOutput.action) {
                        if (!coroutineContext.isActive) break
                        Log.d(TAG, "[Agent] Action executing: ${action.name} params: ${action.params}")
                        val obs = actionExecutor.executeAction(action, androidState)
                        Log.d(TAG, "[Agent] Action result: $obs")
                        observations.add(obs)
                        if (actionExecutor.isDone) {
                            break
                        }
                    }
                }

                // 4. OBSERVE & UPDATE MEMORY
                _status.value = AgentRunStatus.OBSERVING
                val combinedObs = observations.joinToString(" | ")
                historyManager.addStep(
                    stepNumber = step,
                    nextGoal = agentOutput.nextGoal,
                    thinking = agentOutput.thinking,
                    actions = agentOutput.action,
                    observation = combinedObs
                )

                if (agentOutput.memory.isNotBlank()) {
                    fileSystem.appendResults("Step $step Memory: ${agentOutput.memory}")
                }

                // Check for completion
                if (actionExecutor.isDone) {
                    _status.value = AgentRunStatus.DONE
                    val summary = actionExecutor.doneSummary.ifBlank { "Task completed successfully." }
                    _liveStatus.value = AgentLiveStatus(
                        state = AgentExecutionState.COMPLETED,
                        task = userGoal,
                        step = step,
                        totalSteps = maxSteps,
                        activityText = "Completed"
                    )
                    Log.i(TAG, "[Agent] Task completed successfully at step $step: $summary")
                    return AgentRunResult(
                        isSuccess = true,
                        summary = summary,
                        totalSteps = totalStepsExecuted
                    )
                }
            }

            // Max steps exceeded without explicit done action
            _status.value = AgentRunStatus.DONE
            val fallbackSummary = "Agent executed $maxSteps steps. Final observations:\n${historyManager.getHistorySummary().takeLast(300)}"
            _liveStatus.value = AgentLiveStatus(
                state = AgentExecutionState.COMPLETED,
                task = userGoal,
                step = maxSteps,
                totalSteps = maxSteps,
                activityText = "Completed"
            )
            return AgentRunResult(
                isSuccess = true,
                summary = fallbackSummary,
                totalSteps = totalStepsExecuted
            )

        } catch (e: CancellationException) {
            Log.w(TAG, "[Agent] AgentLoop cancelled")
            _status.value = AgentRunStatus.CANCELLED
            _liveStatus.value = AgentLiveStatus(
                state = AgentExecutionState.CANCELLED,
                task = userGoal,
                step = totalStepsExecuted,
                totalSteps = maxSteps,
                activityText = "Cancelled"
            )
            return AgentRunResult(false, "Task cancelled.", totalStepsExecuted)
        } catch (e: Exception) {
            Log.e(TAG, "[Agent] AgentLoop unhandled exception", e)
            _status.value = AgentRunStatus.ERROR
            _liveStatus.value = AgentLiveStatus(
                state = AgentExecutionState.FAILED,
                task = userGoal,
                step = totalStepsExecuted,
                totalSteps = maxSteps,
                activityText = "Error: ${e.message}"
            )
            return AgentRunResult(false, "Agent execution error: ${e.message}", totalStepsExecuted, e.message)
        } finally {
            if (_status.value != AgentRunStatus.DONE && _status.value != AgentRunStatus.ERROR) {
                _status.value = AgentRunStatus.IDLE
            }
        }
    }
}
