package com.lichiai.toolruntime.tools

import com.lichiai.calling.action.CallActionExecutor
import com.lichiai.calling.action.CallActionResult
import com.lichiai.calling.action.StructuredCallAction
import com.lichiai.calling.engine.UniversalCallEngine
import com.lichiai.calling.intent.CallAction
import com.lichiai.calling.intent.CallIntent
import com.lichiai.calling.intent.CallResultStatus
import com.lichiai.intent.model.LichiCapability
import com.lichiai.toolruntime.core.LichiTool
import com.lichiai.toolruntime.model.ToolCall
import com.lichiai.toolruntime.model.ToolCategory
import com.lichiai.toolruntime.model.ToolDefinition
import com.lichiai.toolruntime.model.ToolExecutionContext
import com.lichiai.toolruntime.model.ToolParameter
import com.lichiai.toolruntime.model.ToolResult
import com.lichiai.toolruntime.model.ToolRiskLevel
import com.lichiai.toolruntime.model.VerificationResult

/**
 * Real Telephony Call Tool.
 */
class CallContactTool(
    private val universalCallEngine: UniversalCallEngine
) : LichiTool {

    override val definition = ToolDefinition(
        id = "call.contact",
        name = "Place Phone Call",
        description = "Places an outgoing phone call to a named contact or phone number.",
        purpose = "Make a phone call.",
        category = ToolCategory.TELEPHONY,
        mappedCapability = LichiCapability.CALLS,
        parameters = listOf(
            ToolParameter("target", "string", "Contact name or phone number to call (e.g. 'Rahul', 'Mummy', '9876543210')", required = true)
        ),
        riskLevel = ToolRiskLevel.EXTERNAL_COMMUNICATION,
        requiresConfirmation = false,
        idempotent = false,
        timeoutMs = 15_000L,
        requiresNetwork = false,
        changesWorldState = true
    )

    override suspend fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val target = call.arguments["target"]?.trim() ?: context.userGoal
        if (target.isBlank()) {
            return ToolResult.failure(call.callId, definition.id, "Call target is empty.")
        }

        context.onProgress?.invoke(1, 2, "Placing call to $target...")

        return try {
            val intent = CallIntent(
                action = CallAction.CALL_CONTACT,
                originalText = target,
                targetText = target
            )
            val outcome = universalCallEngine.executeIntent(intent)
            if (outcome.status == CallResultStatus.SUCCESS_STARTED) {
                ToolResult.success(
                    callId = call.callId,
                    toolId = definition.id,
                    summary = outcome.message,
                    data = mapOf("target" to target)
                )
            } else {
                ToolResult.failure(call.callId, definition.id, outcome.message)
            }
        } catch (e: Exception) {
            ToolResult.failure(call.callId, definition.id, "Call failed: ${e.message}")
        }
    }

    override suspend fun verify(call: ToolCall, result: ToolResult, context: ToolExecutionContext): VerificationResult {
        return VerificationResult(
            isVerified = result.isSuccess,
            verifiedState = if (result.isSuccess) "Call placed to ${call.arguments["target"]}" else "Call failed",
            notes = "Checked UniversalCallEngine outcome"
        )
    }
}

/**
 * Real Call Action Tool (answer, end, mute, speaker).
 */
class CallActionTool(
    private val callActionExecutor: CallActionExecutor
) : LichiTool {

    override val definition = ToolDefinition(
        id = "call.action",
        name = "Call Control Action",
        description = "Controls an active phone call: answer, reject, end, mute, unmute, speaker_on, speaker_off.",
        purpose = "Manage live phone call state.",
        category = ToolCategory.TELEPHONY,
        mappedCapability = LichiCapability.CALLS,
        parameters = listOf(
            ToolParameter("action", "string", "Action to perform: 'answer', 'reject', 'end', 'mute', 'speaker'", required = true, enumValues = listOf("answer", "reject", "end", "mute", "speaker"))
        ),
        riskLevel = ToolRiskLevel.LOW_RISK_STATE_CHANGE,
        requiresConfirmation = false,
        idempotent = false,
        timeoutMs = 10_000L,
        changesWorldState = true
    )

    override suspend fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val actionStr = call.arguments["action"]?.trim()?.lowercase() ?: ""
        val structuredAction = when (actionStr) {
            "answer" -> StructuredCallAction.AnswerCall()
            "reject" -> StructuredCallAction.RejectCall
            "end", "hangup" -> StructuredCallAction.EndCall
            "mute" -> StructuredCallAction.MuteCall
            "unmute" -> StructuredCallAction.UnmuteCall
            "speaker", "speaker_on" -> StructuredCallAction.EnableSpeaker
            "speaker_off" -> StructuredCallAction.DisableSpeaker
            else -> return ToolResult.failure(call.callId, definition.id, "Unsupported call action '$actionStr'")
        }

        return try {
            val result = callActionExecutor.execute(structuredAction)
            if (result is CallActionResult.Success) {
                ToolResult.success(call.callId, definition.id, result.message)
            } else {
                ToolResult.failure(call.callId, definition.id, result.message)
            }
        } catch (e: Exception) {
            ToolResult.failure(call.callId, definition.id, "Call action failed: ${e.message}")
        }
    }

    override suspend fun verify(call: ToolCall, result: ToolResult, context: ToolExecutionContext): VerificationResult {
        return VerificationResult(
            isVerified = result.isSuccess,
            verifiedState = if (result.isSuccess) "Call action executed" else "Action rejected",
            notes = "Call action executor response"
        )
    }
}
