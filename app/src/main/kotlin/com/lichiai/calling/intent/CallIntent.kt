package com.lichiai.calling.intent

import com.lichiai.calling.contacts.ContactCandidate
import kotlinx.serialization.Serializable

enum class CallAction {
    CALL_CONTACT,
    CALL_NUMBER,
    NO_CALL_INTENT,
    AMBIGUOUS_CALL,
    INVALID_CALL_TARGET
}

@Serializable
data class CallIntent(
    val action: CallAction,
    val targetText: String = "",
    val phoneNumber: String? = null,
    val simSlot: Int? = null,
    val confidence: Float = 1.0f,
    val originalText: String = ""
)

enum class CallResultStatus {
    SUCCESS_STARTED,
    PERMISSION_REQUIRED,
    CONTACT_NOT_FOUND,
    AMBIGUOUS_CONTACT,
    NO_PHONE_NUMBER,
    INVALID_NUMBER,
    NO_TELEPHONY,
    CALL_INTENT_NOT_FOUND,
    DUPLICATE_DEBOUNCED,
    SYSTEM_ERROR
}

data class CallResult(
    val status: CallResultStatus,
    val targetName: String? = null,
    val targetNumber: String? = null,
    val message: String = "",
    val candidateOptions: List<ContactCandidate> = emptyList()
)
