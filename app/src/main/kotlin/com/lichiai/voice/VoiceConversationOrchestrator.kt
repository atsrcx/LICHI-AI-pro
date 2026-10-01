package com.lichiai.voice

import android.content.Context
import com.lichiai.api.ChatMessage
import com.lichiai.api.LlmClient
import com.lichiai.data.AppSettings
import com.lichiai.data.Assistant
import com.lichiai.data.ProviderConfig
import com.lichiai.data.SettingsRepository
import com.lichiai.data.VoiceSettings
import com.lichiai.data.VoiceSettingsRepository
import com.lichiai.data.Conversation
import com.lichiai.data.ConversationStore
import com.lichiai.data.Message
import com.lichiai.util.PromptVars
import com.lichiai.util.newId
import com.lichiai.data.AssistantStore
import com.lichiai.assistant.resolver.ActiveAssistantResolver
import com.lichiai.prompt.LichiPromptAssembler
import com.lichiai.voice.conversation.SentenceBuffer
import com.lichiai.voice.conversation.VoiceSessionState
import com.lichiai.voice.conversation.VoiceState
import com.lichiai.voice.conversation.VoiceTurn
import com.lichiai.voice.stt.SpeechToTextListener
import com.lichiai.voice.stt.SpeechToTextManager
import com.lichiai.voice.tts.TextToSpeechListener
import com.lichiai.voice.tts.TextToSpeechManager
import com.lichiai.voice.wakeword.MicrophoneOwnershipCoordinator
import com.lichiai.calling.engine.UniversalCallEngine
import com.lichiai.calling.intent.CallAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VoiceConversationOrchestrator(
    private val context: Context,
    private val llmClient: LlmClient,
    val voiceSettingsRepository: VoiceSettingsRepository,
    private val settingsRepository: SettingsRepository,
    private val callEngine: UniversalCallEngine? = null,
    private val callActionExecutor: com.lichiai.calling.action.CallActionExecutor? = null,
    private val callActionIntentResolver: com.lichiai.calling.intent.CallActionIntentResolver? = null,
    private val conversationStore: ConversationStore? = null,
    private val activeConversationIdProvider: () -> String? = { null },
    private val onConversationIdChanged: (String) -> Unit = {},
    autonomousAgentTool: com.lichiai.agent.bridge.AutonomousAgentTool? = null,
    webIntelligenceManager: com.lichiai.web.WebIntelligenceManager? = null,
    private val onShowBrowserUi: (() -> Unit)? = null,
    private val onExecuteBrowserCommand: ((String) -> Unit)? = null,
    private val routeDispatcher: com.lichiai.intent.dispatcher.RouteDispatcher? = null,
    private val taskOrchestratorV2: com.lichiai.orchestrator.UniversalTaskOrchestratorV2? = null,
    providerStore: com.lichiai.data.ProviderStore? = null
) : SpeechToTextListener, TextToSpeechListener {

    private val internalProviderStore = providerStore ?: com.lichiai.data.ProviderStore(context)
    private val agentTool = autonomousAgentTool ?: com.lichiai.agent.bridge.AutonomousAgentTool(context)
    private val skillRepository = com.lichiai.skill.repository.SkillRepository.getInstance(context)
    val webIntelligence = webIntelligenceManager ?: com.lichiai.web.WebIntelligenceManager.getInstance(context)
    val webActivityState: StateFlow<com.lichiai.web.model.WebActivityState>
        get() = webIntelligence.activityState
    private val orchestratorScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _sessionState = MutableStateFlow(VoiceSessionState())
    val sessionState: StateFlow<VoiceSessionState> = _sessionState.asStateFlow()

    val agentLiveStatus: StateFlow<com.lichiai.agent.model.AgentLiveStatus>
        get() = agentTool.liveStatus

    val geminiLiveEngine = com.lichiai.voice.live.GeminiLiveVoiceEngine(
        context = context,
        coroutineScope = orchestratorScope,
        updateState = { transform -> _sessionState.update(transform) },
        getState = { _sessionState.value },
        webIntelligence = webIntelligence,
        callEngine = callEngine,
        callActionExecutor = callActionExecutor,
        callActionIntentResolver = callActionIntentResolver,
        taskOrchestratorV2 = taskOrchestratorV2,
        onShowBrowserUi = onShowBrowserUi ?: onExecuteBrowserCommand?.let { cb -> { cb("") } },
        onExecuteBrowserCommand = onExecuteBrowserCommand,
        onSaveTurn = { userText, asstText ->
            orchestratorScope.launch {
                saveTurnToConversation(userText, asstText)
            }
        }
    )

    private var sttManager: SpeechToTextManager? = null
    private var ttsManager: TextToSpeechManager? = null

    private val assistantStore = AssistantStore(context)
    private var currentVoiceSettings = VoiceSettings()
    private var currentAppSettings = AppSettings()
    private var activeProvider: ProviderConfig? = null
    private var activeAssistant: Assistant? = null

    private var llmStreamJob: Job? = null
    private var restartRetryJob: Job? = null
    private var retryCount = 0

    private val sentenceBuffer = SentenceBuffer { sentence ->
        onSentenceReadyForTts(sentence)
    }

    init {
        sttManager = SpeechToTextManager(context, this)
        ttsManager = TextToSpeechManager(context, this)

        orchestratorScope.launch {
            voiceSettingsRepository.settings.collect { vs ->
                currentVoiceSettings = vs
                ttsManager?.applySettings(vs)
            }
        }
        orchestratorScope.launch {
            settingsRepository.settings.collect { s ->
                currentAppSettings = s
            }
        }

        orchestratorScope.launch {
            _sessionState.collect { session ->
                when (session.state) {
                    VoiceState.LISTENING -> {
                        com.lichiai.dynamicisland.LichiAssistantStateHub.onVoiceListening(
                            partialTranscript = session.partialUserText,
                            rms = session.currentRms
                        )
                    }
                    VoiceState.TRANSCRIBING -> {
                        com.lichiai.dynamicisland.LichiAssistantStateHub.updateState(
                            uiState = com.lichiai.dynamicisland.LichiUiState.TRANSCRIBING,
                            transcript = session.partialUserText
                        )
                    }
                    VoiceState.THINKING -> {
                        com.lichiai.dynamicisland.LichiAssistantStateHub.onVoiceThinking()
                    }
                    VoiceState.SPEAKING -> {
                        com.lichiai.dynamicisland.LichiAssistantStateHub.onVoiceSpeaking(
                            assistantText = session.activeAssistantText,
                            rms = session.currentRms
                        )
                    }
                    VoiceState.IDLE -> {
                        if (MicrophoneOwnershipCoordinator.canWakeWordRecord()) {
                            com.lichiai.dynamicisland.LichiAssistantStateHub.onWakeWordListening()
                        } else {
                            com.lichiai.dynamicisland.LichiAssistantStateHub.resetToIdle()
                        }
                    }
                    VoiceState.ERROR -> {
                        com.lichiai.dynamicisland.LichiAssistantStateHub.onError(session.errorMessage ?: "Voice Error")
                    }
                    VoiceState.PAUSED -> {
                        com.lichiai.dynamicisland.LichiAssistantStateHub.updateState(com.lichiai.dynamicisland.LichiUiState.PAUSED)
                    }
                    VoiceState.INTERRUPTED -> {
                        com.lichiai.dynamicisland.LichiAssistantStateHub.onVoiceListening()
                    }
                }
            }
        }
    }

    private var startSessionJob: Job? = null

    fun startSession(provider: ProviderConfig?, assistant: Assistant?) {
        startSessionJob?.cancel()
        startSessionJob = orchestratorScope.launch {
            activeProvider = provider
            activeAssistant = assistant
            retryCount = 0

            val vs = voiceSettingsRepository.settings.first()
            currentVoiceSettings = vs

            _sessionState.update {
                it.copy(
                    state = VoiceState.IDLE,
                    errorMessage = null,
                    partialUserText = "",
                    activeAssistantText = ""
                )
            }

            // Seed existing conversation turns if starting from an existing conversation
            val activeConvId = activeConversationIdProvider()
            if (activeConvId != null && conversationStore != null) {
                withContext(Dispatchers.IO) {
                    val conv = conversationStore.snapshot().firstOrNull { it.id == activeConvId }
                    if (conv != null && conv.messages.isNotEmpty()) {
                        val loadedTurns = mutableListOf<VoiceTurn>()
                        var i = 0
                        val msgs = conv.messages
                        while (i < msgs.size) {
                            val m = msgs[i]
                            if (m.role == "user") {
                                val next = msgs.getOrNull(i + 1)
                                val asstText = if (next?.role == "assistant") next.content else ""
                                loadedTurns.add(
                                    VoiceTurn(
                                        userText = m.content,
                                        assistantText = asstText,
                                        isUserFinal = true,
                                        isAssistantComplete = true,
                                        timestamp = m.createdAt
                                    )
                                )
                                if (next?.role == "assistant") i += 2 else i += 1
                            } else {
                                i += 1
                            }
                        }
                        if (loadedTurns.isNotEmpty()) {
                            _sessionState.update { it.copy(historyTurns = loadedTurns.takeLast(10)) }
                        }
                    }
                }
            }

            if (vs.voiceEngine == com.lichiai.data.VoiceEngine.GEMINI_LIVE) {
                val apiKey = com.lichiai.voice.live.GeminiKeyResolver.resolveApiKey(provider, internalProviderStore)
                if (apiKey.isNullOrBlank()) {
                    _sessionState.update {
                        it.copy(
                            state = VoiceState.ERROR,
                            errorMessage = "No Gemini API key found. Please configure a Gemini provider in Settings."
                        )
                    }
                    return@launch
                }

                val assistants = runCatching { assistantStore.snapshot() }.getOrDefault(emptyList())
                val activeAsst = ActiveAssistantResolver.resolve(
                    currentAppSettings.activeAssistantId,
                    if (assistants.isNotEmpty()) assistants else (activeAssistant?.let { listOf(it) } ?: emptyList())
                )

                val memoryPack = runCatching {
                    com.lichiai.memory.manager.MemoryContextGateway.retrieve(
                        context = context,
                        query = "Live Voice Session",
                        conversationId = activeConvId
                    )
                }.getOrNull()
                val memoryContextPrompt = memoryPack?.formattedPromptContext ?: ""

                val semanticContext = com.lichiai.context.engine.UniversalContextContinuityEngine.getInstance().getContext(activeConvId ?: "default_session")
                val contextFactPrompt = if (semanticContext.verifiedFacts.isNotEmpty()) {
                    "CURRENT CONVERSATION CONTEXT & VERIFIED FACTS:\n" +
                    semanticContext.verifiedFacts.entries.joinToString("\n") { "- ${it.key}: ${it.value}" } +
                    (semanticContext.activeTopic?.let { "\nActive Topic: $it" } ?: "")
                } else ""

                val liveSystemPrompt = LichiPromptAssembler.assembleSystemPrompt(
                    assistant = activeAsst,
                    model = vs.geminiLiveModel,
                    providerName = "Google Gemini Live",
                    memoryContext = memoryContextPrompt,
                    taskContext = contextFactPrompt,
                    webContext = ""
                )

                geminiLiveEngine.startSession(
                    apiKey = apiKey,
                    model = vs.geminiLiveModel,
                    voiceName = vs.geminiLiveVoice,
                    systemInstruction = liveSystemPrompt
                )
                return@launch
            }

            MicrophoneOwnershipCoordinator.requestForVoiceSession()

            // Initialize TTS and STT
            ttsManager?.initialize(vs) { isSuccess ->
                _sessionState.update { it.copy(isTtsReady = isSuccess) }
            }
            sttManager?.initialize(vs)
            _sessionState.update { it.copy(isSttReady = true) }

            // Begin listening
            startListeningSafe()
        }
    }

    fun setContextInfo(provider: ProviderConfig?, assistant: Assistant?) {
        activeProvider = provider
        activeAssistant = assistant
    }

    private fun startListeningSafe() {
        if (_sessionState.value.isMicMuted) return
        _sessionState.update {
            it.copy(
                state = VoiceState.LISTENING,
                partialUserText = "",
                errorMessage = null
            )
        }
        sttManager?.startListening(currentVoiceSettings)
    }

    fun interruptAndStartListening() {
        if (currentVoiceSettings.voiceEngine == com.lichiai.data.VoiceEngine.GEMINI_LIVE) {
            geminiLiveEngine.interrupt()
            return
        }
        // Immediate Barge-In: Cancel TTS and LLM
        cancelLlmAndTts()
        _sessionState.update { it.copy(state = VoiceState.INTERRUPTED) }
        orchestratorScope.launch {
            delay(100)
            startListeningSafe()
        }
    }

    fun toggleMute() {
        if (currentVoiceSettings.voiceEngine == com.lichiai.data.VoiceEngine.GEMINI_LIVE) {
            geminiLiveEngine.toggleMute()
            return
        }
        val newMute = !_sessionState.value.isMicMuted
        _sessionState.update { it.copy(isMicMuted = newMute) }
        if (newMute) {
            sttManager?.stopListening()
            _sessionState.update { it.copy(state = VoiceState.PAUSED) }
        } else {
            startListeningSafe()
        }
    }

    fun cancelLlmAndTts() {
        llmStreamJob?.cancel()
        llmStreamJob = null
        sentenceBuffer.clear()
        ttsManager?.stopAndClearQueue()
    }

    fun stopSession() {
        if (currentVoiceSettings.voiceEngine == com.lichiai.data.VoiceEngine.GEMINI_LIVE) {
            geminiLiveEngine.stopSession()
            return
        }
        cancelLlmAndTts()
        restartRetryJob?.cancel()
        sttManager?.stopListening()
        sttManager?.destroy {
            MicrophoneOwnershipCoordinator.releaseFromVoiceSession()
        } ?: run {
            MicrophoneOwnershipCoordinator.releaseFromVoiceSession()
        }
        ttsManager?.stopAndClearQueue()
        ttsManager?.shutdown()

        _sessionState.update {
            it.copy(
                state = VoiceState.IDLE,
                currentRms = 0f,
                partialUserText = "",
                activeAssistantText = ""
            )
        }
    }

    // ==========================================
    // STT Callbacks (SpeechToTextListener)
    // ==========================================

    override fun onReadyForSpeech() {
        _sessionState.update { it.copy(state = VoiceState.LISTENING, errorMessage = null) }
        retryCount = 0
    }

    override fun onBeginningOfSpeech() {
        if (_sessionState.value.state == VoiceState.SPEAKING && currentVoiceSettings.bargeInEnabled) {
            interruptAndStartListening()
            return
        }
        _sessionState.update { it.copy(state = VoiceState.TRANSCRIBING) }
    }

    override fun onRmsChanged(rmsdB: Float) {
        _sessionState.update { it.copy(currentRms = rmsdB.coerceAtLeast(0f)) }
    }

    override fun onPartialResult(partialText: String) {
        _sessionState.update {
            it.copy(
                state = VoiceState.TRANSCRIBING,
                partialUserText = partialText
            )
        }
    }

    override fun onFinalResult(text: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) {
            restartListeningWithBackoff()
            return
        }

        // 1. STATE-SPECIFIC PROTOCOL CONTROL: Real-time active-call session controls (Answer, Reject, Mute, Speaker, End)
        val currentCallSession = com.lichiai.dynamicisland.LichiAssistantStateHub.callSession.value
        if (currentCallSession != null && currentCallSession.isCallActiveOrRinging) {
            val structuredAction = callActionIntentResolver?.resolve(trimmed, currentCallSession)
            if (structuredAction != null && callActionExecutor != null) {
                orchestratorScope.launch {
                    _sessionState.update {
                        it.copy(
                            state = VoiceState.THINKING,
                            partialUserText = trimmed,
                            activeAssistantText = "Handling call..."
                        )
                    }
                    val result = callActionExecutor.execute(structuredAction)
                    val responseSpeech = result.message
                    _sessionState.update {
                        val turn = VoiceTurn(
                            userText = trimmed,
                            assistantText = responseSpeech,
                            isUserFinal = true,
                            isAssistantComplete = true
                        )
                        it.copy(
                            state = VoiceState.SPEAKING,
                            historyTurns = it.historyTurns + turn,
                            activeAssistantText = responseSpeech
                        )
                    }
                    saveTurnToConversation(userText = trimmed, assistantText = responseSpeech)
                    ttsManager?.stopAndClearQueue()
                    ttsManager?.enqueueSentence(responseSpeech)
                }
                return
            }
        }

        // 2. CANONICAL TASK ORCHESTRATION (UniversalLlmPlanner -> CapabilityCatalogV2 -> Executor)
        val orchestratorMode = runCatching {
            com.lichiai.orchestrator.model.OrchestratorMode.valueOf(currentAppSettings.orchestratorMode)
        }.getOrDefault(com.lichiai.orchestrator.model.OrchestratorMode.ENABLED)

        if (taskOrchestratorV2 != null && orchestratorMode != com.lichiai.orchestrator.model.OrchestratorMode.DISABLED) {
            orchestratorScope.launch {
                val provider = activeProvider
                val modelToUse = currentAppSettings.activeModel.ifBlank { provider?.models?.firstOrNull() ?: "gpt-4o-mini" }
                val convId = activeConversationIdProvider() ?: ""
                val reqId = newId()
                val msgId = newId()

                _sessionState.update {
                    it.copy(
                        state = VoiceState.THINKING,
                        partialUserText = trimmed,
                        activeAssistantText = "Thinking..."
                    )
                }

                val result = taskOrchestratorV2.orchestrate(
                    rawInput = trimmed,
                    provider = provider,
                    modelId = modelToUse,
                    mode = orchestratorMode,
                    requestId = reqId,
                    messageId = msgId,
                    conversationId = convId
                ) { step, total, statusText ->
                    _sessionState.update {
                        it.copy(activeAssistantText = "Step $step/$total: $statusText")
                    }
                }

                if (result.isDirectChat) {
                    processUserTurnWithLlm(result.directChatPrompt.ifBlank { trimmed })
                    return@launch
                }

                val speech = result.finalSpeech.ifBlank { "Task completed." }
                _sessionState.update {
                    val turn = VoiceTurn(
                        userText = trimmed,
                        assistantText = speech,
                        isUserFinal = true,
                        isAssistantComplete = true
                    )
                    it.copy(
                        state = VoiceState.SPEAKING,
                        historyTurns = it.historyTurns + turn,
                        activeAssistantText = speech
                    )
                }
                saveTurnToConversation(userText = trimmed, assistantText = speech)
                ttsManager?.stopAndClearQueue()
                ttsManager?.enqueueSentence(speech)

                if (result.requiresBrowserUi) {
                    onShowBrowserUi?.invoke() ?: onExecuteBrowserCommand?.invoke("")
                }
            }
            return
        }

        // 3. Direct LLM fallback if orchestrator is not available
        processUserTurnWithLlm(trimmed)
    }

    override fun onEndOfSpeech() {
        // Will transition to THINKING when final result arrives
    }

    override fun onError(errorCode: Int, errorMessage: String) {
        // Handle transient timeouts / silence gracefully
        if (_sessionState.value.state == VoiceState.SPEAKING || _sessionState.value.state == VoiceState.THINKING) {
            return
        }

        if (currentVoiceSettings.sttAutoRestart && !_sessionState.value.isMicMuted) {
            restartListeningWithBackoff()
        } else {
            _sessionState.update {
                it.copy(
                    state = VoiceState.ERROR,
                    errorMessage = errorMessage
                )
            }
        }
    }

    private fun restartListeningWithBackoff() {
        restartRetryJob?.cancel()
        restartRetryJob = orchestratorScope.launch {
            delay(300)
            if (!_sessionState.value.isMicMuted && _sessionState.value.state != VoiceState.SPEAKING && _sessionState.value.state != VoiceState.THINKING) {
                startListeningSafe()
            }
        }
    }

    // ==========================================
    // LLM Stream & Sentence Processing
    // ==========================================

    private fun processUserTurnWithLlm(userQuery: String) {
        val provider = activeProvider
        if (provider == null || provider.apiKey.isBlank()) {
            val err = "No active LLM provider configured. Please configure a provider in Settings."
            _sessionState.update {
                it.copy(
                    state = VoiceState.ERROR,
                    errorMessage = err
                )
            }
            ttsManager?.enqueueSentence(err)
            return
        }

        sentenceBuffer.clear()
        val defaultModel = provider.models.firstOrNull() ?: "gpt-4o-mini"
        val fullAssistantAccumulator = java.lang.StringBuilder()

        // If safe echo protection is on, pause STT during LLM/TTS
        if (currentVoiceSettings.safeEchoProtection) {
            sttManager?.stopListening()
        }

        llmStreamJob?.cancel()
        llmStreamJob = orchestratorScope.launch(Dispatchers.IO) {
            try {
                val assistants = runCatching { assistantStore.snapshot() }.getOrDefault(emptyList())
                val activeAsst = ActiveAssistantResolver.resolve(
                    currentAppSettings.activeAssistantId,
                    if (assistants.isNotEmpty()) assistants else (activeAssistant?.let { listOf(it) } ?: emptyList())
                )
                val modelToUse = activeAsst.preferredModel?.takeIf { it.isNotBlank() }
                    ?: currentAppSettings.activeModel.ifBlank { defaultModel }

                val effectiveVoiceSettings = currentAppSettings.copy(
                    temperature = activeAsst.temperatureOverride ?: currentAppSettings.temperature
                )
                // Check if Web Search should be consulted
                var webVoicePrompt = ""
                val webSettings = webIntelligence.settingsRepository.getSnapshot()
                if (webSettings.enabled && webSettings.hasApiKey(webSettings.activeProvider)) {
                    val (isSearch, isImage) = webIntelligence.detectSearchIntent(userQuery)
                    if (isSearch) {
                        _sessionState.update { it.copy(activeAssistantText = "Searching the web...") }
                        runCatching {
                            val resp = webIntelligence.executeSearch(userQuery, isImageSearch = isImage)
                            webVoicePrompt = webIntelligence.buildWebContextPrompt(resp)
                        }.onFailure { err ->
                            android.util.Log.w("VoiceOrchestrator", "Web search failed: ${err.message}")
                        }
                    }
                }

                val activeConvId = activeConversationIdProvider()
                val memoryPack = runCatching {
                    com.lichiai.memory.manager.MemoryContextGateway.retrieve(
                        context = context,
                        query = userQuery,
                        conversationId = activeConvId
                    )
                }.getOrNull()
                val memoryContextPrompt = memoryPack?.formattedPromptContext ?: ""

                val semanticContext = com.lichiai.context.engine.UniversalContextContinuityEngine.getInstance().getContext(activeConvId ?: "default_session")
                val contextFactPrompt = if (semanticContext.verifiedFacts.isNotEmpty()) {
                    "CURRENT CONVERSATION CONTEXT & VERIFIED FACTS:\n" +
                    semanticContext.verifiedFacts.entries.joinToString("\n") { "- ${it.key}: ${it.value}" } +
                    (semanticContext.activeTopic?.let { "\nActive Topic: $it" } ?: "")
                } else ""

                val effectiveSystemPrompt = LichiPromptAssembler.assembleSystemPrompt(
                    assistant = activeAsst,
                    model = modelToUse,
                    providerName = provider.name,
                    memoryContext = memoryContextPrompt,
                    taskContext = contextFactPrompt,
                    webContext = if (webVoicePrompt.isNotBlank()) {
                        "$webVoicePrompt\n\nNOTE: You are in Voice Mode speaking to the user aloud. Give a concise, conversational answer summarizing the web facts. Do not recite URLs or reference brackets."
                    } else ""
                )

                // Build message history with actual conversation context if available
                val messages = mutableListOf<ChatMessage>()
                messages.add(ChatMessage("system", effectiveSystemPrompt))
                val existingConv = if (activeConvId != null && conversationStore != null) {
                    conversationStore.snapshot().firstOrNull { it.id == activeConvId }
                } else null

                if (existingConv != null && existingConv.messages.isNotEmpty()) {
                    existingConv.messages.filter { it.content.isNotBlank() && it.role != "system" }.takeLast(10).forEach { msg ->
                        messages.add(ChatMessage(msg.role, msg.content))
                    }
                } else {
                    _sessionState.value.historyTurns.takeLast(6).forEach { turn ->
                        if (turn.userText.isNotBlank()) messages.add(ChatMessage("user", turn.userText))
                        if (turn.assistantText.isNotBlank()) messages.add(ChatMessage("assistant", turn.assistantText))
                    }
                }
                messages.add(ChatMessage("user", userQuery))

                var turnSaved = false
                val persistTurnAction: suspend () -> Unit = {
                    if (!turnSaved) {
                        val assistantFinal = fullAssistantAccumulator.toString().trim()
                        if (userQuery.isNotBlank() || assistantFinal.isNotBlank()) {
                            turnSaved = true
                            saveTurnToConversation(userText = userQuery, assistantText = assistantFinal)
                            val resolvedConvId = activeConvId ?: "default_session"
                            runCatching {
                                com.lichiai.memory.manager.MemoryContextGateway.recordTurn(
                                    context = context,
                                    conversationId = resolvedConvId,
                                    messageId = "voice_${System.currentTimeMillis()}",
                                    role = "user",
                                    content = userQuery
                                )
                                if (assistantFinal.isNotBlank()) {
                                    com.lichiai.memory.manager.MemoryContextGateway.recordTurnAsync(
                                        context = context,
                                        conversationId = resolvedConvId,
                                        messageId = "voice_${System.currentTimeMillis() + 1}",
                                        role = "assistant",
                                        content = assistantFinal
                                    )
                                }
                            }
                            com.lichiai.context.engine.UniversalContextContinuityEngine.getInstance().recordExecution(
                                conversationId = resolvedConvId,
                                capability = com.lichiai.intent.model.LichiCapability.CHAT,
                                userGoal = userQuery,
                                assistantResponse = assistantFinal
                            )
                        }
                    }
                }

                try {
                    val streamFlow = llmClient.chatStream(
                        provider = provider,
                        settings = effectiveVoiceSettings,
                        modelId = modelToUse,
                        messages = messages
                    )

                    streamFlow
                        .catch { throwable ->
                            val errMsg = throwable.localizedMessage ?: "Error streaming from LLM"
                            _sessionState.update {
                                it.copy(
                                    state = VoiceState.ERROR,
                                    errorMessage = errMsg
                                )
                            }
                        }
                        .collect { token ->
                            fullAssistantAccumulator.append(token)
                            sentenceBuffer.appendToken(token)

                            _sessionState.update {
                                it.copy(
                                    activeAssistantText = fullAssistantAccumulator.toString()
                                )
                            }
                        }

                    // Flush remaining sentence chunk at end of stream
                    sentenceBuffer.flush()

                    // Save turn to history
                    val completeTurn = VoiceTurn(
                        userText = userQuery,
                        assistantText = fullAssistantAccumulator.toString(),
                        isUserFinal = true,
                        isAssistantComplete = true
                    )

                    _sessionState.update { state ->
                        state.copy(
                            historyTurns = state.historyTurns + completeTurn
                        )
                    }

                    persistTurnAction()
                } finally {
                    persistTurnAction()
                }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    _sessionState.update {
                        it.copy(
                            state = VoiceState.ERROR,
                            errorMessage = e.localizedMessage ?: "Failed to generate response"
                        )
                    }
                }
            }
        }
    }

    private suspend fun saveTurnToConversation(userText: String, assistantText: String) {
        val store = conversationStore ?: return
        val userTrimmed = userText.trim()
        val assistantTrimmed = assistantText.trim()
        if (userTrimmed.isBlank() && assistantTrimmed.isBlank()) return

        withContext(Dispatchers.IO) {
            try {
                var currentConvId = activeConversationIdProvider()
                val existing = if (currentConvId != null) {
                    store.snapshot().firstOrNull { it.id == currentConvId }
                } else null

                if (currentConvId == null || (existing == null && currentConvId.isBlank())) {
                    val newConvId = newId()
                    currentConvId = newConvId
                    withContext(Dispatchers.Main) {
                        onConversationIdChanged(newConvId)
                    }
                }

                val baseTitle = userTrimmed.take(30).replace("\n", " ").ifBlank { "Voice Conversation" }
                val now = System.currentTimeMillis()

                val userMsg = Message(
                    id = newId(),
                    role = "user",
                    content = userTrimmed,
                    createdAt = now
                )
                val assistantMsg = Message(
                    id = newId(),
                    role = "assistant",
                    content = assistantTrimmed,
                    createdAt = now + 1
                )

                val updated = (existing ?: Conversation(id = currentConvId, title = baseTitle, messages = emptyList()))
                    .let { conv ->
                        conv.copy(
                            title = if (conv.messages.isEmpty()) baseTitle else conv.title,
                            messages = conv.messages + userMsg + assistantMsg,
                            updatedAt = now
                        )
                    }
                store.upsert(updated)
            } catch (e: Exception) {
                android.util.Log.e("VoiceOrchestrator", "Failed to save voice turn to conversation", e)
            }
        }
    }

    private fun onSentenceReadyForTts(sentence: String) {
        orchestratorScope.launch(Dispatchers.Main) {
            _sessionState.update { it.copy(state = VoiceState.SPEAKING) }
            ttsManager?.enqueueSentence(sentence)
        }
    }

    // ==========================================
    // TTS Callbacks (TextToSpeechListener)
    // ==========================================

    override fun onEngineInitialized(isSuccess: Boolean) {
        _sessionState.update { it.copy(isTtsReady = isSuccess) }
    }

    override fun onUtteranceStart(utteranceId: String) {
        _sessionState.update { it.copy(state = VoiceState.SPEAKING) }
    }

    override fun onUtteranceDone(utteranceId: String, isQueueEmpty: Boolean) {
        if (isQueueEmpty) {
            // All synthesized sentences have finished playing! Resume listening automatically
            _sessionState.update {
                it.copy(
                    state = VoiceState.LISTENING,
                    partialUserText = "",
                    activeAssistantText = ""
                )
            }
            if (!_sessionState.value.isMicMuted) {
                orchestratorScope.launch {
                    delay(200)
                    startListeningSafe()
                }
            }
        }
    }

    override fun onUtteranceError(utteranceId: String, errorMessage: String) {
        _sessionState.update {
            it.copy(
                errorMessage = "TTS warning: $errorMessage"
            )
        }
    }

    private suspend fun handleVoiceSkillManagement(request: com.lichiai.skill.router.SkillManagementRequest): String {
        return when (request) {
            is com.lichiai.skill.router.SkillManagementRequest.ListSkills -> {
                val current = skillRepository.skills.value
                val active = current.filter { it.enabled }
                if (current.isEmpty()) {
                    "You have no skills installed yet. You can create them in the Skills Library."
                } else {
                    "You have ${current.size} skills installed, with ${active.size} active: ${active.joinToString { it.name }}."
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
                    "${skill.name} skill is now enabled."
                } else {
                    "I couldn't find a skill matching ${request.targetName}."
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
                    "${skill.name} skill is now disabled."
                } else {
                    "I couldn't find a skill matching ${request.targetName}."
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
                        "${skill.name} skill has been deleted."
                    } else {
                        "Cannot delete ${skill.name}: ${res.exceptionOrNull()?.message}"
                    }
                } else {
                    "I couldn't find a skill matching ${request.targetName}."
                }
            }
            is com.lichiai.skill.router.SkillManagementRequest.ExportSkill -> {
                "You can export skills from the Skills Library in Settings."
            }
            is com.lichiai.skill.router.SkillManagementRequest.ProposeCreateSkill -> {
                "You can create custom skills in Settings under the Skills Library."
            }
        }
    }

    fun testVoice(sample: String) {
        ttsManager?.testVoice(
            sampleText = sample,
            onStart = { _sessionState.update { it.copy(state = VoiceState.SPEAKING) } },
            onDone = { _sessionState.update { it.copy(state = VoiceState.IDLE) } }
        )
    }

    fun getAvailableTtsVoices() = ttsManager?.getAvailableVoices() ?: emptyList()

    fun destroy() {
        orchestratorScope.cancel()
        stopSession()
    }
}
