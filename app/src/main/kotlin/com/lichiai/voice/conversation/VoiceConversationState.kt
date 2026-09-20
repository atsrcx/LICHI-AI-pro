package com.lichiai.voice.conversation

enum class VoiceState {
    IDLE,
    LISTENING,
    TRANSCRIBING,
    THINKING,
    SPEAKING,
    INTERRUPTED,
    ERROR,
    PAUSED
}

data class VoiceTurn(
    val id: String = java.util.UUID.randomUUID().toString(),
    val userText: String = "",
    val assistantText: String = "",
    val isUserFinal: Boolean = false,
    val isAssistantComplete: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

data class VoiceSessionState(
    val state: VoiceState = VoiceState.IDLE,
    val isMicMuted: Boolean = false,
    val currentRms: Float = 0f,
    val partialUserText: String = "",
    val activeAssistantText: String = "",
    val historyTurns: List<VoiceTurn> = emptyList(),
    val errorMessage: String? = null,
    val isTtsReady: Boolean = false,
    val isSttReady: Boolean = false
)
