package com.lichiai.assistant.bridge

import android.content.Context
import android.os.Bundle
import android.util.Log
import com.lichiai.api.LlmClient
import com.lichiai.calling.action.CallActionExecutor
import com.lichiai.calling.contacts.ContactAliasesRepository
import com.lichiai.calling.contacts.ContactRepository
import com.lichiai.calling.engine.CallDiagnosticsRepository
import com.lichiai.calling.engine.UniversalCallEngine
import com.lichiai.calling.intent.CallActionIntentResolver
import com.lichiai.calling.permission.CallPermissionManager
import com.lichiai.calling.state.CallStateMonitor
import com.lichiai.data.AssistantStore
import com.lichiai.data.ConversationStore
import com.lichiai.data.ProviderStore
import com.lichiai.data.SettingsRepository
import com.lichiai.data.VoiceSettingsRepository
import com.lichiai.dynamicisland.DynamicIslandController
import com.lichiai.voice.VoiceConversationOrchestrator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * AssistantActivationSource describes where the assistant invocation came from.
 */
enum class AssistantActivationSource {
    SYSTEM_HOTWORD,
    SYSTEM_ASSIST_KEY,
    NAV_BAR_GESTURE,
    VOICE_SEARCH_INTENT,
    DYNAMIC_ISLAND,
    IN_APP
}

data class AssistantActivationEvent(
    val source: AssistantActivationSource,
    val timestamp: Long = System.currentTimeMillis(),
    val assistBundle: Bundle? = null
)

/**
 * SystemAssistantBridge connects Android's VoiceInteractionService and ROLE_ASSISTANT
 * lifecycle to Lichi-AI's core voice orchestrator, dynamic island, and settings repositories.
 *
 * CRITICAL ARCHITECTURAL CONSTRAINTS:
 * 1. Does NOT touch AudioRecord or own the microphone directly.
 * 2. Uses VoiceConversationOrchestrator as the unified authority for voice conversations.
 * 3. Does NOT duplicate STT, TTS, or LLM logic.
 * 4. Ensures resilience against service or background destruction.
 */
class SystemAssistantBridge private constructor(context: Context) {

    companion object {
        private const val TAG = "SystemAssistantBridge"

        @Volatile
        private var instance: SystemAssistantBridge? = null

        fun getInstance(context: Context): SystemAssistantBridge {
            return instance ?: synchronized(this) {
                instance ?: SystemAssistantBridge(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Repositories & Engine instances
    val settingsRepo = SettingsRepository(appContext)
    val voiceSettingsRepo = VoiceSettingsRepository(appContext)
    val providerStore = ProviderStore(appContext)
    val assistantStore = AssistantStore(appContext)
    val conversationStore = ConversationStore(appContext)
    val llmClient = LlmClient()

    val callPermissionManager = CallPermissionManager(appContext)
    val contactAliasesRepo = ContactAliasesRepository(appContext)
    val contactRepo = ContactRepository(appContext, callPermissionManager, contactAliasesRepo)
    val callStateMonitor = CallStateMonitor.getInstance(appContext, contactRepo)
    val callDiagRepo = CallDiagnosticsRepository()
    val universalCallEngine = UniversalCallEngine(appContext, callPermissionManager, contactRepo, callDiagRepo)
    val callActionExecutor = CallActionExecutor(appContext, universalCallEngine, callPermissionManager, callStateMonitor)
    val callActionIntentResolver = CallActionIntentResolver()

    private var activeConversationId: String? = null

    // Single Authoritative Orchestrator for the Assistant bridge
    val voiceOrchestrator = VoiceConversationOrchestrator(
        context = appContext,
        llmClient = llmClient,
        voiceSettingsRepository = voiceSettingsRepo,
        settingsRepository = settingsRepo,
        callEngine = universalCallEngine,
        callActionExecutor = callActionExecutor,
        callActionIntentResolver = callActionIntentResolver,
        conversationStore = conversationStore,
        activeConversationIdProvider = { activeConversationId },
        onConversationIdChanged = { id -> activeConversationId = id }
    )

    private val _activationEvents = MutableSharedFlow<AssistantActivationEvent>(extraBufferCapacity = 16)
    val activationEvents: SharedFlow<AssistantActivationEvent> = _activationEvents.asSharedFlow()

    init {
        Log.i(TAG, "SystemAssistantBridge initialized")
    }

    /**
     * Called when VoiceInteractionSession or system triggers an assistant interaction.
     */
    fun onSystemAssistantTriggered(
        source: AssistantActivationSource,
        assistBundle: Bundle? = null
    ) {
        Log.i(TAG, "System assistant triggered: source=$source")
        _activationEvents.tryEmit(AssistantActivationEvent(source = source, assistBundle = assistBundle))

        // Ensure Dynamic Island is alive for floating visualization
        try {
            DynamicIslandController.getInstance(appContext).start()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed starting DynamicIslandController", t)
        }

        // Start voice session using configured provider and assistant
        scope.launch {
            try {
                val appSettings = settingsRepo.settings.first()
                val providers = providerStore.snapshot()
                val assistants = assistantStore.snapshot()

                val activeProvider = providers.firstOrNull { it.id == appSettings.activeProviderId }
                    ?: providers.firstOrNull()

                val activeAssistant = assistants.firstOrNull { it.id == appSettings.activeAssistantId }
                    ?: assistants.firstOrNull()

                voiceOrchestrator.startSession(
                    provider = activeProvider,
                    assistant = activeAssistant
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch voice session on system trigger", e)
            }
        }
    }

    /**
     * Stop active voice session from system assistant session dismissal.
     */
    fun onSystemAssistantDismissed() {
        Log.i(TAG, "System assistant session dismissed")
        voiceOrchestrator.stopSession()
    }
}
