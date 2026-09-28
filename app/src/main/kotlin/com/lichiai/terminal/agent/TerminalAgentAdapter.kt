package com.lichiai.terminal.agent

import com.lichiai.terminal.core.TerminalCommandRiskAnalyzer
import com.lichiai.terminal.core.TerminalManager
import com.lichiai.terminal.core.TerminalResourceGovernor
import com.lichiai.terminal.model.TerminalResult
import com.lichiai.terminal.model.TerminalRiskLevel

data class TerminalAgentExecutionResult(
    val summary: String,
    val output: String,
    val isSuccess: Boolean,
    val riskLevel: TerminalRiskLevel,
    val exitCode: Int = 0,
    val requiresConfirmation: Boolean = false
)

/**
 * Autonomous Agent V2 & Universal Task Orchestrator adapter for Lichi Terminal V2.
 * Validates deterministic risk policy, enforces bounded output, extracts errors,
 * and reports structured feedback for closed-loop evaluation.
 */
class TerminalAgentAdapter(private val terminalManager: TerminalManager) {

    suspend fun executeTask(
        instructionOrCommand: String,
        sessionId: String? = null,
        userApprovedPrivileged: Boolean = false
    ): TerminalAgentExecutionResult {
        val command = instructionOrCommand.trim()
        val risk = TerminalCommandRiskAnalyzer.analyzeRisk(command)

        if (TerminalCommandRiskAnalyzer.isConfirmationRequired(risk) && !userApprovedPrivileged) {
            return TerminalAgentExecutionResult(
                summary = "Command classified as ${risk.name}. User confirmation is required before execution: `$command`",
                output = "",
                isSuccess = false,
                riskLevel = risk,
                requiresConfirmation = true
            )
        }

        val result = terminalManager.executeCommand(command, sessionId)
        val boundedOutput = TerminalResourceGovernor.truncateForAgent(result.output)

        val hasErrorIndicators = boundedOutput.contains("command not found", ignoreCase = true) ||
                boundedOutput.contains("permission denied", ignoreCase = true) ||
                boundedOutput.contains("fatal:", ignoreCase = true) ||
                boundedOutput.contains("error:", ignoreCase = true)

        val isSuccess = result.isSuccess && !hasErrorIndicators

        val summary = if (isSuccess) {
            "Terminal command `$command` executed successfully."
        } else {
            "Terminal command `$command` failed or encountered errors."
        }

        return TerminalAgentExecutionResult(
            summary = summary,
            output = boundedOutput,
            isSuccess = isSuccess,
            riskLevel = risk,
            exitCode = result.exitCode
        )
    }

    fun explainOutput(output: String): String {
        val lines = output.lines()
        val errorLines = lines.filter {
            it.contains("error", ignoreCase = true) ||
            it.contains("failed", ignoreCase = true) ||
            it.contains("fatal", ignoreCase = true) ||
            it.contains("denied", ignoreCase = true)
        }

        return if (errorLines.isNotEmpty()) {
            "Detected errors:\n" + errorLines.take(5).joinToString("\n") { "• $it" }
        } else {
            "Command output completed without prominent error keywords."
        }
    }
}
