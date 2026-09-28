package com.lichiai.terminal.task

import com.lichiai.intent.model.LichiCapability
import com.lichiai.terminal.model.TerminalBackendType
import com.lichiai.terminal.model.TerminalRiskLevel
import kotlinx.serialization.Serializable

enum class TerminalTaskStatus {
    STARTED,
    CONNECTING,
    CONNECTED,
    EXECUTING,
    VERIFYING,
    COMPLETED,
    FAILED,
    CANCELLED
}

@Serializable
data class TerminalTaskEvent(
    val id: String,
    val taskId: String,
    val timestamp: Long = System.currentTimeMillis(),
    val eventType: String,
    val summary: String,
    val detail: String? = null
)

@Serializable
data class TerminalTaskRecord(
    val taskId: String,
    val requestId: String = "",
    val conversationMessageId: String = "",
    val capability: String = "TERMINAL",
    val backendType: TerminalBackendType = TerminalBackendType.LOCAL_TERMUX,
    val targetHost: String? = null,
    val username: String? = null,
    val command: String,
    val status: TerminalTaskStatus = TerminalTaskStatus.STARTED,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val durationMs: Long = 0L,
    val outputSummary: String = "",
    val rawOutput: String = "",
    val error: String? = null,
    val riskLevel: TerminalRiskLevel = TerminalRiskLevel.LOW_RISK,
    val events: List<TerminalTaskEvent> = emptyList()
) {
    val isFinished: Boolean
        get() = status == TerminalTaskStatus.COMPLETED ||
                status == TerminalTaskStatus.FAILED ||
                status == TerminalTaskStatus.CANCELLED
}
