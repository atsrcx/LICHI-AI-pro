package com.lichiai.voice.stt

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.lichiai.data.SttMode
import com.lichiai.data.VoiceSettings
import java.util.Locale

interface SpeechToTextListener {
    fun onReadyForSpeech()
    fun onBeginningOfSpeech()
    fun onRmsChanged(rmsdB: Float)
    fun onPartialResult(partialText: String)
    fun onFinalResult(text: String)
    fun onError(errorCode: Int, errorMessage: String)
    fun onEndOfSpeech()
}

class SpeechToTextManager(
    private val context: Context,
    private val listener: SpeechToTextListener
) : RecognitionListener {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var currentSettings: VoiceSettings? = null

    fun initialize(settings: VoiceSettings) {
        currentSettings = settings
        mainHandler.post {
            destroyRecognizer()
            createRecognizer(settings)
        }
    }

    private fun createRecognizer(settings: VoiceSettings) {
        try {
            speechRecognizer = when (settings.sttMode) {
                SttMode.ON_DEVICE -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
                        SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                    } else {
                        SpeechRecognizer.createSpeechRecognizer(context)
                    }
                }
                SttMode.SPECIFIC_PROVIDER -> {
                    val comp = settings.sttComponent.takeIf { it.isNotBlank() }?.let {
                        ComponentName.unflattenFromString(it)
                    }
                    if (comp != null) {
                        SpeechRecognizer.createSpeechRecognizer(context, comp)
                    } else {
                        SpeechRecognizer.createSpeechRecognizer(context)
                    }
                }
                SttMode.SYSTEM_DEFAULT -> {
                    SpeechRecognizer.createSpeechRecognizer(context)
                }
            }
            speechRecognizer?.setRecognitionListener(this)
        } catch (e: Exception) {
            listener.onError(-1, "Failed to initialize SpeechRecognizer: ${e.localizedMessage}")
        }
    }

    fun startListening(settings: VoiceSettings? = null) {
        val voiceSettings = settings ?: currentSettings ?: VoiceSettings()
        currentSettings = voiceSettings

        mainHandler.post {
            try {
                if (speechRecognizer == null) {
                    createRecognizer(voiceSettings)
                }

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, voiceSettings.sttPartialResults)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    
                    if (voiceSettings.sttLanguage != "default" && voiceSettings.sttLanguage.isNotBlank()) {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, voiceSettings.sttLanguage)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, voiceSettings.sttLanguage)
                    } else {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                    }

                    if (voiceSettings.sttMode == SttMode.ON_DEVICE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    }
                }

                isListening = true
                speechRecognizer?.startListening(intent)
            } catch (e: Exception) {
                isListening = false
                listener.onError(-1, "Error starting speech recognition: ${e.localizedMessage}")
            }
        }
    }

    fun stopListening() {
        mainHandler.post {
            try {
                isListening = false
                speechRecognizer?.stopListening()
            } catch (_: Exception) {}
        }
    }

    fun cancel() {
        mainHandler.post {
            try {
                isListening = false
                speechRecognizer?.cancel()
            } catch (_: Exception) {}
        }
    }

    fun destroy() {
        mainHandler.post {
            destroyRecognizer()
        }
    }

    private fun destroyRecognizer() {
        try {
            isListening = false
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
            speechRecognizer = null
        } catch (_: Exception) {}
    }

    // RecognitionListener Callbacks
    override fun onReadyForSpeech(params: Bundle?) {
        listener.onReadyForSpeech()
    }

    override fun onBeginningOfSpeech() {
        listener.onBeginningOfSpeech()
    }

    override fun onRmsChanged(rmsdB: Float) {
        listener.onRmsChanged(rmsdB)
    }

    override fun onBufferReceived(buffer: ByteArray?) {}

    override fun onEndOfSpeech() {
        isListening = false
        listener.onEndOfSpeech()
    }

    override fun onError(error: Int) {
        isListening = false
        val message = when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
            SpeechRecognizer.ERROR_CLIENT -> "Client-side recognition error"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission required"
            SpeechRecognizer.ERROR_NETWORK -> "Network error during speech recognition"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
            SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer busy"
            SpeechRecognizer.ERROR_SERVER -> "Server-side recognition error"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout (silence detected)"
            else -> "Speech error (code: $error)"
        }
        listener.onError(error, message)
    }

    override fun onResults(results: Bundle?) {
        isListening = false
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        val text = matches?.firstOrNull()?.trim() ?: ""
        if (text.isNotBlank()) {
            listener.onFinalResult(text)
        } else {
            listener.onError(SpeechRecognizer.ERROR_NO_MATCH, "No speech detected")
        }
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        val partial = matches?.firstOrNull()?.trim() ?: ""
        if (partial.isNotBlank()) {
            listener.onPartialResult(partial)
        }
    }

    override fun onEvent(eventType: Int, params: Bundle?) {}
}
