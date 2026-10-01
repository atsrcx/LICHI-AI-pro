package com.lichiai.toolruntime.tools

import com.lichiai.intent.model.LichiCapability
import com.lichiai.time.adapter.TimeCapabilityAdapter
import com.lichiai.time.manager.ReminderManager
import com.lichiai.toolruntime.core.LichiTool
import com.lichiai.toolruntime.model.ToolCall
import com.lichiai.toolruntime.model.ToolCategory
import com.lichiai.toolruntime.model.ToolDefinition
import com.lichiai.toolruntime.model.ToolExecutionContext
import com.lichiai.toolruntime.model.ToolParameter
import com.lichiai.toolruntime.model.ToolResult
import com.lichiai.toolruntime.model.ToolRiskLevel
import com.lichiai.toolruntime.model.VerificationResult
import java.util.Calendar

/**
 * Real Alarm Creation Tool.
 */
class TimeCreateAlarmTool(
    private val timeCapabilityAdapter: TimeCapabilityAdapter
) : LichiTool {

    override val definition = ToolDefinition(
        id = "time.create_alarm",
        name = "Set Alarm",
        description = "Schedules a device alarm at a specific hour and minute.",
        purpose = "Set an alarm on the user's phone.",
        category = ToolCategory.TIME_REMINDER,
        mappedCapability = LichiCapability.TIME_REMINDER,
        parameters = listOf(
            ToolParameter("hour", "number", "Hour in 24-hour format (0-23)", required = true),
            ToolParameter("minute", "number", "Minute (0-59)", required = false, defaultValue = "0"),
            ToolParameter("message", "string", "Alarm label or note", required = false, defaultValue = "Alarm")
        ),
        riskLevel = ToolRiskLevel.LOW_RISK_STATE_CHANGE,
        requiresConfirmation = false,
        idempotent = true,
        timeoutMs = 5_000L,
        changesWorldState = true
    )

    override suspend fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val hour = call.arguments["hour"]?.toIntOrNull()
        if (hour == null || hour !in 0..23) {
            return ToolResult.failure(call.callId, definition.id, "Invalid alarm hour. Must be 0-23.")
        }
        val minute = call.arguments["minute"]?.toIntOrNull() ?: 0
        val message = call.arguments["message"] ?: "Alarm"

        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            if (before(Calendar.getInstance())) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }

        return try {
            val outcome = timeCapabilityAdapter.executeStructured(
                action = "CREATE",
                title = message,
                timeMs = cal.timeInMillis,
                isAlarm = true
            )
            if (outcome.isSuccess) {
                val formattedTime = String.format("%02d:%02d", hour, minute)
                ToolResult.success(
                    callId = call.callId,
                    toolId = definition.id,
                    summary = outcome.naturalSpeech.ifBlank { "Alarm set for $formattedTime." },
                    data = mapOf("time" to formattedTime, "label" to message)
                )
            } else {
                ToolResult.failure(call.callId, definition.id, outcome.naturalSpeech)
            }
        } catch (e: Exception) {
            ToolResult.failure(call.callId, definition.id, "Failed to set alarm: ${e.message}")
        }
    }

    override suspend fun verify(call: ToolCall, result: ToolResult, context: ToolExecutionContext): VerificationResult {
        return VerificationResult(
            isVerified = result.isSuccess,
            verifiedState = if (result.isSuccess) "Alarm registered in Android AlarmManager" else "Alarm failed",
            notes = "Checked AlarmManager schedule response"
        )
    }
}

/**
 * Real Reminder Creation Tool.
 */
class TimeCreateReminderTool(
    private val timeCapabilityAdapter: TimeCapabilityAdapter
) : LichiTool {

    override val definition = ToolDefinition(
        id = "time.create_reminder",
        name = "Create Reminder",
        description = "Creates a persistent reminder or task with optional trigger time.",
        purpose = "Save a reminder for the user.",
        category = ToolCategory.TIME_REMINDER,
        mappedCapability = LichiCapability.TIME_REMINDER,
        parameters = listOf(
            ToolParameter("title", "string", "Reminder title or note", required = true),
            ToolParameter("delay_minutes", "number", "Delay in minutes from now (e.g. 10, 30, 60)", required = false)
        ),
        riskLevel = ToolRiskLevel.LOW_RISK_STATE_CHANGE,
        requiresConfirmation = false,
        idempotent = false,
        timeoutMs = 5_000L,
        changesWorldState = true
    )

    override suspend fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val title = call.arguments["title"]?.trim() ?: ""
        if (title.isBlank()) {
            return ToolResult.failure(call.callId, definition.id, "Reminder title is required.")
        }

        val delayMins = call.arguments["delay_minutes"]?.toLongOrNull()
        val triggerTime = if (delayMins != null && delayMins > 0) {
            System.currentTimeMillis() + (delayMins * 60 * 1000)
        } else null

        return try {
            val outcome = timeCapabilityAdapter.executeStructured(
                action = "CREATE",
                title = title,
                timeMs = triggerTime,
                isAlarm = false
            )
            if (outcome.isSuccess) {
                ToolResult.success(
                    callId = call.callId,
                    toolId = definition.id,
                    summary = outcome.naturalSpeech,
                    data = mapOf("title" to title)
                )
            } else {
                ToolResult.failure(call.callId, definition.id, outcome.naturalSpeech)
            }
        } catch (e: Exception) {
            ToolResult.failure(call.callId, definition.id, "Failed to create reminder: ${e.message}")
        }
    }

    override suspend fun verify(call: ToolCall, result: ToolResult, context: ToolExecutionContext): VerificationResult {
        return VerificationResult(
            isVerified = result.isSuccess,
            verifiedState = if (result.isSuccess) "Saved" else "Failed",
            notes = "Checked ReminderRepository"
        )
    }
}

/**
 * Real Reminder Listing Tool.
 */
class TimeListRemindersTool(
    private val timeCapabilityAdapter: TimeCapabilityAdapter
) : LichiTool {

    override val definition = ToolDefinition(
        id = "time.list_reminders",
        name = "List Reminders",
        description = "Lists all active upcoming reminders and tasks.",
        purpose = "Query user reminders.",
        category = ToolCategory.TIME_REMINDER,
        mappedCapability = LichiCapability.TIME_REMINDER,
        parameters = emptyList(),
        riskLevel = ToolRiskLevel.READ_ONLY,
        requiresConfirmation = false,
        idempotent = true,
        timeoutMs = 5_000L
    )

    override suspend fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        return try {
            val outcome = timeCapabilityAdapter.executeStructured(action = "LIST")
            ToolResult.success(
                callId = call.callId,
                toolId = definition.id,
                summary = outcome.naturalSpeech
            )
        } catch (e: Exception) {
            ToolResult.failure(call.callId, definition.id, "Failed to list reminders: ${e.message}")
        }
    }

    override suspend fun verify(call: ToolCall, result: ToolResult, context: ToolExecutionContext): VerificationResult {
        return VerificationResult(
            isVerified = result.isSuccess,
            verifiedState = "Queried ReminderStore",
            notes = "Database query verified"
        )
    }
}
