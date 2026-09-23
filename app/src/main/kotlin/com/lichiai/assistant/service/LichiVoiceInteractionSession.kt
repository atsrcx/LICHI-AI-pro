package com.lichiai.assistant.service

import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.lichiai.assistant.bridge.AssistantActivationSource
import com.lichiai.assistant.bridge.SystemAssistantBridge
import com.lichiai.dynamicisland.LichiAssistantStateHub
import com.lichiai.dynamicisland.LichiUiState
import com.lichiai.ui.theme.LichiAITheme
import com.lichiai.voice.conversation.VoiceState

private class SessionViewLifecycleOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
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
 * LichiVoiceInteractionSession handles the assistant session window and lifecycle.
 *
 * It provides a translucent system assistant bottom sheet overlay while delegating
 * the actual speech recognition and LLM processing to SystemAssistantBridge
 * and VoiceConversationOrchestrator.
 */
class LichiVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {

    companion object {
        private const val TAG = "LichiSession"
    }

    private val bridge = SystemAssistantBridge.getInstance(context)
    private var lifecycleOwner: SessionViewLifecycleOwner? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "LichiVoiceInteractionSession onCreate")
    }

    override fun onCreateContentView(): View {
        val rootLayout = FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            background = ColorDrawable(Color.TRANSPARENT)
        }

        val sessionLifecycle = SessionViewLifecycleOwner().also { lifecycleOwner = it }

        val composeView = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setViewTreeLifecycleOwner(sessionLifecycle)
            setViewTreeViewModelStoreOwner(sessionLifecycle)
            setViewTreeSavedStateRegistryOwner(sessionLifecycle)

            setContent {
                val settings by bridge.settingsRepo.settings.collectAsState(initial = com.lichiai.data.AppSettings())
                LichiAITheme(themeMode = settings.themeMode, dynamicColor = settings.dynamicColor) {
                    AssistantSessionSheet(
                        bridge = bridge,
                        onClose = { hide() },
                        onOpenApp = {
                            hide()
                            try {
                                val intent = Intent(context, MainActivity::class.java).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                                    putExtra(MainActivity.EXTRA_START_VOICE_MODE, true)
                                }
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to launch MainActivity", e)
                            }
                        }
                    )
                }
            }
        }

        rootLayout.addView(
            composeView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        return rootLayout
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        Log.i(TAG, "onShow showFlags=$showFlags args=$args")

        val sourceName = args?.getString("source") ?: AssistantActivationSource.SYSTEM_ASSIST_KEY.name
        val source = try {
            AssistantActivationSource.valueOf(sourceName)
        } catch (_: Exception) {
            AssistantActivationSource.SYSTEM_ASSIST_KEY
        }

        bridge.onSystemAssistantTriggered(source, args)
    }

    override fun onHandleAssist(data: Bundle?, structure: AssistStructure?, content: AssistContent?) {
        super.onHandleAssist(data, structure, content)
        Log.d(TAG, "onHandleAssist received contextual metadata")
    }

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        super.onHandleScreenshot(screenshot)
        Log.d(TAG, "onHandleScreenshot received (bitmap=${screenshot != null})")
    }

    override fun onHide() {
        super.onHide()
        Log.i(TAG, "onHide")
        bridge.onSystemAssistantDismissed()
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        lifecycleOwner?.destroy()
        lifecycleOwner = null
        super.onDestroy()
    }
}

@Composable
private fun AssistantSessionSheet(
    bridge: SystemAssistantBridge,
    onClose: () -> Unit,
    onOpenApp: () -> Unit
) {
    val orchestratorState by bridge.voiceOrchestrator.sessionState.collectAsState()
    val hubState by LichiAssistantStateHub.assistantState.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(onClick = onClose),
        contentAlignment = Alignment.BottomCenter
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = false) {}
                .padding(12.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                // Header Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = "Assistant",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "LICHI-AI Assistant",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = when (orchestratorState.state) {
                                VoiceState.LISTENING -> "Listening to you..."
                                VoiceState.TRANSCRIBING -> "Processing speech..."
                                VoiceState.THINKING -> "Thinking..."
                                VoiceState.SPEAKING -> "Speaking..."
                                VoiceState.IDLE -> "Ready"
                                VoiceState.PAUSED -> "Paused"
                                VoiceState.INTERRUPTED -> "Interrupted"
                                VoiceState.ERROR -> orchestratorState.errorMessage ?: "Voice Error"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    IconButton(onClick = onOpenApp) {
                        Icon(
                            imageVector = Icons.Default.OpenInFull,
                            contentDescription = "Open Full Screen",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // User Speech / Transcript or Assistant Response
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        val displayText = when {
                            orchestratorState.partialUserText.isNotBlank() -> orchestratorState.partialUserText
                            orchestratorState.activeAssistantText.isNotBlank() -> orchestratorState.activeAssistantText
                            hubState.transcript.isNotBlank() -> hubState.transcript
                            hubState.responsePreview.isNotBlank() -> hubState.responsePreview
                            else -> "Say something or ask a question..."
                        }

                        Text(
                            text = displayText,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
            }
        }
    }
}
