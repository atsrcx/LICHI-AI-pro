package com.lichiai.voice.wakeword

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.lichiai.data.VoiceSettingsRepository
import com.lichiai.voice.VoiceConversationOrchestrator
import com.lichiai.voice.conversation.VoiceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

class WakeWordManager(
    private val context: Context,
    private val voiceSettingsRepository: VoiceSettingsRepository,
    private val voiceOrchestrator: VoiceConversationOrchestrator
) {
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val engineState: StateFlow<WakeWordEngineState> = WakeWordForegroundService.serviceState
    val modelState: StateFlow<VoskModelState> = VoskModelManager.modelState

    private val _wakeEvents = MutableSharedFlow<WakeWordEvent>(extraBufferCapacity = 16)
    val wakeEvents: SharedFlow<WakeWordEvent> = _wakeEvents.asSharedFlow()

    private var currentSettings = WakeWordSettings()
    private var isAppInForeground = true
    private var settingsJob: Job? = null
    private var orchestratorJob: Job? = null
    private var serviceEventsJob: Job? = null

    private fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    init {
        VoskModelManager.checkModelState(context)

        // Collect wake word settings and control the foreground service
        settingsJob = managerScope.launch {
            voiceSettingsRepository.wakeWordSettings.collect { settings ->
                currentSettings = settings
                if (settings.enabled && hasAudioPermission()) {
                    WakeWordForegroundService.start(context)
                } else {
                    WakeWordForegroundService.stop(context)
                }
            }
        }

        // Coordinate with VoiceConversationOrchestrator
        orchestratorJob = managerScope.launch {
            voiceOrchestrator.sessionState.collect { sessionState ->
                if (sessionState.state != VoiceState.IDLE) {
                    MicrophoneOwnershipCoordinator.requestForVoiceSession()
                } else {
                    MicrophoneOwnershipCoordinator.releaseFromVoiceSession()
                }
            }
        }

        // Forward service events to UI
        serviceEventsJob = managerScope.launch {
            WakeWordForegroundService.serviceEvents.collect { event ->
                _wakeEvents.emit(event)
            }
        }
    }

    fun onAppForegroundChanged(inForeground: Boolean) {
        isAppInForeground = inForeground
        if (!inForeground) {
            if (!currentSettings.backgroundListening) {
                // If background listening is disabled by user, pause the service
                WakeWordForegroundService.pause(context)
            }
        } else {
            if (currentSettings.enabled && hasAudioPermission()) {
                if (!WakeWordForegroundService.isServiceRunning) {
                    WakeWordForegroundService.start(context)
                } else {
                    WakeWordForegroundService.resume(context)
                }
            }
        }
    }

    fun restartListening() {
        if (currentSettings.enabled && hasAudioPermission()) {
            WakeWordForegroundService.start(context)
            WakeWordForegroundService.resume(context)
        }
    }

    fun pauseListening() {
        WakeWordForegroundService.pause(context)
    }

    fun resumeListening() {
        WakeWordForegroundService.resume(context)
    }

    fun downloadModel(onStatus: (String) -> Unit = {}) {
        managerScope.launch {
            val success = VoskModelManager.downloadAndInstallModel(context, onStatus)
            if (success && currentSettings.enabled && hasAudioPermission()) {
                restartListening()
            }
        }
    }

    fun deleteModel() {
        managerScope.launch {
            WakeWordForegroundService.stop(context)
            VoskModelManager.deleteModel(context)
        }
    }

    fun stopListening() {
        WakeWordForegroundService.stop(context)
    }

    fun release() {
        try {
            settingsJob?.cancel()
            orchestratorJob?.cancel()
            serviceEventsJob?.cancel()
        } catch (_: Throwable) {}
    }
}
