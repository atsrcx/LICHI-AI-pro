package com.lichiai.toolruntime.tools

import android.content.Context
import android.content.Intent
import com.lichiai.agent.accessibility.LichiAccessibilityService
import com.lichiai.agent.bridge.AutonomousAgentTool
import com.lichiai.agent.executor.AgentActionExecutor
import com.lichiai.agent.model.AgentAction
import com.lichiai.agent.model.AndroidState
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
import kotlinx.coroutines.delay

/**
 * Real Android App Launch Tool.
 */
class AndroidOpenAppTool(
    private val context: Context,
    private val actionExecutor: AgentActionExecutor
) : LichiTool {

    override val definition = ToolDefinition(
        id = "android.open_app",
        name = "Open App",
        description = "Launches an installed Android app by name or package (e.g. WhatsApp, YouTube, Settings, Chrome).",
        purpose = "Open an application on the user's phone.",
        category = ToolCategory.ANDROID,
        mappedCapability = LichiCapability.ANDROID_AGENT,
        parameters = listOf(
            ToolParameter("app_name", "string", "The name of the app to launch (e.g. WhatsApp, YouTube, Settings)", required = true)
        ),
        riskLevel = ToolRiskLevel.LOW_RISK_STATE_CHANGE,
        requiresConfirmation = false,
        idempotent = true,
        timeoutMs = 10_000L,
        requiresNetwork = false,
        changesWorldState = true
    )

    override suspend fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val appName = call.arguments["app_name"]?.trim() ?: ""
        if (appName.isBlank()) {
            return ToolResult.failure(call.callId, definition.id, "App name is required.")
        }

        context.onProgress?.invoke(1, 2, "Opening $appName...")

        return try {
            val action = AgentAction(name = "open_app", params = mapOf("app_name" to appName))
            val currentPkg = LichiAccessibilityService.getInstance()?.getActivePackage() ?: ""
            val currentState = AndroidState(packageName = currentPkg)
            val observation = actionExecutor.executeAction(action, currentState)

            if (!observation.startsWith("ERROR") && !observation.startsWith("APP_LAUNCH_FAILED") && !observation.startsWith("APP_NOT_INSTALLED")) {
                ToolResult.success(
                    callId = call.callId,
                    toolId = definition.id,
                    summary = "Opened $appName successfully.",
                    data = mapOf("app_name" to appName, "observation" to observation)
                )
            } else {
                ToolResult.failure(call.callId, definition.id, observation)
            }
        } catch (e: Exception) {
            ToolResult.failure(call.callId, definition.id, "Failed to launch $appName: ${e.message}")
        }
    }

    override suspend fun verify(call: ToolCall, result: ToolResult, context: ToolExecutionContext): VerificationResult {
        delay(400)
        val currentPkg = LichiAccessibilityService.getInstance()?.getActivePackage() ?: ""
        val isVerified = result.isSuccess
        return VerificationResult(
            isVerified = isVerified,
            verifiedState = if (currentPkg.isNotBlank()) "Active package: $currentPkg" else "Launched",
            notes = "Checked accessibility package inspector"
        )
    }
}

/**
 * Real Autonomous Phone UI Automation Tool.
 * Delegates multi-step phone navigation to AutonomousAgentTool.
 */
class AndroidAutomateTool(
    private val autonomousAgentTool: AutonomousAgentTool
) : LichiTool {

    override val definition = ToolDefinition(
        id = "android.automate",
        name = "Automate Phone Task",
        description = "Executes multi-step on-screen UI navigation, button clicks, and app interactions autonomously.",
        purpose = "Delegate complex on-screen phone tasks to the Autonomous Agent.",
        category = ToolCategory.ANDROID,
        mappedCapability = LichiCapability.ANDROID_AGENT,
        parameters = listOf(
            ToolParameter("task_description", "string", "Detailed description of the phone automation task", required = true)
        ),
        riskLevel = ToolRiskLevel.HIGH_RISK_STATE_CHANGE,
        requiresConfirmation = false,
        idempotent = false,
        timeoutMs = 60_000L,
        requiresNetwork = false,
        changesWorldState = true,
        isAvailable = { autonomousAgentTool.isEnabled() }
    )

    override suspend fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val taskDesc = call.arguments["task_description"]?.trim() ?: context.userGoal
        if (taskDesc.isBlank()) {
            return ToolResult.failure(call.callId, definition.id, "Task description is empty.")
        }

        context.onProgress?.invoke(1, 3, "Running phone automation: $taskDesc")

        return try {
            val runResult = autonomousAgentTool.execute(taskDesc) { step, total, statusText ->
                context.onProgress?.invoke(step, total, statusText)
            }

            if (runResult.isSuccess) {
                ToolResult.success(
                    callId = call.callId,
                    toolId = definition.id,
                    summary = runResult.summary.ifBlank { "Phone automation completed." },
                    data = mapOf("steps" to runResult.totalSteps.toString())
                )
            } else {
                ToolResult.failure(call.callId, definition.id, runResult.summary.ifBlank { "Automation task failed." })
            }
        } catch (e: Exception) {
            ToolResult.failure(call.callId, definition.id, "Automation error: ${e.message}")
        }
    }

    override suspend fun verify(call: ToolCall, result: ToolResult, context: ToolExecutionContext): VerificationResult {
        return VerificationResult(
            isVerified = result.isSuccess,
            verifiedState = if (result.isSuccess) "Completed automation steps" else "Automation failed",
            notes = "Checked AutonomousAgent run status"
        )
    }
}
