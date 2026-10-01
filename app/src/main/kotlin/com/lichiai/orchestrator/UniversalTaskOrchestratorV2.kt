package com.lichiai.orchestrator

import android.content.Context
import android.util.Log
import com.lichiai.api.LlmClient
import com.lichiai.calling.action.CallActionExecutor
import com.lichiai.data.ProviderConfig
import com.lichiai.intent.context.ContextBuilder
import com.lichiai.intent.dispatcher.RouteDispatcher
import com.lichiai.intent.model.IntentContext
import com.lichiai.intent.model.LichiCapability
import com.lichiai.orchestrator.catalog.CapabilityCatalogV2
import com.lichiai.orchestrator.evaluator.TaskResultEvaluator
import com.lichiai.orchestrator.loop.OrchestratorLoopGuard
import com.lichiai.orchestrator.model.OrchestratorMode
import com.lichiai.orchestrator.model.PlanStep
import com.lichiai.orchestrator.model.StepExecutionRecord
import com.lichiai.orchestrator.model.TaskPlan
import com.lichiai.orchestrator.model.TaskState
import com.lichiai.orchestrator.shadow.ShadowExecutionComparator
import com.lichiai.toolruntime.brain.LichiCentralBrain
import com.lichiai.toolruntime.model.PausedTaskState
import com.lichiai.toolruntime.model.WorldRuntimeState
import com.lichiai.toolruntime.registry.UnifiedToolRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Result returned by the Universal Task Orchestrator V2.
 */
data class OrchestrationResult(
    val finalSpeech: String,
    val isSuccess: Boolean,
    val executedPlan: TaskPlan? = null,
    val primaryCapability: LichiCapability,
    val isDirectChat: Boolean = false,
    val directChatPrompt: String = "",
    val webContextPrompt: String? = null,
    val requiresBrowserUi: Boolean = false,
    val requiresConfirmation: Boolean = false,
    val confirmationPrompt: String? = null,
    val isPaused: Boolean = false,
    val pausedTask: PausedTaskState? = null,
    val spyProfile: com.lichiai.spy.model.PlatformProfile? = null,
    val spyProfiles: List<com.lichiai.spy.model.PlatformProfile> = emptyList(),
    val requiresLlmSynthesis: Boolean = false
)

typealias ConversationalAgentRuntime = UniversalTaskOrchestratorV2

/**
 * Universal LLM Task Orchestrator V2 for Lichi AI.
 * Thin adapter boundary connecting entry points to LichiCentralBrain.
 */
