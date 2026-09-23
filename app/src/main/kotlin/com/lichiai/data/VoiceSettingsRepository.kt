package com.lichiai.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lichiai.voice.wakeword.WakePhraseConfig
import com.lichiai.voice.wakeword.WakeWordSensitivity
import com.lichiai.voice.wakeword.WakeWordSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.voiceDataStore by preferencesDataStore(name = "voice_settings")

enum class SttMode {
    SYSTEM_DEFAULT,
    ON_DEVICE,
    SPECIFIC_PROVIDER
}

enum class TtsMode {
    SYSTEM_DEFAULT,
    SPECIFIC_ENGINE
}

data class VoiceSettings(
    val sttMode: SttMode = SttMode.SYSTEM_DEFAULT,
    val sttComponent: String = "", // e.g. "package/serviceClass"
    val sttLanguage: String = "default", // e.g. "default", "en-US", "hi-IN"
    val sttPartialResults: Boolean = true,
    val sttAutoRestart: Boolean = true,

    val ttsMode: TtsMode = TtsMode.SYSTEM_DEFAULT,
    val ttsEnginePackage: String = "",
    val ttsLanguage: String = "default",
    val ttsVoiceName: String = "default",
    val speechRate: Float = 1.0f,
    val pitch: Float = 1.0f,

    val bargeInEnabled: Boolean = true,
    val safeEchoProtection: Boolean = true
)

class VoiceSettingsRepository(private val context: Context) {

    private object Keys {
        val STT_MODE = stringPreferencesKey("stt_mode")
        val STT_COMPONENT = stringPreferencesKey("stt_component")
        val STT_LANGUAGE = stringPreferencesKey("stt_language")
        val STT_PARTIAL_RESULTS = booleanPreferencesKey("stt_partial_results")
        val STT_AUTO_RESTART = booleanPreferencesKey("stt_auto_restart")

        val TTS_MODE = stringPreferencesKey("tts_mode")
        val TTS_ENGINE_PACKAGE = stringPreferencesKey("tts_engine_package")
        val TTS_LANGUAGE = stringPreferencesKey("tts_language")
        val TTS_VOICE_NAME = stringPreferencesKey("tts_voice_name")
        val SPEECH_RATE = floatPreferencesKey("speech_rate")
        val PITCH = floatPreferencesKey("pitch")

        val BARGE_IN_ENABLED = booleanPreferencesKey("barge_in_enabled")
        val SAFE_ECHO_PROTECTION = booleanPreferencesKey("safe_echo_protection")

        val WAKE_WORD_SETTINGS_JSON = stringPreferencesKey("wake_word_settings_json")
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val wakeWordSettings: Flow<WakeWordSettings> = context.voiceDataStore.data.map { prefs ->
        val rawJson = prefs[Keys.WAKE_WORD_SETTINGS_JSON]
        if (!rawJson.isNullOrBlank()) {
            runCatching { json.decodeFromString<WakeWordSettings>(rawJson) }.getOrElse { WakeWordSettings() }
        } else {
            WakeWordSettings()
        }
    }

    val settings: Flow<VoiceSettings> = context.voiceDataStore.data.map { prefs ->
        VoiceSettings(
            sttMode = prefs[Keys.STT_MODE]?.let { runCatching { SttMode.valueOf(it) }.getOrNull() } ?: SttMode.SYSTEM_DEFAULT,
            sttComponent = prefs[Keys.STT_COMPONENT] ?: "",
            sttLanguage = prefs[Keys.STT_LANGUAGE] ?: "default",
            sttPartialResults = prefs[Keys.STT_PARTIAL_RESULTS] ?: true,
            sttAutoRestart = prefs[Keys.STT_AUTO_RESTART] ?: true,

            ttsMode = prefs[Keys.TTS_MODE]?.let { runCatching { TtsMode.valueOf(it) }.getOrNull() } ?: TtsMode.SYSTEM_DEFAULT,
            ttsEnginePackage = prefs[Keys.TTS_ENGINE_PACKAGE] ?: "",
            ttsLanguage = prefs[Keys.TTS_LANGUAGE] ?: "default",
            ttsVoiceName = prefs[Keys.TTS_VOICE_NAME] ?: "default",
            speechRate = prefs[Keys.SPEECH_RATE] ?: 1.0f,
            pitch = prefs[Keys.PITCH] ?: 1.0f,

            bargeInEnabled = prefs[Keys.BARGE_IN_ENABLED] ?: true,
            safeEchoProtection = prefs[Keys.SAFE_ECHO_PROTECTION] ?: true
        )
    }

