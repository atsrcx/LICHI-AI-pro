package com.lichiai.agentvision.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.lichiai.agentvision.model.VisualPhase
import com.lichiai.agentvision.model.VisualSource
import com.lichiai.agentvision.renderer.AgentVisionOverlay
import com.lichiai.agentvision.settings.AgentVisionSettingsRepository
import com.lichiai.agentvision.telemetry.AgentVisionTelemetryHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

private class VisionOverlayLifecycleOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    init {
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    fun destroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
    }
}

/**
 * System-level Overlay Manager for Agent Vision.
 *
 * Provides full-screen, click-through (FLAG_NOT_TOUCHABLE) visual overlay
 * across Android apps when Autonomous Agent V2 is executing actions.
 *
 * OBSERVER & VISUAL ONLY:
 * - Never consumes touches.
 * - Never intercepts user input.
 * - Purely transparent visual canvas.
 */
class AgentVisionOverlayManager private constructor(
    private val context: Context
) {
    companion object {
        private const val TAG = "AgentVisionOverlay"

        @Volatile
        private var instance: AgentVisionOverlayManager? = null

        fun getInstance(context: Context): AgentVisionOverlayManager {
            return instance ?: synchronized(this) {
                instance ?: AgentVisionOverlayManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val repository = AgentVisionSettingsRepository.getInstance(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var overlayView: ComposeView? = null
    private var lifecycleOwner: VisionOverlayLifecycleOwner? = null
    private var isAttached = false
    private var autoDismissJob: Job? = null
    private var observationJob: Job? = null

    fun startObserving() {
        if (observationJob != null) return
        observationJob = scope.launch {
            combine(
                repository.settings,
                AgentVisionTelemetryHub.activeSessionState
            ) { settings, sessionState ->
                Pair(settings, sessionState)
            }.collect { (settings, sessionState) ->
                if (!settings.enabled) {
                    detach()
                    return@collect
                }

                // Attach overlay if there is an active session running on ANDROID_AGENT
                val isActive = sessionState != null &&
                        sessionState.source == VisualSource.ANDROID_AGENT &&
                        !sessionState.isCompleted &&
                        !sessionState.isFailed

                if (isActive) {
                    autoDismissJob?.cancel()
                    attach()
                } else if (sessionState != null && (sessionState.isCompleted || sessionState.isFailed)) {
                    // Delay auto-detach by 2.5 seconds to let user view completion state
                    autoDismissJob?.cancel()
                    autoDismissJob = scope.launch {
                        delay(2500)
                        if (!isSessionRunning()) {
                            detach()
                        }
                    }
                }
            }
        }
    }

    private fun isSessionRunning(): Boolean {
        val state = AgentVisionTelemetryHub.activeSessionState.value ?: return false
        return !state.isCompleted && !state.isFailed
    }

    fun isOverlayPermitted(): Boolean {
        return Settings.canDrawOverlays(context)
    }

    @Synchronized
    fun attach() {
        if (isAttached) return
        if (!isOverlayPermitted()) {
            Log.w(TAG, "Cannot attach Agent Vision overlay: Overlay permission not granted")
            return
        }

        try {
            val flags = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                flags,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 0
            }

            val newOwner = VisionOverlayLifecycleOwner()
            lifecycleOwner = newOwner

            val view = ComposeView(context).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
                setViewTreeLifecycleOwner(newOwner)
                setViewTreeViewModelStoreOwner(newOwner)
                setViewTreeSavedStateRegistryOwner(newOwner)

                setContent {
                    val settings by repository.settings.collectAsState(initial = com.lichiai.agentvision.model.AgentVisionSettings())
                    val activeSession by AgentVisionTelemetryHub.activeSessionState.collectAsState()

                    AgentVisionOverlay(
                        session = activeSession,
                        config = settings.config,
                        showTimelinePanel = settings.config.actionTimeline
                    )
                }
            }

            windowManager.addView(view, params)
            overlayView = view
            isAttached = true
            Log.i(TAG, "Agent Vision system overlay successfully attached")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to attach Agent Vision system overlay", e)
            cleanup()
        }
    }

    @Synchronized
    fun detach() {
        if (!isAttached && overlayView == null) return
        try {
            overlayView?.let {
                windowManager.removeView(it)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Exception while removing overlayView: ${e.message}")
        } finally {
            cleanup()
        }
    }

    private fun cleanup() {
        overlayView = null
        lifecycleOwner?.destroy()
        lifecycleOwner = null
        isAttached = false
    }
}
