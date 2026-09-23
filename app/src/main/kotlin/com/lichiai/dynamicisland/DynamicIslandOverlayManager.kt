package com.lichiai.dynamicisland

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import com.lichiai.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

enum class OverlayAttachmentState {
    NOT_ATTACHED,
    ATTACHING,
    ATTACHED,
    UPDATING,
    DETACHING
}

private class OverlayLifecycleOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
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
 * Authoritative WindowManager Overlay Manager.
 * Single Instance, Idempotent, Safe from duplicates and crashes.
 *
 * CRITICAL RULE: This manager manages UI OVERLAY ONLY.
 * It NEVER acquires, controls, or touches any microphone hardware.
 */
class DynamicIslandOverlayManager(
    private val context: Context,
    private val repository: DynamicIslandRepository
) {
    companion object {
        private const val TAG = "DynamicIslandOverlay"
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _attachmentState = MutableStateFlow(OverlayAttachmentState.NOT_ATTACHED)
    val attachmentState: StateFlow<OverlayAttachmentState> = _attachmentState.asStateFlow()

    private val callActionExecutor by lazy {
        val permManager = com.lichiai.calling.permission.CallPermissionManager(context)
        val aliasRepo = com.lichiai.calling.contacts.ContactAliasesRepository(context)
        val contactsRepo = com.lichiai.calling.contacts.ContactRepository(context, permManager, aliasRepo)
        val diagRepo = com.lichiai.calling.engine.CallDiagnosticsRepository()
        val engine = com.lichiai.calling.engine.UniversalCallEngine(context, permManager, contactsRepo, diagRepo)
        val monitor = com.lichiai.calling.state.CallStateMonitor.getInstance(context, contactsRepo)
        com.lichiai.calling.action.CallActionExecutor(
            context = context,
            universalCallEngine = engine,
            callPermissionManager = permManager,
            callStateMonitor = monitor
        )
    }

    private var overlayView: View? = null
    private var lifecycleOwner: OverlayLifecycleOwner? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var configObserverJob: kotlinx.coroutines.Job? = null

    private var isExpandedState by mutableStateOf(false)

    // Touch & Drag state
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var initialParamX = 0
    private var initialParamY = 0
    private var isDragging = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    enum class CutoutPosition {
        CENTER,
        LEFT,
        RIGHT,
        NONE
    }

    private fun getStatusBarHeight(): Int {
        val resourceId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) {
            context.resources.getDimensionPixelSize(resourceId)
        } else {
            (24 * context.resources.displayMetrics.density).toInt()
        }
    }

    private fun detectCutoutPosition(screenWidth: Int): CutoutPosition {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    context.display
                } else {
                    @Suppress("DEPRECATION")
                    windowManager.defaultDisplay
                }
                val cutout = display?.cutout
                if (cutout != null) {
                    val rects = cutout.boundingRects
                    if (rects.isNotEmpty()) {
                        val firstRect = rects[0]
                        val centerX = firstRect.centerX()
                        return when {
                            centerX < screenWidth * 0.35f -> CutoutPosition.LEFT
                            centerX > screenWidth * 0.65f -> CutoutPosition.RIGHT
                            else -> CutoutPosition.CENTER
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Error detecting display cutout: ${e.message}")
            }
        }
        return CutoutPosition.CENTER
    }

    private fun calculateCoordinates(
        config: DynamicIslandConfig,
        islandWidthPx: Int,
        islandHeightPx: Int,
        screenWidth: Int,
        screenHeight: Int,
        density: Float
    ): Pair<Int, Int> {
        val statusBarHeight = getStatusBarHeight()
        val xOffsetPx = (config.xOffsetDp * density).toInt()
        val yOffsetPx = (config.yOffsetDp * density).toInt()

        return when (config.positionMode) {
            PositionMode.AUTO_DETECT -> {
                val cutoutPos = detectCutoutPosition(screenWidth)
                val defaultY = ((statusBarHeight - islandHeightPx) / 2).coerceAtLeast(0) + yOffsetPx
                val defaultX = when (cutoutPos) {
                    CutoutPosition.LEFT -> (16 * density).toInt() + xOffsetPx
                    CutoutPosition.RIGHT -> screenWidth - islandWidthPx - (16 * density).toInt() + xOffsetPx
                    CutoutPosition.CENTER, CutoutPosition.NONE -> ((screenWidth - islandWidthPx) / 2) + xOffsetPx
                }
                defaultX.coerceIn(0, (screenWidth - islandWidthPx).coerceAtLeast(0)) to
                        defaultY.coerceIn(0, (screenHeight - islandHeightPx).coerceAtLeast(0))
            }
            PositionMode.TOP_CENTER -> {
                val startX = ((screenWidth - islandWidthPx) / 2) + xOffsetPx
                val startY = yOffsetPx
                startX.coerceIn(0, (screenWidth - islandWidthPx).coerceAtLeast(0)) to
                        startY.coerceIn(0, (screenHeight - islandHeightPx).coerceAtLeast(0))
            }
            PositionMode.TOP_LEFT -> {
                val startX = (16 * density).toInt() + xOffsetPx
                val startY = yOffsetPx
                startX.coerceIn(0, (screenWidth - islandWidthPx).coerceAtLeast(0)) to
                        startY.coerceIn(0, (screenHeight - islandHeightPx).coerceAtLeast(0))
            }
            PositionMode.TOP_RIGHT -> {
                val startX = screenWidth - islandWidthPx - (16 * density).toInt() + xOffsetPx
                val startY = yOffsetPx
                startX.coerceIn(0, (screenWidth - islandWidthPx).coerceAtLeast(0)) to
                        startY.coerceIn(0, (screenHeight - islandHeightPx).coerceAtLeast(0))
            }
            PositionMode.BOTTOM_CENTER -> {
                val startX = ((screenWidth - islandWidthPx) / 2) + xOffsetPx
                val startY = screenHeight - islandHeightPx - (60 * density).toInt() - yOffsetPx
                startX.coerceIn(0, (screenWidth - islandWidthPx).coerceAtLeast(0)) to
                        startY.coerceIn(0, (screenHeight - islandHeightPx).coerceAtLeast(0))
            }
            PositionMode.LEFT_CENTER -> {
                val startX = (16 * density).toInt() + xOffsetPx
                val startY = (screenHeight - islandHeightPx) / 2 + yOffsetPx
                startX.coerceIn(0, (screenWidth - islandWidthPx).coerceAtLeast(0)) to
                        startY.coerceIn(0, (screenHeight - islandHeightPx).coerceAtLeast(0))
            }
            PositionMode.RIGHT_CENTER -> {
                val startX = screenWidth - islandWidthPx - (16 * density).toInt() + xOffsetPx
                val startY = (screenHeight - islandHeightPx) / 2 + yOffsetPx
                startX.coerceIn(0, (screenWidth - islandWidthPx).coerceAtLeast(0)) to
                        startY.coerceIn(0, (screenHeight - islandHeightPx).coerceAtLeast(0))
            }
            PositionMode.FREE -> {
                val startX = (config.xFraction * (screenWidth - islandWidthPx)).toInt() + xOffsetPx
                val startY = (config.yFraction * (screenHeight - islandHeightPx)).toInt() + yOffsetPx
                startX.coerceIn(0, (screenWidth - islandWidthPx).coerceAtLeast(0)) to
                        startY.coerceIn(0, (screenHeight - islandHeightPx).coerceAtLeast(0))
            }
        }
    }

    fun isOverlayPermitted(): Boolean {
        return Settings.canDrawOverlays(context)
    }

    @Synchronized
    fun attach() {
        if (_attachmentState.value == OverlayAttachmentState.ATTACHED ||
            _attachmentState.value == OverlayAttachmentState.ATTACHING
        ) {
            Log.d(TAG, "Overlay already attached or attaching")
            return
        }

        if (!isOverlayPermitted()) {
            Log.w(TAG, "Cannot attach overlay: SYSTEM_ALERT_WINDOW permission not granted")
            return
        }

        _attachmentState.value = OverlayAttachmentState.ATTACHING

        try {
            val config = repository.configState.value

            val displayMetrics = context.resources.displayMetrics
            val density = displayMetrics.density
            val screenWidth = displayMetrics.widthPixels
            val screenHeight = displayMetrics.heightPixels

            val islandWidthPx = ((if (isExpandedState) config.expandedWidthDp else config.widthDp) * density).toInt()
            val islandHeightPx = ((if (isExpandedState) config.expandedHeightDp else config.heightDp) * density).toInt()

            // Calculate starting X and Y using dynamic notch/status-bar detection
            val (startX, startY) = calculateCoordinates(
                config = config,
                islandWidthPx = islandWidthPx,
                islandHeightPx = islandHeightPx,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                density = density
            )

            val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

            val params = WindowManager.LayoutParams(
                islandWidthPx,
                islandHeightPx,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                flags,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = startX
                y = startY
            }

            layoutParams = params

            val newLifecycleOwner = OverlayLifecycleOwner()
            lifecycleOwner = newLifecycleOwner

            val composeView = ComposeView(context).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
                setViewTreeLifecycleOwner(newLifecycleOwner)
                setViewTreeViewModelStoreOwner(newLifecycleOwner)
                setViewTreeSavedStateRegistryOwner(newLifecycleOwner)

                setContent {
                    val currentConfig by repository.configFlow.collectAsState(initial = config)
                    val assistantState by LichiAssistantStateHub.assistantState.collectAsState()

                    DynamicIslandSurface(
                        config = currentConfig,
                        state = assistantState,
                        isExpanded = isExpandedState,
                        onExpandChanged = { expanded ->
                            isExpandedState = expanded
                            updateLayoutDimensions(expanded)
                        },
                        onOpenApp = {
                            openMainActivity()
                        },
                        onOpenSettings = {
                            openSettings()
                        },
                        onToggleVoice = {
                            triggerVoiceMode()
                        },
                        onStopSession = {
                            LichiAssistantStateHub.resetToIdle()
                        },
                        onCallAction = { action ->
                            scope.launch {
                                callActionExecutor.execute(action)
                            }
                        },
                        onOpenCallDiagnostics = {
                            openMainActivity()
                        }
                    )
                }
            }

            setupDragTouchListener(composeView)
            overlayView = composeView

            windowManager.addView(composeView, params)
            _attachmentState.value = OverlayAttachmentState.ATTACHED
            Log.i(TAG, "Dynamic Island overlay attached successfully at ($startX, $startY)")

            // Start observing config changes for live real-time position/size updates
            configObserverJob?.cancel()
            configObserverJob = scope.launch {
                repository.configFlow.collect { updatedConfig ->
                    if (!isDragging) {
                        applyConfigPositionAndSize(updatedConfig)
                    }
                }
            }
        } catch (e: Exception) {
            _attachmentState.value = OverlayAttachmentState.NOT_ATTACHED
            Log.e(TAG, "Failed to attach Dynamic Island overlay", e)
            cleanupViews()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDragTouchListener(view: View) {
        view.setOnTouchListener { _, event ->
            val params = layoutParams ?: return@setOnTouchListener false

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    initialParamX = params.x
                    initialParamY = params.y
                    isDragging = false
                    false // Allow child to receive tap/click gestures
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.rawX - initialTouchX
                    val deltaY = event.rawY - initialTouchY

                    if (!isDragging && (abs(deltaX) > touchSlop || abs(deltaY) > touchSlop)) {
                        isDragging = true
                    }

                    if (isDragging) {
                        val displayMetrics = context.resources.displayMetrics
                        val screenWidth = displayMetrics.widthPixels
                        val screenHeight = displayMetrics.heightPixels
                        val currentWidth = view.width.coerceAtLeast(100)
                        val currentHeight = view.height.coerceAtLeast(50)

                        val newX = (initialParamX + deltaX.toInt()).coerceIn(0, screenWidth - currentWidth)
                        val newY = (initialParamY + deltaY.toInt()).coerceIn(0, screenHeight - currentHeight)

                        params.x = newX
                        params.y = newY
                        try {
                            windowManager.updateViewLayout(view, params)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error updating overlay layout during drag", e)
                        }
                        true // Consume drag
                    } else {
                        false
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isDragging) {
                        isDragging = false
                        onDragFinished(view, params)
                        true
                    } else {
                        false
                    }
                }
                else -> false
            }
        }
    }

    private fun onDragFinished(view: View, params: WindowManager.LayoutParams) {
        val config = repository.configState.value
        val displayMetrics = context.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        val currentWidth = view.width.coerceAtLeast(100)
        val currentHeight = view.height.coerceAtLeast(50)
        val density = displayMetrics.density

        var finalX = params.x
        var finalY = params.y

        // Handle snapping if configured
        if (config.snapToEdges) {
            val snapDistPx = (config.snapDistanceDp * density).toInt()
            val distLeft = finalX
            val distRight = screenWidth - (finalX + currentWidth)
            val distTop = finalY
            val distBottom = screenHeight - (finalY + currentHeight)

            when (config.snapEdge) {
                SnapEdge.AUTO -> {
                    if (distLeft < snapDistPx) finalX = (12 * density).toInt()
                    else if (distRight < snapDistPx) finalX = screenWidth - currentWidth - (12 * density).toInt()

                    if (distTop < snapDistPx) finalY = (24 * density).toInt()
                    else if (distBottom < snapDistPx) finalY = screenHeight - currentHeight - (48 * density).toInt()
                }
                SnapEdge.TOP -> if (distTop < snapDistPx) finalY = (24 * density).toInt()
                SnapEdge.BOTTOM -> if (distBottom < snapDistPx) finalY = screenHeight - currentHeight - (48 * density).toInt()
                SnapEdge.LEFT -> if (distLeft < snapDistPx) finalX = (12 * density).toInt()
                SnapEdge.RIGHT -> if (distRight < snapDistPx) finalX = screenWidth - currentWidth - (12 * density).toInt()
                SnapEdge.NONE -> {}
            }

            params.x = finalX
            params.y = finalY
            try {
                windowManager.updateViewLayout(view, params)
            } catch (e: Exception) {
                Log.e(TAG, "Error updating snapped overlay layout", e)
            }
        }

        // Calculate and persist normalized fractions
        val usableWidth = (screenWidth - currentWidth).coerceAtLeast(1)
        val usableHeight = (screenHeight - currentHeight).coerceAtLeast(1)
        val xFraction = (finalX.toFloat() / usableWidth.toFloat()).coerceIn(0f, 1f)
        val yFraction = (finalY.toFloat() / usableHeight.toFloat()).coerceIn(0f, 1f)

        repository.updatePosition(
            xFraction = xFraction,
            yFraction = yFraction,
            xOffsetDp = (finalX / density).toInt(),
            yOffsetDp = (finalY / density).toInt()
        )
    }

    private fun updateLayoutDimensions(expanded: Boolean) {
        val view = overlayView ?: return
        val params = layoutParams ?: return
        val config = repository.configState.value
        val displayMetrics = context.resources.displayMetrics
        val density = displayMetrics.density
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        val currentWidthPx = ((if (expanded) config.expandedWidthDp else config.widthDp) * density).toInt()
        val currentHeightPx = ((if (expanded) config.expandedHeightDp else config.heightDp) * density).toInt()

        params.width = currentWidthPx
        params.height = currentHeightPx

        // Keep inside screen bounds
        params.x = params.x.coerceIn(0, (screenWidth - currentWidthPx).coerceAtLeast(0))
        params.y = params.y.coerceIn(0, (screenHeight - currentHeightPx).coerceAtLeast(0))

        try {
            windowManager.updateViewLayout(view, params)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating dimensions layout", e)
        }
    }

    private fun applyConfigPositionAndSize(config: DynamicIslandConfig) {
        val view = overlayView ?: return
        val params = layoutParams ?: return
        val displayMetrics = context.resources.displayMetrics
        val density = displayMetrics.density
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        val currentWidthPx = ((if (isExpandedState) config.expandedWidthDp else config.widthDp) * density).toInt()
        val currentHeightPx = ((if (isExpandedState) config.expandedHeightDp else config.heightDp) * density).toInt()

        val (newX, newY) = calculateCoordinates(
            config = config,
            islandWidthPx = currentWidthPx,
            islandHeightPx = currentHeightPx,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            density = density
        )

        params.width = currentWidthPx
        params.height = currentHeightPx
        params.x = newX
        params.y = newY

        try {
            windowManager.updateViewLayout(view, params)
        } catch (e: Exception) {
            Log.e(TAG, "Error applying config position and size", e)
        }
    }

    @Synchronized
    fun detach() {
        if (_attachmentState.value == OverlayAttachmentState.NOT_ATTACHED) return
        _attachmentState.value = OverlayAttachmentState.DETACHING

        try {
            cleanupViews()
            _attachmentState.value = OverlayAttachmentState.NOT_ATTACHED
            Log.i(TAG, "Dynamic Island overlay detached successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error during overlay detachment", e)
            _attachmentState.value = OverlayAttachmentState.NOT_ATTACHED
        }
    }

    private fun cleanupViews() {
        configObserverJob?.cancel()
        configObserverJob = null
        overlayView?.let { view ->
            try {
                windowManager.removeView(view)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to removeView from WindowManager", e)
            }
        }
        overlayView = null
        layoutParams = null
        lifecycleOwner?.destroy()
        lifecycleOwner = null
    }

    private fun openMainActivity() {
        try {
            val intent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening MainActivity from overlay", e)
        }
    }

    private fun openSettings() {
        try {
            val intent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening settings from overlay", e)
        }
    }

    private fun triggerVoiceMode() {
        try {
            val intent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(MainActivity.EXTRA_START_VOICE_MODE, true)
                putExtra(MainActivity.EXTRA_TRIGGERED_PHRASE, "Dynamic Island")
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error triggering voice mode from overlay", e)
        }
    }
}
