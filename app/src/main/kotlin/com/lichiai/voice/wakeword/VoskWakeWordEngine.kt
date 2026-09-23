package com.lichiai.voice.wakeword

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import com.lichiai.voice.arbitration.MicrophoneArbitrator
import com.lichiai.voice.arbitration.MicrophoneOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.vosk.Model
import org.vosk.Recognizer
import java.util.concurrent.atomic.AtomicBoolean

class VoskWakeWordEngine(
    private val context: Context,
    private val modelProvider: suspend () -> Model?
) {
    companion object {
        private const val TAG = "VoskWakeWordEngine"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val engineMutex = Mutex()

    private val _engineState = MutableStateFlow(WakeWordEngineState.UNINITIALIZED)
    val engineState: StateFlow<WakeWordEngineState> = _engineState.asStateFlow()

    private val _events = MutableSharedFlow<WakeWordEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<WakeWordEvent> = _events.asSharedFlow()

    private var currentSettings = WakeWordSettings()
    private var recognizer: Recognizer? = null
    private var audioRecord: AudioRecord? = null
    private var audioJob: Job? = null
    private var retryJob: Job? = null

    private val isRunning = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)
    private val isCooldown = AtomicBoolean(false)
    private var lastWakeTimestamp = 0L
    private var consecutiveRetryCount = 0

    fun updateSettings(settings: WakeWordSettings) {
        currentSettings = settings
        if (isRunning.get()) {
            engineScope.launch {
                updateGrammar()
            }
        }
    }

    private suspend fun updateGrammar() {
        engineMutex.withLock {
            val rec = recognizer ?: return@withLock
            try {
                val grammarJson = WakeWordPhraseMatcher.buildGrammarJson(currentSettings.phrases)
                rec.setGrammar(grammarJson)
                Log.d(TAG, "Grammar updated with ${currentSettings.phrases.size} phrases")
            } catch (e: Exception) {
                Log.w(TAG, "Grammar update skipped: ${e.message}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun startListening(settings: WakeWordSettings? = null) {
        if (settings != null) {
            currentSettings = settings
        }

        if (!currentSettings.enabled) {
            stopListening()
            return
        }

        if (ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.RECORD_AUDIO
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Cannot start listening: RECORD_AUDIO permission not granted")
            _engineState.value = WakeWordEngineState.MIC_UNAVAILABLE
            return
        }

        engineMutex.withLock {
            if (isRunning.get()) {
                Log.d(TAG, "Already running, skipping startListening")
                return@withLock
            }

            // Acquire microphone ownership from central arbitrator
            Log.d("LICHI_VOICE", "[VOSK] START")
            val granted = MicrophoneArbitrator.requestAcquisition(MicrophoneOwner.WAKE_WORD, "WakeWord engine startListening")
            if (!granted) {
                val owner = MicrophoneArbitrator.currentOwner.value
                Log.w(TAG, "Microphone arbitration denied for WAKE_WORD (current owner: $owner)")
                _engineState.value = if (owner == MicrophoneOwner.VOICE_STT || owner == MicrophoneOwner.OTHER_STT) {
                    WakeWordEngineState.CONVERSATION_ACTIVE
                } else {
                    WakeWordEngineState.MIC_UNAVAILABLE
                }
                return@withLock
            }

            _engineState.value = WakeWordEngineState.LOADING_MODEL
            val model = modelProvider()
            if (model == null) {
                Log.w(TAG, "Vosk acoustic model not initialized or found")
                _engineState.value = WakeWordEngineState.READY
                _events.emit(WakeWordEvent.Error("Vosk acoustic model not initialized or found"))
                MicrophoneArbitrator.release(MicrophoneOwner.WAKE_WORD, "Model missing")
                return@withLock
            }

            try {
                val grammarJson = WakeWordPhraseMatcher.buildGrammarJson(currentSettings.phrases)
                recognizer = Recognizer(model, SAMPLE_RATE.toFloat(), grammarJson)
            } catch (e: Exception) {
                try {
                    recognizer = Recognizer(model, SAMPLE_RATE.toFloat())
                } catch (e2: Exception) {
                    Log.e(TAG, "Failed to create recognizer", e2)
                    _engineState.value = WakeWordEngineState.ERROR
                    _events.emit(WakeWordEvent.Error("Failed to create recognizer: ${e2.message}"))
                    MicrophoneArbitrator.release(MicrophoneOwner.WAKE_WORD, "Recognizer creation failed")
                    return@withLock
                }
            }

            val minBufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            val bufferSize = if (minBufSize > 0) minBufSize.coerceAtLeast(SAMPLE_RATE * 2) else SAMPLE_RATE * 2

            // Clean up any stale audio record
            releaseAudioRecordOnly()

            try {
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize
                )
            } catch (e: Exception) {
                try {
                    audioRecord = AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        SAMPLE_RATE,
                        CHANNEL_CONFIG,
                        AUDIO_FORMAT,
                        bufferSize
                    )
                } catch (e2: Exception) {
                    Log.e(TAG, "Failed to instantiate AudioRecord", e2)
                    handleAudioRecordFailed("Failed to instantiate AudioRecord")
                    return@withLock
                }
            }

            val record = audioRecord
            if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
                Log.w(TAG, "AudioRecord state is NOT INITIALIZED (${record?.state})")
                handleAudioRecordFailed("AudioRecord not initialized")
                return@withLock
            }

            try {
                record.startRecording()
                if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    Log.w(TAG, "AudioRecord failed to enter RECORDSTATE_RECORDING (${record.recordingState})")
                    handleAudioRecordFailed("AudioRecord not recording")
                    return@withLock
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception during startRecording", e)
                handleAudioRecordFailed("Exception during startRecording: ${e.message}")
                return@withLock
            }

            isRunning.set(true)
            isCooldown.set(false)
            isPaused.set(false)
            _engineState.value = WakeWordEngineState.LISTENING
            _events.emit(WakeWordEvent.Started)
            Log.d(TAG, "WakeWord engine is now LISTENING")
            Log.d("LICHI_VOICE", "[VOSK] STARTED")

            // Launch audio capture loop
            audioJob?.cancel()
            audioJob = engineScope.launch(Dispatchers.IO) {
                runAudioCaptureLoop(record)
            }
        }
    }

    private suspend fun handleAudioRecordFailed(reason: String) {
        _engineState.value = WakeWordEngineState.MIC_UNAVAILABLE
        _events.emit(WakeWordEvent.MicrophoneUnavailable)
        releaseAudioResources()
        MicrophoneArbitrator.release(MicrophoneOwner.WAKE_WORD, reason)
        scheduleRetry()
    }

    private suspend fun runAudioCaptureLoop(record: AudioRecord) {
        val buffer = ShortArray(1024)
        var hasMarkedActive = false

        while (currentCoroutineContext().isActive && isRunning.get() && !isPaused.get()) {
            if (!MicrophoneArbitrator.canWakeWordRecord()) {
                Log.d(TAG, "Yielding microphone: arbitrator says cannot record")
                pauseAndYield()
                break
            }

            val readShorts = try {
                record.read(buffer, 0, buffer.size)
            } catch (e: Exception) {
                Log.w(TAG, "AudioRecord read exception", e)
                AudioRecord.ERROR_INVALID_OPERATION
            }

            if (readShorts > 0) {
                consecutiveRetryCount = 0
                if (!hasMarkedActive) {
                    hasMarkedActive = true
                    MicrophoneArbitrator.markActive(MicrophoneOwner.WAKE_WORD)
                }

                val rec = recognizer ?: break
                val isFinal = try {
                    rec.acceptWaveForm(buffer, readShorts)
                } catch (_: Exception) {
                    false
                }

                val jsonResult = try {
                    if (isFinal) rec.result else rec.partialResult
                } catch (_: Exception) {
                    ""
                }

                val recognizedText = WakeWordPhraseMatcher.extractTextFromJson(jsonResult)
                if (recognizedText.isNotBlank()) {
                    handleRecognizedText(recognizedText)
                }
            } else if (readShorts == AudioRecord.ERROR_INVALID_OPERATION || readShorts == AudioRecord.ERROR_BAD_VALUE) {
                if (!isRunning.get() || isPaused.get()) {
                    // Clean exit initiated by stopListening or pause
                    break
                }
                Log.w(TAG, "AudioRecord returned read error: $readShorts")
                handleMicInterruption()
                break
            }
        }
    }

    private suspend fun handleRecognizedText(rawText: String) {
        if (isCooldown.get()) return

        val matched = WakeWordPhraseMatcher.findMatchingPhrase(
            recognizedRawText = rawText,
            activePhrases = currentSettings.phrases,
            sensitivity = currentSettings.sensitivity
        )

        if (matched != null) {
            val now = System.currentTimeMillis()
            if (now - lastWakeTimestamp < currentSettings.debounceMs) {
                Log.d(TAG, "Wake word match debounced: ${matched.phrase}")
                return
            }

            lastWakeTimestamp = now
            isCooldown.set(true)
            _engineState.value = WakeWordEngineState.WAKE_DETECTED
            Log.d(TAG, "WAKE WORD DETECTED: \"${matched.phrase}\" -> Stopping wake capture and releasing mic")

            // Stop wake detector audio capture immediately so Voice Conversation can own the mic
            stopListeningInternal()

            _events.emit(WakeWordEvent.Detected(matched.phrase, now))
        }
    }

    private suspend fun pauseAndYield() {
        stopListeningInternal()
        val owner = MicrophoneArbitrator.currentOwner.value
        _engineState.value = if (owner == MicrophoneOwner.VOICE_STT || owner == MicrophoneOwner.OTHER_STT) {
            WakeWordEngineState.CONVERSATION_ACTIVE
        } else {
            WakeWordEngineState.PAUSED
        }
    }

    private suspend fun handleMicInterruption() {
        Log.w(TAG, "AudioRecord read error encountered, resetting audio capture")
        stopListeningInternal()
        _engineState.value = WakeWordEngineState.MIC_UNAVAILABLE
        _events.emit(WakeWordEvent.MicrophoneUnavailable)
        scheduleRetry()
    }

    private fun scheduleRetry() {
        retryJob?.cancel()
        consecutiveRetryCount++
        if (consecutiveRetryCount > 5) {
            Log.w(TAG, "Max retry attempts reached, entering MIC_UNAVAILABLE standby")
            _engineState.value = WakeWordEngineState.MIC_UNAVAILABLE
            return
        }

        _engineState.value = WakeWordEngineState.RECOVERING
        retryJob = engineScope.launch {
            val backoffMs = (500L * consecutiveRetryCount).coerceAtMost(5000L)
            Log.d(TAG, "Scheduling retry #$consecutiveRetryCount in ${backoffMs}ms")
            delay(backoffMs)

            if (currentSettings.enabled && !isRunning.get() && !isPaused.get() &&
                !MicrophoneArbitrator.isVoiceSessionActive() && MicrophoneArbitrator.canWakeWordRecord()) {
                startListening()
            }
        }
    }

    fun resetRetryCounter() {
        consecutiveRetryCount = 0
        retryJob?.cancel()
    }

    fun yieldMicrophone() {
        Log.d("LICHI_VOICE", "[VOSK] YIELDING")
        isPaused.set(true)
        stopListeningInternal()
        _engineState.value = WakeWordEngineState.PAUSED
    }

    suspend fun pauseListening() {
        engineMutex.withLock {
            isPaused.set(true)
            stopListeningInternal()
            _engineState.value = WakeWordEngineState.PAUSED
            _events.emit(WakeWordEvent.Paused)
            Log.d(TAG, "WakeWord engine PAUSED")
        }
    }

    suspend fun resumeListening() {
        engineMutex.withLock {
            isPaused.set(false)
            resetRetryCounter()
            if (currentSettings.enabled && !MicrophoneArbitrator.isVoiceSessionActive()) {
                startListening()
                _events.emit(WakeWordEvent.Resumed)
                Log.d(TAG, "WakeWord engine RESUMED")
            }
        }
    }

    suspend fun stopListening() {
        engineMutex.withLock {
            Log.d("LICHI_VOICE", "[VOSK] STOP")
            isPaused.set(false)
            stopListeningInternal()
            _engineState.value = WakeWordEngineState.STOPPED
            _events.emit(WakeWordEvent.Stopped)
            Log.d(TAG, "WakeWord engine STOPPED")
        }
    }

    private fun stopListeningInternal() {
        isRunning.set(false)
        audioJob?.cancel()
        audioJob = null
        releaseAudioResources()
        MicrophoneArbitrator.releaseBlocking(MicrophoneOwner.WAKE_WORD, "stopListeningInternal")
        Log.d("LICHI_VOICE", "[VOSK] RELEASED")
    }

    private fun releaseAudioRecordOnly() {
        try {
            audioRecord?.let {
                if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    it.stop()
                }
                it.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing audioRecord", e)
        }
        audioRecord = null
    }

    private fun releaseAudioResources() {
        releaseAudioRecordOnly()
        try {
            recognizer?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing recognizer", e)
        }
        recognizer = null
    }

    fun release() {
        isRunning.set(false)
        audioJob?.cancel()
        retryJob?.cancel()
        releaseAudioResources()
    }
}
