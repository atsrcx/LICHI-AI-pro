package com.lichiai.calling.conversation

import android.content.Context
import android.speech.SpeechRecognizer
import android.util.Log
import com.lichiai.calling.action.CallActionExecutor
import com.lichiai.calling.action.StructuredCallAction
import com.lichiai.calling.audio.CallRingerSilenceController
import com.lichiai.calling.contacts.ContactAliasesRepository
import com.lichiai.calling.contacts.ContactRepository
import com.lichiai.calling.data.CallHandlingSettings
import com.lichiai.calling.data.CallHandlingSettingsRepository
import com.lichiai.calling.data.IncomingCallsRule
import com.lichiai.calling.data.UnknownCallsRule
import com.lichiai.calling.engine.CallDiagnosticsRepository
import com.lichiai.calling.engine.UniversalCallEngine
import com.lichiai.calling.intent.CallActionIntentResolver
import com.lichiai.calling.permission.CallPermissionManager
import com.lichiai.calling.state.CallSessionInfo
import com.lichiai.calling.state.CallState
import com.lichiai.calling.state.CallStateMonitor
import com.lichiai.data.VoiceSettings
import com.lichiai.data.VoiceSettingsRepository
import com.lichiai.dynamicisland.LichiAssistantStateHub
import com.lichiai.dynamicisland.LichiUiState
import com.lichiai.voice.arbitration.MicrophoneArbitrator
import com.lichiai.voice.arbitration.MicrophoneOwner
import com.lichiai.voice.stt.SpeechToTextListener
import com.lichiai.voice.stt.SpeechToTextManager
import com.lichiai.voice.tts.TextToSpeechListener
import com.lichiai.voice.tts.TextToSpeechManager
import com.lichiai.voice.wakeword.MicrophoneOwnershipCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Authoritative Conversational Incoming Call Handling Manager for Lichi AI ("Handle My Calls").
 *
 * Coordinates proactive caller announcement, conversational decision states,
 * seamless TTS -> STT microphone arbitration, natural language intent resolution,
 * structured telephony call action execution, and spoken result verification.
 */
