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
import com.lichiai.util.PromptVars
import com.lichiai.voice.conversation.SentenceBuffer
import com.lichiai.voice.conversation.VoiceSessionState
import com.lichiai.voice.conversation.VoiceState
import com.lichiai.voice.conversation.VoiceTurn
import com.lichiai.voice.stt.SpeechToTextListener
import com.lichiai.voice.stt.SpeechToTextManager
import com.lichiai.voice.tts.TextToSpeechListener
import com.lichiai.voice.tts.TextToSpeechManager
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

class VoiceConversationOrchestrator(
    private val context: Context,
    private val llmClient: LlmClient,
    private val voiceSettingsRepository: VoiceSettingsRepository,
    private val settingsRepository: SettingsRepository,
    private val callEngine: UniversalCallEngine? = null
) : SpeechToTextListener, TextToSpeechListener {

    private val orchestratorScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _sessionState = MutableStateFlow(VoiceSessionState())
    val sessionState: StateFlow<VoiceSessionState> = _sessionState.asStateFlow()

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
    }

    fun startSession(provider: ProviderConfig?, assistant: Assistant?) {
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
        sttManager?.destroy()
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
                ttsManager?.stopAndClearQueue()
                ttsManager?.enqueueSentence(responseSpeech)
            }
            return
        }

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

        // Build message history from past turns
        val messages = mutableListOf<ChatMessage>()
        if (systemPrompt.isNotBlank()) {
            messages.add(ChatMessage("system", systemPrompt))
        }

        // Add last 6 turns for context
        _sessionState.value.historyTurns.takeLast(6).forEach { turn ->
            if (turn.userText.isNotBlank()) messages.add(ChatMessage("user", turn.userText))
            if (turn.assistantText.isNotBlank()) messages.add(ChatMessage("assistant", turn.assistantText))
        }
        messages.add(ChatMessage("user", userQuery))

        val fullAssistantAccumulator = java.lang.StringBuilder()

        // If safe echo protection is on, pause STT during LLM/TTS
        if (currentVoiceSettings.safeEchoProtection) {
            sttManager?.stopListening()
        }

        llmStreamJob?.cancel()
        llmStreamJob = orchestratorScope.launch(Dispatchers.IO) {
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
