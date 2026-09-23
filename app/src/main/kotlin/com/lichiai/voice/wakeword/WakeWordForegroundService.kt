package com.lichiai.voice.wakeword

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import android.util.Log
import com.lichiai.MainActivity
import com.lichiai.R
import com.lichiai.data.VoiceSettingsRepository
import com.lichiai.voice.arbitration.MicrophoneArbitrator
import com.lichiai.voice.arbitration.MicrophoneOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class WakeWordForegroundService : Service() {

    companion object {
        private const val TAG = "WakeWordService"
        const val CHANNEL_ID = "lichi_wake_word_channel"
        const val NOTIFICATION_ID = 2001

        const val ACTION_START = "com.lichiai.wakeword.START"
        const val ACTION_STOP = "com.lichiai.wakeword.STOP"
        const val ACTION_PAUSE = "com.lichiai.wakeword.PAUSE"
        const val ACTION_RESUME = "com.lichiai.wakeword.RESUME"
        const val ACTION_DISABLE = "com.lichiai.wakeword.DISABLE"

        // Companion flows shared across the application
        private val _serviceState = MutableStateFlow(WakeWordEngineState.DISABLED)
        val serviceState: StateFlow<WakeWordEngineState> = _serviceState.asStateFlow()

        private val _serviceEvents = MutableSharedFlow<WakeWordEvent>(extraBufferCapacity = 16)
        val serviceEvents: SharedFlow<WakeWordEvent> = _serviceEvents.asSharedFlow()

        var isServiceRunning: Boolean = false
            private set

        fun start(context: Context) {
            try {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    return
                }
                val intent = Intent(context, WakeWordForegroundService::class.java).apply {
                    action = ACTION_START
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Throwable) {}
        }

        fun stop(context: Context) {
            try {
                val intent = Intent(context, WakeWordForegroundService::class.java).apply {
                    action = ACTION_STOP
                }
                context.startService(intent)
            } catch (_: Throwable) {}
        }

        fun pause(context: Context) {
            try {
                val intent = Intent(context, WakeWordForegroundService::class.java).apply {
                    action = ACTION_PAUSE
                }
                context.startService(intent)
            } catch (_: Throwable) {}
        }

        fun resume(context: Context) {
            try {
                val intent = Intent(context, WakeWordForegroundService::class.java).apply {
                    action = ACTION_RESUME
                }
                context.startService(intent)
            } catch (_: Throwable) {}
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var engine: VoskWakeWordEngine? = null
    private var voiceSettingsRepo: VoiceSettingsRepository? = null

    private var settingsJob: Job? = null
    private var micOwnerJob: Job? = null
    private var engineEventsJob: Job? = null
    private var engineStateJob: Job? = null

    private var currentSettings = WakeWordSettings()
    private var isUserPaused = false

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        createNotificationChannel()

        // Ensure Dynamic Island controller is active for system-wide persistence
        try {
            com.lichiai.dynamicisland.DynamicIslandController.getInstance(applicationContext).start()
        } catch (_: Throwable) {}

        // Ensure Incoming Call Conversation Manager is active in background
        try {
            com.lichiai.calling.conversation.IncomingCallConversationManager.getInstance(applicationContext).start()
        } catch (_: Throwable) {}

        voiceSettingsRepo = VoiceSettingsRepository(applicationContext)

        engine = VoskWakeWordEngine(applicationContext) {
            VoskModelManager.getOrInitModel(applicationContext)
        }

        MicrophoneArbitrator.registerWakeWordYieldHandler {
            Log.d("LICHI_VOICE", "[VOSK] Yield handler triggered by MicrophoneArbitrator")
            engine?.yieldMicrophone()
        }

        // Observe engine state and forward
        engineStateJob = serviceScope.launch {
            engine?.engineState?.collect { state ->
                _serviceState.value = state
                updateNotification()

                when (state) {
                    WakeWordEngineState.LISTENING -> {
                        if (!MicrophoneOwnershipCoordinator.isVoiceSessionActive()) {
                            com.lichiai.dynamicisland.LichiAssistantStateHub.onWakeWordListening()
                        }
                    }
                    WakeWordEngineState.MIC_UNAVAILABLE -> {
                        com.lichiai.dynamicisland.LichiAssistantStateHub.onMicUnavailable("Microphone unavailable")
                    }
                    WakeWordEngineState.STOPPED, WakeWordEngineState.DISABLED -> {
                        if (com.lichiai.dynamicisland.LichiAssistantStateHub.assistantState.value.uiState == com.lichiai.dynamicisland.LichiUiState.WAKE_LISTENING) {
                            com.lichiai.dynamicisland.LichiAssistantStateHub.resetToIdle()
                        }
                    }
                    else -> {}
                }
            }
        }

        // Observe engine events
        engineEventsJob = serviceScope.launch {
            engine?.events?.collect { event ->
                _serviceEvents.emit(event)
                if (event is WakeWordEvent.Detected) {
                    com.lichiai.dynamicisland.LichiAssistantStateHub.onWakeWordDetected(event.phrase)
                    handleWakeWordDetected(event.phrase)
                }
            }
        }

        // Observe wake word settings
        settingsJob = serviceScope.launch {
            voiceSettingsRepo?.wakeWordSettings?.collect { settings ->
                currentSettings = settings
                engine?.updateSettings(settings)
                if (!settings.enabled) {
                    stopServiceInternal()
                } else if (!isUserPaused && !MicrophoneOwnershipCoordinator.isVoiceSessionActive()) {
                    engine?.startListening(settings)
                }
                updateNotification()
            }
        }

        // Observe microphone ownership changes
        micOwnerJob = serviceScope.launch {
            MicrophoneOwnershipCoordinator.currentOwner.collect { owner ->
                when (owner) {
                    MicrophoneOwner.VOICE_STT, MicrophoneOwner.OTHER_STT -> {
                        engine?.stopListening()
                        updateNotification()
                    }
                    MicrophoneOwner.PHONE_CALL, MicrophoneOwner.EXTERNAL_APP -> {
                        engine?.stopListening()
                        updateNotification()
                    }
                    MicrophoneOwner.NONE -> {
                        if (currentSettings.enabled && !isUserPaused && hasAudioPermission()) {
                            engine?.resetRetryCounter()
                            engine?.startListening(currentSettings)
                            updateNotification()
                        }
                    }
                    MicrophoneOwner.WAKE_WORD -> {
                        // Current owner is already wake word
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android 12: Satisfy startForegroundService contract immediately
        startForegroundSafely()

        when (intent?.action) {
            ACTION_STOP -> {
                stopServiceInternal()
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> {
                isUserPaused = true
                serviceScope.launch {
                    engine?.pauseListening()
                    updateNotification()
                }
                return START_STICKY
            }
            ACTION_RESUME -> {
                isUserPaused = false
                serviceScope.launch {
                    engine?.resumeListening()
                    updateNotification()
                }
                return START_STICKY
            }
            ACTION_DISABLE -> {
                serviceScope.launch {
                    voiceSettingsRepo?.updateWakeWordEnabled(false)
                    stopServiceInternal()
                }
                return START_NOT_STICKY
            }
            else -> {
                if (!hasAudioPermission()) {
                    _serviceState.value = WakeWordEngineState.MIC_UNAVAILABLE
                    updateNotification("Microphone permission required")
                    return START_STICKY
                }
                try {
                    if (!isUserPaused && !MicrophoneOwnershipCoordinator.isVoiceSessionActive()) {
                        serviceScope.launch {
                            engine?.startListening(currentSettings)
                        }
                    }
                    return START_STICKY
                } catch (_: Throwable) {
                    return START_STICKY
                }
            }
        }
    }

    private fun handleWakeWordDetected(phrase: String) {
        // Request ownership for voice session immediately
        MicrophoneOwnershipCoordinator.requestForVoiceSession()
        updateNotification("Triggered: \"$phrase\" • Launching Voice Mode")

        // Bring MainActivity to foreground and trigger Voice Mode
        try {
            val launchIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(MainActivity.EXTRA_START_VOICE_MODE, true)
                putExtra(MainActivity.EXTRA_TRIGGERED_PHRASE, phrase)
            }
            startActivity(launchIntent)
        } catch (_: Throwable) {}
    }

    private fun startForegroundSafely() {
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                } else {
                    0
                }
                startForeground(NOTIFICATION_ID, notification, fgsType)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException starting foreground service with type microphone, falling back", e)
            try {
                startForeground(NOTIFICATION_ID, notification)
            } catch (_: Throwable) {}
        } catch (t: Throwable) {
            Log.e(TAG, "Failed startForeground", t)
        }
    }

    private fun updateNotification(customStatus: String? = null) {
        if (!isServiceRunning) return
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIFICATION_ID, buildNotification(customStatus))
        } catch (_: Throwable) {}
    }

    private fun buildNotification(customStatus: String? = null): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingOpen = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pauseIntent = Intent(this, WakeWordForegroundService::class.java).apply { action = ACTION_PAUSE }
        val pendingPause = PendingIntent.getService(
            this,
            1,
            pauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val resumeIntent = Intent(this, WakeWordForegroundService::class.java).apply { action = ACTION_RESUME }
        val pendingResume = PendingIntent.getService(
            this,
            2,
            resumeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, WakeWordForegroundService::class.java).apply { action = ACTION_STOP }
        val pendingStop = PendingIntent.getService(
            this,
            3,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val state = _serviceState.value
        val statusText = customStatus ?: when {
            isUserPaused -> "Listening paused"
            state == WakeWordEngineState.WAKE_DETECTED -> "Wake phrase detected! Opening Voice Mode..."
            state == WakeWordEngineState.CONVERSATION_ACTIVE -> "Voice conversation active • Mic in use"
            state == WakeWordEngineState.MIC_UNAVAILABLE -> "Microphone unavailable / in use"
            state == WakeWordEngineState.LOADING_MODEL -> "Loading offline acoustic model..."
            state == WakeWordEngineState.RECOVERING -> "Reconnecting to audio input..."
            state == WakeWordEngineState.LISTENING -> {
                val activeCount = currentSettings.phrases.count { it.isEnabled }
                "Listening for wake phrases ($activeCount active: Hey Lichi, Hi Lichi...)"
            }
            else -> "Standby"
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("LICHI AI Voice Trigger")
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingOpen)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)

        // Action buttons
        if (isUserPaused || state == WakeWordEngineState.PAUSED) {
            builder.addAction(0, "Resume", pendingResume)
        } else {
            builder.addAction(0, "Pause", pendingPause)
        }
        builder.addAction(0, "Stop", pendingStop)

        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "LICHI AI Wake Word Listener",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Monitors for wake word activation phrases"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun stopServiceInternal() {
        isServiceRunning = false
        _serviceState.value = WakeWordEngineState.STOPPED
        try {
            settingsJob?.cancel()
            micOwnerJob?.cancel()
            engineEventsJob?.cancel()
            engineStateJob?.cancel()
            engine?.release()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } catch (_: Throwable) {}
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // If wake word is enabled and background listening is allowed, keep the foreground service alive
        if (currentSettings.enabled && currentSettings.backgroundListening && hasAudioPermission()) {
            // Service continues running as foreground service
        } else {
            stopServiceInternal()
        }
    }

    override fun onDestroy() {
        isServiceRunning = false
        _serviceState.value = WakeWordEngineState.STOPPED
        try {
            MicrophoneArbitrator.unregisterWakeWordYieldHandler()
            settingsJob?.cancel()
            micOwnerJob?.cancel()
            engineEventsJob?.cancel()
            engineStateJob?.cancel()
            engine?.release()
            MicrophoneOwnershipCoordinator.releaseFromWakeWord()
        } catch (_: Throwable) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
