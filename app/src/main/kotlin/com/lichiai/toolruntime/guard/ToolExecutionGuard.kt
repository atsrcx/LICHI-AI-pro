package com.lichiai.toolruntime.guard

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.lichiai.toolruntime.core.LichiTool
import com.lichiai.toolruntime.model.ToolCall
import com.lichiai.toolruntime.model.ToolCategory
import com.lichiai.toolruntime.model.ToolRiskLevel
import com.lichiai.toolruntime.model.WorldRuntimeState

/**
 * Authoritative Centralized Tool Execution Guard for Lichi AI.
 *
 * Enforces strict runtime invariants:
 * 1. Tool availability & presence.
 * 2. Parameter schema validation.
 * 3. Android runtime permission guards.
 * 4. Destructive command prevention (e.g. terminal system modifications).
 * 5. Deterministic confirmation policy — the LLM can NEVER override this safety layer.
 */
class ToolExecutionGuard(
    private val context: Context? = null
) {

    sealed class GuardResult {
        object Allowed : GuardResult()
        data class RequiresConfirmation(val prompt: String) : GuardResult()
        data class Denied(val reason: String) : GuardResult()
    }

    /**
     * Evaluates a requested tool call against the security and confirmation policy.
     */
    fun evaluate(
        tool: LichiTool?,
        call: ToolCall,
        worldState: WorldRuntimeState,
        userConfirmed: Boolean = false
    ): GuardResult {
        // 1. Tool Existence
        if (tool == null) {
            return GuardResult.Denied("Tool '${call.toolId}' is not registered in the system.")
        }

        // 2. Runtime Availability
        if (!tool.definition.isAvailable()) {
            return GuardResult.Denied("Tool '${tool.definition.id}' (${tool.definition.name}) is currently unavailable or disabled.")
        }

        // 3. Schema & Required Arguments Validation
        for (param in tool.definition.parameters) {
            if (param.required) {
                val value = call.arguments[param.name]?.trim()
                if (value.isNullOrBlank()) {
                    return GuardResult.Denied("Required parameter '${param.name}' is missing for tool '${tool.definition.id}'.")
                }
            }
        }

        // 4. Destructive Terminal Protection
        if (tool.definition.category == ToolCategory.TERMINAL) {
            val cmd = call.arguments["command"]?.trim()?.lowercase() ?: ""
            if (isDestructiveTerminalCommand(cmd)) {
                return GuardResult.Denied("Command blocked by safety policy: Destructive system-level modification is prohibited.")
            }
            if (isDangerousTerminalCommand(cmd) && !userConfirmed) {
                return GuardResult.RequiresConfirmation("The terminal command '${call.arguments["command"]}' makes system modifications. Do you want to proceed?")
            }
        }

        // 5. System Permissions (e.g. Telephony)
        if (tool.definition.category == ToolCategory.TELEPHONY && tool.definition.id == "call.contact") {
            if (context != null) {
                val hasCallPerm = ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.CALL_PHONE
                ) == PackageManager.PERMISSION_GRANTED
                if (!hasCallPerm) {
                    return GuardResult.Denied("Missing CALL_PHONE permission to place phone call.")
                }
            }
        }

        // 6. Centralized Confirmation Enforcement
        // If a tool is marked as high-risk, destructive, or external communication, enforce confirmation unless user confirmed
        val requiresConfirmation = tool.definition.requiresConfirmation ||
                tool.definition.riskLevel == ToolRiskLevel.DESTRUCTIVE ||
                tool.definition.riskLevel == ToolRiskLevel.HIGH_RISK_STATE_CHANGE

        if (requiresConfirmation && !userConfirmed) {
            val prompt = generateConfirmationPrompt(tool, call)
            return GuardResult.RequiresConfirmation(prompt)
        }

        return GuardResult.Allowed
    }

    private fun isDestructiveTerminalCommand(cmd: String): Boolean {
        val destructiveTokens = listOf(
            "rm -rf /", "rm -rf *", "mkfs", "dd if=", ":(){ :|:& };:", "> /dev/sda",
            "chmod -r 777 /", "chmod 777 /", "wipefs", "fdisk", "parted"
        )
        return destructiveTokens.any { cmd.contains(it) }
    }

    private fun isDangerousTerminalCommand(cmd: String): Boolean {
        val dangerousPrefixes = listOf("rm ", "kill ", "reboot", "shutdown", "format", "wipe", "mv /")
        return dangerousPrefixes.any { cmd.startsWith(it) }
    }

    private fun generateConfirmationPrompt(tool: LichiTool, call: ToolCall): String {
        return when (tool.definition.id) {
            "call.contact" -> "Are you sure you want to call ${call.arguments["target"] ?: "this contact"}?"
            "call.action" -> "Are you sure you want to execute '${call.arguments["action"]}' on the active call?"
            "android.automate" -> "Proceed with on-screen automation: ${call.arguments["task_description"]}?"
            "terminal.execute" -> "Execute terminal command: '${call.arguments["command"]}'?"
            else -> "Do you want to proceed with ${tool.definition.name}?"
        }
    }
}
