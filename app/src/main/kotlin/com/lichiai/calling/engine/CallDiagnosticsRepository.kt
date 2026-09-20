package com.lichiai.calling.engine

import com.lichiai.calling.intent.CallResultStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

@Serializable
data class CallDiagnosticEvent(
    val id: String = java.util.UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val rawInput: String,
    val detectedAction: String,
    val targetText: String,
    val candidatesCount: Int,
    val simContactsSearched: Boolean,
    val deviceContactsSearched: Boolean,
    val matchedContactName: String? = null,
    val maskedPhoneNumber: String? = null,
    val status: CallResultStatus,
    val failureReason: String? = null,
    val executionMode: String // "TEXT" or "VOICE"
)

class CallDiagnosticsRepository {

    private val maxEvents = 30
    private val _events = MutableStateFlow<List<CallDiagnosticEvent>>(emptyList())
    val events: StateFlow<List<CallDiagnosticEvent>> = _events.asStateFlow()

    fun logEvent(event: CallDiagnosticEvent) {
        val updated = (listOf(event) + _events.value).take(maxEvents)
        _events.value = updated
    }

    fun clear() {
        _events.value = emptyList()
    }
}
