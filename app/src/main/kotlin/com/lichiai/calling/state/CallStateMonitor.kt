package com.lichiai.calling.state

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.lichiai.calling.audio.CallRingerSilenceController
import com.lichiai.calling.contacts.ContactRepository
import com.lichiai.calling.contacts.PhoneNumberNormalizer
import com.lichiai.calling.data.CallHandlingSettingsRepository
import com.lichiai.dynamicisland.LichiAssistantStateHub
import com.lichiai.voice.wakeword.MicrophoneOwnershipCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Single authoritative Call State Monitor for Lichi AI.
 * Monitors cellular and system telephony states, coordinates audio focus,
 * manages live call duration, and synchronizes with Lichi UI & Assistant State Hub.
 */
class CallStateMonitor(
    private val context: Context,
    private val contactRepository: ContactRepository? = null
) {
    companion object {
        @Volatile
        private var instance: CallStateMonitor? = null

        fun getInstance(context: Context, contactRepository: ContactRepository? = null): CallStateMonitor {
            return instance ?: synchronized(this) {
                instance ?: CallStateMonitor(context.applicationContext, contactRepository).also {
                    instance = it
                }
            }
        }
    }
    private val monitorScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val _callSessionState = MutableStateFlow(CallSessionInfo())
    val callSessionState: StateFlow<CallSessionInfo> = _callSessionState.asStateFlow()

    private var durationJob: Job? = null
    private var telephonyCallback: Any? = null
    private var isListeningToTelephony = false

    init {
        startTelephonyListener()
        monitorScope.launch {
            _callSessionState.collect { session ->
                LichiAssistantStateHub.updateCallSession(session)
            }
        }
    }

    private fun startTelephonyListener() {
        if (isListeningToTelephony || telephonyManager == null) return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) {
                        handleRawCallState(state, incomingNumber = null)
                    }
                }
                telephonyManager.registerTelephonyCallback(
                    ContextCompat.getMainExecutor(context),
                    callback
                )
                telephonyCallback = callback
                isListeningToTelephony = true
            } else {
                @Suppress("DEPRECATION")
                val listener = object : PhoneStateListener() {
                    @Deprecated("Deprecated in Java")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                        handleRawCallState(state, incomingNumber = phoneNumber)
                    }
                }
                @Suppress("DEPRECATION")
                telephonyManager.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
                telephonyCallback = listener
                isListeningToTelephony = true
            }
        } catch (e: Exception) {
            // Permission or security exception — graceful degradation
        }
    }

    fun handleRawCallState(rawState: Int, incomingNumber: String?) {
        when (rawState) {
            TelephonyManager.CALL_STATE_RINGING -> {
                android.util.Log.i("CallPipeline", "[CALL] INCOMING_DETECTED timestamp=${System.currentTimeMillis()} num=$incomingNumber")
                val isHandlingEnabled = CallHandlingSettingsRepository.isCallHandlingEnabledFast()
                if (isHandlingEnabled) {
                    android.util.Log.i("CallPipeline", "[CALL] HANDLE_MY_CALLS_ENABLED")
                    CallRingerSilenceController.getInstance(context).silenceRingerImmediately()
                    android.util.Log.i("CallPipeline", "[CALL] RINGER_SILENCED timestamp=${System.currentTimeMillis()}")
                }
                onIncomingCallRinging(incomingNumber)
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                android.util.Log.i("CallPipeline", "[CALL] CALL_OFFHOOK timestamp=${System.currentTimeMillis()}")
                CallRingerSilenceController.getInstance(context).restore()
                onCallOffhook(incomingNumber)
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                android.util.Log.i("CallPipeline", "[CALL] CALL_IDLE timestamp=${System.currentTimeMillis()}")
                CallRingerSilenceController.getInstance(context).restore()
                onCallEnded()
            }
        }
    }

    fun onOutgoingCallInitiated(targetName: String, targetNumber: String) {
        durationJob?.cancel()
        _callSessionState.update {
            it.copy(
                callState = CallState.OUTGOING_DIALING,
                callerName = targetName,
                callerNumber = targetNumber,
                callDurationSeconds = 0L,
                isMuted = false,
                isSpeakerOn = audioManager?.isSpeakerphoneOn == true,
                isHeld = false,
                isUnknownCaller = false,
                timestamp = System.currentTimeMillis(),
                statusMessage = "Dialing $targetName..."
            )
        }
        MicrophoneOwnershipCoordinator.notifyCallActive(true)
        LichiAssistantStateHub.onCalling(targetName)
    }

    fun onIncomingCallRinging(phoneNumber: String?) {
        durationJob?.cancel()
        val num = phoneNumber?.takeIf { it.isNotBlank() }

        val now = System.currentTimeMillis()
        Log.d("CallStateMonitor", "[CALL] INCOMING_DETECTED $now")

        val isHandlingEnabled = CallHandlingSettingsRepository.isCallHandlingEnabledFast()
        if (isHandlingEnabled) {
            Log.d("CallStateMonitor", "[CALL] HANDLE_MY_CALLS_ENABLED true")
            CallRingerSilenceController.getInstance(context).silenceRingerImmediately()
        } else {
            Log.d("CallStateMonitor", "[CALL] HANDLE_MY_CALLS_ENABLED false")
        }

        val currentSession = _callSessionState.value
        val effectiveNum = num ?: currentSession.callerNumber?.takeIf { it.isNotBlank() }
        val isUnknown = effectiveNum.isNullOrBlank()

        // Fast zero-delay synchronous lookup against in-memory contacts
        var resolvedName: String? = if (!effectiveNum.isNullOrBlank()) {
            contactRepository?.findFastMatch(effectiveNum)?.contact?.displayName
        } else null

        if (resolvedName == null && !currentSession.callerName.isNullOrBlank() && currentSession.callerName != "Unknown Caller") {
            resolvedName = currentSession.callerName
        }

        val freshSessionId = if (currentSession.callState.isRinging && currentSession.callSessionId.isNotBlank()) {
            currentSession.callSessionId
        } else {
            java.util.UUID.randomUUID().toString()
        }

        monitorScope.launch {
            if (resolvedName == null && !effectiveNum.isNullOrBlank() && contactRepository != null) {
                try {
                    withTimeoutOrNull(250) {
                        val match = contactRepository.findBestMatch(effectiveNum)
                        resolvedName = match?.contact?.displayName
                    }
                } catch (_: Exception) {}
            }
            Log.d("CallStateMonitor", "[CALL] CALLER_RESOLVED ${System.currentTimeMillis()}")

            _callSessionState.update { current ->
                val state = when {
                    current.callState.isDecisionPending -> current.callState
                    resolvedName != null -> CallState.INCOMING_KNOWN
                    !effectiveNum.isNullOrBlank() -> CallState.INCOMING_UNKNOWN
                    else -> CallState.INCOMING_RINGING
                }
                current.copy(
                    callState = state,
                    callerName = resolvedName ?: current.callerName ?: if (isUnknown) "Unknown Caller" else PhoneNumberNormalizer.formatForDisplay(effectiveNum ?: ""),
                    callerNumber = effectiveNum,
                    callDurationSeconds = 0L,
                    isMuted = false,
                    isSpeakerOn = false,
                    isHeld = false,
                    isUnknownCaller = isUnknown || (resolvedName == null && current.callerName == null),
                    timestamp = if (current.callState.isRinging) current.timestamp else now,
                    callSessionId = freshSessionId,
                    statusMessage = current.statusMessage ?: "Incoming call from ${resolvedName ?: effectiveNum ?: "Unknown"}"
                )
            }

            val targetDesc = resolvedName ?: effectiveNum ?: "Incoming Call"
            LichiAssistantStateHub.onCalling(targetDesc)
        }
    }

    fun onCallOffhook(phoneNumber: String? = null) {
        CallRingerSilenceController.getInstance(context).restore()
        val current = _callSessionState.value
        val newState = if (current.callState == CallState.OUTGOING_DIALING || current.callState == CallState.OUTGOING_RINGING) {
            CallState.ACTIVE
        } else if (current.callState.isRinging || current.callState == CallState.ANSWERING) {
            CallState.ACTIVE
        } else {
            CallState.ACTIVE
        }

        _callSessionState.update {
            it.copy(
                callState = newState,
                callerName = it.callerName ?: phoneNumber ?: "Active Call",
                callerNumber = it.callerNumber ?: phoneNumber,
                isMuted = audioManager?.isMicrophoneMute == true,
                isSpeakerOn = audioManager?.isSpeakerphoneOn == true,
                timestamp = System.currentTimeMillis(),
                statusMessage = "Call in progress"
            )
        }

        MicrophoneOwnershipCoordinator.notifyCallActive(true)
        startDurationTimer()
    }

    fun onCallAnswering() {
        _callSessionState.update {
            it.copy(
                callState = CallState.ANSWERING,
                statusMessage = "Answering call..."
            )
        }
    }

    fun onCallEnded(reason: String? = null) {
        durationJob?.cancel()
        CallRingerSilenceController.getInstance(context).restore()
        val current = _callSessionState.value

        _callSessionState.update {
            it.copy(
                callState = CallState.ENDED,
                statusMessage = reason ?: "Call ended"
            )
        }

        MicrophoneOwnershipCoordinator.notifyCallActive(false)

        monitorScope.launch {
            delay(1500)
            if (_callSessionState.value.callState == CallState.ENDED || _callSessionState.value.callState == CallState.REJECTED) {
                _callSessionState.value = CallSessionInfo(callState = CallState.IDLE)
                LichiAssistantStateHub.resetToIdle()
            }
        }
    }

    fun onCallRejected(reason: String? = null) {
        durationJob?.cancel()
        CallRingerSilenceController.getInstance(context).restore()
        _callSessionState.update {
            it.copy(
                callState = CallState.REJECTED,
                statusMessage = reason ?: "Call rejected"
            )
        }

        MicrophoneOwnershipCoordinator.notifyCallActive(false)

        monitorScope.launch {
            delay(1500)
            if (_callSessionState.value.callState == CallState.REJECTED) {
                _callSessionState.value = CallSessionInfo(callState = CallState.IDLE)
                LichiAssistantStateHub.resetToIdle()
            }
        }
    }

    fun updateAudioControls(isMuted: Boolean? = null, isSpeakerOn: Boolean? = null, isHeld: Boolean? = null) {
        _callSessionState.update { current ->
            val m = isMuted ?: current.isMuted
            val s = isSpeakerOn ?: current.isSpeakerOn
            val h = isHeld ?: current.isHeld

            val nextState = when {
                h -> CallState.HELD
                m -> CallState.MUTED
                s -> CallState.SPEAKER_ON
                current.callState.isConnectedOrActive -> CallState.ACTIVE
                else -> current.callState
            }

            current.copy(
                isMuted = m,
                isSpeakerOn = s,
                isHeld = h,
                callState = nextState
            )
        }
    }

    private fun startDurationTimer() {
        durationJob?.cancel()
        durationJob = monitorScope.launch {
            while (isActive) {
                delay(1000)
                if (_callSessionState.value.callState.isConnectedOrActive) {
                    _callSessionState.update {
                        it.copy(callDurationSeconds = it.callDurationSeconds + 1)
                    }
                } else {
                    break
                }
            }
        }
    }

    fun currentSession(): CallSessionInfo = _callSessionState.value

    fun updateDecisionQuestion(sessionId: String, question: String) {
        _callSessionState.update {
            it.copy(
                callSessionId = sessionId,
                callState = CallState.CALL_DECISION_REQUIRED,
                decisionQuestion = question,
                isWaitingForDecision = false,
                statusMessage = question
            )
        }
    }

    fun updateDecisionWaiting(isWaiting: Boolean) {
        _callSessionState.update {
            it.copy(
                callState = if (isWaiting) CallState.WAITING_FOR_CALL_COMMAND else CallState.CALL_DECISION_REQUIRED,
                isWaitingForDecision = isWaiting,
                statusMessage = if (isWaiting) "Listening for your decision..." else it.decisionQuestion
            )
        }
    }

    fun clearDecisionState() {
        _callSessionState.update {
            if (it.callState.isDecisionPending) {
                val fallbackState = when {
                    !it.callerName.isNullOrBlank() && !it.isUnknownCaller -> CallState.INCOMING_KNOWN
                    !it.callerNumber.isNullOrBlank() -> CallState.INCOMING_UNKNOWN
                    else -> CallState.INCOMING_RINGING
                }
                it.copy(
                    callState = fallbackState,
                    isWaitingForDecision = false,
                    statusMessage = "Incoming call from ${it.displayTitle}"
                )
            } else {
                it
            }
        }
    }

    fun updateSessionDirectly(updated: CallSessionInfo) {
        _callSessionState.value = updated
    }

    /**
     * Testing & Simulator affordance: simulates an incoming call or active call
     * to verify the complete UI, voice flows, and Dynamic Island controllers.
     */
    fun simulateIncomingCall(callerName: String = "Rahul Sharma", phoneNumber: String = "+91 98765 43210") {
        durationJob?.cancel()
        _callSessionState.value = CallSessionInfo(
            callState = CallState.INCOMING_KNOWN,
            callerName = callerName,
            callerNumber = phoneNumber,
            isUnknownCaller = false,
            statusMessage = "Incoming call from $callerName"
        )
        LichiAssistantStateHub.onCalling(callerName)
    }

    fun simulateActiveCall(callerName: String = "Rahul Sharma", phoneNumber: String = "+91 98765 43210") {
        _callSessionState.value = CallSessionInfo(
            callState = CallState.ACTIVE,
            callerName = callerName,
            callerNumber = phoneNumber,
            callDurationSeconds = 12,
            isMuted = false,
            isSpeakerOn = false,
            statusMessage = "Call in progress"
        )
        MicrophoneOwnershipCoordinator.notifyCallActive(true)
        startDurationTimer()
        LichiAssistantStateHub.onCalling(callerName)
    }

    fun simulateEndCall() {
        onCallEnded("Call ended by user")
    }
}
