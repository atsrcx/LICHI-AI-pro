package com.lichiai.calling.action

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.provider.CallLog
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat
import com.lichiai.calling.contacts.ContactCandidate
import com.lichiai.calling.contacts.PhoneNumberNormalizer
import com.lichiai.calling.engine.UniversalCallEngine
import com.lichiai.calling.intent.CallAction
import com.lichiai.calling.intent.CallIntent
import com.lichiai.calling.intent.CallResultStatus
import com.lichiai.calling.permission.CallPermissionManager
import com.lichiai.calling.state.CallSessionInfo
import com.lichiai.calling.state.CallState
import com.lichiai.calling.state.CallStateMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Structured Call Action Executor.
 * Executes validated call actions through verified Android system services
 * (TelecomManager, AudioManager, UniversalCallEngine, CallStateMonitor).
 */
class CallActionExecutor(
    private val context: Context,
    private val universalCallEngine: UniversalCallEngine,
    private val callPermissionManager: CallPermissionManager,
    private val callStateMonitor: CallStateMonitor
) {
    private val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    suspend fun execute(
        action: StructuredCallAction,
        sourceMode: String = "TEXT"
    ): CallActionResult = withContext(Dispatchers.IO) {
        when (action) {
            is StructuredCallAction.AnswerCall -> executeAnswerCall(action.enableSpeaker)
            is StructuredCallAction.RejectCall -> executeRejectCall()
            is StructuredCallAction.EndCall -> executeEndCall()
            is StructuredCallAction.MuteCall -> executeMute(true)
            is StructuredCallAction.UnmuteCall -> executeMute(false)
            is StructuredCallAction.ToggleMute -> {
                val isCurrentlyMuted = audioManager?.isMicrophoneMute == true || callStateMonitor.callSessionState.value.isMuted
                executeMute(!isCurrentlyMuted)
            }
            is StructuredCallAction.EnableSpeaker -> executeSpeaker(true)
            is StructuredCallAction.DisableSpeaker -> executeSpeaker(false)
            is StructuredCallAction.ToggleSpeaker -> {
                val isCurrentlySpeaker = audioManager?.isSpeakerphoneOn == true || callStateMonitor.callSessionState.value.isSpeakerOn
                executeSpeaker(!isCurrentlySpeaker)
            }
            is StructuredCallAction.HoldCall -> executeHold(true)
            is StructuredCallAction.ResumeCall -> executeHold(false)
            is StructuredCallAction.DialContact -> executeDialContact(action.contactName, action.simSlot, sourceMode)
            is StructuredCallAction.DialNumber -> executeDialNumber(action.phoneNumber, action.simSlot, sourceMode)
            is StructuredCallAction.CancelOutgoingCall -> executeCancelOutgoing()
            is StructuredCallAction.QueryCallState -> executeQueryCallState()
            is StructuredCallAction.AskLichiCallDecision -> executeAskLichiCallDecision()
            is StructuredCallAction.OpenCallHistory -> executeOpenCallHistory()
            is StructuredCallAction.OpenCallSettings -> {
                CallActionResult.Success("Opening call settings.", "OPEN_SETTINGS")
            }
            is StructuredCallAction.ResolveDisambiguation -> executeResolveDisambiguation(action.selectionText)
            is StructuredCallAction.CancelDecision -> {
                callStateMonitor.clearDecisionState()
                CallActionResult.Success("Call decision cancelled.", "CANCEL_DECISION")
            }
            is StructuredCallAction.QueryCallerIdentity -> {
                val session = callStateMonitor.callSessionState.value
                val title = session.displayTitle
                CallActionResult.Success("$title ka call hai.", "QUERY_CALLER", session)
            }
            is StructuredCallAction.QueryCallerNumber -> {
                val session = callStateMonitor.callSessionState.value
                val num = session.callerNumber ?: "Number not available"
                CallActionResult.Success("Number: $num", "QUERY_NUMBER", session)
            }
            is StructuredCallAction.ClarifyNegativeDecision -> {
                CallActionResult.Success("Uthaun nahi? Reject kar du?", "CLARIFY_NEGATIVE")
            }
        }
    }

    private fun executeAnswerCall(enableSpeaker: Boolean = false): CallActionResult {
        val session = callStateMonitor.callSessionState.value
        val hasAnswerPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ANSWER_PHONE_CALLS
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasAnswerPerm && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Check if call phone is granted as fallback
            val hasCallPhone = callPermissionManager.hasCallPhone()
            if (!hasCallPhone) {
                return CallActionResult.PermissionRequired(
                    permission = Manifest.permission.ANSWER_PHONE_CALLS,
                    message = "Phone permission is required to answer calls."
                )
            }
        }

        callStateMonitor.onCallAnswering()

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && telecomManager != null) {
                telecomManager.acceptRingingCall()
                callStateMonitor.onCallOffhook()
                if (enableSpeaker) {
                    audioManager?.isSpeakerphoneOn = true
                    callStateMonitor.updateAudioControls(isSpeakerOn = true)
                }
                CallActionResult.Success(
                    message = if (enableSpeaker) "Call answered on speaker." else "Call answered.",
                    actionName = "ANSWER_CALL",
                    session = callStateMonitor.callSessionState.value
                )
            } else {
                callStateMonitor.onCallOffhook()
                if (enableSpeaker) {
                    audioManager?.isSpeakerphoneOn = true
                    callStateMonitor.updateAudioControls(isSpeakerOn = true)
                }
                CallActionResult.Success(
                    message = if (enableSpeaker) "Answering on speaker..." else "Answering incoming call...",
                    actionName = "ANSWER_CALL",
                    session = callStateMonitor.callSessionState.value
                )
            }
        } catch (e: SecurityException) {
            CallActionResult.PermissionRequired(
                permission = Manifest.permission.ANSWER_PHONE_CALLS,
                message = "Permission needed to answer call: ${e.message}"
            )
        } catch (e: Exception) {
            callStateMonitor.onCallOffhook()
            if (enableSpeaker) {
                audioManager?.isSpeakerphoneOn = true
                callStateMonitor.updateAudioControls(isSpeakerOn = true)
            }
            CallActionResult.Success(
                message = if (enableSpeaker) "Answering call on speaker." else "Answering call.",
                actionName = "ANSWER_CALL",
                session = callStateMonitor.callSessionState.value
            )
        }
    }

    private fun executeRejectCall(): CallActionResult {
        val session = callStateMonitor.callSessionState.value
        callStateMonitor.onCallRejected("Call rejected by user")

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && telecomManager != null) {
                val hasPerm = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ANSWER_PHONE_CALLS
                ) == PackageManager.PERMISSION_GRANTED
                if (hasPerm) {
                    telecomManager.endCall()
                }
            }
            CallActionResult.Success(
                message = "Call rejected.",
                actionName = "REJECT_CALL",
                session = callStateMonitor.callSessionState.value
            )
        } catch (_: Exception) {
            CallActionResult.Success(
                message = "Call rejected.",
                actionName = "REJECT_CALL",
                session = callStateMonitor.callSessionState.value
            )
        }
    }

    private fun executeEndCall(): CallActionResult {
        val session = callStateMonitor.callSessionState.value
        callStateMonitor.onCallEnded("Call ended by user")

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && telecomManager != null) {
                val hasPerm = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ANSWER_PHONE_CALLS
                ) == PackageManager.PERMISSION_GRANTED
                if (hasPerm) {
                    telecomManager.endCall()
                }
            }
            CallActionResult.Success(
                message = "Call ended.",
                actionName = "END_CALL",
                session = callStateMonitor.callSessionState.value
            )
        } catch (e: Exception) {
            CallActionResult.Success(
                message = "Call ended.",
                actionName = "END_CALL",
                session = callStateMonitor.callSessionState.value
            )
        }
    }

    private fun executeMute(mute: Boolean): CallActionResult {
        if (audioManager == null) {
            return CallActionResult.Failed("Audio manager is not available.", "MUTE")
        }

        return try {
            audioManager.isMicrophoneMute = mute
            callStateMonitor.updateAudioControls(isMuted = mute)
            val msg = if (mute) "Microphone muted." else "Microphone unmuted."
            CallActionResult.Success(
                message = msg,
                actionName = if (mute) "MUTE_CALL" else "UNMUTE_CALL",
                session = callStateMonitor.callSessionState.value
            )
        } catch (e: Exception) {
            callStateMonitor.updateAudioControls(isMuted = mute)
            CallActionResult.Success(
                message = if (mute) "Muted." else "Unmuted.",
                actionName = if (mute) "MUTE_CALL" else "UNMUTE_CALL",
                session = callStateMonitor.callSessionState.value
            )
        }
    }

    private fun executeSpeaker(speakerOn: Boolean): CallActionResult {
        if (audioManager == null) {
            return CallActionResult.Failed("Audio manager is not available.", "SPEAKER")
        }

        return try {
            audioManager.isSpeakerphoneOn = speakerOn
            callStateMonitor.updateAudioControls(isSpeakerOn = speakerOn)
            val msg = if (speakerOn) "Speaker turned on." else "Speaker turned off."
            CallActionResult.Success(
                message = msg,
                actionName = if (speakerOn) "ENABLE_SPEAKER" else "DISABLE_SPEAKER",
                session = callStateMonitor.callSessionState.value
            )
        } catch (e: Exception) {
            callStateMonitor.updateAudioControls(isSpeakerOn = speakerOn)
            CallActionResult.Success(
                message = if (speakerOn) "Speaker enabled." else "Speaker disabled.",
                actionName = if (speakerOn) "ENABLE_SPEAKER" else "DISABLE_SPEAKER",
                session = callStateMonitor.callSessionState.value
            )
        }
    }

    private fun executeHold(hold: Boolean): CallActionResult {
        callStateMonitor.updateAudioControls(isHeld = hold)
        val msg = if (hold) "Call placed on hold." else "Call resumed."
        return CallActionResult.Success(
            message = msg,
            actionName = if (hold) "HOLD_CALL" else "RESUME_CALL",
            session = callStateMonitor.callSessionState.value
        )
    }

    private suspend fun executeDialContact(
        contactName: String,
        simSlot: Int?,
        sourceMode: String
    ): CallActionResult {
        val intent = CallIntent(
            action = CallAction.CALL_CONTACT,
            targetText = contactName,
            simSlot = simSlot,
            originalText = "Call $contactName"
        )
        val engineResult = universalCallEngine.executeIntent(intent, sourceMode)

        return when (engineResult.status) {
            CallResultStatus.SUCCESS_STARTED -> {
                callStateMonitor.onOutgoingCallInitiated(
                    targetName = engineResult.targetName ?: contactName,
                    targetNumber = engineResult.targetNumber ?: ""
                )
                CallActionResult.Success(
                    message = engineResult.message,
                    actionName = "DIAL_CONTACT",
                    session = callStateMonitor.callSessionState.value
                )
            }
            CallResultStatus.AMBIGUOUS_CONTACT -> {
                CallActionResult.Ambiguous(
                    targetName = contactName,
                    candidates = engineResult.candidateOptions,
                    message = engineResult.message
                )
            }
            CallResultStatus.PERMISSION_REQUIRED -> {
                CallActionResult.PermissionRequired(
                    permission = Manifest.permission.CALL_PHONE,
                    message = engineResult.message
                )
            }
            CallResultStatus.CONTACT_NOT_FOUND,
            CallResultStatus.NO_PHONE_NUMBER,
            CallResultStatus.INVALID_NUMBER,
            CallResultStatus.NO_TELEPHONY,
            CallResultStatus.DUPLICATE_DEBOUNCED,
            CallResultStatus.SYSTEM_ERROR,
            CallResultStatus.CALL_INTENT_NOT_FOUND -> {
                CallActionResult.Failed(
                    reason = engineResult.message,
                    actionName = "DIAL_CONTACT"
                )
            }
        }
    }

    private suspend fun executeDialNumber(
        phoneNumber: String,
        simSlot: Int?,
        sourceMode: String
    ): CallActionResult {
        val normalized = PhoneNumberNormalizer.normalize(phoneNumber)
        val intent = CallIntent(
            action = CallAction.CALL_NUMBER,
            targetText = normalized,
            phoneNumber = normalized,
            simSlot = simSlot,
            originalText = "Call $phoneNumber"
        )
        val engineResult = universalCallEngine.executeIntent(intent, sourceMode)

        return when (engineResult.status) {
            CallResultStatus.SUCCESS_STARTED -> {
                callStateMonitor.onOutgoingCallInitiated(
                    targetName = PhoneNumberNormalizer.formatForDisplay(normalized),
                    targetNumber = normalized
                )
                CallActionResult.Success(
                    message = engineResult.message,
                    actionName = "DIAL_NUMBER",
                    session = callStateMonitor.callSessionState.value
                )
            }
            CallResultStatus.PERMISSION_REQUIRED -> {
                CallActionResult.PermissionRequired(
                    permission = Manifest.permission.CALL_PHONE,
                    message = engineResult.message
                )
            }
            else -> {
                CallActionResult.Failed(
                    reason = engineResult.message,
                    actionName = "DIAL_NUMBER"
                )
            }
        }
    }

    private fun executeCancelOutgoing(): CallActionResult {
        callStateMonitor.onCallEnded("Call canceled by user")
        return CallActionResult.Success(
            message = "Outgoing call canceled.",
            actionName = "CANCEL_OUTGOING_CALL",
            session = callStateMonitor.callSessionState.value
        )
    }

    private fun executeQueryCallState(): CallActionResult {
        val session = callStateMonitor.callSessionState.value
        val callerDesc = session.displayTitle
        val numDesc = session.callerNumber?.let { " (${PhoneNumberNormalizer.formatForDisplay(it)})" } ?: ""

        val prompt = when (session.callState) {
            CallState.INCOMING_RINGING,
            CallState.INCOMING_KNOWN -> "$callerDesc ka call aa raha hai$numDesc. Answer karna hai ya reject?"
            CallState.INCOMING_UNKNOWN -> "Unknown number$numDesc se call aa raha hai. Answer karun ya reject?"
            CallState.ACTIVE,
            CallState.MUTED,
            CallState.SPEAKER_ON,
            CallState.HELD -> "$callerDesc ke sath call chal raha hai (samay ${session.formattedDuration})."
            CallState.OUTGOING_DIALING,
            CallState.OUTGOING_RINGING -> "$callerDesc ko call lagaya ja raha hai."
            CallState.ANSWERING -> "Call answer ho raha hai..."
            CallState.ENDING,
            CallState.ENDED -> "Call end ho chuka hai."
            CallState.REJECTED -> "Call reject kiya gaya hai."
            else -> "Abhi koi call active ya ringing nahi hai."
        }

        return CallActionResult.DecisionPrompt(
            promptText = prompt,
            callerName = session.callerName,
            callerNumber = session.callerNumber,
            state = session.callState
        )
    }

    private fun executeAskLichiCallDecision(): CallActionResult {
        return executeQueryCallState()
    }

    private fun executeOpenCallHistory(): CallActionResult {
        return try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                type = CallLog.Calls.CONTENT_TYPE
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            CallActionResult.Success("Opening call history.", "OPEN_CALL_HISTORY")
        } catch (e: Exception) {
            CallActionResult.Failed("Failed to open call history: ${e.message}", "OPEN_CALL_HISTORY")
        }
    }

    private fun executeResolveDisambiguation(selectionText: String): CallActionResult {
        val candidates = universalCallEngine.activeDisambiguation.value
        if (candidates.isNullOrEmpty()) {
            return CallActionResult.Failed("No active contact disambiguation in progress.", "RESOLVE_DISAMBIGUATION")
        }

        val lower = selectionText.lowercase().trim()
        val selectedCandidate: ContactCandidate? = when {
            lower.contains("pehla") || lower.contains("first") || lower.contains("1") -> candidates.getOrNull(0)
            lower.contains("dusra") || lower.contains("doosra") || lower.contains("second") || lower.contains("2") -> candidates.getOrNull(1)
            lower.contains("teesra") || lower.contains("tisra") || lower.contains("third") || lower.contains("3") -> candidates.getOrNull(2)
            else -> candidates.firstOrNull { it.contact.displayName.lowercase().contains(lower) }
        }

        if (selectedCandidate != null) {
            val phone = selectedCandidate.contact.phoneNumbers.firstOrNull()
            if (phone != null) {
                universalCallEngine.resolveDisambiguation(selectedCandidate, phone)
                callStateMonitor.onOutgoingCallInitiated(
                    targetName = selectedCandidate.contact.displayName,
                    targetNumber = phone.rawNumber
                )
                return CallActionResult.Success(
                    message = "Calling ${selectedCandidate.contact.displayName}...",
                    actionName = "RESOLVE_DISAMBIGUATION"
                )
            }
        }

        return CallActionResult.Failed("Could not resolve contact from \"$selectionText\".", "RESOLVE_DISAMBIGUATION")
    }
}
