package com.lichiai.dynamicisland

import com.lichiai.calling.state.CallSessionInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class LichiUiState {
    IDLE,
    WAKE_LISTENING,
    WAKE_DETECTED,
    LISTENING,
    TRANSCRIBING,
    THINKING,
    TOOL_EXECUTION,
    SPEAKING,
    CALLING,
    CALL_DECISION_REQUIRED,
    WAITING_FOR_CALL_COMMAND,
    MIC_UNAVAILABLE,
    ERROR,
    PAUSED
}

data class LichiAssistantState(
    val uiState: LichiUiState = LichiUiState.IDLE,
    val statusText: String = "Lichi",
    val transcript: String = "",
    val responsePreview: String = "",
    val toolName: String? = null,
    val callingTarget: String? = null,
    val rmsLevel: Float = 0f,
    val errorText: String? = null,
    val lastStateChangeMillis: Long = System.currentTimeMillis()
)

/**
 * Central State Hub for Lichi Assistant Surfaces.
 * Aggregates live states from Wake Word, Voice STT/TTS Orchestrator, LLM Streaming,
 * Telephony Calling Engine, and Microphone Arbitrator.
 *
 * CRITICAL RULE: This class is UI-observation ONLY. It does not acquire, release,
 * or touch any microphone or audio recording hardware.
 */
object LichiAssistantStateHub {

    private val _assistantState = MutableStateFlow(LichiAssistantState())
    val assistantState: StateFlow<LichiAssistantState> = _assistantState.asStateFlow()

    private val _callSession = MutableStateFlow(CallSessionInfo())
    val callSession: StateFlow<CallSessionInfo> = _callSession.asStateFlow()

    fun updateCallSession(session: CallSessionInfo) {
        _callSession.value = session
    }

    fun updateState(
        uiState: LichiUiState,
        statusText: String? = null,
        transcript: String? = null,
        responsePreview: String? = null,
        toolName: String? = null,
        callingTarget: String? = null,
        rmsLevel: Float? = null,
        errorText: String? = null
    ) {
        val current = _assistantState.value
        val defaultStatus = when (uiState) {
            LichiUiState.IDLE -> "Lichi"
            LichiUiState.WAKE_LISTENING -> "Listening for wake word"
            LichiUiState.WAKE_DETECTED -> "Lichi ✓"
            LichiUiState.LISTENING -> "Listening..."
            LichiUiState.TRANSCRIBING -> "Transcribing..."
            LichiUiState.THINKING -> "Thinking..."
            LichiUiState.TOOL_EXECUTION -> toolName ?: "Executing tool..."
            LichiUiState.SPEAKING -> "Speaking..."
            LichiUiState.CALLING -> callingTarget?.let { "Calling $it..." } ?: "Calling..."
            LichiUiState.CALL_DECISION_REQUIRED -> "Call Decision Required"
            LichiUiState.WAITING_FOR_CALL_COMMAND -> "Listening for decision..."
            LichiUiState.MIC_UNAVAILABLE -> "Mic In Use"
            LichiUiState.ERROR -> errorText ?: "Error"
            LichiUiState.PAUSED -> "Paused"
        }

        _assistantState.value = current.copy(
            uiState = uiState,
            statusText = statusText ?: defaultStatus,
            transcript = transcript ?: if (uiState == LichiUiState.IDLE) "" else current.transcript,
            responsePreview = responsePreview ?: if (uiState == LichiUiState.IDLE) "" else current.responsePreview,
            toolName = toolName ?: if (uiState == LichiUiState.TOOL_EXECUTION) current.toolName else null,
            callingTarget = callingTarget ?: if (uiState == LichiUiState.CALLING) current.callingTarget else null,
            rmsLevel = (rmsLevel ?: current.rmsLevel).coerceIn(0f, 1f),
            errorText = errorText,
            lastStateChangeMillis = System.currentTimeMillis()
        )
    }

    fun onWakeWordListening() {
        if (_assistantState.value.uiState == LichiUiState.IDLE || _assistantState.value.uiState == LichiUiState.WAKE_LISTENING) {
            updateState(LichiUiState.WAKE_LISTENING, statusText = "Listening for wake word")
        }
    }

    fun onWakeWordDetected(phrase: String) {
        updateState(LichiUiState.WAKE_DETECTED, statusText = "$phrase ✓")
    }

    fun onVoiceListening(partialTranscript: String = "", rms: Float = 0f) {
        updateState(
            uiState = LichiUiState.LISTENING,
            statusText = "Listening...",
            transcript = partialTranscript,
            rmsLevel = rms
        )
    }

    fun onVoiceThinking() {
        updateState(LichiUiState.THINKING, statusText = "Thinking...")
    }

    fun onVoiceSpeaking(assistantText: String = "", rms: Float = 0.5f) {
        val preview = if (assistantText.length > 90) assistantText.take(90) + "..." else assistantText
        updateState(
            uiState = LichiUiState.SPEAKING,
            statusText = "Speaking...",
            responsePreview = preview,
            rmsLevel = rms
        )
    }

    fun onToolExecution(toolName: String) {
        updateState(
            uiState = LichiUiState.TOOL_EXECUTION,
            statusText = toolName,
            toolName = toolName
        )
    }

    fun onCalling(target: String) {
        updateState(
            uiState = LichiUiState.CALLING,
            statusText = "Calling $target...",
            callingTarget = target
        )
    }

    fun onMicUnavailable(reason: String = "Microphone in use") {
        updateState(
            uiState = LichiUiState.MIC_UNAVAILABLE,
            statusText = reason,
            errorText = reason
        )
    }

    fun onError(message: String) {
        updateState(
            uiState = LichiUiState.ERROR,
            statusText = "Error: $message",
            errorText = message
        )
    }

    fun resetToIdle() {
        updateState(
            uiState = LichiUiState.IDLE,
            statusText = "Lichi",
            transcript = "",
            responsePreview = "",
            toolName = null,
            callingTarget = null,
            rmsLevel = 0f,
            errorText = null
        )
    }
}