    suspend fun updateSttMode(mode: SttMode, component: String = "") {
        context.voiceDataStore.edit { prefs ->
            prefs[Keys.STT_MODE] = mode.name
            prefs[Keys.STT_COMPONENT] = component
        }
    }

    suspend fun updateSttLanguage(language: String) {
        context.voiceDataStore.edit { prefs ->
            prefs[Keys.STT_LANGUAGE] = language
        }
    }

    suspend fun updateSttPartialResults(enabled: Boolean) {
        context.voiceDataStore.edit { prefs ->
            prefs[Keys.STT_PARTIAL_RESULTS] = enabled
        }
    }

    suspend fun updateSttAutoRestart(enabled: Boolean) {
        context.voiceDataStore.edit { prefs ->
            prefs[Keys.STT_AUTO_RESTART] = enabled
        }
    }

    suspend fun updateTtsMode(mode: TtsMode, enginePackage: String = "") {
        context.voiceDataStore.edit { prefs ->
            prefs[Keys.TTS_MODE] = mode.name
            prefs[Keys.TTS_ENGINE_PACKAGE] = enginePackage
        }
    }

    suspend fun updateTtsLanguage(language: String) {
        context.voiceDataStore.edit { prefs ->
            prefs[Keys.TTS_LANGUAGE] = language
        }
    }

    suspend fun updateTtsVoiceName(voiceName: String) {
        context.voiceDataStore.edit { prefs ->
            prefs[Keys.TTS_VOICE_NAME] = voiceName
        }
    }

    suspend fun updateSpeechRate(rate: Float) {
        context.voiceDataStore.edit { prefs ->
            prefs[Keys.SPEECH_RATE] = rate.coerceIn(0.5f, 2.0f)
        }
    }

    suspend fun updatePitch(pitch: Float) {
        context.voiceDataStore.edit { prefs ->
            prefs[Keys.PITCH] = pitch.coerceIn(0.5f, 2.0f)
        }
    }

    suspend fun updateBargeIn(enabled: Boolean) {
        context.voiceDataStore.edit { prefs ->
            prefs[Keys.BARGE_IN_ENABLED] = enabled
        }
    }

    suspend fun updateSafeEchoProtection(enabled: Boolean) {
        context.voiceDataStore.edit { prefs ->
            prefs[Keys.SAFE_ECHO_PROTECTION] = enabled
        }
    }

    suspend fun updateWakeWordSettings(transform: (WakeWordSettings) -> WakeWordSettings) {
        context.voiceDataStore.edit { prefs ->
            val current = prefs[Keys.WAKE_WORD_SETTINGS_JSON]?.let {
                runCatching { json.decodeFromString<WakeWordSettings>(it) }.getOrNull()
            } ?: WakeWordSettings()
            val updated = transform(current)
            prefs[Keys.WAKE_WORD_SETTINGS_JSON] = json.encodeToString(updated)
        }
    }

    suspend fun updateWakeWordEnabled(enabled: Boolean) {
        updateWakeWordSettings { it.copy(enabled = enabled) }
    }

    suspend fun updateWakeWordBackground(background: Boolean) {
        updateWakeWordSettings { it.copy(backgroundListening = background) }
    }

    suspend fun updateWakeWordSensitivity(sensitivity: WakeWordSensitivity) {
        updateWakeWordSettings { it.copy(sensitivity = sensitivity) }
    }

    suspend fun toggleWakeWordPhrase(phraseId: String, isEnabled: Boolean) {
        updateWakeWordSettings { settings ->
            val updatedPhrases = settings.phrases.map {
                if (it.id == phraseId) it.copy(isEnabled = isEnabled) else it
            }
            settings.copy(phrases = updatedPhrases)
        }
    }

    suspend fun updateWakeWordPhraseText(phraseId: String, newText: String) {
        updateWakeWordSettings { settings ->
            val updatedPhrases = settings.phrases.map {
                if (it.id == phraseId) it.copy(phrase = newText) else it
            }
            settings.copy(phrases = updatedPhrases)
        }
    }

    suspend fun addWakeWordPhrase(phraseText: String) {
        if (phraseText.isBlank()) return
        updateWakeWordSettings { settings ->
            val newId = "wp_${System.currentTimeMillis()}"
            val newPhrase = WakePhraseConfig(id = newId, phrase = phraseText.trim(), isEnabled = true)
            settings.copy(phrases = settings.phrases + newPhrase)
        }
    }

    suspend fun removeWakeWordPhrase(phraseId: String) {
        updateWakeWordSettings { settings ->
            settings.copy(phrases = settings.phrases.filterNot { it.id == phraseId })
        }
    }
}
