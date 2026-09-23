package com.lichiai.calling.action

import com.lichiai.calling.contacts.ContactCandidate
import com.lichiai.calling.state.CallSessionInfo
import com.lichiai.calling.state.CallState

sealed interface StructuredCallAction {
    data class AnswerCall(val enableSpeaker: Boolean = false) : StructuredCallAction {
        companion object {
            operator fun invoke(): AnswerCall = AnswerCall(false)
        }
    }
    data object RejectCall : StructuredCallAction
    data object EndCall : StructuredCallAction
    data object MuteCall : StructuredCallAction
    data object UnmuteCall : StructuredCallAction
    data object ToggleMute : StructuredCallAction
    data object EnableSpeaker : StructuredCallAction
    data object DisableSpeaker : StructuredCallAction
    data object ToggleSpeaker : StructuredCallAction
    data object HoldCall : StructuredCallAction
    data object ResumeCall : StructuredCallAction
    data class DialContact(val contactName: String, val simSlot: Int? = null) : StructuredCallAction
    data class DialNumber(val phoneNumber: String, val simSlot: Int? = null) : StructuredCallAction
    data object CancelOutgoingCall : StructuredCallAction
    data object QueryCallState : StructuredCallAction
    data object AskLichiCallDecision : StructuredCallAction
    data object OpenCallHistory : StructuredCallAction
    data object OpenCallSettings : StructuredCallAction
    data class ResolveDisambiguation(val selectionText: String) : StructuredCallAction
    data object CancelDecision : StructuredCallAction
    data object QueryCallerIdentity : StructuredCallAction
    data object QueryCallerNumber : StructuredCallAction
    data object ClarifyNegativeDecision : StructuredCallAction
}

sealed class CallActionResult {
    abstract val message: String

    data class Success(
        override val message: String,
        val actionName: String,
        val session: CallSessionInfo? = null
    ) : CallActionResult()

    data class Failed(
        val reason: String,
        val actionName: String
    ) : CallActionResult() {
        override val message: String get() = reason
    }

    data class PermissionRequired(
        val permission: String,
        override val message: String
    ) : CallActionResult()

    data class Unsupported(
        val capability: String,
        override val message: String
    ) : CallActionResult()

    data class Ambiguous(
        val targetName: String,
        val candidates: List<ContactCandidate>,
        override val message: String
    ) : CallActionResult()

    data class NoActiveCall(
        override val message: String = "No call is currently active or ringing."
    ) : CallActionResult()

    data class DecisionPrompt(
        val promptText: String,
        val callerName: String?,
        val callerNumber: String?,
        val state: CallState
    ) : CallActionResult() {
        override val message: String get() = promptText
    }
}
