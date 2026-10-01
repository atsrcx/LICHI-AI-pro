package com.lichiai.toolruntime.tools

import android.content.Context
import android.media.AudioManager
import android.os.BatteryManager
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
 * Real Device Volume Control Tool.
 */
class DeviceVolumeTool(
    private val context: Context
) : LichiTool {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    override val definition = ToolDefinition(
        id = "device.set_volume",
        name = "Set Device Volume",
        description = "Adjusts device volume for media, ringtone, or alarm. Supports percentage (0-100) or 'up'/'down'/'mute'.",
        purpose = "Control device volume levels.",
        category = ToolCategory.DEVICE,
        mappedCapability = LichiCapability.DEVICE_CONTROL,
        parameters = listOf(
            ToolParameter("level", "string", "Volume level: percentage (e.g. '50', '80'), or 'up', 'down', 'mute', 'max'", required = true),
            ToolParameter("stream", "string", "Stream type: 'media', 'ring', or 'alarm'", required = false, defaultValue = "media")
        ),
        riskLevel = ToolRiskLevel.LOW_RISK_STATE_CHANGE,
        requiresConfirmation = false,
        idempotent = true,
        timeoutMs = 5_000L,
        changesWorldState = true
    )

    override suspend fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val streamType = when (call.arguments["stream"]?.lowercase()) {
            "ring", "call" -> AudioManager.STREAM_RING
            "alarm" -> AudioManager.STREAM_ALARM
            else -> AudioManager.STREAM_MUSIC
        }
        val maxVol = audioManager.getStreamMaxVolume(streamType)
        val currentVol = audioManager.getStreamVolume(streamType)
        val levelStr = call.arguments["level"]?.trim()?.lowercase() ?: ""

        val targetVol = when {
            levelStr == "up" -> (currentVol + 2).coerceAtMost(maxVol)
            levelStr == "down" -> (currentVol - 2).coerceAtLeast(0)
            levelStr == "mute" || levelStr == "0" -> 0
            levelStr == "max" || levelStr == "100" -> maxVol
            levelStr.endsWith("%") -> {
                val pct = levelStr.removeSuffix("%").toIntOrNull() ?: 50
                (maxVol * (pct / 100.0)).toInt().coerceIn(0, maxVol)
            }
            levelStr.toIntOrNull() != null -> {
                val num = levelStr.toInt()
                if (num in 0..100 && num > maxVol) {
                    (maxVol * (num / 100.0)).toInt().coerceIn(0, maxVol)
                } else {
                    num.coerceIn(0, maxVol)
                }
            }
            else -> return ToolResult.failure(call.callId, definition.id, "Invalid volume target: '$levelStr'")
        }

        audioManager.setStreamVolume(streamType, targetVol, AudioManager.FLAG_SHOW_UI)
        val newVol = audioManager.getStreamVolume(streamType)
        val pct = if (maxVol > 0) (newVol * 100) / maxVol else 0
        val summary = "Volume set to $pct% ($newVol/$maxVol)."

        return ToolResult.success(
            callId = call.callId,
            toolId = definition.id,
            summary = summary,
            data = mapOf(
                "stream" to streamType.toString(),
                "volume" to newVol.toString(),
                "max_volume" to maxVol.toString(),
                "percentage" to "$pct%"
            )
        )
    }

    override suspend fun verify(call: ToolCall, result: ToolResult, context: ToolExecutionContext): VerificationResult {
        val streamType = result.data["stream"]?.toIntOrNull() ?: AudioManager.STREAM_MUSIC
        val current = audioManager.getStreamVolume(streamType)
        val reported = result.data["volume"]?.toIntOrNull()
        val isVerified = reported == current
        return VerificationResult(
            isVerified = isVerified,
            verifiedState = "Current AudioManager stream volume: $current",
            notes = "Direct hardware AudioManager verification"
        )
    }
}

/**
 * Real Device State Inspection Tool.
 */
class DeviceStateTool(
    private val context: Context
) : LichiTool {

    override val definition = ToolDefinition(
        id = "device.get_state",
        name = "Get Device State",
        description = "Reads current device state: battery percentage, volume level, and system status.",
        purpose = "Inspect device hardware state.",
        category = ToolCategory.DEVICE,
        mappedCapability = LichiCapability.DEVICE_CONTROL,
        parameters = emptyList(),
        riskLevel = ToolRiskLevel.READ_ONLY,
        requiresConfirmation = false,
        idempotent = true,
        timeoutMs = 5_000L
    )

    override suspend fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val bm = this.context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val batteryPct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

        val am = this.context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val mediaVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maxMedia = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

        val summary = "Device state: Battery $batteryPct%, Media Volume $mediaVol/$maxMedia."
        return ToolResult.success(
            callId = call.callId,
            toolId = definition.id,
            summary = summary,
            data = mapOf(
                "battery" to "$batteryPct%",
                "media_volume" to "$mediaVol/$maxMedia"
            )
        )
    }

    override suspend fun verify(call: ToolCall, result: ToolResult, context: ToolExecutionContext): VerificationResult {
        return VerificationResult(
            isVerified = result.isSuccess,
            verifiedState = "Read actual hardware battery and audio status",
            notes = "Checked system services"
        )
    }
}
