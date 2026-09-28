package com.lichiai.intent.dispatcher

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.lichiai.agent.bridge.AutonomousAgentTool
import com.lichiai.browser.BrowserController
import com.lichiai.browser.api.ScrollDirection
import com.lichiai.calling.engine.UniversalCallEngine
import com.lichiai.intent.context.ContextBuilder
import com.lichiai.intent.model.BrowserActionType
import com.lichiai.intent.model.LichiCapability
import com.lichiai.intent.model.ResolvedIntent
import com.lichiai.web.WebIntelligenceManager
import com.lichiai.web.model.WebSearchResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed class DispatchExecutionResult {
    data class BrowserExecuted(val message: String) : DispatchExecutionResult()
    data class WebSearchExecuted(val response: WebSearchResponse, val contextPrompt: String, val message: String) : DispatchExecutionResult()
    data class AndroidAgentExecuted(val summary: String, val isSuccess: Boolean, val steps: Int) : DispatchExecutionResult()
    data class CallExecuted(val message: String, val isSuccess: Boolean) : DispatchExecutionResult()
    data class MediaExecuted(val message: String) : DispatchExecutionResult()
    data class DeviceControlExecuted(val message: String) : DispatchExecutionResult()
    data class TimeReminderExecuted(val message: String, val isSuccess: Boolean, val requiresScreenNavigation: Boolean) : DispatchExecutionResult()
    data class TerminalExecuted(val message: String, val rawOutput: String = "", val isSuccess: Boolean = true, val requiresScreenNavigation: Boolean = false) : DispatchExecutionResult()
    data class ClarificationNeeded(val question: String) : DispatchExecutionResult()
    data class FallbackChat(val prompt: String) : DispatchExecutionResult()
    data class ExecutionFailed(val error: String) : DispatchExecutionResult()
}

/**
 * Dispatches resolved intents to the appropriate isolated peer capability.
 */
