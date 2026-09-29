package com.lichiai

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lichiai.api.ChatMessage
import com.lichiai.api.LlmClient
import com.lichiai.data.AppSettings
import com.lichiai.data.Assistant
import com.lichiai.data.AssistantPresets
import com.lichiai.data.AssistantStore
import com.lichiai.assistant.model.ActiveAssistant
import com.lichiai.assistant.resolver.ActiveAssistantResolver
import com.lichiai.prompt.LichiPromptAssembler
import com.lichiai.data.Conversation
import com.lichiai.data.ConversationStore
import com.lichiai.data.Message
import com.lichiai.data.ProviderConfig
import com.lichiai.data.ProviderStore
import com.lichiai.data.SettingsRepository
import com.lichiai.ui.activity.toAssistantActivity
import com.lichiai.util.PromptVars
import com.lichiai.util.newId
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val store = ConversationStore(app)
    val settingsRepo = SettingsRepository(app)
    val providerStore = ProviderStore(app)
    val assistantStore = AssistantStore(app)
    val voiceSettingsRepo = com.lichiai.data.VoiceSettingsRepository(app)
    val callPermissionManager = com.lichiai.calling.permission.CallPermissionManager(app)
    val contactAliasesRepo = com.lichiai.calling.contacts.ContactAliasesRepository(app)
    val contactRepository = com.lichiai.calling.contacts.ContactRepository(app, callPermissionManager, contactAliasesRepo)
    val callDiagnosticsRepo = com.lichiai.calling.engine.CallDiagnosticsRepository()
    val universalCallEngine = com.lichiai.calling.engine.UniversalCallEngine(app, callPermissionManager, contactRepository, callDiagnosticsRepo)
    val callHandlingSettingsRepo = com.lichiai.calling.data.CallHandlingSettingsRepository(app)
    val callStateMonitor = com.lichiai.calling.state.CallStateMonitor.getInstance(app, contactRepository)
    val callActionIntentResolver = com.lichiai.calling.intent.CallActionIntentResolver()
    val callActionExecutor = com.lichiai.calling.action.CallActionExecutor(
        context = app,
        universalCallEngine = universalCallEngine,
        callPermissionManager = callPermissionManager,
        callStateMonitor = callStateMonitor
    )
    val dynamicIslandController = com.lichiai.dynamicisland.DynamicIslandController.getInstance(app)
    val webIntelligenceManager = com.lichiai.web.WebIntelligenceManager.getInstance(app)
    val webActivityState: StateFlow<com.lichiai.web.model.WebActivityState> = webIntelligenceManager.activityState
    val autonomousAgentTool = com.lichiai.agent.bridge.AutonomousAgentTool(app)
    val agentLiveStatus: StateFlow<com.lichiai.agent.model.AgentLiveStatus> = autonomousAgentTool.liveStatus
    val skillRepository = com.lichiai.skill.repository.SkillRepository.getInstance(app)
    val skills: StateFlow<List<com.lichiai.skill.model.Skill>> = skillRepository.skills
    val browserController = com.lichiai.browser.BrowserController(app, viewModelScope)
    val reminderManager = com.lichiai.time.manager.ReminderManager(app)
    val timeCapabilityAdapter = com.lichiai.time.adapter.TimeCapabilityAdapter(app)
    val terminalManager = com.lichiai.terminal.core.TerminalManager.getInstance(app)
    val memoryEngine = com.lichiai.memory.manager.LichiMemoryEngine.getInstance(app)

    private val _browserNavigationEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val browserNavigationEvent: SharedFlow<Unit> = _browserNavigationEvent.asSharedFlow()

    private val _reminderNavigationEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val reminderNavigationEvent: SharedFlow<Unit> = _reminderNavigationEvent.asSharedFlow()

    private val _terminalNavigationEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val terminalNavigationEvent: SharedFlow<Unit> = _terminalNavigationEvent.asSharedFlow()

    fun openTerminal() {
        _terminalNavigationEvent.tryEmit(Unit)
    }

    private val client = LlmClient()

    val intentContextBuilder = com.lichiai.intent.context.ContextBuilder(
        browserController = browserController,
        webIntelligenceManager = webIntelligenceManager
    )
    val capabilityRegistry = com.lichiai.intent.registry.CapabilityRegistry(
        context = app,
        isAutonomousAgentEnabled = { autonomousAgentTool.isEnabled() },
        isWebSearchEnabled = { true }
    )
    val universalIntentEngine = com.lichiai.intent.UniversalIntentEngine(
        capabilityRegistry = capabilityRegistry,
        contextBuilder = intentContextBuilder,
        semanticRouter = com.lichiai.intent.router.SemanticRouter(availableSkillsProvider = { skillRepository.skills.value }),
        llmClient = client
    )
    val routeDispatcher = com.lichiai.intent.dispatcher.RouteDispatcher(
        context = app,
        browserController = browserController,
        webIntelligenceManager = webIntelligenceManager,
        autonomousAgentTool = autonomousAgentTool,
        universalCallEngine = universalCallEngine,
        contextBuilder = intentContextBuilder,
        onNavigateToBrowser = { _browserNavigationEvent.tryEmit(Unit) },
        onNavigateToTerminal = { _terminalNavigationEvent.tryEmit(Unit) }
    )

    val taskOrchestratorV2 = com.lichiai.orchestrator.UniversalTaskOrchestratorV2(
        context = app,
        capabilityCatalog = com.lichiai.orchestrator.catalog.CapabilityCatalogV2(
            isAutonomousAgentEnabled = { autonomousAgentTool.isEnabled() },
            isWebSearchEnabled = { true }
        ),
        contextBuilder = intentContextBuilder,
        routeDispatcher = routeDispatcher,
        legacyIntentEngine = universalIntentEngine,
        llmClient = client
    )

    private val _activeId = MutableStateFlow<String?>(null)
    val activeId: StateFlow<String?> = _activeId.asStateFlow()

    val voiceOrchestrator = com.lichiai.voice.VoiceConversationOrchestrator(
        context = app,
        llmClient = client,
        voiceSettingsRepository = voiceSettingsRepo,
        settingsRepository = settingsRepo,
        callEngine = universalCallEngine,
        callActionExecutor = callActionExecutor,
        callActionIntentResolver = callActionIntentResolver,
        conversationStore = store,
        activeConversationIdProvider = { _activeId.value },
        onConversationIdChanged = { id -> _activeId.value = id },
        webIntelligenceManager = webIntelligenceManager,
        onExecuteBrowserCommand = { command ->
            _browserNavigationEvent.tryEmit(Unit)
            browserController.agent.submitInstruction(command)
        },
        universalIntentEngine = universalIntentEngine,
        routeDispatcher = routeDispatcher,
        taskOrchestratorV2 = taskOrchestratorV2,
        providerStore = providerStore
    )

    val wakeWordManager = com.lichiai.voice.wakeword.WakeWordManager(
        context = app,
        voiceSettingsRepository = voiceSettingsRepo,
        voiceOrchestrator = voiceOrchestrator
    )

    private val _wakeDetectedEvent = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val wakeDetectedEvent: SharedFlow<String> = _wakeDetectedEvent.asSharedFlow()

    val settings: StateFlow<AppSettings> = settingsRepo.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val providers: StateFlow<List<ProviderConfig>> = providerStore.providersFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val assistants: StateFlow<List<Assistant>> = assistantStore.assistantsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val conversations: StateFlow<List<Conversation>> = store.conversationsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** In-memory overlay for the message currently being streamed.
     *  Avoids a DataStore write on every token while keeping the UI live. */
    private val _streamingOverlay = MutableStateFlow<Pair<String, String>?>(null)
    val streamingOverlay: StateFlow<Pair<String, String>?> = _streamingOverlay.asStateFlow()

    private val _liveActivityState = MutableStateFlow<com.lichiai.ui.activity.AssistantActivityState?>(null)
    val liveActivityState: StateFlow<com.lichiai.ui.activity.AssistantActivityState?> = _liveActivityState.asStateFlow()

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    private val _fetchingModelsFor = MutableStateFlow<String?>(null)
    val fetchingModelsFor: StateFlow<String?> = _fetchingModelsFor.asStateFlow()

    private var streamingJob: Job? = null

    // Canonical Text-Mode TTS Manager (shares the exact same VoiceSettings as Voice Mode)
    private var textTtsManager: com.lichiai.voice.tts.TextToSpeechManager? = null
    private var lastTtsSettings = com.lichiai.data.VoiceSettings()
    private val _activeSpeakingMessageId = MutableStateFlow<String?>(null)
    val activeSpeakingMessageId: StateFlow<String?> = _activeSpeakingMessageId.asStateFlow()

    init {
        try {
            dynamicIslandController.start()
        } catch (_: Throwable) {}

        // Initialize Text-Mode TTS engine listener
        textTtsManager = com.lichiai.voice.tts.TextToSpeechManager(app, object : com.lichiai.voice.tts.TextToSpeechListener {
            override fun onEngineInitialized(isSuccess: Boolean) {}
            override fun onUtteranceStart(utteranceId: String) {}
            override fun onUtteranceDone(utteranceId: String, isQueueEmpty: Boolean) {
                if (isQueueEmpty) {
                    _activeSpeakingMessageId.value = null
                }
            }
            override fun onUtteranceError(utteranceId: String, errorMessage: String) {
                _activeSpeakingMessageId.value = null
            }
        })

        viewModelScope.launch {
            voiceSettingsRepo.settings.collect { vs ->
                val engineChanged = vs.ttsMode != lastTtsSettings.ttsMode || vs.ttsEnginePackage != lastTtsSettings.ttsEnginePackage
                lastTtsSettings = vs
                if (engineChanged || textTtsManager?.isReady() != true) {
                    textTtsManager?.initialize(vs)
                } else {
                    textTtsManager?.applySettings(vs)
                }
            }
        }

        viewModelScope.launch {
            wakeWordManager.wakeEvents.collect { event ->
                if (event is com.lichiai.voice.wakeword.WakeWordEvent.Detected) {
                    _wakeDetectedEvent.emit(event.phrase)
                }
            }
        }

        viewModelScope.launch {
            com.lichiai.terminal.task.TerminalTaskManager.getInstance(app).currentLiveActivity.collect { terminalActivity ->
                if (terminalActivity != null) {
                    _liveActivityState.value = terminalActivity
                }
            }
        }
    }

    fun toggleSpeakMessage(messageId: String, text: String) {
        if (_activeSpeakingMessageId.value == messageId) {
            stopSpeakingMessage()
        } else {
            stopSpeakingMessage()
            if (text.isNotBlank()) {
                _activeSpeakingMessageId.value = messageId
                textTtsManager?.applySettings(lastTtsSettings)
                textTtsManager?.enqueueSentence(text)
            }
        }
    }

    fun stopSpeakingMessage() {
        _activeSpeakingMessageId.value = null
        textTtsManager?.stopAndClearQueue()
    }

    fun triggerVoiceMode(phrase: String = "Wake Word") {
        viewModelScope.launch {
            _wakeDetectedEvent.emit(phrase)
        }
    }

    fun selectConversation(id: String?) {
        _activeId.value = id
        if (id != null) {
            viewModelScope.launch {
                val conv = store.snapshot().firstOrNull { it.id == id }
                if (!conv?.conversationStateJson.isNullOrBlank()) {
                    com.lichiai.context.engine.UniversalContextContinuityEngine.getInstance().importContext(id, conv!!.conversationStateJson!!)
                } else {
                    val lastSpyProfile = conv?.messages?.asReversed()?.firstNotNullOfOrNull { it.spyProfile ?: it.spyProfiles.firstOrNull() }
                    val allSpyProfiles = conv?.messages?.asReversed()?.flatMap { it.spyProfiles + listOfNotNull(it.spyProfile) }?.distinctBy { it.username to it.platform } ?: emptyList()
                    if (lastSpyProfile != null) {
                        com.lichiai.context.engine.UniversalContextContinuityEngine.getInstance().restoreFromProfiles(id, allSpyProfiles.ifEmpty { listOf(lastSpyProfile) })
                        intentContextBuilder.recordSpyExecution(lastSpyProfile, allSpyProfiles, "Restored from history", "Restored conversation context", conversationId = id)
                    }
                }
                intentContextBuilder.buildContext(id)
            }
        }
    }

    fun newConversation(): String {
        val id = newId()
        _activeId.value = id
        return id
    }

    fun deleteConversation(id: String) {
        viewModelScope.launch {
            store.delete(id)
            if (_activeId.value == id) _activeId.value = null
        }
    }

    fun renameConversation(id: String, title: String) {
        viewModelScope.launch { store.rename(id, title) }
    }

    fun stopStreaming() {
        streamingJob?.cancel()
        streamingJob = null
        _isStreaming.value = false
    }

    fun clearError() { _error.value = null }
    fun clearToast() { _toast.value = null }

    fun activeProvider(): ProviderConfig? =
        providers.value.firstOrNull { it.id == settings.value.activeProviderId }

    fun activeAssistantProfile(): ActiveAssistant =
        ActiveAssistantResolver.resolve(settings.value.activeAssistantId, assistants.value)

    fun activeAssistant(): Assistant? =
        activeAssistantProfile().toAssistant()

    fun selectModel(providerId: String, model: String) {
        viewModelScope.launch {
            settingsRepo.update { it.copy(activeProviderId = providerId, activeModel = model) }
        }
    }

    fun selectAssistant(id: String) {
        viewModelScope.launch {
            settingsRepo.update { it.copy(activeAssistantId = id) }
        }
    }

    fun upsertAssistant(a: Assistant) {
        viewModelScope.launch { assistantStore.upsert(a) }
    }

    fun deleteAssistant(id: String) {
        viewModelScope.launch {
            assistantStore.delete(id)
            if (settings.value.activeAssistantId == id) {
                val remaining = assistantStore.snapshot()
                settingsRepo.update {
                    it.copy(activeAssistantId = remaining.firstOrNull()?.id ?: "default")
                }
            }
        }
    }

    fun upsertProvider(p: ProviderConfig) {
        viewModelScope.launch {
            providerStore.upsert(p)
            val all = providerStore.snapshot()
            if (settings.value.activeProviderId.isBlank() && all.isNotEmpty()) {
                val target = all.firstOrNull { it.id == p.id } ?: all.first()
                val firstModel = target.models.firstOrNull() ?: ""
                settingsRepo.update {
                    it.copy(activeProviderId = target.id, activeModel = firstModel)
                }
            }
        }
    }

    fun deleteProvider(id: String) {
        viewModelScope.launch {
            providerStore.delete(id)
            if (settings.value.activeProviderId == id) {
                val remaining = providerStore.snapshot()
                val nextProvider = remaining.firstOrNull()
                settingsRepo.update {
                    it.copy(
                        activeProviderId = nextProvider?.id ?: "",
                        activeModel = nextProvider?.models?.firstOrNull() ?: ""
                    )
                }
            }
        }
    }

    fun fetchModels(providerId: String) {
        viewModelScope.launch {
            val provider = providerStore.snapshot().firstOrNull { it.id == providerId } ?: return@launch
            _fetchingModelsFor.value = providerId
            try {
                val models = client.listModels(provider)
                if (models.isEmpty()) {
                    _toast.value = "No models returned from /models"
                } else {
                    val updated = provider.copy(models = (provider.models + models).distinct().sorted())
                    providerStore.upsert(updated)
                    _toast.value = "Fetched ${models.size} models"
                }
            } catch (e: Exception) {
                _error.value = "Fetch models failed: ${e.message}"
            } finally {
                _fetchingModelsFor.value = null
            }
        }
    }

    fun addManualModel(providerId: String, model: String) {
        viewModelScope.launch {
            val trimmed = model.trim()
            if (trimmed.isEmpty()) return@launch
            val provider = providerStore.snapshot().firstOrNull { it.id == providerId } ?: return@launch
            val updated = provider.copy(models = (provider.models + trimmed).distinct().sorted())
            providerStore.upsert(updated)
        }
    }

    fun removeModel(providerId: String, model: String) {
        viewModelScope.launch {
            val provider = providerStore.snapshot().firstOrNull { it.id == providerId } ?: return@launch
            val updated = provider.copy(models = provider.models - model)
            providerStore.upsert(updated)
            if (settings.value.activeProviderId == providerId && settings.value.activeModel == model) {
                settingsRepo.update {
                    it.copy(activeModel = updated.models.firstOrNull() ?: "")
                }
            }
        }
    }

    fun executeCallAction(action: com.lichiai.calling.action.StructuredCallAction) {
        viewModelScope.launch {
            callActionExecutor.execute(action)
        }
    }

    private suspend fun updateAssistantMessage(
        convId: String,
        msgId: String,
        content: String? = null,
        taskActivity: com.lichiai.ui.activity.AssistantActivityState? = null,
        spyProfile: com.lichiai.spy.model.PlatformProfile? = null,
        spyProfiles: List<com.lichiai.spy.model.PlatformProfile> = emptyList()
    ) {
        val list = store.snapshot()
        val conv = list.firstOrNull { it.id == convId } ?: return
        val newMsgs = conv.messages.map {
            if (it.id == msgId) {
                it.copy(
                    content = content ?: it.content,
                    taskActivity = taskActivity ?: it.taskActivity,
                    spyProfile = spyProfile ?: it.spyProfile,
                    spyProfiles = if (spyProfiles.isNotEmpty()) spyProfiles else it.spyProfiles
                )
            } else it
        }
        val exportedContext = com.lichiai.context.engine.UniversalContextContinuityEngine.getInstance().exportContext(convId)
        store.upsert(conv.copy(
            messages = newMsgs,
            updatedAt = System.currentTimeMillis(),
            conversationStateJson = exportedContext.ifBlank { conv.conversationStateJson }
        ))
    }

    fun sendMessage(text: String, attachments: List<com.lichiai.data.Attachment> = emptyList()) {
        // Reject re-entrant sends while a stream is in flight (debounce double-tap)
        if (_isStreaming.value || streamingJob?.isActive == true) return
        val trimmed = text.trim()
        if (trimmed.isEmpty() && attachments.isEmpty()) return

        val activeId = _activeId.value ?: newId().also { _activeId.value = it }
        val baseTitle = trimmed.take(30).replace("\n", " ").ifBlank { "New Chat" }
        val requestId = newId()
        val userMsgId = newId()
        val assistantMsgId = newId()

        // Intercept structured call action commands (Answer, Reject, Mute, Speaker, Hold, End)
        val currentCallSession = callStateMonitor.currentSession()
        val structuredAction = callActionIntentResolver.resolve(trimmed, currentCallSession)
        if (attachments.isEmpty() && structuredAction != null) {
            viewModelScope.launch {
                val userMsg = Message(
                    id = userMsgId,
                    role = "user",
                    content = trimmed,
                    attachments = attachments,
                    requestId = requestId
                )
                val assistantPlaceholder = Message(
                    id = assistantMsgId,
                    role = "assistant",
                    content = "",
                    requestId = requestId
                )
                val existing = store.snapshot().firstOrNull { it.id == activeId }
                val initialConv = (existing ?: Conversation(id = activeId, title = baseTitle, messages = emptyList()))
                    .let { conv ->
                        conv.copy(
                            title = if (conv.messages.isEmpty()) baseTitle else conv.title,
                            messages = conv.messages + userMsg + assistantPlaceholder,
                            updatedAt = System.currentTimeMillis()
                        )
                    }
                store.upsert(initialConv)

                val callActionResult = callActionExecutor.execute(structuredAction)
                updateAssistantMessage(activeId, assistantMsgId, content = callActionResult.message)
            }
            return
        }

        val current = settings.value
        val asstSnapshot = assistants.value.ifEmpty { AssistantPresets.defaults() }
        val activeAsstProfile = ActiveAssistantResolver.resolve(current.activeAssistantId, asstSnapshot)
        val assistant = activeAsstProfile.toAssistant()

        // Resolve effective provider and model: assistant override > settings active
        val provider = activeAsstProfile.preferredProviderId
            ?.let { id -> providers.value.firstOrNull { it.id == id } }
            ?: activeProvider()
        val model = activeAsstProfile.preferredModel?.takeIf { it.isNotBlank() }
            ?: current.activeModel

        val orchMode = runCatching {
            com.lichiai.orchestrator.model.OrchestratorMode.valueOf(current.orchestratorMode)
        }.getOrDefault(com.lichiai.orchestrator.model.OrchestratorMode.ENABLED)

        viewModelScope.launch {
            // Immediate insertion of UserMessage and AssistantPlaceholder explicitly associated with requestId
            val userMsg = Message(
                id = userMsgId,
                role = "user",
                content = trimmed,
                attachments = attachments,
                requestId = requestId
            )
            val assistantPlaceholder = Message(
                id = assistantMsgId,
                role = "assistant",
                content = "",
                requestId = requestId
            )
            val existing = store.snapshot().firstOrNull { it.id == activeId }
            val initialConv = (existing ?: Conversation(id = activeId, title = baseTitle, messages = emptyList()))
                .let { conv ->
                    conv.copy(
                        title = if (conv.messages.isEmpty()) baseTitle else conv.title,
                        messages = conv.messages + userMsg + assistantPlaceholder,
                        updatedAt = System.currentTimeMillis()
                    )
                }
            store.upsert(initialConv)
            runCatching { memoryEngine.recordTurn(conversationId = activeId, messageId = userMsgId, role = "user", content = trimmed) }

            var webSearchOverridePrompt = ""

            if (attachments.isEmpty() && orchMode != com.lichiai.orchestrator.model.OrchestratorMode.DISABLED) {
                val intentCtx = intentContextBuilder.buildContext(_activeId.value)
                val stepsList = mutableListOf<com.lichiai.ui.activity.AssistantActivityStep>()

                _isStreaming.value = true
                _liveActivityState.value = com.lichiai.ui.activity.AssistantActivityState(
                    requestId = requestId,
                    messageId = assistantMsgId,
                    kind = com.lichiai.ui.activity.ActivityKind.THINKING,
                    title = "Understanding task...",
                    isActive = true
                )

                val orchResult = taskOrchestratorV2.orchestrate(
                    rawInput = trimmed,
                    context = intentCtx,
                    provider = provider,
                    modelId = model,
                    mode = orchMode,
                    requestId = requestId,
                    messageId = assistantMsgId,
                    conversationId = activeId
                ) { step, total, statusText ->
                    stepsList.add(com.lichiai.ui.activity.AssistantActivityStep(
                        stepIndex = step,
                        title = statusText,
                        isCompleted = true
                    ))
                    _liveActivityState.value = com.lichiai.ui.activity.AssistantActivityState(
                        requestId = requestId,
                        messageId = assistantMsgId,
                        kind = com.lichiai.ui.activity.ActivityKind.AGENT_WORKING,
                        title = "Executing task...",
                        subtitle = "Step $step/$total: $statusText",
                        step = step,
                        totalSteps = total,
                        isActive = true,
                        stepHistory = stepsList.toList()
                    )
                }

                _liveActivityState.value = null
                _isStreaming.value = false

                if (!orchResult.isDirectChat) {
                    val iconPrefix = when (orchResult.primaryCapability) {
                        com.lichiai.intent.model.LichiCapability.BROWSER -> "🌐 "
                        com.lichiai.intent.model.LichiCapability.ANDROID_AGENT -> "🤖 "
                        com.lichiai.intent.model.LichiCapability.CALLS -> "📞 "
                        com.lichiai.intent.model.LichiCapability.MEDIA_YOUTUBE -> "🎵 "
                        com.lichiai.intent.model.LichiCapability.TERMINAL -> "💻 "
                        com.lichiai.intent.model.LichiCapability.TIME_REMINDER -> "⏰ "
                        else -> ""
                    }
                    val finalMsgContent = "$iconPrefix${orchResult.finalSpeech.ifBlank { "Task completed." }}"
                    val completedActivity = com.lichiai.ui.activity.AssistantActivityState(
                        requestId = requestId,
                        messageId = assistantMsgId,
                        kind = if (orchResult.isSuccess) com.lichiai.ui.activity.ActivityKind.COMPLETED else com.lichiai.ui.activity.ActivityKind.FAILED,
                        title = if (orchResult.isSuccess) "Task completed" else "Task paused",
                        subtitle = if (stepsList.isNotEmpty()) "Completed ${stepsList.size} step${if (stepsList.size > 1) "s" else ""}" else "",
                        isActive = false,
                        stepHistory = stepsList.toList()
                    )
                    updateAssistantMessage(
                        activeId,
                        assistantMsgId,
                        content = finalMsgContent,
                        taskActivity = completedActivity,
                        spyProfile = orchResult.spyProfile,
                        spyProfiles = orchResult.spyProfiles
                    )
                    if (orchResult.spyProfile != null) {
                        intentContextBuilder.recordSpyExecution(orchResult.spyProfile, orchResult.spyProfiles, trimmed, orchResult.finalSpeech, conversationId = activeId)
                    } else {
                        intentContextBuilder.recordExecution(
                            capability = orchResult.primaryCapability,
                            userGoal = trimmed,
                            assistantResponse = orchResult.finalSpeech,
                            conversationId = activeId
                        )
                    }

                    if (orchResult.requiresBrowserUi) {
                        _browserNavigationEvent.emit(Unit)
                    }
                    if (orchResult.primaryCapability == com.lichiai.intent.model.LichiCapability.TERMINAL) {
                        val termActivity = com.lichiai.terminal.task.TerminalTaskManager.getInstance(getApplication()).currentLiveActivity.value
                        if (termActivity != null) {
                            updateAssistantMessage(activeId, assistantMsgId, content = finalMsgContent, taskActivity = termActivity)
                        }
                    }
                    return@launch
                }

                if (orchResult.webContextPrompt != null) {
                    webSearchOverridePrompt = orchResult.webContextPrompt
                } else if (orchResult.directChatPrompt.isNotBlank() && orchResult.directChatPrompt != trimmed) {
                    webSearchOverridePrompt = orchResult.directChatPrompt
                }
            } else {
                val intentResolution = if (attachments.isEmpty()) {
                    val intentCtx = intentContextBuilder.buildContext(_activeId.value)
                    universalIntentEngine.resolve(
                        rawInput = trimmed,
                        context = intentCtx,
                        provider = provider,
                        modelId = model
                    )
                } else {
                    com.lichiai.intent.model.IntentResolutionResult(
                        intent = com.lichiai.intent.model.ResolvedIntent.NormalChat(prompt = trimmed),
                        confidence = 1.0f,
                        source = com.lichiai.intent.model.ResolutionSource.FALLBACK,
                        normalizedInput = trimmed,
                        rationale = "Multimodal attachments provided."
                    )
                }

                when (val resIntent = intentResolution.intent) {
                    is com.lichiai.intent.model.ResolvedIntent.TerminalTask -> {
                        val outcome = routeDispatcher.dispatch(resIntent)
                        if (outcome is com.lichiai.intent.dispatcher.DispatchExecutionResult.TerminalExecuted) {
                            val finalActivity = com.lichiai.terminal.task.TerminalTaskManager.getInstance(getApplication()).currentLiveActivity.value
                            updateAssistantMessage(
                                activeId,
                                assistantMsgId,
                                content = outcome.message,
                                taskActivity = finalActivity
                            )
                            _liveActivityState.value = null
                            if (outcome.requiresScreenNavigation) {
                                _terminalNavigationEvent.emit(Unit)
                            }
                        }
                        return@launch
                    }

                    is com.lichiai.intent.model.ResolvedIntent.TimeReminderTask -> {
                        val outcome = timeCapabilityAdapter.handleQuery(resIntent.rawInput)
                        updateAssistantMessage(activeId, assistantMsgId, content = "⏰ ${outcome.naturalSpeech}")
                        if (outcome.requiresScreenNavigation) {
                            _reminderNavigationEvent.emit(Unit)
                        }
                        intentContextBuilder.recordExecution(
                            capability = com.lichiai.intent.model.LichiCapability.TIME_REMINDER,
                            userGoal = trimmed,
                            assistantResponse = outcome.naturalSpeech
                        )
                        return@launch
                    }

                    is com.lichiai.intent.model.ResolvedIntent.CallTask -> {
                        val callOutcome = universalCallEngine.executeIntent(resIntent.callIntent, sourceMode = "TEXT")
                        updateAssistantMessage(activeId, assistantMsgId, content = callOutcome.message)
                        intentContextBuilder.recordExecution(
                            capability = com.lichiai.intent.model.LichiCapability.CALLS,
                            userGoal = trimmed,
                            assistantResponse = callOutcome.message
                        )
                        return@launch
                    }

                    is com.lichiai.intent.model.ResolvedIntent.BrowserTask -> {
                        updateAssistantMessage(activeId, assistantMsgId, content = "🌐 ${resIntent.naturalAcknowledgment}")

                        // Navigate to visible BrowserScreen
                        _browserNavigationEvent.emit(Unit)

                        // Execute via routeDispatcher / browserController
                        val execInstruction = if (resIntent.action == com.lichiai.intent.model.BrowserActionType.SEARCH && !resIntent.query.isNullOrBlank()) {
                            "search on ${resIntent.searchEngine} for ${resIntent.query}"
                        } else {
                            resIntent.rawPrompt.ifBlank { trimmed }
                        }

                        if (resIntent.action == com.lichiai.intent.model.BrowserActionType.SEARCH && !resIntent.query.isNullOrBlank()) {
                            browserController.search(resIntent.query, resIntent.searchEngine)
                        }

                        browserController.agent.submitInstruction(execInstruction) { resultText ->
                            viewModelScope.launch {
                                if (resultText.isNotBlank()) {
                                    updateAssistantMessage(activeId, assistantMsgId, content = "🌐 $resultText")
                                    intentContextBuilder.recordExecution(
                                        capability = com.lichiai.intent.model.LichiCapability.BROWSER,
                                        userGoal = trimmed,
                                        assistantResponse = resultText,
                                        searchQuery = resIntent.query
                                    )
                                }
                            }
                        }
                        return@launch
                    }

                    is com.lichiai.intent.model.ResolvedIntent.AndroidAgentTask -> {
                        if (!autonomousAgentTool.isEnabled()) {
                            val responseMsgText = "Autonomous Agent is currently disabled. You can enable it in Settings under Autonomous Agent V2 to perform device automation."
                            updateAssistantMessage(activeId, assistantMsgId, content = responseMsgText)
                            return@launch
                        }

                        val skillTag = if (resIntent.matchedSkills.isNotEmpty()) {
                            " [Skill: ${resIntent.matchedSkills.joinToString { it.name }}]"
                        } else ""

                        updateAssistantMessage(activeId, assistantMsgId, content = "🤖$skillTag ${resIntent.naturalAcknowledgment}")

                        val stepsList = mutableListOf<com.lichiai.ui.activity.AssistantActivityStep>()
                        _isStreaming.value = true
                        _liveActivityState.value = com.lichiai.ui.activity.AssistantActivityState(
                            requestId = requestId,
                            messageId = assistantMsgId,
                            kind = com.lichiai.ui.activity.ActivityKind.AGENT_WORKING,
                            title = "Agent working",
                            subtitle = resIntent.naturalAcknowledgment,
                            isActive = true
                        )

                        val result = autonomousAgentTool.execute(resIntent.goal, resIntent.matchedSkills) { step, total, statusText ->
                            stepsList.add(com.lichiai.ui.activity.AssistantActivityStep(
                                stepIndex = step,
                                title = statusText,
                                isCompleted = true
                            ))
                            _liveActivityState.value = com.lichiai.ui.activity.AssistantActivityState(
                                requestId = requestId,
                                messageId = assistantMsgId,
                                kind = com.lichiai.ui.activity.ActivityKind.INTERACTING_SCREEN,
                                title = "Interacting with screen",
                                subtitle = "Step $step/$total: $statusText",
                                step = step,
                                totalSteps = total,
                                isActive = true,
                                stepHistory = stepsList.toList()
                            )
                        }

                        val finalOutput = if (result.isSuccess) {
                            result.summary
                        } else {
                            "⚠️ ${result.summary}"
                        }

                        _liveActivityState.value = null
                        _isStreaming.value = false

                        val completedActivity = com.lichiai.ui.activity.AssistantActivityState(
                            requestId = requestId,
                            messageId = assistantMsgId,
                            kind = if (result.isSuccess) com.lichiai.ui.activity.ActivityKind.COMPLETED else com.lichiai.ui.activity.ActivityKind.FAILED,
                            title = if (result.isSuccess) "Agent completed" else "Agent paused",
                            subtitle = "Executed ${result.totalSteps} steps",
                            isActive = false,
                            stepHistory = stepsList.toList()
                        )
                        updateAssistantMessage(activeId, assistantMsgId, content = finalOutput, taskActivity = completedActivity)
                        intentContextBuilder.recordExecution(
                            capability = com.lichiai.intent.model.LichiCapability.ANDROID_AGENT,
                            userGoal = trimmed,
                            assistantResponse = result.summary
                        )
                        return@launch
                    }

                    is com.lichiai.intent.model.ResolvedIntent.SkillManagementTask -> {
                        handleSkillManagement(resIntent.request, activeId, assistantMsgId)
                        return@launch
                    }

                    is com.lichiai.intent.model.ResolvedIntent.Clarification -> {
                        updateAssistantMessage(activeId, assistantMsgId, content = resIntent.question)
                        return@launch
                    }

                    is com.lichiai.intent.model.ResolvedIntent.MultiStepTask -> {
                        updateAssistantMessage(activeId, assistantMsgId, content = "🌐 ${resIntent.naturalAcknowledgment}")

                        val stepsList = mutableListOf<com.lichiai.ui.activity.AssistantActivityStep>()
                        _isStreaming.value = true
                        _liveActivityState.value = com.lichiai.ui.activity.AssistantActivityState(
                            requestId = requestId,
                            messageId = assistantMsgId,
                            kind = com.lichiai.ui.activity.ActivityKind.AGENT_WORKING,
                            title = "Executing task...",
                            subtitle = resIntent.naturalAcknowledgment,
                            isActive = true
                        )
                        val result = routeDispatcher.dispatch(resIntent) { step, total, text ->
                            stepsList.add(com.lichiai.ui.activity.AssistantActivityStep(
                                stepIndex = step,
                                title = text,
                                isCompleted = true
                            ))
                            _liveActivityState.value = com.lichiai.ui.activity.AssistantActivityState(
                                requestId = requestId,
                                messageId = assistantMsgId,
                                kind = com.lichiai.ui.activity.ActivityKind.AGENT_WORKING,
                                title = "Step $step/$total",
                                subtitle = text,
                                step = step,
                                totalSteps = total,
                                isActive = true,
                                stepHistory = stepsList.toList()
                            )
                        }
                        _liveActivityState.value = null
                        _isStreaming.value = false

                        val finalMsgText = when (result) {
                            is com.lichiai.intent.dispatcher.DispatchExecutionResult.BrowserExecuted -> "🌐 ${result.message}"
                            is com.lichiai.intent.dispatcher.DispatchExecutionResult.ExecutionFailed -> "⚠️ ${result.error}"
                            else -> "Task completed."
                        }
                        val completedActivity = com.lichiai.ui.activity.AssistantActivityState(
                            requestId = requestId,
                            messageId = assistantMsgId,
                            kind = com.lichiai.ui.activity.ActivityKind.COMPLETED,
                            title = "Task completed",
                            subtitle = "Executed ${stepsList.size} steps",
                            isActive = false,
                            stepHistory = stepsList.toList()
                        )
                        updateAssistantMessage(activeId, assistantMsgId, content = finalMsgText, taskActivity = completedActivity)
                        return@launch
                    }

                    is com.lichiai.intent.model.ResolvedIntent.WebSearchTask -> {
                        // Pre-fetch Web Search data to enrich conversational response
                        runCatching {
                            val webResponse = webIntelligenceManager.executeSearch(
                                query = resIntent.query,
                                isImageSearch = resIntent.isImageSearch,
                                isNewsSearch = resIntent.isNewsSearch
                            )
                            webSearchOverridePrompt = webIntelligenceManager.buildWebContextPrompt(webResponse)
                            intentContextBuilder.recordExecution(
                                capability = com.lichiai.intent.model.LichiCapability.WEB_SEARCH,
                                userGoal = trimmed,
                                assistantResponse = "Web search executed for ${resIntent.query}",
                                searchQuery = resIntent.query
                            )
                        }.onFailure { err ->
                            android.util.Log.w("ChatViewModel", "Web search failed: ${err.message}")
                        }
                    }

                    is com.lichiai.intent.model.ResolvedIntent.MediaTask -> {
                        routeDispatcher.dispatch(resIntent)
                        updateAssistantMessage(activeId, assistantMsgId, content = "🎵 Playing media...")
                        return@launch
                    }

                    is com.lichiai.intent.model.ResolvedIntent.DeviceControlTask -> {
                        routeDispatcher.dispatch(resIntent)
                        updateAssistantMessage(activeId, assistantMsgId, content = "Adjusted device setting.")
                        return@launch
                    }

                    is com.lichiai.intent.model.ResolvedIntent.Cancellation -> {
                        updateAssistantMessage(activeId, assistantMsgId, content = resIntent.naturalAcknowledgment)
                        intentContextBuilder.reset()
                        return@launch
                    }

                    is com.lichiai.intent.model.ResolvedIntent.ResumeTask -> {
                        updateAssistantMessage(activeId, assistantMsgId, content = resIntent.naturalAcknowledgment)
                        return@launch
                    }

                    is com.lichiai.intent.model.ResolvedIntent.TaskInterruption -> {
                        updateAssistantMessage(activeId, assistantMsgId, content = resIntent.naturalAcknowledgment)
                        return@launch
                    }

                    is com.lichiai.intent.model.ResolvedIntent.ContextualQuestion -> {
                        // Handled in normal conversational flow
                    }

                    is com.lichiai.intent.model.ResolvedIntent.NormalChat -> {
                        // Proceed to normal conversation flow
                    }

                    else -> {
                        val outcome = routeDispatcher.dispatch(resIntent)
                        val outcomeMsg = when (outcome) {
                            is com.lichiai.intent.dispatcher.DispatchExecutionResult.WebSearchExecuted -> {
                                webSearchOverridePrompt = outcome.contextPrompt
                                outcome.message
                            }
                            is com.lichiai.intent.dispatcher.DispatchExecutionResult.BrowserExecuted -> {
                                _browserNavigationEvent.emit(Unit)
                                outcome.message
                            }
                            is com.lichiai.intent.dispatcher.DispatchExecutionResult.ExecutionFailed -> "⚠️ ${outcome.error}"
                            else -> resIntent.naturalAcknowledgment
                        }
                        if (webSearchOverridePrompt.isNullOrBlank()) {
                            updateAssistantMessage(activeId, assistantMsgId, content = outcomeMsg)
                            return@launch
                        }
                    }
                }
            }

            if (provider == null) {
                _error.value = "No provider configured."
                updateAssistantMessage(activeId, assistantMsgId, content = "(No provider configured)")
                return@launch
            }

            if (model.isBlank()) {
                _error.value = "No model selected."
                updateAssistantMessage(activeId, assistantMsgId, content = "(No model selected)")
                return@launch
            }

            if (provider.apiKey.isBlank()
                && !provider.baseUrl.contains("localhost")
                && !provider.baseUrl.contains("10.0.2.2")
            ) {
                _error.value = "API key is empty for ${provider.name}."
                updateAssistantMessage(activeId, assistantMsgId, content = "(API key is empty for ${provider.name})")
                return@launch
            }

            val temperature = activeAsstProfile.temperatureOverride ?: current.temperature

            // Check Web Search Intent & Retrieve Real-time Data
            var webContextPrompt = webSearchOverridePrompt
            var capturedWebActivity: com.lichiai.web.model.WebActivityState? = null
            if (webContextPrompt.isBlank()) {
                val webSettings = webIntelligenceManager.settingsRepository.getSnapshot()
                if (webSettings.enabled && webSettings.hasApiKey(webSettings.activeProvider)) {
                    val (isSearch, isImage) = webIntelligenceManager.detectSearchIntent(trimmed)
                    if (isSearch) {
                        runCatching {
                            val webResponse = webIntelligenceManager.executeSearch(trimmed, isImageSearch = isImage)
                            webContextPrompt = webIntelligenceManager.buildWebContextPrompt(webResponse)
                            capturedWebActivity = webIntelligenceManager.activityState.value
                        }.onFailure { err ->
                            android.util.Log.w("ChatViewModel", "Web search failed: ${err.message}")
                        }
                    }
                }
            } else {
                capturedWebActivity = webIntelligenceManager.activityState.value
            }

            if (capturedWebActivity != null) {
                updateAssistantMessage(
                    activeId,
                    assistantMsgId,
                    taskActivity = capturedWebActivity.toAssistantActivity(requestId = requestId, messageId = assistantMsgId)
                )
            }

            val historyForApi = mutableListOf<ChatMessage>()
            val memoryPack = runCatching { memoryEngine.getMemoryPack(trimmed, activeId) }.getOrNull()
            val memoryContext = memoryPack?.formattedPromptContext ?: ""
            val effectiveSystemPrompt = LichiPromptAssembler.assembleSystemPrompt(
                assistant = activeAsstProfile,
                model = model,
                providerName = provider.name,
                memoryContext = memoryContext,
                webContext = webContextPrompt
            )

            if (effectiveSystemPrompt.isNotBlank()) {
                historyForApi.add(ChatMessage("system", effectiveSystemPrompt))
            }

            val convSnapshot = store.snapshot().firstOrNull { it.id == activeId }
            convSnapshot?.messages
                ?.filter { it.role != "system" && !(it.role == "assistant" && it.content.isEmpty()) }
                ?.forEach { msg ->
                    val imgs = msg.attachments.filter { it.type == "image" }
                    if (msg.role == "user" && imgs.isNotEmpty()) {
                        // Build multipart content: text + image_url parts.
                        val parts = mutableListOf<com.lichiai.api.ChatPart>()
                        if (msg.content.isNotBlank()) {
                            parts.add(com.lichiai.api.ChatPart(type = "text", text = msg.content))
                        }
                        for (att in imgs) {
                            // Try to load + base64-encode the image. Fall back silently if it fails.
                            val loaded = runCatching {
                                com.lichiai.util.AttachmentLoader.load(
                                    resolver = getApplication<android.app.Application>().contentResolver,
                                    uri = android.net.Uri.parse(att.uri),
                                    mimeFallback = att.mimeType.ifBlank { "image/jpeg" }
                                )
                            }.getOrNull() ?: continue
                            val dataUrl = "data:${loaded.mimeType};base64,${loaded.base64}"
                            parts.add(com.lichiai.api.ChatPart(
                                type = "image_url",
                                imageUrl = com.lichiai.api.ChatPart.ImageUrl(url = dataUrl)
                            ))
                        }
                        // Also describe non-image attachments as text references.
                        val others = msg.attachments.filter { it.type != "image" }
                        if (others.isNotEmpty()) {
                            val tail = others.joinToString("\n") { "[file: ${it.name} (${it.mimeType})]" }
                            val merged = if (parts.firstOrNull()?.type == "text") {
                                parts[0] = com.lichiai.api.ChatPart(
                                    type = "text",
                                    text = (parts[0].text ?: "") + "\n\n" + tail
                                )
                                parts
                            } else {
                                listOf(com.lichiai.api.ChatPart(type = "text", text = tail)) + parts
                            }
                            historyForApi.add(ChatMessage(msg.role, msg.content, merged))
                        } else {
                            historyForApi.add(ChatMessage(msg.role, msg.content, parts))
                        }
                    } else if (msg.role == "user" && msg.attachments.isNotEmpty()) {
                        // Files only — describe inline as text refs.
                        val refs = msg.attachments.joinToString("\n") {
                            "[file: ${it.name} (${it.mimeType})]"
                        }
                        val combined = if (msg.content.isBlank()) refs else "${msg.content}\n\n$refs"
                        historyForApi.add(ChatMessage(msg.role, combined))
                    } else {
                        historyForApi.add(ChatMessage(msg.role, msg.content))
                    }
                }

            _isStreaming.value = true
            _liveActivityState.value = com.lichiai.ui.activity.AssistantActivityState(
                requestId = requestId,
                messageId = assistantMsgId,
                kind = com.lichiai.ui.activity.ActivityKind.THINKING,
                title = "Thinking...",
                subtitle = if (webContextPrompt.isNotBlank()) "Synthesizing research..." else "Formulating response",
                isActive = true,
                sources = capturedWebActivity?.completedSources ?: emptyList()
            )
            val builder = StringBuilder()

            // Use settings.copy with assistant temperature override
            val effectiveSettings = current.copy(temperature = temperature)

            streamingJob = launch {
                var lastFlush = 0L
                val flushIntervalMs = 2500L
                var lastOverlayUpdate = 0L
                val overlayThrottleMs = 30L
                _streamingOverlay.value = assistantMsgId to ""
                try {
                    client.chatStream(provider, effectiveSettings, model, historyForApi)
                        .catch { e ->
                            val rawMsg = e.message ?: "Request failed"
                            val userFriendlyMsg = when {
                                rawMsg.contains("429") || rawMsg.contains("RESOURCE_EXHAUSTED", ignoreCase = true) || rawMsg.contains("quota", ignoreCase = true) ->
                                    "⚠️ Rate limit / API quota exceeded for ${provider.name}. Please check your quota or switch to another provider/model in Settings."
                                rawMsg.contains("503") || rawMsg.contains("overloaded", ignoreCase = true) || rawMsg.contains("UNAVAILABLE", ignoreCase = true) ->
                                    "⚠️ The AI model is temporarily overloaded (HTTP 503). Please wait a moment and try again."
                                rawMsg.contains("401") || rawMsg.contains("403") || rawMsg.contains("unauthorized", ignoreCase = true) ->
                                    "⚠️ Authentication failed: Invalid API key for ${provider.name}. Please update it in Settings."
                                rawMsg.contains("SocketTimeoutException", ignoreCase = true) || rawMsg.contains("timeout", ignoreCase = true) ->
                                    "⚠️ Request timed out while connecting to ${provider.name}. Please check your internet connection."
                                else -> "⚠️ Request failed: $rawMsg"
                            }
                            _error.value = userFriendlyMsg
                            val finalContent = if (builder.isEmpty()) userFriendlyMsg else "${builder.toString()}\n\n$userFriendlyMsg"
                            updateAssistantMessage(activeId, assistantMsgId, content = finalContent)
                        }
                        .collect { delta ->
                            builder.append(delta)
                            // As soon as tokens arrive, clear the initial thinking indicator
                            if (_liveActivityState.value?.messageId == assistantMsgId &&
                                _liveActivityState.value?.kind == com.lichiai.ui.activity.ActivityKind.THINKING) {
                                _liveActivityState.value = null
                            }
                            // Smooth, ultra-fast token streaming rendering without UI jank
                            val now = System.currentTimeMillis()
                            if (lastOverlayUpdate == 0L || now - lastOverlayUpdate >= overlayThrottleMs) {
                                _streamingOverlay.value = assistantMsgId to builder.toString()
                                lastOverlayUpdate = now
                            }
                            // Throttled DataStore persistence
                            if (now - lastFlush >= flushIntervalMs) {
                                appendAssistant(activeId, assistantMsgId, builder.toString())
                                lastFlush = now
                            }
                        }
                } finally {
                    _liveActivityState.value = null
                    val completedActivity = capturedWebActivity?.toAssistantActivity(
                        requestId = requestId,
                        messageId = assistantMsgId
                    )?.copy(
                        isActive = false
                    )
                    val finalContent = builder.toString()
                    if (finalContent.isNotEmpty()) {
                        _streamingOverlay.value = assistantMsgId to finalContent
                        updateAssistantMessage(activeId, assistantMsgId, content = finalContent, taskActivity = completedActivity)
                        memoryEngine.recordTurnAsync(conversationId = activeId, messageId = assistantMsgId, role = "assistant", content = finalContent)
                        intentContextBuilder.recordExecution(
                            capability = com.lichiai.intent.model.LichiCapability.CHAT,
                            userGoal = trimmed,
                            assistantResponse = finalContent,
                            conversationId = activeId
                        )
                    }
                    _streamingOverlay.value = null
                    _isStreaming.value = false
                    streamingJob = null
                    webIntelligenceManager.resetActivity()
                }
            }
        }
    }

    private suspend fun appendAssistant(convId: String, msgId: String, content: String) {
        val list = store.snapshot()
        val conv = list.firstOrNull { it.id == convId } ?: return
        val newMsgs = conv.messages.map { if (it.id == msgId) it.copy(content = content) else it }
        store.upsert(conv.copy(messages = newMsgs, updatedAt = System.currentTimeMillis()))
    }

    fun regenerate() {
        viewModelScope.launch {
            val convId = _activeId.value ?: return@launch
            val conv = store.snapshot().firstOrNull { it.id == convId } ?: return@launch
            val msgs = conv.messages
            val lastUserIdx = msgs.indexOfLast { it.role == "user" }
            if (lastUserIdx < 0) return@launch
            val lastUser = msgs[lastUserIdx]
            val trimmed = msgs.subList(0, lastUserIdx + 1)
            store.upsert(conv.copy(messages = trimmed, updatedAt = System.currentTimeMillis()))
            sendMessage(lastUser.content, lastUser.attachments)
        }
    }

    /** Branch from a specific user message — drops everything after it and re-sends. */
    fun regenerateFrom(messageId: String) {
        viewModelScope.launch {
            val convId = _activeId.value ?: return@launch
            val conv = store.snapshot().firstOrNull { it.id == convId } ?: return@launch
            val msgs = conv.messages
            val idx = msgs.indexOfFirst { it.id == messageId }
            if (idx < 0) return@launch
            val target = msgs[idx]
            if (target.role != "user") return@launch
            val trimmed = msgs.subList(0, idx + 1)
            store.upsert(conv.copy(messages = trimmed, updatedAt = System.currentTimeMillis()))
            sendMessage(target.content, target.attachments)
        }
    }

    /** Delete a single message in the current conversation. */
    fun deleteMessage(messageId: String) {
        viewModelScope.launch {
            val convId = _activeId.value ?: return@launch
            val conv = store.snapshot().firstOrNull { it.id == convId } ?: return@launch
            val newMsgs = conv.messages.filterNot { it.id == messageId }
            store.upsert(conv.copy(messages = newMsgs, updatedAt = System.currentTimeMillis()))
        }
    }

    /** Edit a message's text content (no resend). */
    fun editMessage(messageId: String, newContent: String) {
        viewModelScope.launch {
            val convId = _activeId.value ?: return@launch
            val conv = store.snapshot().firstOrNull { it.id == convId } ?: return@launch
            val newMsgs = conv.messages.map {
                if (it.id == messageId) it.copy(content = newContent) else it
            }
            store.upsert(conv.copy(messages = newMsgs, updatedAt = System.currentTimeMillis()))
        }
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { settingsRepo.update(transform) }
    }

    private suspend fun handleSkillManagement(
        request: com.lichiai.skill.router.SkillManagementRequest,
        activeId: String,
        assistantMsgId: String
    ) {
        val replyText = when (request) {
            is com.lichiai.skill.router.SkillManagementRequest.ListSkills -> {
                val currentSkills = skillRepository.skills.value
                if (currentSkills.isEmpty()) {
                    "No skills installed yet. You can create or import skills in Settings > Skills Library."
                } else {
                    val sb = StringBuilder("Installed Lichi Skills (${currentSkills.count { it.enabled }}/${currentSkills.size} active):\n\n")
                    currentSkills.forEach { s ->
                        val status = if (s.enabled) "🟢 Enabled" else "⚪ Disabled"
                        sb.append("• **${s.name}** (v${s.version}) - $status [${s.source.name}]\n  _${s.description}_\n\n")
                    }
                    sb.append("Manage skills in Settings > Skills Library.")
                    sb.toString()
                }
            }
            is com.lichiai.skill.router.SkillManagementRequest.EnableSkill -> {
                val target = request.targetName.lowercase(java.util.Locale.getDefault())
                val skill = skillRepository.skills.value.firstOrNull {
                    it.name.lowercase(java.util.Locale.getDefault()).contains(target) ||
                    it.id.lowercase(java.util.Locale.getDefault()).contains(target)
                }
                if (skill != null) {
                    skillRepository.toggleSkill(skill.id, true)
                    "Skill **${skill.name}** (v${skill.version}) has been enabled."
                } else {
                    "Could not find any skill matching \"${request.targetName}\"."
                }
            }
            is com.lichiai.skill.router.SkillManagementRequest.DisableSkill -> {
                val target = request.targetName.lowercase(java.util.Locale.getDefault())
                val skill = skillRepository.skills.value.firstOrNull {
                    it.name.lowercase(java.util.Locale.getDefault()).contains(target) ||
                    it.id.lowercase(java.util.Locale.getDefault()).contains(target)
                }
                if (skill != null) {
                    skillRepository.toggleSkill(skill.id, false)
                    "Skill **${skill.name}** has been disabled."
                } else {
                    "Could not find any skill matching \"${request.targetName}\"."
                }
            }
            is com.lichiai.skill.router.SkillManagementRequest.DeleteSkill -> {
                val target = request.targetName.lowercase(java.util.Locale.getDefault())
                val skill = skillRepository.skills.value.firstOrNull {
                    it.name.lowercase(java.util.Locale.getDefault()).contains(target) ||
                    it.id.lowercase(java.util.Locale.getDefault()).contains(target)
                }
                if (skill != null) {
                    val res = skillRepository.deleteSkill(skill.id)
                    if (res.isSuccess) {
                        "Skill **${skill.name}** has been removed."
                    } else {
                        "Could not delete **${skill.name}**: ${res.exceptionOrNull()?.message}"
                    }
                } else {
                    "Could not find any skill matching \"${request.targetName}\"."
                }
            }
            is com.lichiai.skill.router.SkillManagementRequest.ExportSkill -> {
                val target = request.targetName.lowercase(java.util.Locale.getDefault())
                val skill = skillRepository.skills.value.firstOrNull {
                    it.name.lowercase(java.util.Locale.getDefault()).contains(target) ||
                    it.id.lowercase(java.util.Locale.getDefault()).contains(target)
                }
                if (skill != null) {
                    "Here is the specification for **${skill.name}**:\n\n```markdown\n${skill.markdownContent}\n```"
                } else {
                    "Could not find any skill matching \"${request.targetName}\"."
                }
            }
            is com.lichiai.skill.router.SkillManagementRequest.ProposeCreateSkill -> {
                val proposedMarkdown = """
                    # ${request.topicOrGoal.replaceFirstChar { it.uppercase() }} Assistant
                    
                    ## Purpose
                    Automate tasks and workflows for ${request.topicOrGoal}.
                    
                    ## When to use
                    Use when the user requests actions related to ${request.topicOrGoal}.
                    
                    ## Workflow
                    1. Open the relevant app or screen.
                    2. Locate the required interaction elements.
                    3. Perform the requested task.
                    4. Verify state before confirming completion.
                    
                    ## Rules
                    - Verify all UI changes before reporting success.
                    - Do not perform sensitive actions without user confirmation.
                """.trimIndent()
                val createRes = skillRepository.createSkill(proposedMarkdown, com.lichiai.skill.model.SkillSource.AGENT_GENERATED)
                if (createRes.isSuccess) {
                    val created = createRes.getOrThrow()
                    "I have drafted and created a new skill: **${created.name}** (v${created.version}). You can review and adjust it anytime in Settings > Skills Library."
                } else {
                    "Drafted skill proposal failed validation: ${createRes.exceptionOrNull()?.message}"
                }
            }
        }

        updateAssistantMessage(activeId, assistantMsgId, content = replyText)
    }

    override fun onCleared() {
        super.onCleared()
        wakeWordManager.release()
        contactRepository.destroy()
        voiceOrchestrator.destroy()
        textTtsManager?.shutdown()
    }
}
