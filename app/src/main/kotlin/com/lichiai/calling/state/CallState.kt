package com.lichiai.calling.state

import java.util.Locale

/**
 * Authoritative single call state model for Lichi AI.
 */
enum class CallState {
    IDLE,
    INCOMING_RINGING,
    INCOMING_UNKNOWN,
    INCOMING_KNOWN,
    CALL_DECISION_REQUIRED,
    WAITING_FOR_CALL_COMMAND,
    ANSWERING,
    ACTIVE,
    OUTGOING_DIALING,
    OUTGOING_RINGING,
    HELD,
    MUTED,
    SPEAKER_ON,
    ENDING,
    ENDED,
    REJECTED,
    MISSED,
    BUSY,
    FAILED,
    PERMISSION_REQUIRED,
    SIM_UNAVAILABLE,
    CALL_SERVICE_UNAVAILABLE,
    ERROR;

    val isRinging: Boolean
        get() = this == INCOMING_RINGING || this == INCOMING_KNOWN || this == INCOMING_UNKNOWN ||
                this == CALL_DECISION_REQUIRED || this == WAITING_FOR_CALL_COMMAND

    val isDecisionPending: Boolean
        get() = this == CALL_DECISION_REQUIRED || this == WAITING_FOR_CALL_COMMAND

    val isConnectedOrActive: Boolean
        get() = this == ACTIVE || this == MUTED || this == SPEAKER_ON || this == HELD

    val isOutgoing: Boolean
        get() = this == OUTGOING_DIALING || this == OUTGOING_RINGING

    val isTerminal: Boolean
        get() = this == ENDED || this == REJECTED || this == MISSED || this == BUSY || this == FAILED || this == ERROR
}

data class CallSessionInfo(
    val callState: CallState = CallState.IDLE,
    val callerName: String? = null,
    val callerNumber: String? = null,
    val callDurationSeconds: Long = 0L,
    val isMuted: Boolean = false,
    val isSpeakerOn: Boolean = false,
    val isHeld: Boolean = false,
    val isUnknownCaller: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
    val statusMessage: String? = null,
    val callSessionId: String = "",
    val decisionQuestion: String? = null,
    val isWaitingForDecision: Boolean = false,
    val isClarifyingNegative: Boolean = false
) {
    val formattedDuration: String
        get() {
            val minutes = callDurationSeconds / 60
            val seconds = callDurationSeconds % 60
            return String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }

    val displayTitle: String
        get() {
            return when {
                !callerName.isNullOrBlank() -> callerName
                !callerNumber.isNullOrBlank() -> callerNumber
                isUnknownCaller -> "Unknown Number"
                else -> "Call"
            }
        }

    val isCallActiveOrRinging: Boolean
        get() = callState.isRinging || callState.isConnectedOrActive || callState.isOutgoing || callState == CallState.ANSWERING
}
