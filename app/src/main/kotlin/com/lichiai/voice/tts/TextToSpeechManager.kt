package com.lichiai.voice.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.lichiai.data.TtsMode
import com.lichiai.data.VoiceSettings
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

interface TextToSpeechListener {
    fun onEngineInitialized(isSuccess: Boolean)
    fun onUtteranceStart(utteranceId: String)
    fun onUtteranceDone(utteranceId: String, isQueueEmpty: Boolean)
    fun onUtteranceError(utteranceId: String, errorMessage: String)
}

class TextToSpeechManager(
    private val context: Context,
    private val listener: TextToSpeechListener
) : TextToSpeech.OnInitListener, UtteranceProgressListener() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var currentSettings: VoiceSettings? = null

    private val utteranceCounter = AtomicInteger(0)
    private val sentenceQueue = ConcurrentLinkedQueue<Pair<String, String>>() // (utteranceId, text)
    private var currentlyPlayingUtteranceId: String? = null

    fun initialize(settings: VoiceSettings, onComplete: ((Boolean) -> Unit)? = null) {
        currentSettings = settings
        mainHandler.post {
            destroyTts()
            try {
                if (settings.ttsMode == TtsMode.SPECIFIC_ENGINE && settings.ttsEnginePackage.isNotBlank()) {
                    tts = TextToSpeech(context.applicationContext, { status ->
                        onInit(status)
                        onComplete?.invoke(status == TextToSpeech.SUCCESS)
                    }, settings.ttsEnginePackage)
                } else {
                    tts = TextToSpeech(context.applicationContext) { status ->
                        onInit(status)
                        onComplete?.invoke(status == TextToSpeech.SUCCESS)
                    }
                }
            } catch (e: Exception) {
                isInitialized = false
                listener.onEngineInitialized(false)
                onComplete?.invoke(false)
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isInitialized = true
            tts?.setOnUtteranceProgressListener(this)
            currentSettings?.let { applySettings(it) }
            mainHandler.post { listener.onEngineInitialized(true) }
        } else {
            isInitialized = false
            mainHandler.post { listener.onEngineInitialized(false) }
        }
    }

    fun applySettings(settings: VoiceSettings) {
        currentSettings = settings
        val engine = tts ?: return
        if (!isInitialized) return

        try {
            // Apply Speech Rate and Pitch
            engine.setSpeechRate(settings.speechRate)
            engine.setPitch(settings.pitch)

            // Apply Language & Voice
            val targetLocale = if (settings.ttsLanguage != "default" && settings.ttsLanguage.isNotBlank()) {
                Locale.forLanguageTag(settings.ttsLanguage)
            } else {
                Locale.getDefault()
            }

            val langResult = engine.isLanguageAvailable(targetLocale)
            if (langResult >= TextToSpeech.LANG_AVAILABLE) {
                engine.language = targetLocale
            } else {
                // Fallback to base language (e.g., "en" if "en-IN" is unavailable)
                val baseLocale = Locale(targetLocale.language)
                if (engine.isLanguageAvailable(baseLocale) >= TextToSpeech.LANG_AVAILABLE) {
                    engine.language = baseLocale
                } else {
                    engine.language = Locale.US
                }
            }

            // Apply Voice if selected and supported
            if (settings.ttsVoiceName != "default" && settings.ttsVoiceName.isNotBlank()) {
                val availableVoices = runCatching { engine.voices }.getOrNull()
                val matchedVoice = availableVoices?.firstOrNull { it.name == settings.ttsVoiceName }
                if (matchedVoice != null) {
                    engine.voice = matchedVoice
                }
            }
        } catch (_: Exception) {}
    }

    fun getAvailableVoices(): List<TtsVoiceInfo> {
        val engine = tts ?: return emptyList()
        if (!isInitialized) return emptyList()
        return try {
            engine.voices?.map { voice ->
                val label = buildString {
                    append(voice.locale.displayName)
                    if (voice.name.isNotBlank()) {
                        append(" (").append(voice.name.substringAfterLast("-", voice.name)).append(")")
                    }
                }
                TtsVoiceInfo(
                    name = voice.name,
                    localeTag = voice.locale.toLanguageTag(),
                    displayName = label,
                    isNetworkConnectionRequired = voice.isNetworkConnectionRequired,
                    quality = voice.quality
                )
            } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun enqueueSentence(sentence: String) {
        val cleanText = sentence.trim()
        if (cleanText.isBlank()) return

        val utteranceId = "utt_${utteranceCounter.incrementAndGet()}"
        sentenceQueue.offer(Pair(utteranceId, cleanText))

        if (currentlyPlayingUtteranceId == null) {
            playNextInQueue()
        }
    }

    private fun playNextInQueue() {
        val next = sentenceQueue.poll()
        if (next == null) {
            currentlyPlayingUtteranceId = null
            releaseAudioFocus()
            return
        }

        currentlyPlayingUtteranceId = next.first
        val textToSpeak = next.second

        mainHandler.post {
            val engine = tts
            if (engine == null || !isInitialized) {
                currentlyPlayingUtteranceId = null
                return@post
            }

            requestAudioFocus()

            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, next.first)
            }

            engine.speak(textToSpeak, TextToSpeech.QUEUE_ADD, params, next.first)
        }
    }

    fun stopAndClearQueue() {
        sentenceQueue.clear()
        currentlyPlayingUtteranceId = null
        mainHandler.post {
            try {
                tts?.stop()
            } catch (_: Exception) {}
            releaseAudioFocus()
        }
    }

    fun testVoice(sampleText: String, onStart: () -> Unit, onDone: () -> Unit) {
        val engine = tts ?: return
        if (!isInitialized) return

        stopAndClearQueue()
        requestAudioFocus()

        val testId = "test_utt_${System.currentTimeMillis()}"
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, testId)
        }

        engine.speak(sampleText, TextToSpeech.QUEUE_FLUSH, params, testId)
    }

    private fun requestAudioFocus() {
        if (audioManager == null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (audioFocusRequest == null) {
                audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setOnAudioFocusChangeListener { /* Handle transient focus changes */ }
                    .build()
            }
            audioFocusRequest?.let { audioManager.requestAudioFocus(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
        }
    }

    private fun releaseAudioFocus() {
        if (audioManager == null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }

    fun shutdown() {
        mainHandler.post {
            destroyTts()
        }
    }

    private fun destroyTts() {
        stopAndClearQueue()
        try {
            tts?.shutdown()
            tts = null
        } catch (_: Exception) {}
        isInitialized = false
    }

    // UtteranceProgressListener Callbacks
    override fun onStart(utteranceId: String?) {
        val id = utteranceId ?: return
        mainHandler.post {
            listener.onUtteranceStart(id)
        }
    }

    override fun onDone(utteranceId: String?) {
        val id = utteranceId ?: return
        mainHandler.post {
            val isQueueEmpty = sentenceQueue.isEmpty()
            listener.onUtteranceDone(id, isQueueEmpty)
            playNextInQueue()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onError(utteranceId: String?) {
        val id = utteranceId ?: return
        mainHandler.post {
            listener.onUtteranceError(id, "TTS playback error")
            playNextInQueue()
        }
    }

    override fun onError(utteranceId: String?, errorCode: Int) {
        val id = utteranceId ?: return
        mainHandler.post {
            listener.onUtteranceError(id, "TTS error code: $errorCode")
            playNextInQueue()
        }
    }
}
