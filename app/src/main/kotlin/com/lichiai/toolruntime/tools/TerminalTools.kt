package com.lichiai.toolruntime.tools

import com.lichiai.intent.model.LichiCapability
import com.lichiai.terminal.core.TerminalManager
import com.lichiai.terminal.task.TerminalTaskManager
import com.lichiai.toolruntime.core.LichiTool
import com.lichiai.toolruntime.model.ToolCall
import com.lichiai.toolruntime.model.ToolCategory
import com.lichiai.toolruntime.model.ToolDefinition
import com.lichiai.toolruntime.model.ToolExecutionContext
import com.lichiai.toolruntime.model.ToolExecutionOutcome
import com.lichiai.toolruntime.model.ToolParameter
import com.lichiai.toolruntime.model.ToolResult
import com.lichiai.toolruntime.model.ToolRiskLevel
import com.lichiai.toolruntime.model.VerificationResult

/**
 * Real Terminal Shell Execution Tool.
 * Enforces security policy via ToolExecutionGuard and verifies real process exit codes.
 */
class TerminalExecuteTool(
    private val terminalManager: TerminalManager,
    private val taskManager: TerminalTaskManager,
    private val onNavigateToTerminal: () -> Unit = {}
) : LichiTool {

    override val definition = ToolDefinition(
        id = "terminal.execute",
        name = "Execute Terminal Command",
        description = "Runs a shell command in the Lichi Terminal environment.",
        purpose = "Execute safe terminal commands and return stdout/stderr.",
        category = ToolCategory.TERMINAL,
        mappedCapability = LichiCapability.TERMINAL,
        parameters = listOf(
            ToolParameter("command", "string", "Shell command to run (e.g. 'ls -la', 'uname -a', 'ping -c 3 8.8.8.8')", required = true)
        ),
        riskLevel = ToolRiskLevel.HIGH_RISK_STATE_CHANGE,
        requiresConfirmation = true,
        idempotent = false,
        timeoutMs = 30_000L,
        changesWorldState = true
    )

    override suspend fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val command = call.arguments["command"]?.trim() ?: ""
        if (command.isBlank()) {
            return ToolResult.failure(call.callId, definition.id, "Terminal command is empty.")
        }

        context.onProgress?.invoke(1, 2, "Running: $command")
        onNavigateToTerminal()

        return try {
            val session = terminalManager.createLocalSession()
            val outcome = terminalManager.executeCommand(command, session?.id)

            if (outcome.isSuccess) {
                val cleanOutput = outcome.output.trim().take(1200)
                val summary = if (cleanOutput.isNotBlank()) {
                    "Command output:\n$cleanOutput"
                } else {
                    "Command executed successfully with exit code 0."
                }
                ToolResult.success(
                    callId = call.callId,
                    toolId = definition.id,
                    summary = summary,
                    data = mapOf("command" to command, "exit_code" to outcome.exitCode.toString()),
                    rawOutput = outcome.output,
                    outcome = ToolExecutionOutcome.EXECUTION_SUCCEEDED_UNVERIFIED
                )
            } else {
                ToolResult.failure(
                    call.callId,
                    definition.id,
                    outcome.errorOutput ?: "Command execution failed with exit code ${outcome.exitCode}"
                )
            }
        } catch (e: Exception) {
            ToolResult.failure(call.callId, definition.id, "Terminal execution error: ${e.message}")
        }
    }

    override suspend fun verify(call: ToolCall, result: ToolResult, context: ToolExecutionContext): VerificationResult {
        val code = result.data["exit_code"]?.toIntOrNull()
        val isVerified = result.isSuccess && (code == 0)
        return VerificationResult(
            isVerified = isVerified,
            verifiedState = "Exit code: ${code ?: "unknown"}",
            notes = "Checked command process completion",
            outcome = if (isVerified) ToolExecutionOutcome.EXECUTION_SUCCEEDED_VERIFIED else ToolExecutionOutcome.VERIFICATION_FAILED
        )
    }
}
