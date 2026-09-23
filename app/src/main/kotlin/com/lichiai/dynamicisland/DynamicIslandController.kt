package com.lichiai.dynamicisland

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Single Authoritative Controller for Lichi Dynamic Island.
 *
 * Responsibilities:
 * 1. Observes DynamicIslandConfig from DynamicIslandRepository.
 * 2. Starts/Stops DynamicIslandOverlayManager based on user configuration and overlay permissions.
 * 3. Bridges application lifecycle events without keeping Activity references alive.
 *
 * CRITICAL RULE: UI Controller ONLY.
 * NEVER accesses, allocates, or controls AudioRecord, Vosk, or SpeechRecognizer.
 */
class DynamicIslandController(
    private val context: Context,
    val repository: DynamicIslandRepository = DynamicIslandRepository(context.applicationContext)
) {
    companion object {
        private const val TAG = "DynamicIslandController"

        @Volatile
        private var instance: DynamicIslandController? = null

        fun getInstance(context: Context): DynamicIslandController {
            return instance ?: synchronized(this) {
                instance ?: DynamicIslandController(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val overlayManager = DynamicIslandOverlayManager(context.applicationContext, repository)

    private var configObservationJob: Job? = null

    val configState: StateFlow<DynamicIslandConfig> = repository.configState
    val attachmentState: StateFlow<OverlayAttachmentState> = overlayManager.attachmentState

    fun start() {
        if (configObservationJob != null) return

        Log.d(TAG, "Starting DynamicIslandController")
        configObservationJob = scope.launch {
            repository.configFlow.collect { config ->
                handleConfigChange(config)
            }
        }
    }

    fun stop() {
        Log.d(TAG, "Stopping DynamicIslandController")
        configObservationJob?.cancel()
        configObservationJob = null
        overlayManager.detach()
    }

    fun checkAndRefreshOverlay() {
        val config = repository.configState.value
        handleConfigChange(config)
    }

    private fun handleConfigChange(config: DynamicIslandConfig) {
        if (!config.enabled) {
            if (overlayManager.attachmentState.value != OverlayAttachmentState.NOT_ATTACHED) {
                overlayManager.detach()
            }
            return
        }

        if (overlayManager.isOverlayPermitted()) {
            if (overlayManager.attachmentState.value == OverlayAttachmentState.NOT_ATTACHED) {
                overlayManager.attach()
            }
        } else {
            Log.w(TAG, "Overlay enabled in config but SYSTEM_ALERT_WINDOW permission missing")
            if (overlayManager.attachmentState.value != OverlayAttachmentState.NOT_ATTACHED) {
                overlayManager.detach()
            }
        }
    }

    fun isPermissionGranted(): Boolean = overlayManager.isOverlayPermitted()
}
