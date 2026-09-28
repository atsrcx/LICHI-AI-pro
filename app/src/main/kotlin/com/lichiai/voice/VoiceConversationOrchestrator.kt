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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VoiceConversationOrchestrator(
    private val context: Context,
    private val llmClient: LlmClient,
    private val voiceSettingsRepository: VoiceSettingsRepository,
    private val settingsRepository: SettingsRepository,
    private val callEngine: UniversalCallEngine? = null,
    private val callActionExecutor: com.lichiai.calling.action.CallActionExecutor? = null,
    private val callActionIntentResolver: com.lichiai.calling.intent.CallActionIntentResolver? = null,
    private val conversationStore: ConversationStore? = null,
    private val activeConversationIdProvider: () -> String? = { null },
    private val onConversationIdChanged: (String) -> Unit = {},
    autonomousAgentTool: com.lichiai.agent.bridge.AutonomousAgentTool? = null,
    webIntelligenceManager: com.lichiai.web.WebIntelligenceManager? = null,
    private val onExecuteBrowserCommand: ((String) -> Unit)? = null,
    private val universalIntentEngine: com.lichiai.intent.UniversalIntentEngine? = null,
    private val routeDispatcher: com.lichiai.intent.dispatcher.RouteDispatcher? = null,
    private val taskOrchestratorV2: com.lichiai.orchestrator.UniversalTaskOrchestratorV2? = null
) : SpeechToTextListener, TextToSpeechListener {

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

    private var sttManager: SpeechToTextManager? = null
    private var ttsManager: TextToSpeechManager? = null

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

    fun startSession(provider: ProviderConfig?, assistant: Assistant?) {
        MicrophoneOwnershipCoordinator.requestForVoiceSession()
        activeProvider = provider
        activeAssistant = assistant
        retryCount = 0

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
            orchestratorScope.launch(Dispatchers.IO) {
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

        // Initialize TTS and STT
        ttsManager?.initialize(currentVoiceSettings) { isSuccess ->
            _sessionState.update { it.copy(isTtsReady = isSuccess) }
        }
        sttManager?.initialize(currentVoiceSettings)
        _sessionState.update { it.copy(isSttReady = true) }

        // Begin listening
        startListeningSafe()
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
        // Immediate Barge-In: Cancel TTS and LLM
        cancelLlmAndTts()
        _sessionState.update { it.copy(state = VoiceState.INTERRUPTED) }
        orchestratorScope.launch {
            delay(100)
            startListeningSafe()
        }
    }

    fun toggleMute() {
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

        // Check if user is issuing a structured call action (Answer, Reject, Mute, Speaker, Hold, End)
        val currentCallSession = com.lichiai.dynamicisland.LichiAssistantStateHub.callSession.value
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

        // Fast-path zero-LLM Time Engine resolution (Alarms, Reminders, Routines, Tasks)
        val timeParse = com.lichiai.time.parser.OfflineReminderIntentParser.parse(trimmed)
        if (timeParse !is com.lichiai.time.parser.ParsedTimeAction.NotRecognized) {
            orchestratorScope.launch {
                val adapter = com.lichiai.time.adapter.TimeCapabilityAdapter(context)
                val outcome = adapter.handleQuery(trimmed)
                val responseSpeech = outcome.naturalSpeech
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

        // Universal Task Orchestrator V2 (Universal Task Understanding + Closed-Loop Feedback)
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
                    onExecuteBrowserCommand?.invoke(trimmed)
                }
            }
            return
        }

        // Universal Intent Engine routing (Rollback / Fallback path)
        if (universalIntentEngine != null) {
            orchestratorScope.launch {
                val provider = activeProvider
                val modelToUse = currentAppSettings.activeModel.ifBlank { provider?.models?.firstOrNull() ?: "gpt-4o-mini" }
                val resolution = universalIntentEngine.resolve(
                    rawInput = trimmed,
                    provider = provider,
                    modelId = modelToUse
                )
                when (val intent = resolution.intent) {
                    is com.lichiai.intent.model.ResolvedIntent.CallTask -> {
                        _sessionState.update {
                            it.copy(
                                state = VoiceState.THINKING,
                                partialUserText = trimmed,
                                activeAssistantText = "Placing call..."
                            )
                        }
                        val outcome = callEngine?.executeIntent(intent.callIntent, sourceMode = "VOICE")
                        val responseSpeech = outcome?.message ?: intent.naturalAcknowledgment
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
                        return@launch
                    }
                    is com.lichiai.intent.model.ResolvedIntent.BrowserTask -> {
                        val ack = intent.naturalAcknowledgment
                        _sessionState.update {
                            val turn = VoiceTurn(
                                userText = trimmed,
                                assistantText = ack,
                                isUserFinal = true,
                                isAssistantComplete = true
                            )
                            it.copy(
                                state = VoiceState.SPEAKING,
                                historyTurns = it.historyTurns + turn,
                                activeAssistantText = ack
                            )
                        }
                        saveTurnToConversation(userText = trimmed, assistantText = ack)
                        ttsManager?.stopAndClearQueue()
                        ttsManager?.enqueueSentence(ack)
                        val queryToSearch = intent.query
                        if (intent.action == com.lichiai.intent.model.BrowserActionType.SEARCH && !queryToSearch.isNullOrBlank()) {
                            onExecuteBrowserCommand?.invoke("search on ${intent.searchEngine} for $queryToSearch")
                        } else {
                            onExecuteBrowserCommand?.invoke(intent.rawPrompt.ifBlank { trimmed })
                        }
                        return@launch
                    }
                    is com.lichiai.intent.model.ResolvedIntent.AndroidAgentTask -> {
                        if (!agentTool.isEnabled()) {
                            val disabledSpeech = "Autonomous Agent is disabled in Settings."
                            _sessionState.update {
                                val turn = VoiceTurn(userText = trimmed, assistantText = disabledSpeech, isUserFinal = true, isAssistantComplete = true)
                                it.copy(state = VoiceState.SPEAKING, historyTurns = it.historyTurns + turn, activeAssistantText = disabledSpeech)
                            }
                            saveTurnToConversation(userText = trimmed, assistantText = disabledSpeech)
                            ttsManager?.stopAndClearQueue()
                            ttsManager?.enqueueSentence(disabledSpeech)
                            return@launch
                        }
                        val ack = intent.naturalAcknowledgment
                        _sessionState.update {
                            val turn = VoiceTurn(userText = trimmed, assistantText = ack, isUserFinal = true, isAssistantComplete = false)
                            it.copy(state = VoiceState.SPEAKING, historyTurns = it.historyTurns + turn, activeAssistantText = ack)
                        }
                        ttsManager?.stopAndClearQueue()
                        ttsManager?.enqueueSentence(ack)

                        val agentResult = agentTool.execute(
                            taskDescription = intent.goal,
                            matchedSkills = intent.matchedSkills
                        ) { step, total, statusText ->
                            _sessionState.update {
                                it.copy(activeAssistantText = "Step $step/$total: $statusText")
                            }
                        }

                        val completionSpeech = if (agentResult.isSuccess) {
                            agentResult.summary
                        } else {
                            "Agent stopped: ${agentResult.summary}"
                        }
                        _sessionState.update {
                            val completedTurn = VoiceTurn(userText = trimmed, assistantText = completionSpeech, isUserFinal = true, isAssistantComplete = true)
                            val currentTurns = if (it.historyTurns.isNotEmpty()) it.historyTurns.dropLast(1) else emptyList()
                            it.copy(state = VoiceState.SPEAKING, historyTurns = currentTurns + completedTurn, activeAssistantText = completionSpeech)
                        }
                        saveTurnToConversation(userText = trimmed, assistantText = completionSpeech)
                        ttsManager?.stopAndClearQueue()
                        ttsManager?.enqueueSentence(completionSpeech)
                        return@launch
                    }
                    is com.lichiai.intent.model.ResolvedIntent.MediaTask -> {
                        val ack = intent.naturalAcknowledgment
                        _sessionState.update {
                            val turn = VoiceTurn(userText = trimmed, assistantText = ack, isUserFinal = true, isAssistantComplete = true)
                            it.copy(state = VoiceState.SPEAKING, historyTurns = it.historyTurns + turn, activeAssistantText = ack)
                        }
                        saveTurnToConversation(userText = trimmed, assistantText = ack)
                        ttsManager?.stopAndClearQueue()
                        ttsManager?.enqueueSentence(ack)
                        routeDispatcher?.dispatch(intent)
                        return@launch
                    }
                    is com.lichiai.intent.model.ResolvedIntent.DeviceControlTask -> {
                        val ack = intent.naturalAcknowledgment
                        _sessionState.update {
                            val turn = VoiceTurn(userText = trimmed, assistantText = ack, isUserFinal = true, isAssistantComplete = true)
                            it.copy(state = VoiceState.SPEAKING, historyTurns = it.historyTurns + turn, activeAssistantText = ack)
                        }
                        saveTurnToConversation(userText = trimmed, assistantText = ack)
                        ttsManager?.stopAndClearQueue()
                        ttsManager?.enqueueSentence(ack)
                        routeDispatcher?.dispatch(intent)
                        return@launch
                    }
                    is com.lichiai.intent.model.ResolvedIntent.MultiStepTask -> {
                        val ack = intent.naturalAcknowledgment
                        _sessionState.update {
                            val turn = VoiceTurn(userText = trimmed, assistantText = ack, isUserFinal = true, isAssistantComplete = true)
                            it.copy(state = VoiceState.SPEAKING, historyTurns = it.historyTurns + turn, activeAssistantText = ack)
                        }
                        saveTurnToConversation(userText = trimmed, assistantText = ack)
                        ttsManager?.stopAndClearQueue()
                        ttsManager?.enqueueSentence(ack)
                        routeDispatcher?.dispatch(intent)
                        return@launch
                    }
                    is com.lichiai.intent.model.ResolvedIntent.Clarification -> {
                        val q = intent.question
                        _sessionState.update {
                            val turn = VoiceTurn(userText = trimmed, assistantText = q, isUserFinal = true, isAssistantComplete = true)
                            it.copy(state = VoiceState.SPEAKING, historyTurns = it.historyTurns + turn, activeAssistantText = q)
                        }
                        saveTurnToConversation(userText = trimmed, assistantText = q)
                        ttsManager?.stopAndClearQueue()
                        ttsManager?.enqueueSentence(q)
                        return@launch
                    }
                    is com.lichiai.intent.model.ResolvedIntent.Cancellation -> {
                        val ack = intent.naturalAcknowledgment
                        _sessionState.update {
                            val turn = VoiceTurn(userText = trimmed, assistantText = ack, isUserFinal = true, isAssistantComplete = true)
                            it.copy(state = VoiceState.SPEAKING, historyTurns = it.historyTurns + turn, activeAssistantText = ack)
                        }
                        saveTurnToConversation(userText = trimmed, assistantText = ack)
                        ttsManager?.stopAndClearQueue()
                        ttsManager?.enqueueSentence(ack)
                        return@launch
                    }
                    else -> {}
                }
            }
        }

        // Check if user is issuing a natural language phone call command
        val callIntent = callEngine?.intentResolver?.resolve(trimmed)
        if (callEngine != null && callIntent != null && (callIntent.action == CallAction.CALL_CONTACT || callIntent.action == CallAction.CALL_NUMBER)) {
            orchestratorScope.launch {
                _sessionState.update {
                    it.copy(
                        state = VoiceState.THINKING,
                        partialUserText = trimmed,
                        activeAssistantText = "Placing call..."
                    )
                }
                val outcome = callEngine.executeIntent(callIntent, sourceMode = "VOICE")
                val responseSpeech = outcome.message
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

        // Top-Level Intent Boundary: Check if user is requesting a Browser task
        if (com.lichiai.browser.api.BrowserIntentBoundary.isBrowserIntent(trimmed)) {
            orchestratorScope.launch {
                val (_, naturalAck) = com.lichiai.browser.api.BrowserIntentBoundary.resolveIntent(trimmed)
                _sessionState.update {
                    val turn = VoiceTurn(
                        userText = trimmed,
                        assistantText = naturalAck,
                        isUserFinal = true,
                        isAssistantComplete = true
                    )
                    it.copy(
                        state = VoiceState.SPEAKING,
                        historyTurns = it.historyTurns + turn,
                        activeAssistantText = naturalAck
                    )
                }
                saveTurnToConversation(userText = trimmed, assistantText = naturalAck)
                ttsManager?.stopAndClearQueue()
                ttsManager?.enqueueSentence(naturalAck)
                onExecuteBrowserCommand?.invoke(trimmed)
            }
            return
        }

        // Check if user is requesting an Autonomous Agent task (strict capability routing)
        orchestratorScope.launch {
            val provider = activeProvider
            val modelToUse = currentAppSettings.activeModel.ifBlank { provider?.models?.firstOrNull() ?: "gpt-4o-mini" }
            val routeDecision = com.lichiai.agent.routing.AgentCapabilityRouter.resolveRouting(
                text = trimmed,
                provider = provider,
                modelId = modelToUse,
                llmClient = llmClient,
                availableSkills = skillRepository.skills.value
            )

            when (routeDecision) {
                is com.lichiai.agent.routing.AgentRouteDecision.SkillManagement -> {
                    val speechText = handleVoiceSkillManagement(routeDecision.request)
                    _sessionState.update {
                        val turn = VoiceTurn(
                            userText = trimmed,
                            assistantText = speechText,
                            isUserFinal = true,
                            isAssistantComplete = true
                        )
                        it.copy(
                            state = VoiceState.SPEAKING,
                            historyTurns = it.historyTurns + turn,
                            activeAssistantText = speechText
                        )
                    }
                    saveTurnToConversation(userText = trimmed, assistantText = speechText)
                    ttsManager?.stopAndClearQueue()
                    ttsManager?.enqueueSentence(speechText)
                    return@launch
                }
                is com.lichiai.agent.routing.AgentRouteDecision.ClarificationNeeded -> {
                    val q = routeDecision.question
                    _sessionState.update {
                        val turn = VoiceTurn(
                            userText = trimmed,
                            assistantText = q,
                            isUserFinal = true,
                            isAssistantComplete = true
                        )
                        it.copy(
                            state = VoiceState.SPEAKING,
                            historyTurns = it.historyTurns + turn,
                            activeAssistantText = q
                        )
                    }
                    saveTurnToConversation(userText = trimmed, assistantText = q)
                    ttsManager?.stopAndClearQueue()
                    ttsManager?.enqueueSentence(q)
                    return@launch
                }
                is com.lichiai.agent.routing.AgentRouteDecision.AgentTask -> {
                    if (!agentTool.isEnabled()) {
                        val msg = "Autonomous Agent is currently disabled in Settings."
                        _sessionState.update {
                            val turn = VoiceTurn(
                                userText = trimmed,
                                assistantText = msg,
                                isUserFinal = true,
                                isAssistantComplete = true
                            )
                            it.copy(
                                state = VoiceState.SPEAKING,
                                historyTurns = it.historyTurns + turn,
                                activeAssistantText = msg
                            )
                        }
                        saveTurnToConversation(userText = trimmed, assistantText = msg)
                        ttsManager?.stopAndClearQueue()
                        ttsManager?.enqueueSentence(msg)
                        return@launch
                    }

                    val ack = routeDecision.naturalAcknowledgment
                    _sessionState.update {
                        it.copy(
                            state = VoiceState.THINKING,
                            partialUserText = trimmed,
                            activeAssistantText = ack
                        )
                    }
                    ttsManager?.stopAndClearQueue()
                    ttsManager?.enqueueSentence(ack)

                    val result = agentTool.execute(routeDecision.fullGoalWithContext, routeDecision.matchedSkills) { step, total, statusText ->
                        _sessionState.update {
                            it.copy(activeAssistantText = "Step $step: $statusText")
                        }
                    }

                    val speechText = if (result.isSuccess) {
                        "Autonomous task completed. ${result.summary}"
                    } else {
                        "Autonomous task issue: ${result.summary}"
                    }

                    _sessionState.update {
                        val turn = VoiceTurn(
                            userText = trimmed,
                            assistantText = speechText,
                            isUserFinal = true,
                            isAssistantComplete = true
                        )
                        it.copy(
                            state = VoiceState.SPEAKING,
                            historyTurns = it.historyTurns + turn,
                            activeAssistantText = speechText
                        )
                    }
                    saveTurnToConversation(userText = trimmed, assistantText = speechText)
                    ttsManager?.stopAndClearQueue()
                    ttsManager?.enqueueSentence(speechText)
                    return@launch
                }
                is com.lichiai.agent.routing.AgentRouteDecision.NormalConversation -> {
                    _sessionState.update {
                        it.copy(
                            state = VoiceState.THINKING,
                            partialUserText = trimmed,
                            activeAssistantText = ""
                        )
                    }
                    // Process final turn with LLM
                    processUserTurnWithLlm(trimmed)
                }
            }
        }
        return
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
        val assistant = activeAssistant
        val defaultModel = provider.models.firstOrNull() ?: "gpt-4o-mini"
        val modelToUse = currentAppSettings.activeModel.ifBlank { defaultModel }

        val systemPrompt = PromptVars.render(
            template = (assistant?.systemPrompt ?: currentAppSettings.systemPrompt).ifBlank {
                "You are a helpful, conversational AI voice assistant. Keep answers natural, concise, and easy to speak aloud."
            },
            model = modelToUse,
            provider = provider.name,
            assistant = assistant?.name ?: "Assistant"
        )

        val fullAssistantAccumulator = java.lang.StringBuilder()

        // If safe echo protection is on, pause STT during LLM/TTS
        if (currentVoiceSettings.safeEchoProtection) {
            sttManager?.stopListening()
        }

        llmStreamJob?.cancel()
        llmStreamJob = orchestratorScope.launch(Dispatchers.IO) {
            try {
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
                val semanticContext = com.lichiai.context.engine.UniversalContextContinuityEngine.getInstance().getContext(activeConvId ?: "default_session")
                val contextFactPrompt = if (semanticContext.verifiedFacts.isNotEmpty()) {
                    "\n\nCURRENT CONVERSATION CONTEXT & VERIFIED FACTS:\n" +
                    semanticContext.verifiedFacts.entries.joinToString("\n") { "- ${it.key}: ${it.value}" } +
                    (semanticContext.activeTopic?.let { "\nActive Topic: $it" } ?: "")
                } else ""

                // Build message history with actual conversation context if available
                val messages = mutableListOf<ChatMessage>()
                val effectiveSystemPrompt = buildString {
                    append(systemPrompt)
                    if (contextFactPrompt.isNotBlank()) append(contextFactPrompt)
                    if (webVoicePrompt.isNotBlank()) {
                        append("\n\n$webVoicePrompt\n\nNOTE: You are in Voice Mode speaking to the user aloud. Give a concise, conversational answer summarizing the web facts. Do not recite URLs or reference brackets.")
                    }
                }

                if (effectiveSystemPrompt.isNotBlank()) {
                    messages.add(ChatMessage("system", effectiveSystemPrompt))
                }
                val existingConv = if (activeConvId != null && conversationStore != null) {
                    conversationStore.snapshot().firstOrNull { it.id == activeConvId }
                } else null

                if (existingConv != null && existingConv.messages.isNotEmpty()) {
                    existingConv.messages.filter { it.content.isNotBlank() }.takeLast(10).forEach { msg ->
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
                            val activeConvId = activeConversationIdProvider() ?: "default_session"
                            com.lichiai.context.engine.UniversalContextContinuityEngine.getInstance().recordExecution(
                                conversationId = activeConvId,
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
                        settings = currentAppSettings,
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