class IncomingCallConversationManager(
    private val context: Context,
    private val callStateMonitor: CallStateMonitor,
    private val callHandlingSettingsRepo: CallHandlingSettingsRepository,
    private val callActionExecutor: CallActionExecutor,
    private val callActionIntentResolver: CallActionIntentResolver,
    private val voiceSettingsRepository: VoiceSettingsRepository
) : TextToSpeechListener, SpeechToTextListener {

    companion object {
        private const val TAG = "IncomingCallConvMgr"
        private const val DECISION_TIMEOUT_MS = 10_000L

        @Volatile
        private var instance: IncomingCallConversationManager? = null

        fun getInstance(context: Context): IncomingCallConversationManager {
            return instance ?: synchronized(this) {
                instance ?: run {
                    val appCtx = context.applicationContext
                    val permManager = CallPermissionManager(appCtx)
                    val aliasRepo = ContactAliasesRepository(appCtx)
                    val contactsRepo = ContactRepository(appCtx, permManager, aliasRepo)
                    val monitor = CallStateMonitor.getInstance(appCtx, contactsRepo)
                    val diagRepo = CallDiagnosticsRepository()
                    val engine = UniversalCallEngine(appCtx, permManager, contactsRepo, diagRepo)
                    val executor = CallActionExecutor(appCtx, engine, permManager, monitor)
                    val resolver = CallActionIntentResolver()
                    val settingsRepo = CallHandlingSettingsRepository(appCtx)
                    val voiceRepo = VoiceSettingsRepository(appCtx)

                    IncomingCallConversationManager(
                        context = appCtx,
                        callStateMonitor = monitor,
                        callHandlingSettingsRepo = settingsRepo,
                        callActionExecutor = executor,
                        callActionIntentResolver = resolver,
                        voiceSettingsRepository = voiceRepo
                    ).also { instance = it }
                }
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var ttsManager: TextToSpeechManager? = null
    private var sttManager: SpeechToTextManager? = null

    private var currentSettings = CallHandlingSettings()
    private var currentVoiceSettings = VoiceSettings()

    private var isStarted = false
    private var currentDecisionContext: CallDecisionContext? = null
    private var handledSessionKey: String? = null
    private var decisionTimeoutJob: Job? = null
    private var awaitingTtsToFinishQuestion = false

    fun isHandleMyCallsEnabled(): Boolean {
        return CallHandlingSettingsRepository.isCallHandlingEnabledFast() && currentSettings.callHandlingEnabled
    }

    fun start() {
        if (isStarted) return
        isStarted = true
        Log.d(TAG, "Starting IncomingCallConversationManager")

        // Initialize Speech and Voice engines
        ttsManager = TextToSpeechManager(context, this)
        sttManager = SpeechToTextManager(context, this)

        scope.launch {
            voiceSettingsRepository.settings.collect { vs ->
                currentVoiceSettings = vs
                ttsManager?.initialize(vs)
                sttManager?.initialize(vs)
            }
        }

        scope.launch {
            callHandlingSettingsRepo.settings.collect { settings ->
                currentSettings = settings
            }
        }

        // Observe Call State Transitions
        scope.launch {
            callStateMonitor.callSessionState.collect { session ->
                handleCallStateChange(session)
            }
        }
    }

    private suspend fun handleCallStateChange(session: CallSessionInfo) {
        val state = session.callState

        // When call ends, is answered, rejected, or idle, clean up active decision flows
        if (state.isConnectedOrActive || state.isTerminal || state == CallState.IDLE) {
            if (currentDecisionContext != null || awaitingTtsToFinishQuestion) {
                Log.d(TAG, "Call transitioned to $state; tearing down decision conversation flow")
                cancelActiveVoiceInteraction()
            }
            handledSessionKey = null
            return
        }

        // If a decision context already exists and the caller identity is updated with a resolved contact name:
        if (currentDecisionContext != null && state.isRinging) {
            val ctx = currentDecisionContext
            if (ctx != null && (ctx.callerName.isNullOrBlank() || ctx.callerName == "Unknown Caller") &&
                !session.callerName.isNullOrBlank() && session.callerName != "Unknown Caller"
            ) {
                Log.d(TAG, "Caller resolved late to ${session.callerName}, updating active decision context")
                currentDecisionContext = ctx.copy(callerName = session.callerName)
            }
            return
        }

        // Check if incoming ringing and no decision has been initialized for this ringing call
        if (state.isRinging && !state.isDecisionPending) {
            val sessionKey = session.callSessionId.ifBlank { "${session.callerNumber ?: "unknown"}_${session.timestamp}" }
            if (handledSessionKey == sessionKey) {
                return // Already processing this incoming call
            }

            // Check if feature is enabled in settings
            val isEnabled = CallHandlingSettingsRepository.isCallHandlingEnabledFast() && currentSettings.callHandlingEnabled
            Log.d(TAG, "[CALL] HANDLE_MY_CALLS_ENABLED $isEnabled")
            if (!isEnabled) {
                Log.d(TAG, "Handle My Calls is disabled; bypassing conversational handling")
                return
            }

            // Immediate ringer silence guarantee
            CallRingerSilenceController.getInstance(context).silenceRingerImmediately()

            // Preemptively claim microphone ownership for VOICE_STT to yield wake-word engine early
            MicrophoneArbitrator.requestAcquisitionBlocking(
                MicrophoneOwner.VOICE_STT,
                "Incoming call conversational handling started"
            )

            // Check rule bypasses
            if (currentSettings.incomingCallsRule == IncomingCallsRule.MANUAL_ONLY) {
                Log.d(TAG, "IncomingCallsRule is MANUAL_ONLY; bypassing conversational handling")
                return
            }

            if (session.isUnknownCaller) {
                when (currentSettings.unknownCallsRule) {
                    UnknownCallsRule.ALWAYS_REJECT -> {
                        Log.d(TAG, "Unknown caller rule is ALWAYS_REJECT; rejecting call immediately")
                        handledSessionKey = sessionKey
                        callActionExecutor.execute(StructuredCallAction.RejectCall)
                        return
                    }
                    UnknownCallsRule.NEVER_INTERFERE -> {
                        Log.d(TAG, "Unknown caller rule is NEVER_INTERFERE; ignoring unknown call")
                        return
                    }
                    else -> {}
                }
            }

            handledSessionKey = sessionKey
            startProactiveCallDecisionFlow(session)
        }
    }

    /**
     * Step 1: Formulate concise natural-language question, update UI, speak proactive prompt.
     */
    private fun startProactiveCallDecisionFlow(session: CallSessionInfo) {
        val callerName = session.callerName?.trim()
        val isUnknown = session.isUnknownCaller || callerName.isNullOrBlank() || callerName.equals("Unknown Caller", ignoreCase = true)

        val spokenQuestion = when {
            !isUnknown && !callerName.isNullOrBlank() -> "$callerName ka call aa raha hai. Uthaun ya reject karun?"
            !session.callerNumber.isNullOrBlank() -> "Unknown number se call aa raha hai. Uthaun ya reject karun?"
            else -> "Unknown call aa raha hai. Uthaun ya reject karun?"
        }

        val sessionId = "call_decision_${System.currentTimeMillis()}"

        currentDecisionContext = CallDecisionContext(
            callSessionId = sessionId,
            callerName = if (isUnknown) null else callerName,
            callerNumber = session.callerNumber,
            callDirection = "INCOMING",
            currentCallState = CallState.CALL_DECISION_REQUIRED,
            questionAsked = spokenQuestion,
            awaitingDecision = false,
            handleMyCallsEnabled = true,
            ttsActive = true,
            sttActive = false,
            createdAt = System.currentTimeMillis()
        )

        callStateMonitor.updateDecisionQuestion(sessionId, spokenQuestion)

        LichiAssistantStateHub.updateState(
            uiState = LichiUiState.SPEAKING,
            statusText = "Speaking...",
            responsePreview = spokenQuestion
        )

        awaitingTtsToFinishQuestion = true

        Log.d(TAG, "[CALL] TTS_STARTED ${System.currentTimeMillis()}")
        Log.d("LICHI_VOICE", "[CALL] TTS_STARTED ${System.currentTimeMillis()}")
        ttsManager?.stopAndClearQueue()
        ttsManager?.enqueueSentence(spokenQuestion)
    }

    /**
     * Step 2: Triggered after TTS completes speaking the prompt.
     * Hands off deterministic control to STT listening.
     */
    private fun transitionToListeningForDecision() {
        val currentSession = callStateMonitor.callSessionState.value
        if (!currentSession.callState.isRinging) {
            Log.d(TAG, "Call is no longer ringing; skipping STT handoff")
            return
        }

        awaitingTtsToFinishQuestion = false

        currentDecisionContext = currentDecisionContext?.copy(
            currentCallState = CallState.WAITING_FOR_CALL_COMMAND,
            awaitingDecision = true,
            ttsActive = false,
            sttActive = true,
            decisionDeadline = System.currentTimeMillis() + DECISION_TIMEOUT_MS
        )

        callStateMonitor.updateDecisionWaiting(true)

        LichiAssistantStateHub.updateState(
            uiState = LichiUiState.LISTENING,
            statusText = "Listening for your decision..."
        )

        Log.d("LICHI_VOICE", "[CALL] HANDOFF_TTS_TO_STT ${System.currentTimeMillis()}")
        // Authoritatively request microphone ownership for STT
        val acquired = MicrophoneArbitrator.requestAcquisitionBlocking(
            MicrophoneOwner.VOICE_STT,
            "Call decision voice input"
        )
        if (!acquired) {
            Log.w(TAG, "Failed to acquire microphone for call decision")
            Log.w("LICHI_VOICE", "[CALL] STT_MIC_ACQUIRE_FAILED")
            return
        }

        Log.d(TAG, "[CALL] STT_STARTED ${System.currentTimeMillis()}")
        Log.d("LICHI_VOICE", "[CALL] STT_STARTED ${System.currentTimeMillis()}")
        sttManager?.startListening(currentVoiceSettings)

        // Launch 10-second timeout
        decisionTimeoutJob?.cancel()
        decisionTimeoutJob = scope.launch {
            delay(DECISION_TIMEOUT_MS)
            handleDecisionTimeout()
        }
    }

    /**
     * Step 3: Handle 10-second listening timeout gracefully.
     */
    private fun handleDecisionTimeout() {
        Log.d(TAG, "Decision listening timed out after ${DECISION_TIMEOUT_MS}ms")
        sttManager?.release("Call decision timeout")

        val currentSession = callStateMonitor.callSessionState.value
        if (!currentSession.callState.isRinging) {
            currentDecisionContext = null
            CallRingerSilenceController.getInstance(context).restore()
            return
        }

        callStateMonitor.clearDecisionState()

        val timeoutSpoken = "Theek hai, main call ka decision nahi le raha."
        LichiAssistantStateHub.updateState(
            uiState = LichiUiState.SPEAKING,
            statusText = "Speaking...",
            responsePreview = timeoutSpoken
        )

        ttsManager?.enqueueSentence(timeoutSpoken)
        currentDecisionContext = null
        CallRingerSilenceController.getInstance(context).restore()
        Log.d(TAG, "[CALL] SESSION_FINISHED ${System.currentTimeMillis()}")
        Log.d("LICHI_VOICE", "[CALL] SESSION_FINISHED ${System.currentTimeMillis()}")
    }

    /**
     * Step 4: Process user voice command during call decision.
     */
    private fun processUserDecision(spokenText: String) {
        val now = System.currentTimeMillis()
        Log.d(TAG, "[CALL] COMMAND_RECEIVED $now query='$spokenText'")
        Log.d("LICHI_VOICE", "[CALL] COMMAND_RECEIVED $now query='$spokenText'")

        decisionTimeoutJob?.cancel()
        sttManager?.stopListening()

        val contextSnapshot = currentDecisionContext ?: return
        val currentSession = callStateMonitor.callSessionState.value

        if (!currentSession.callState.isRinging) {
            Log.d(TAG, "Call is no longer active or ringing; abandoning decision")
            sttManager?.release("Call no longer ringing")
            currentDecisionContext = null
            CallRingerSilenceController.getInstance(context).restore()
            return
        }

        val action = callActionIntentResolver.resolve(spokenText, currentSession)
        Log.d(TAG, "[CALL] ACTION_RESOLVED ${System.currentTimeMillis()} action=$action")
        Log.d("LICHI_VOICE", "[CALL] ACTION_RESOLVED ${System.currentTimeMillis()} action=$action")

        when (action) {
            is StructuredCallAction.AnswerCall -> {
                executeUserAnswer(action.enableSpeaker, contextSnapshot)
            }
            is StructuredCallAction.RejectCall -> {
                executeUserReject(contextSnapshot)
            }
            is StructuredCallAction.ClarifyNegativeDecision -> {
                promptNegativeClarification(contextSnapshot)
            }
            is StructuredCallAction.CancelDecision -> {
                executeCancelDecision()
            }
            is StructuredCallAction.QueryCallerIdentity -> {
                respondWithCallerIdentity(contextSnapshot)
            }
            is StructuredCallAction.QueryCallerNumber -> {
                respondWithCallerNumber(contextSnapshot)
            }
            else -> {
                // Ambiguous input: clarify once and resume listening
                promptClarification()
            }
        }
    }

    private fun executeUserAnswer(enableSpeaker: Boolean, decisionContext: CallDecisionContext) {
        val now = System.currentTimeMillis()
        Log.d(TAG, "[CALL] ACTION_EXECUTED $now enableSpeaker=$enableSpeaker")
        Log.d("LICHI_VOICE", "[CALL] ACTION_EXECUTED $now enableSpeaker=$enableSpeaker")

        LichiAssistantStateHub.updateState(
            uiState = LichiUiState.CALLING,
            statusText = if (enableSpeaker) "Answering on speaker..." else "Answering call..."
        )

        scope.launch {
            callActionExecutor.execute(StructuredCallAction.AnswerCall(enableSpeaker = enableSpeaker))

            // Step 5: Verification (wait up to 1500ms for active call state)
            var isSuccess = false
            for (i in 0 until 15) {
                delay(100)
                val updatedSession = callStateMonitor.callSessionState.value
                if (updatedSession.callState.isConnectedOrActive || updatedSession.callState == CallState.ACTIVE) {
                    isSuccess = true
                    break
                }
            }

            Log.d(TAG, "[CALL] ACTION_VERIFIED ${System.currentTimeMillis()} isSuccess=$isSuccess")
            Log.d("LICHI_VOICE", "[CALL] ACTION_VERIFIED ${System.currentTimeMillis()} isSuccess=$isSuccess")

            // Release STT completely so microphone is cleanly handed over
            sttManager?.release("Call answered and verified")
            Log.d("LICHI_VOICE", "[CALL] STT_RELEASED ${System.currentTimeMillis()}")

            val confirmation = if (isSuccess) {
                if (enableSpeaker) {
                    "Call speaker pe connect ho gaya."
                } else {
                    "Call connect ho gaya."
                }
            } else {
                "Call connect nahi ho paya."
            }

            LichiAssistantStateHub.updateState(
                uiState = LichiUiState.SPEAKING,
                statusText = "Speaking...",
                responsePreview = confirmation
            )

            ttsManager?.enqueueSentence(confirmation)
            currentDecisionContext = null
            CallRingerSilenceController.getInstance(context).restore()
            Log.d(TAG, "[CALL] SESSION_FINISHED ${System.currentTimeMillis()}")
            Log.d("LICHI_VOICE", "[CALL] SESSION_FINISHED ${System.currentTimeMillis()}")
        }
    }

    private fun executeUserReject(decisionContext: CallDecisionContext) {
        val now = System.currentTimeMillis()
        Log.d(TAG, "[CALL] ACTION_EXECUTED $now")
        Log.d("LICHI_VOICE", "[CALL] ACTION_EXECUTED $now reject")

        LichiAssistantStateHub.updateState(
            uiState = LichiUiState.CALLING,
            statusText = "Rejecting call..."
        )

        scope.launch {
            callActionExecutor.execute(StructuredCallAction.RejectCall)

            // Step 5: Verification (wait up to 1000ms for rejected/ended/idle state)
            var isSuccess = false
            for (i in 0 until 10) {
                delay(100)
                val updatedSession = callStateMonitor.callSessionState.value
                if (updatedSession.callState == CallState.REJECTED || 
                    updatedSession.callState == CallState.ENDED || 
                    updatedSession.callState == CallState.IDLE) {
                    isSuccess = true
                    break
                }
            }

            Log.d(TAG, "[CALL] ACTION_VERIFIED ${System.currentTimeMillis()} isSuccess=$isSuccess")
            Log.d("LICHI_VOICE", "[CALL] ACTION_VERIFIED ${System.currentTimeMillis()} isSuccess=$isSuccess")

            // Release STT so microphone returns to IDLE / Vosk
            sttManager?.release("Call rejected and verified")
            Log.d("LICHI_VOICE", "[CALL] STT_RELEASED ${System.currentTimeMillis()}")

            val confirmation = if (isSuccess) {
                "Call reject kar diya."
            } else {
                "Call reject nahi ho paya."
            }

            LichiAssistantStateHub.updateState(
                uiState = LichiUiState.SPEAKING,
                statusText = "Speaking...",
                responsePreview = confirmation
            )

            ttsManager?.enqueueSentence(confirmation)
            currentDecisionContext = null
            CallRingerSilenceController.getInstance(context).restore()
            Log.d(TAG, "[CALL] SESSION_FINISHED ${System.currentTimeMillis()}")
            Log.d("LICHI_VOICE", "[CALL] SESSION_FINISHED ${System.currentTimeMillis()}")
        }
    }

    private fun promptNegativeClarification(decisionContext: CallDecisionContext) {
        val clarifyText = "Uthaun nahi? Reject kar du?"
        currentDecisionContext = decisionContext.copy(
            questionAsked = clarifyText,
            currentCallState = CallState.CALL_DECISION_REQUIRED,
            awaitingDecision = false
        )
        callStateMonitor.updateDecisionQuestion(decisionContext.callSessionId, clarifyText)
        LichiAssistantStateHub.updateState(
            uiState = LichiUiState.SPEAKING,
            statusText = "Speaking...",
            responsePreview = clarifyText
        )
        awaitingTtsToFinishQuestion = true
        ttsManager?.enqueueSentence(clarifyText)
    }

    private fun executeCancelDecision() {
        callStateMonitor.clearDecisionState()
        sttManager?.release("Decision cancelled")
        val cancelSpoken = "Theek hai."

        LichiAssistantStateHub.updateState(
            uiState = LichiUiState.SPEAKING,
            statusText = "Speaking...",
            responsePreview = cancelSpoken
        )

        ttsManager?.enqueueSentence(cancelSpoken)
        currentDecisionContext = null
        CallRingerSilenceController.getInstance(context).restore()
        Log.d("LICHI_VOICE", "[CALL] SESSION_FINISHED ${System.currentTimeMillis()}")
    }

    private fun respondWithCallerIdentity(decisionContext: CallDecisionContext) {
        val name = decisionContext.callerName
        val reply = if (!name.isNullOrBlank() && !name.equals("Unknown Caller", ignoreCase = true)) {
            "$name ka call hai. Uthaun ya reject karun?"
        } else {
            "Unknown number se call hai. Uthaun ya reject karun?"
        }

        LichiAssistantStateHub.updateState(
            uiState = LichiUiState.SPEAKING,
            statusText = "Speaking...",
            responsePreview = reply
        )

        awaitingTtsToFinishQuestion = true
        ttsManager?.enqueueSentence(reply)
    }

    private fun respondWithCallerNumber(decisionContext: CallDecisionContext) {
        val num = decisionContext.callerNumber
        val reply = if (!num.isNullOrBlank()) {
            val last4 = if (num.length >= 4) num.takeLast(4) else num
            "Number ke last four digits $last4 hain. Uthaun ya reject karun?"
        } else {
            "Number available nahi hai. Uthaun ya reject karun?"
        }

        LichiAssistantStateHub.updateState(
            uiState = LichiUiState.SPEAKING,
            statusText = "Speaking...",
            responsePreview = reply
        )

        awaitingTtsToFinishQuestion = true
        ttsManager?.enqueueSentence(reply)
    }

    private fun promptClarification() {
        val clarify = "Call uthana hai ya reject karna hai?"
        LichiAssistantStateHub.updateState(
            uiState = LichiUiState.SPEAKING,
            statusText = "Speaking...",
            responsePreview = clarify
        )

        awaitingTtsToFinishQuestion = true
        ttsManager?.enqueueSentence(clarify)
    }

    private fun cancelActiveVoiceInteraction() {
        decisionTimeoutJob?.cancel()
        awaitingTtsToFinishQuestion = false
        currentDecisionContext = null

        try {
            sttManager?.release("Call decision cancelled")
        } catch (_: Throwable) {}

        try {
            ttsManager?.stopAndClearQueue()
        } catch (_: Throwable) {}

        CallRingerSilenceController.getInstance(context).restore()
        callStateMonitor.clearDecisionState()
    }

    // TextToSpeechListener Implementation
    override fun onEngineInitialized(isSuccess: Boolean) {
        Log.d(TAG, "TTS Engine Initialized: success=$isSuccess")
    }

    override fun onUtteranceStart(utteranceId: String) {
        Log.d(TAG, "TTS Utterance start: $utteranceId")
    }

    override fun onUtteranceDone(utteranceId: String, isQueueEmpty: Boolean) {
        Log.d(TAG, "TTS Utterance done: $utteranceId, isQueueEmpty=$isQueueEmpty")
        if (isQueueEmpty && awaitingTtsToFinishQuestion) {
            Log.d(TAG, "[CALL] TTS_FINISHED ${System.currentTimeMillis()}")
            Log.d("LICHI_VOICE", "[CALL] TTS_FINISHED ${System.currentTimeMillis()}")
            scope.launch {
                transitionToListeningForDecision()
            }
        }
    }

    override fun onUtteranceError(utteranceId: String, errorMessage: String) {
        Log.e(TAG, "TTS Utterance error: $utteranceId, error=$errorMessage")
        if (awaitingTtsToFinishQuestion) {
            Log.d("LICHI_VOICE", "[CALL] TTS_ERROR_HANDOFF ${System.currentTimeMillis()}")
            scope.launch {
                transitionToListeningForDecision()
            }
        }
    }

    // SpeechToTextListener Implementation
    override fun onReadyForSpeech() {
        Log.d(TAG, "STT onReadyForSpeech")
    }

    override fun onBeginningOfSpeech() {
        Log.d(TAG, "STT onBeginningOfSpeech - User started speaking decision")
        decisionTimeoutJob?.cancel() // User is speaking; don't timeout
    }

    override fun onRmsChanged(rmsdB: Float) {
        val normalizedRms = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
        LichiAssistantStateHub.updateState(
            uiState = LichiUiState.LISTENING,
            rmsLevel = normalizedRms
        )
    }

    override fun onPartialResult(partialText: String) {
        Log.d(TAG, "STT Partial: $partialText")
        LichiAssistantStateHub.updateState(
            uiState = LichiUiState.LISTENING,
            transcript = partialText
        )

        // Fast-path for unambiguous answer/reject commands in partial results
        val clean = partialText.trim().lowercase()
        val currentSession = callStateMonitor.callSessionState.value
        if (currentSession.callState.isRinging && currentDecisionContext?.awaitingDecision == true) {
            val quickAction = callActionIntentResolver.resolve(clean, currentSession)
            if (quickAction is StructuredCallAction.AnswerCall || quickAction is StructuredCallAction.RejectCall) {
                Log.d(TAG, "Fast-path action matched on partial speech: $quickAction")
                sttManager?.stopListening()
                processUserDecision(clean)
            }
        }
    }

    override fun onFinalResult(text: String) {
        Log.d(TAG, "STT Final: $text")
        if (text.isNotBlank()) {
            LichiAssistantStateHub.updateState(
                uiState = LichiUiState.THINKING,
                transcript = text
            )
            processUserDecision(text)
        }
    }

    override fun onError(errorCode: Int, errorMessage: String) {
        Log.w(TAG, "STT Error: code=$errorCode, msg=$errorMessage")
        val currentSession = callStateMonitor.callSessionState.value
        if (currentSession.callState.isRinging && currentDecisionContext?.awaitingDecision == true) {
            scope.launch {
                delay(300)
                if (callStateMonitor.callSessionState.value.callState.isRinging && currentDecisionContext?.awaitingDecision == true) {
                    Log.d(TAG, "Retrying STT listening during decision window after error $errorCode")
                    sttManager?.startListening(currentVoiceSettings)
                }
            }
        }
    }

    override fun onEndOfSpeech() {
        Log.d(TAG, "STT onEndOfSpeech")
    }
}

