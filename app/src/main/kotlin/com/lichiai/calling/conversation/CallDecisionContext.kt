package com.lichiai.calling.conversation

import com.lichiai.calling.state.CallState

/**
 * Encapsulates the ongoing call decision state and conversational context.
 * Preserves caller identity, question asked, and session identifier across
 * the TTS speech synthesis to STT speech recognition handoff.
 */
data class CallDecisionContext(
    val callSessionId: String,
    val callerName: String? = null,
    val callerNumber: String? = null,
    val callDirection: String = "INCOMING",
    val currentCallState: CallState = CallState.CALL_DECISION_REQUIRED,
    val questionAsked: String = "",
    val awaitingDecision: Boolean = false,
    val isClarifyingNegative: Boolean = false,
    val handleMyCallsEnabled: Boolean = true,
    val awaitingUserDecision: Boolean = awaitingDecision,
    val ttsActive: Boolean = false,
    val sttActive: Boolean = false,
    val decisionDeadline: Long = 0L,
    val createdAt: Long = System.currentTimeMillis(),
    val timestamp: Long = createdAt
) {
    val phoneNumber: String? get() = callerNumber
    val resolvedCallerName: String? get() = callerName
    val currentTelephonyState: CallState get() = currentCallState
}
