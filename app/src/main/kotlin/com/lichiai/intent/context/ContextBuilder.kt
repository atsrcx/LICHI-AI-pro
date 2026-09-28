package com.lichiai.intent.context

import com.lichiai.browser.BrowserController
import com.lichiai.context.engine.UniversalContextContinuityEngine
import com.lichiai.context.model.SemanticEntity
import com.lichiai.intent.model.IntentContext
import com.lichiai.intent.model.LichiCapability
import com.lichiai.web.WebIntelligenceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * ContextBuilder manages the live conversational and execution context
 * across multi-turn interactions, bridged with UniversalContextContinuityEngine.
 */
class ContextBuilder(
    private val browserController: BrowserController? = null,
    private val webIntelligenceManager: WebIntelligenceManager? = null
) {
    private val continuityEngine = UniversalContextContinuityEngine.getInstance()

    private val _contextState = MutableStateFlow(IntentContext())
    val contextState = _contextState.asStateFlow()

    private val _activeTaskPlan = MutableStateFlow<com.lichiai.orchestrator.model.TaskPlan?>(null)
    val activeTaskPlan = _activeTaskPlan.asStateFlow()

    private val taskStack = mutableListOf<com.lichiai.orchestrator.model.TaskPlan>()

    fun setActiveTask(plan: com.lichiai.orchestrator.model.TaskPlan?) {
        _activeTaskPlan.value = plan
    }

    fun pushPausedTask(plan: com.lichiai.orchestrator.model.TaskPlan) {
        val paused = plan.copy(
            taskState = com.lichiai.orchestrator.model.TaskState.PAUSED,
            pausedAt = System.currentTimeMillis()
        )
        taskStack.removeAll { it.taskId == plan.taskId }
        taskStack.add(paused)
        if (_activeTaskPlan.value?.taskId == plan.taskId) {
            _activeTaskPlan.value = null
        }
    }

    fun popPausedTask(): com.lichiai.orchestrator.model.TaskPlan? {
        return if (taskStack.isNotEmpty()) taskStack.removeAt(taskStack.lastIndex) else null
    }

    fun peekPausedTask(): com.lichiai.orchestrator.model.TaskPlan? {
        return taskStack.lastOrNull()
    }

    fun clearTaskStack() {
        taskStack.clear()
        _activeTaskPlan.value = null
    }

    /**
     * Builds prioritized, bounded relevant context according to the Master Directive:
     * CURRENT USER INSTRUCTION > EXPLICIT CORRECTION > CURRENT TASK STATE > RECENT RESULT > CURRENT CAPABILITY STATE > CONVERSATION CONTEXT
     */
    fun compilePrioritizedContext(rawInput: String): String {
        val snapshot = _contextState.value
        val convId = snapshot.conversationId ?: "default_session"
        val semanticCtx = continuityEngine.getContext(convId)
        val active = _activeTaskPlan.value
        val paused = peekPausedTask()

        return buildString {
            // 1. Current user instruction
            append("1. CURRENT INSTRUCTION: \"$rawInput\"\n")

            // 2. Active topic and goal
            if (!semanticCtx.activeTopic.isNullOrBlank()) {
                append("2. ACTIVE TOPIC: ${semanticCtx.activeTopic}\n")
            }
            if (!semanticCtx.activeGoal.isNullOrBlank()) {
                append("2. ACTIVE GOAL: ${semanticCtx.activeGoal}\n")
            }

            // 3. Active Entity & Verified Facts
            val activeEntity = semanticCtx.getActiveEntity()
            if (activeEntity != null) {
                append("3. ACTIVE ENTITY: ${activeEntity.toCompactSummary()}\n")
            }
            if (semanticCtx.verifiedFacts.isNotEmpty()) {
                append("3. VERIFIED FACTS: ${semanticCtx.verifiedFacts.entries.take(8).joinToString { "${it.key}: ${it.value}" }}\n")
            }

            // 4. Active or paused task state
            if (active != null) {
                append("4. ACTIVE TASK: [${active.taskId}] Goal=\"${active.userGoal}\", Step ${active.currentStepIndex + 1}/${active.steps.size}\n")
            } else if (paused != null) {
                append("4. PAUSED TASK IN STACK: [${paused.taskId}] Goal=\"${paused.userGoal}\", PausedAtStep=${paused.currentStepIndex + 1}/${paused.steps.size}\n")
            }

            // 5. Recent results & candidates
            if (snapshot.browserCandidates.isNotEmpty()) {
                append("5. RECENT BROWSER CANDIDATES: ${snapshot.browserCandidates.take(5).joinToString(" | ")}\n")
            } else if (snapshot.recentResults.isNotEmpty()) {
                append("5. RECENT SEARCH RESULTS: ${snapshot.recentResults.take(4).joinToString(" | ")}\n")
            }

            // 6. Current capability state
            if (!snapshot.currentBrowserUrl.isNullOrBlank()) {
                append("6. CURRENT BROWSER: URL=\"${snapshot.currentBrowserUrl}\", Title=\"${snapshot.currentBrowserTitle ?: ""}\"\n")
            }
            if (snapshot.lastExecutedCapability != null) {
                append("6. LAST EXECUTED CAPABILITY: ${snapshot.lastExecutedCapability.displayName}\n")
            }

            // 7. Recent conversation context
            if (snapshot.recentTurns.isNotEmpty()) {
                append("7. RECENT TURNS:\n")
                for ((u, a) in snapshot.recentTurns.takeLast(3)) {
                    append("  User: \"$u\"\n  Assistant: \"${a.take(100)}\"\n")
                }
            }
        }
    }

    /**
     * Builds the current snapshot of IntentContext.
     */
    suspend fun buildContext(conversationId: String? = null): IntentContext {
        val currentSnapshot = _contextState.value
        val convId = conversationId?.takeIf { it.isNotBlank() } ?: currentSnapshot.conversationId ?: "default_session"
        _contextState.update { it.copy(conversationId = convId) }
        val semanticCtx = continuityEngine.getContext(convId)

        var currentUrl: String? = null
        var currentTitle: String? = null
        var candidates: List<String> = emptyList()

        if (browserController != null) {
            runCatching {
                val pageContext = browserController?.getPageContext()
                if (pageContext != null) {
                    currentUrl = pageContext.currentUrl.takeIf { it != "about:blank" }
                    currentTitle = pageContext.currentTitle.takeIf { it.isNotBlank() }
                    candidates = pageContext.extractedCandidates.map { it.title.ifBlank { it.url } }
                    // Update continuity engine with browser page context
                    continuityEngine.recordBrowserState(convId, currentUrl, currentTitle, candidates)
                }
            }
        }

        val semanticProfiles = semanticCtx.getProfiles().map { it.toPlatformProfile() }
        val activeProfile = semanticCtx.getActiveEntity()?.toPlatformProfile()
            ?: semanticProfiles.firstOrNull()
            ?: currentSnapshot.lastPlatformProfile

        val mergedProfiles = (semanticProfiles + currentSnapshot.recentProfiles).distinctBy { it.username to it.platform }

        val newContext = currentSnapshot.copy(
            conversationId = convId,
            currentBrowserUrl = currentUrl ?: semanticCtx.currentBrowserUrl ?: currentSnapshot.currentBrowserUrl,
            currentBrowserTitle = currentTitle ?: semanticCtx.currentBrowserTitle ?: currentSnapshot.currentBrowserTitle,
            browserCandidates = if (candidates.isNotEmpty()) candidates else if (semanticCtx.browserCandidates.isNotEmpty()) semanticCtx.browserCandidates else currentSnapshot.browserCandidates,
            lastPlatformProfile = activeProfile,
            recentProfiles = if (mergedProfiles.isNotEmpty()) mergedProfiles else currentSnapshot.recentProfiles,
            lastPlatform = activeProfile?.platform?.name ?: currentSnapshot.lastPlatform,
            lastUsername = activeProfile?.username ?: currentSnapshot.lastUsername,
            lastProfileUrl = activeProfile?.profileUrl ?: currentSnapshot.lastProfileUrl,
            recentTurns = if (semanticCtx.recentTurns.isNotEmpty()) semanticCtx.recentTurns else currentSnapshot.recentTurns
        )
        _contextState.value = newContext
        return newContext
    }

    /**
     * Updates context after a successful task execution.
     */
    fun recordExecution(
        capability: LichiCapability,
        userGoal: String,
        assistantResponse: String,
        searchQuery: String? = null,
        results: List<String> = emptyList(),
        entities: Map<String, String> = emptyMap(),
        actionType: String? = null,
        activeTaskState: com.lichiai.intent.model.ActiveTaskState = com.lichiai.intent.model.ActiveTaskState.COMPLETED,
        conversationId: String? = null
    ) {
        val convId = conversationId?.takeIf { it.isNotBlank() } ?: _contextState.value.conversationId ?: "default_session"
        continuityEngine.recordExecution(
            conversationId = convId,
            capability = capability,
            userGoal = userGoal,
            assistantResponse = assistantResponse,
            searchQuery = searchQuery,
            results = results,
            entities = entities,
            actionType = actionType
        )

        _contextState.update { curr ->
            val updatedEntities = curr.recentEntities.toMutableMap().apply { putAll(entities) }
            val updatedTurns = (curr.recentTurns + (userGoal to assistantResponse)).takeLast(10)
            curr.copy(
                conversationId = convId,
                activeCapability = capability,
                lastExecutedCapability = capability,
                lastUserGoal = userGoal,
                lastAssistantResponse = assistantResponse,
                lastSearchQuery = searchQuery ?: curr.lastSearchQuery,
                lastActionTimestamp = System.currentTimeMillis(),
                recentResults = if (results.isNotEmpty()) results else curr.recentResults,
                recentEntities = updatedEntities,
                lastActionType = actionType ?: curr.lastActionType,
                activeTaskState = activeTaskState,
                pendingConfirmation = null,
                recentTurns = updatedTurns
            )
        }
    }

    fun recordSpyExecution(
        profile: com.lichiai.spy.model.PlatformProfile?,
        profiles: List<com.lichiai.spy.model.PlatformProfile>,
        userGoal: String,
        assistantResponse: String,
        conversationId: String? = null
    ) {
        val convId = conversationId?.takeIf { it.isNotBlank() } ?: _contextState.value.conversationId ?: "default_session"
        continuityEngine.recordSpyExecution(
            conversationId = convId,
            primaryProfile = profile,
            allProfiles = profiles,
            userGoal = userGoal,
            assistantResponse = assistantResponse
        )

        _contextState.update { curr ->
            val updatedProfiles = (profiles + listOfNotNull(profile)).distinctBy { it.username to it.platform }
            val updatedTurns = (curr.recentTurns + (userGoal to assistantResponse)).takeLast(10)
            curr.copy(
                conversationId = convId,
                activeCapability = LichiCapability.CHAT,
                lastExecutedCapability = LichiCapability.CHAT,
                lastUserGoal = userGoal,
                lastAssistantResponse = assistantResponse,
                lastActionTimestamp = System.currentTimeMillis(),
                lastPlatform = profile?.platform?.name ?: curr.lastPlatform,
                lastUsername = profile?.username ?: curr.lastUsername,
                lastProfileUrl = profile?.profileUrl ?: curr.lastProfileUrl,
                lastPlatformProfile = profile ?: curr.lastPlatformProfile,
                recentProfiles = if (updatedProfiles.isNotEmpty()) updatedProfiles else curr.recentProfiles,
                recentEntities = profile?.let { mapOf("username" to it.username, "platform" to it.platform.name, "entity" to "@${it.username}") } ?: curr.recentEntities,
                recentTurns = updatedTurns,
                activeTaskState = com.lichiai.intent.model.ActiveTaskState.COMPLETED
            )
        }
    }

    /**
     * Records a pending confirmation request (e.g. "Rahul ko call karun?").
     */
    fun setPendingConfirmation(actionDescription: String) {
        _contextState.update { curr ->
            curr.copy(
                pendingConfirmation = actionDescription,
                activeTaskState = com.lichiai.intent.model.ActiveTaskState.WAITING_FOR_CONFIRMATION
            )
        }
    }

    /**
     * Clears pending confirmation.
     */
    fun clearPendingConfirmation() {
        _contextState.update { curr ->
            curr.copy(
                pendingConfirmation = null,
                activeTaskState = com.lichiai.intent.model.ActiveTaskState.NO_ACTIVE_TASK
            )
        }
    }

    /**
     * Clears transient task context.
     */
    fun reset() {
        _contextState.value = IntentContext()
    }
}