class UniversalTaskOrchestratorV2(
    private val context: Context,
    private val contextBuilder: ContextBuilder,
    private val llmClient: LlmClient,
    private val browserController: com.lichiai.browser.BrowserController? = null,
    private val webIntelligenceManager: com.lichiai.web.WebIntelligenceManager? = null,
    private val universalCallEngine: com.lichiai.calling.engine.UniversalCallEngine? = null,
    private val callActionExecutor: CallActionExecutor? = null,
    private val autonomousAgentTool: com.lichiai.agent.bridge.AutonomousAgentTool? = null,
    private val onNavigateToBrowser: () -> Unit = {},
    private val onNavigateToTerminal: () -> Unit = {},
    private val capabilityCatalog: CapabilityCatalogV2? = null,
    private val routeDispatcher: RouteDispatcher? = null,
    private val shadowComparator: ShadowExecutionComparator = ShadowExecutionComparator(),
    private val loopGuard: OrchestratorLoopGuard? = null,
    private val evaluator: TaskResultEvaluator? = null
) {

    companion object {
        private const val TAG = "TaskOrchestratorV2"
    }

    val shadowExecutionComparator: ShadowExecutionComparator get() = shadowComparator

    val toolRegistry = UnifiedToolRegistry(
        context = context,
        webIntelligenceManager = webIntelligenceManager ?: routeDispatcher?.webIntelligenceManager,
        browserController = browserController ?: routeDispatcher?.browserController,
        universalCallEngine = universalCallEngine ?: routeDispatcher?.universalCallEngine,
        callActionExecutor = callActionExecutor,
        autonomousAgentTool = autonomousAgentTool ?: routeDispatcher?.autonomousAgentTool,
        actionExecutor = com.lichiai.agent.Agent.getInstance(context).actionExecutor,
        terminalManager = com.lichiai.terminal.core.TerminalManager.getInstance(context),
        reminderManager = com.lichiai.time.manager.ReminderManager(context),
        timeCapabilityAdapter = com.lichiai.time.adapter.TimeCapabilityAdapter(context),
        memoryEngine = com.lichiai.memory.manager.LichiMemoryEngine.getInstance(context),
        skillRepository = com.lichiai.skill.repository.SkillRepository.getInstance(context),
        onNavigateToBrowser = onNavigateToBrowser.takeIf { it != {} } ?: (routeDispatcher?.onNavigateToBrowser ?: {}),
        onNavigateToTerminal = onNavigateToTerminal.takeIf { it != {} } ?: (routeDispatcher?.onNavigateToTerminal ?: {})
    )

    val centralBrain = LichiCentralBrain(context, toolRegistry, llmClient)

    /**
     * Executes a user request through the authoritative Lichi Central Brain.
     */
    suspend fun orchestrate(
        rawInput: String,
        context: IntentContext? = null,
        provider: ProviderConfig? = null,
        modelId: String? = null,
        mode: OrchestratorMode = OrchestratorMode.ENABLED,
        requestId: String = "",
        messageId: String = "",
        conversationId: String = "",
        onProgress: ((step: Int, total: Int, text: String) -> Unit)? = null
    ): OrchestrationResult = withContext(Dispatchers.Main) {
        val trimmed = rawInput.trim()
        if (trimmed.isBlank()) {
            return@withContext OrchestrationResult(
                finalSpeech = "",
                isSuccess = true,
                primaryCapability = LichiCapability.CHAT,
                isDirectChat = true,
                directChatPrompt = ""
            )
        }

        val convId = conversationId.ifBlank { context?.conversationId ?: "default_session" }
        val activeContext = context ?: contextBuilder.buildContext(convId)

        // 0. DETERMINISTIC #SPY PLATFORM INTELLIGENCE GATE & UNIVERSAL CONTEXT CONTINUATION
        val spyTrigger = com.lichiai.spy.core.SpyGate.checkTrigger(trimmed)
        if (spyTrigger is com.lichiai.spy.core.SpyGateResult.Triggered) {
            val appContext = this@UniversalTaskOrchestratorV2.context
            val spyOrchestrator = com.lichiai.spy.orchestrator.SpyRuntimeOrchestrator(
                context = appContext,
                settingsRepository = com.lichiai.data.SettingsRepository(appContext),
                llmClient = llmClient
            )
            val spyResult = spyOrchestrator.execute(
                rawInput = trimmed,
                provider = provider,
                modelId = modelId,
                requestId = requestId,
                messageId = messageId,
                onProgress = onProgress
            )
            contextBuilder.recordSpyExecution(spyResult.primaryProfile, spyResult.profiles, trimmed, spyResult.speech, conversationId = convId)
            return@withContext OrchestrationResult(
                finalSpeech = spyResult.speech,
                isSuccess = spyResult.isSuccess,
                primaryCapability = LichiCapability.CHAT,
                spyProfile = spyResult.primaryProfile,
                spyProfiles = spyResult.profiles
            )
        }

        if (mode == OrchestratorMode.DISABLED) {
            return@withContext OrchestrationResult(
                finalSpeech = "",
                isSuccess = true,
                primaryCapability = LichiCapability.CHAT,
                isDirectChat = true,
                directChatPrompt = trimmed
            )
        }

        // 1. UNIFIED COGNITION: Execute goal via Central Lichi Brain
        val worldState = WorldRuntimeState(
            currentBrowserUrl = activeContext.currentBrowserUrl,
            currentBrowserTitle = activeContext.currentBrowserTitle,
            activeCallState = activeContext.lastCallContact
        )

        val brainResult = centralBrain.executeGoal(
            rawInput = trimmed,
            provider = provider,
            modelId = modelId,
            conversationId = convId,
            requestId = requestId,
            worldState = worldState,
            onProgress = onProgress
        )

        if (brainResult.isDirectChat) {
            return@withContext OrchestrationResult(
                finalSpeech = "",
                isSuccess = true,
                primaryCapability = LichiCapability.CHAT,
                isDirectChat = true,
                directChatPrompt = brainResult.directChatPrompt.ifBlank { trimmed }
            )
        }

        if (brainResult.requiresConfirmation) {
            return@withContext OrchestrationResult(
                finalSpeech = brainResult.confirmationPrompt ?: brainResult.finalSpeech,
                isSuccess = true,
                primaryCapability = brainResult.primaryCapability,
                requiresConfirmation = true,
                confirmationPrompt = brainResult.confirmationPrompt,
                isPaused = true,
                pausedTask = brainResult.pausedTask
            )
        }

        // Form truthful executed TaskPlan from real tool execution records
        val steps = brainResult.executedTools.mapIndexed { idx, toolRes ->
            PlanStep(
                stepIndex = idx,
                capability = brainResult.primaryCapability,
                action = toolRes.toolId,
                arguments = toolRes.data,
                expectedOutcome = toolRes.outputSummary
            )
        }
        val records = brainResult.executedTools.mapIndexed { idx, toolRes ->
            StepExecutionRecord(
                step = steps[idx],
                isSuccess = toolRes.isSuccess,
                outputSummary = toolRes.outputSummary,
                rawResult = null
            )
        }
        val taskPlan = TaskPlan(
            taskId = UUID.randomUUID().toString(),
            conversationId = convId,
            requestId = requestId,
            messageId = messageId,
            userGoal = trimmed,
            rawInput = trimmed,
            steps = steps,
            executionRecords = records,
            isCompleted = brainResult.isSuccess,
            currentStepIndex = steps.size,
            taskState = if (brainResult.isSuccess) TaskState.COMPLETED else TaskState.FAILED
        )

        contextBuilder.recordExecution(
            capability = brainResult.primaryCapability,
            userGoal = trimmed,
            assistantResponse = brainResult.finalSpeech,
            actionType = brainResult.executedTools.lastOrNull()?.toolId ?: "CENTRAL_BRAIN",
            conversationId = convId
        )

        return@withContext OrchestrationResult(
            finalSpeech = brainResult.finalSpeech,
            isSuccess = brainResult.isSuccess,
            executedPlan = taskPlan,
            primaryCapability = brainResult.primaryCapability,
            requiresBrowserUi = brainResult.requiresBrowserUi,
            webContextPrompt = brainResult.webContextPrompt,
            requiresLlmSynthesis = brainResult.webContextPrompt != null && brainResult.finalSpeech.isBlank()
        )
    }

    /**
     * Resumes a paused cognitive task after confirmation.
     */
    suspend fun resumeTask(
        pausedTask: PausedTaskState,
        provider: ProviderConfig? = null,
        modelId: String? = null,
        userConfirmed: Boolean = true,
        onProgress: ((step: Int, total: Int, text: String) -> Unit)? = null
    ): OrchestrationResult = withContext(Dispatchers.Main) {
        val brainResult = centralBrain.resumeGoal(
            pausedTask = pausedTask,
            provider = provider,
            modelId = modelId,
            userConfirmed = userConfirmed,
            onProgress = onProgress
        )
        OrchestrationResult(
            finalSpeech = brainResult.finalSpeech,
            isSuccess = brainResult.isSuccess,
            primaryCapability = brainResult.primaryCapability,
            requiresBrowserUi = brainResult.requiresBrowserUi,
            webContextPrompt = brainResult.webContextPrompt
        )
    }
}
