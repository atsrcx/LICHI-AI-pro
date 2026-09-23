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
import android.util.Log
import com.lichiai.data.SttMode
import com.lichiai.data.VoiceSettings
import com.lichiai.voice.arbitration.MicrophoneArbitrator
import com.lichiai.voice.arbitration.MicrophoneOwner
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

    companion object {
        private const val TAG = "SpeechToTextManager"
    }

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
            Log.d(TAG, "SpeechRecognizer created successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize SpeechRecognizer", e)
            listener.onError(-1, "Failed to initialize SpeechRecognizer: ${e.localizedMessage}")
        }
    }

    fun startListening(settings: VoiceSettings? = null) {
        val voiceSettings = settings ?: currentSettings ?: VoiceSettings()
        currentSettings = voiceSettings

        Log.d("LICHI_VOICE", "[STT] START")
        val acquired = MicrophoneArbitrator.requestAcquisitionBlocking(MicrophoneOwner.VOICE_STT, "STT start listening")
        if (!acquired) {
            Log.w(TAG, "Cannot start listening: MicrophoneArbitrator denied access")
            Log.w("LICHI_VOICE", "[STT] ACQUIRE_DENIED")
            listener.onError(-1, "Microphone in use by higher priority system")
            return
        }

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
                Log.d("LICHI_VOICE", "[STT] STARTED")
                Log.d(TAG, "SpeechRecognizer.startListening() dispatched")
            } catch (e: Exception) {
                isListening = false
                Log.e(TAG, "Error starting speech recognition", e)
                destroyRecognizer()
                listener.onError(-1, "Error starting speech recognition: ${e.localizedMessage}")
            }
        }
    }

    fun stopListening() {
        Log.d("LICHI_VOICE", "[STT] STOP")
        mainHandler.post {
            try {
                isListening = false
                speechRecognizer?.stopListening()
                Log.d(TAG, "SpeechRecognizer.stopListening() dispatched")
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping SpeechRecognizer", e)
            }
        }
    }

    fun cancel() {
        Log.d("LICHI_VOICE", "[STT] CANCEL")
        mainHandler.post {
            try {
                isListening = false
                speechRecognizer?.cancel()
                Log.d(TAG, "SpeechRecognizer.cancel() dispatched")
            } catch (e: Exception) {
                Log.w(TAG, "Error cancelling SpeechRecognizer", e)
            }
        }
    }

    /**
     * Fully tear down SpeechRecognizer and authoritatively release VOICE_STT ownership.
     */
    fun release(reason: String = "STT done", onComplete: (() -> Unit)? = null) {
        Log.d("LICHI_VOICE", "[STT] RELEASE_REQUEST reason=\"$reason\"")
        mainHandler.post {
            destroyRecognizer()
            MicrophoneArbitrator.releaseBlocking(MicrophoneOwner.VOICE_STT, reason)
            Log.d("LICHI_VOICE", "[STT] RELEASED")
            onComplete?.invoke()
        }
    }

    fun destroy(onComplete: (() -> Unit)? = null) {
        release("STT destroy", onComplete)
    }

    private fun destroyRecognizer() {
        try {
            isListening = false
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
            speechRecognizer = null
            Log.d(TAG, "SpeechRecognizer destroyed and released")
        } catch (e: Exception) {
            Log.w(TAG, "Error destroying SpeechRecognizer", e)
        }
    }

    // RecognitionListener Callbacks
    override fun onReadyForSpeech(params: Bundle?) {
        Log.d(TAG, "onReadyForSpeech")
        listener.onReadyForSpeech()
    }

    override fun onBeginningOfSpeech() {
        Log.d(TAG, "onBeginningOfSpeech")
        listener.onBeginningOfSpeech()
    }

    override fun onRmsChanged(rmsdB: Float) {
        listener.onRmsChanged(rmsdB)
    }

    override fun onBufferReceived(buffer: ByteArray?) {}

    override fun onEndOfSpeech() {
        Log.d(TAG, "onEndOfSpeech")
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
        Log.w(TAG, "onError: code=$error msg=$message")
        if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
            error == SpeechRecognizer.ERROR_CLIENT ||
            error == SpeechRecognizer.ERROR_AUDIO
        ) {
            destroyRecognizer()
        }
        listener.onError(error, message)
    }

    override fun onResults(results: Bundle?) {
        isListening = false
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        val text = matches?.firstOrNull()?.trim() ?: ""
        Log.d(TAG, "onResults text=\"$text\"")
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