class RouteDispatcher(
    private val context: Context,
    private val browserController: BrowserController,
    private val webIntelligenceManager: WebIntelligenceManager,
    private val autonomousAgentTool: AutonomousAgentTool,
    private val universalCallEngine: UniversalCallEngine,
    private val contextBuilder: ContextBuilder,
    private val onNavigateToBrowser: () -> Unit,
    private val onNavigateToTerminal: () -> Unit = {}
) {

    suspend fun dispatch(
        intent: ResolvedIntent,
        onProgress: ((step: Int, total: Int, text: String) -> Unit)? = null
    ): DispatchExecutionResult = withContext(Dispatchers.Main) {
        when (intent) {
            is ResolvedIntent.BrowserTask -> executeBrowserTask(intent)
            is ResolvedIntent.WebSearchTask -> executeWebSearchTask(intent)
            is ResolvedIntent.AndroidAgentTask -> executeAndroidAgentTask(intent, onProgress)
            is ResolvedIntent.CallTask -> executeCallTask(intent)
            is ResolvedIntent.MediaTask -> executeMediaTask(intent)
            is ResolvedIntent.DeviceControlTask -> executeDeviceControlTask(intent)
            is ResolvedIntent.TimeReminderTask -> executeTimeReminderTask(intent)
            is ResolvedIntent.TerminalTask -> executeTerminalTask(intent, onProgress)
            is ResolvedIntent.MultiStepTask -> executeMultiStepTask(intent, onProgress)
            is ResolvedIntent.Clarification -> DispatchExecutionResult.ClarificationNeeded(intent.question)
            is ResolvedIntent.Cancellation -> {
                contextBuilder.reset()
                DispatchExecutionResult.FallbackChat(intent.naturalAcknowledgment)
            }
            is ResolvedIntent.NormalChat -> DispatchExecutionResult.FallbackChat(intent.prompt)
            is ResolvedIntent.ContextualQuestion -> DispatchExecutionResult.FallbackChat(intent.question)
            is ResolvedIntent.ResumeTask -> DispatchExecutionResult.FallbackChat(intent.naturalAcknowledgment)
            is ResolvedIntent.TaskInterruption -> DispatchExecutionResult.FallbackChat(intent.naturalAcknowledgment)
            is ResolvedIntent.SkillManagementTask -> DispatchExecutionResult.FallbackChat(intent.naturalAcknowledgment)
        }
    }

    private suspend fun executeBrowserTask(task: ResolvedIntent.BrowserTask): DispatchExecutionResult {
        onNavigateToBrowser()

        return when (task.action) {
            BrowserActionType.SEARCH -> {
                val query = task.query ?: ""
                browserController.search(query, task.searchEngine)
                contextBuilder.recordExecution(
                    capability = LichiCapability.BROWSER,
                    userGoal = task.rawPrompt,
                    assistantResponse = task.naturalAcknowledgment,
                    searchQuery = query,
                    actionType = "BROWSER_SEARCH"
                )
                DispatchExecutionResult.BrowserExecuted(task.naturalAcknowledgment)
            }
            BrowserActionType.NAVIGATE -> {
                val url = task.url ?: "https://www.google.com"
                browserController.navigate(url)
                contextBuilder.recordExecution(
                    capability = LichiCapability.BROWSER,
                    userGoal = task.rawPrompt,
                    assistantResponse = task.naturalAcknowledgment,
                    actionType = "BROWSER_NAVIGATE"
                )
                DispatchExecutionResult.BrowserExecuted(task.naturalAcknowledgment)
            }
            BrowserActionType.CLICK_CANDIDATE -> {
                val instruction = task.rawPrompt.ifBlank { "click candidate ${task.candidateIndex ?: 0}" }
                browserController.agent.submitInstruction(instruction)
                contextBuilder.recordExecution(
                    capability = LichiCapability.BROWSER,
                    userGoal = task.rawPrompt,
                    assistantResponse = task.naturalAcknowledgment,
                    actionType = "BROWSER_CLICK"
                )
                DispatchExecutionResult.BrowserExecuted(task.naturalAcknowledgment)
            }
            BrowserActionType.SCROLL_DOWN -> {
                browserController.scroll(ScrollDirection.DOWN)
                DispatchExecutionResult.BrowserExecuted("Scrolled down.")
            }
            BrowserActionType.SCROLL_UP -> {
                browserController.scroll(ScrollDirection.UP)
                DispatchExecutionResult.BrowserExecuted("Scrolled up.")
            }
            BrowserActionType.BACK -> {
                browserController.goBack()
                DispatchExecutionResult.BrowserExecuted("Navigated back.")
            }
            BrowserActionType.FORWARD -> {
                browserController.goForward()
                DispatchExecutionResult.BrowserExecuted("Navigated forward.")
            }
            BrowserActionType.RELOAD -> {
                browserController.reload()
                DispatchExecutionResult.BrowserExecuted("Page reloaded.")
            }
            BrowserActionType.NEW_TAB -> {
                browserController.tabManager.createTab()
                DispatchExecutionResult.BrowserExecuted("New tab opened.")
            }
            BrowserActionType.CLOSE_TAB -> {
                browserController.closeTab(browserController.tabManager.activeTabId.value)
                DispatchExecutionResult.BrowserExecuted("Tab closed.")
            }
            BrowserActionType.FIND_ON_PAGE -> {
                val instruction = task.findTarget?.let { "find on page $it" } ?: task.rawPrompt
                browserController.agent.submitInstruction(instruction)
                DispatchExecutionResult.BrowserExecuted(task.naturalAcknowledgment)
            }
        }
    }

    private suspend fun executeWebSearchTask(task: ResolvedIntent.WebSearchTask): DispatchExecutionResult = withContext(Dispatchers.IO) {
        try {
            val response = webIntelligenceManager.executeSearch(
                query = task.query,
                isImageSearch = task.isImageSearch,
                isNewsSearch = task.isNewsSearch
            )
            val contextPrompt = webIntelligenceManager.buildWebContextPrompt(response)
            contextBuilder.recordExecution(
                capability = LichiCapability.WEB_SEARCH,
                userGoal = task.query,
                assistantResponse = task.naturalAcknowledgment,
                searchQuery = task.query,
                results = response.results.map { it.title },
                actionType = "WEB_SEARCH"
            )
            DispatchExecutionResult.WebSearchExecuted(response, contextPrompt, task.naturalAcknowledgment)
        } catch (e: Exception) {
            DispatchExecutionResult.ExecutionFailed("Web search failed: ${e.message}")
        }
    }

    private suspend fun executeAndroidAgentTask(
        task: ResolvedIntent.AndroidAgentTask,
        onProgress: ((step: Int, total: Int, text: String) -> Unit)?
    ): DispatchExecutionResult {
        if (!autonomousAgentTool.isEnabled()) {
            return DispatchExecutionResult.ExecutionFailed(
                "Autonomous Agent is currently disabled. Please enable it in Settings under Autonomous Agent V2."
            )
        }

        val result = autonomousAgentTool.execute(
            taskDescription = task.goal,
            matchedSkills = task.matchedSkills,
            onProgress = onProgress
        )

        val appEntity = task.targetApp?.let { mapOf("app" to it) } ?: emptyMap()
        contextBuilder.recordExecution(
            capability = LichiCapability.ANDROID_AGENT,
            userGoal = task.goal,
            assistantResponse = result.summary,
            entities = appEntity,
            actionType = "ANDROID_AGENT"
        )

        return DispatchExecutionResult.AndroidAgentExecuted(
            summary = result.summary,
            isSuccess = result.isSuccess,
            steps = result.totalSteps
        )
    }

    private suspend fun executeCallTask(task: ResolvedIntent.CallTask): DispatchExecutionResult {
        val outcome = universalCallEngine.executeIntent(task.callIntent, sourceMode = "TEXT")
        val contactTarget = task.callIntent.targetText ?: task.callIntent.phoneNumber ?: ""
        val callEntities = if (contactTarget.isNotBlank()) mapOf("contact" to contactTarget) else emptyMap()
        contextBuilder.recordExecution(
            capability = LichiCapability.CALLS,
            userGoal = task.callIntent.originalText,
            assistantResponse = outcome.message,
            entities = callEntities,
            actionType = "CALL"
        )
        val isSuccess = outcome.status == com.lichiai.calling.intent.CallResultStatus.SUCCESS_STARTED
        return DispatchExecutionResult.CallExecuted(outcome.message, isSuccess)
    }

    private suspend fun executeMediaTask(task: ResolvedIntent.MediaTask): DispatchExecutionResult {
        return try {
            val pm = context.packageManager
            if (task.targetApp == "youtube") {
                val appIntent = Intent(Intent.ACTION_SEARCH).apply {
                    setPackage("com.google.android.youtube")
                    putExtra("query", task.query)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                val canLaunchApp = pm.queryIntentActivities(appIntent, 0).isNotEmpty() ||
                    pm.getLaunchIntentForPackage("com.google.android.youtube") != null
                if (canLaunchApp) {
                    try {
                        context.startActivity(appIntent)
                        contextBuilder.recordExecution(
                            capability = LichiCapability.MEDIA_YOUTUBE,
                            userGoal = task.query,
                            assistantResponse = task.naturalAcknowledgment
                        )
                        return DispatchExecutionResult.MediaExecuted(task.naturalAcknowledgment)
                    } catch (_: Exception) {
                        // Fallback to browser
                    }
                }
            } else if (task.targetApp == "spotify") {
                val spotifyIntent = Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:" + Uri.encode(task.query))).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                val canLaunchSpotify = pm.queryIntentActivities(spotifyIntent, 0).isNotEmpty() ||
                    pm.getLaunchIntentForPackage("com.spotify.music") != null
                if (canLaunchSpotify) {
                    try {
                        context.startActivity(spotifyIntent)
                        contextBuilder.recordExecution(
                            capability = LichiCapability.MEDIA_YOUTUBE,
                            userGoal = task.query,
                            assistantResponse = task.naturalAcknowledgment
                        )
                        return DispatchExecutionResult.MediaExecuted(task.naturalAcknowledgment)
                    } catch (_: Exception) {
                        // Fallback to browser
                    }
                }
            }

            // Fallback: Visible Browser
            val youtubeUrl = "https://www.youtube.com/results?search_query=" + Uri.encode(task.query)
            onNavigateToBrowser()
            browserController.navigate(youtubeUrl)
            contextBuilder.recordExecution(
                capability = LichiCapability.MEDIA_YOUTUBE,
                userGoal = task.query,
                assistantResponse = task.naturalAcknowledgment
            )
            DispatchExecutionResult.MediaExecuted(task.naturalAcknowledgment)
        } catch (e: Exception) {
            DispatchExecutionResult.ExecutionFailed("Media launch failed: ${e.message}")
        }
    }

    private suspend fun executeDeviceControlTask(task: ResolvedIntent.DeviceControlTask): DispatchExecutionResult {
        return DispatchExecutionResult.DeviceControlExecuted(task.naturalAcknowledgment)
    }

    private suspend fun executeTimeReminderTask(task: ResolvedIntent.TimeReminderTask): DispatchExecutionResult {
        val adapter = com.lichiai.time.adapter.TimeCapabilityAdapter(context)
        val outcome = adapter.handleQuery(task.rawInput)
        contextBuilder.recordExecution(
            capability = LichiCapability.TIME_REMINDER,
            userGoal = task.rawInput,
            assistantResponse = outcome.naturalSpeech
        )
        return DispatchExecutionResult.TimeReminderExecuted(
            message = outcome.naturalSpeech,
            isSuccess = outcome.isSuccess,
            requiresScreenNavigation = outcome.requiresScreenNavigation
        )
    }

    private suspend fun executeMultiStepTask(
        task: ResolvedIntent.MultiStepTask,
        onProgress: ((step: Int, total: Int, text: String) -> Unit)?
    ): DispatchExecutionResult {
        var lastResult: DispatchExecutionResult = DispatchExecutionResult.BrowserExecuted(task.naturalAcknowledgment)
        val total = task.steps.size

        for ((index, step) in task.steps.withIndex()) {
            onProgress?.invoke(index + 1, total, "Executing step ${index + 1}: ${step.naturalAcknowledgment}")
            lastResult = dispatch(step, onProgress)
        }
        return lastResult
    }

    private suspend fun executeTerminalTask(
        task: ResolvedIntent.TerminalTask,
        onProgress: ((step: Int, total: Int, text: String) -> Unit)?
    ): DispatchExecutionResult = withContext(Dispatchers.IO) {
        if (task.action.equals("OPEN", ignoreCase = true) || task.command.isBlank()) {
            withContext(Dispatchers.Main) { onNavigateToTerminal() }
            return@withContext DispatchExecutionResult.TerminalExecuted(
                message = "Lichi Terminal V3 open kar diya hai.",
                rawOutput = "",
                isSuccess = true,
                requiresScreenNavigation = true
            )
        }

        val taskManager = com.lichiai.terminal.task.TerminalTaskManager.getInstance(context)
        val termManager = com.lichiai.terminal.core.TerminalManager.getInstance(context)
        val agentAdapter = com.lichiai.terminal.agent.TerminalAgentAdapter(termManager)

        val backendType = if (task.backendType.contains("SSH", ignoreCase = true)) {
            com.lichiai.terminal.model.TerminalBackendType.SSH
        } else {
            com.lichiai.terminal.model.TerminalBackendType.LOCAL_TERMUX
        }

        val record = taskManager.createTask(
            command = task.command,
            requestId = "req_${System.currentTimeMillis()}",
            backendType = backendType,
            targetHost = task.host,
            username = task.user
        )

        onProgress?.invoke(1, 3, "Connecting to ${if (backendType == com.lichiai.terminal.model.TerminalBackendType.SSH) task.host ?: "remote" else "Local Termux"}...")
        taskManager.updateTask(
            taskId = record.taskId,
            status = com.lichiai.terminal.task.TerminalTaskStatus.EXECUTING,
            eventSummary = "Executing: ${task.command.take(30)}"
        )

        val agentResult = agentAdapter.executeTask(task.command)

        val finalStatus = if (agentResult.isSuccess) {
            com.lichiai.terminal.task.TerminalTaskStatus.COMPLETED
        } else {
            com.lichiai.terminal.task.TerminalTaskStatus.FAILED
        }

        taskManager.updateTask(
            taskId = record.taskId,
            status = finalStatus,
            eventSummary = if (agentResult.isSuccess) "Command completed successfully" else "Command returned non-zero or error output",
            rawOutput = agentResult.output,
            outputSummary = agentResult.summary,
            error = if (!agentResult.isSuccess) agentResult.summary else null
        )

        onProgress?.invoke(3, 3, if (agentResult.isSuccess) "Completed" else "Failed")

        val summaryForChat = buildString {
            if (agentResult.isSuccess) {
                append("Command `${task.command}` execute ho gaya.")
                if (agentResult.output.isNotBlank()) {
                    append("\n\n```\n")
                    append(agentResult.output.take(400).trimEnd())
                    append("\n```")
                }
            } else {
                append("Command `${task.command}` me error aaya:\n${agentResult.summary}")
            }
        }

        DispatchExecutionResult.TerminalExecuted(
            message = summaryForChat,
            rawOutput = agentResult.output,
            isSuccess = agentResult.isSuccess,
            requiresScreenNavigation = false
        )
    }
}
