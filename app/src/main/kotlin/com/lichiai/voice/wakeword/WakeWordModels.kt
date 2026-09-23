package com.lichiai.voice.wakeword

import kotlinx.serialization.Serializable

@Serializable
enum class WakeWordSensitivity {
    STRICT,
    BALANCED
}

@Serializable
data class WakePhraseConfig(
    val id: String,
    val phrase: String,
    val isEnabled: Boolean = true
)

@Serializable
data class WakeWordSettings(
    val enabled: Boolean = false,
    val backgroundListening: Boolean = true,
    val sensitivity: WakeWordSensitivity = WakeWordSensitivity.STRICT,
    val debounceMs: Long = 2000L,
    val phrases: List<WakePhraseConfig> = listOf(
        WakePhraseConfig(id = "wp_1", phrase = "Hey Lichi", isEnabled = true),
        WakePhraseConfig(id = "wp_2", phrase = "Hi Lichi", isEnabled = true),
        WakePhraseConfig(id = "wp_3", phrase = "Okay Lichi", isEnabled = true),
        WakePhraseConfig(id = "wp_4", phrase = "Lichi", isEnabled = true)
    )
)

enum class WakeWordEngineState {
    DISABLED,
    UNINITIALIZED,
    LOADING_MODEL,
    READY,
    LISTENING,
    WAKE_DETECTED,
    PAUSED,
    CONVERSATION_ACTIVE,
    MIC_UNAVAILABLE,
    RECOVERING,
    ERROR,
    STOPPED
}

sealed interface WakeWordEvent {
    data class Detected(val phrase: String, val timestamp: Long = System.currentTimeMillis()) : WakeWordEvent
    data object Started : WakeWordEvent
    data object Stopped : WakeWordEvent
    data object Paused : WakeWordEvent
    data object Resumed : WakeWordEvent
    data object MicrophoneUnavailable : WakeWordEvent
    data object MicrophoneRecovered : WakeWordEvent
    data class Error(val message: String) : WakeWordEvent
}
