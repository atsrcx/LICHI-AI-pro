package com.lichiai.toolruntime.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.lichiai.intent.model.LichiCapability
import com.lichiai.skill.repository.SkillRepository
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
import java.net.URLEncoder

/**
 * Real Skill List Tool.
 */
class SkillListTool(
    private val skillRepository: SkillRepository
) : LichiTool {

    override val definition = ToolDefinition(
        id = "skills.list",
        name = "List Skills",
        description = "Lists all installed and active capabilities/skills available to Lichi AI.",
        purpose = "Discover installed community and system skills.",
        category = ToolCategory.SKILLS,
        mappedCapability = LichiCapability.SKILL_MANAGEMENT,
        parameters = emptyList(),
        riskLevel = ToolRiskLevel.READ_ONLY,
        requiresConfirmation = false,
        idempotent = true,
        timeoutMs = 5_000L
    )

    override suspend fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val list = skillRepository.skills.value
        val summary = if (list.isEmpty()) {
            "No custom skills currently active."
        } else {
            "Installed skills (${list.size}):\n" + list.joinToString("\n") { "• ${it.name}: ${it.description.take(60)}" }
        }
        return ToolResult.success(
            callId = call.callId,
            toolId = definition.id,
            summary = summary,
            data = mapOf("count" to list.size.toString()),
            outcome = ToolExecutionOutcome.EXECUTION_SUCCEEDED_VERIFIED
        )
    }

    override suspend fun verify(call: ToolCall, result: ToolResult, context: ToolExecutionContext): VerificationResult {
        return VerificationResult(
            isVerified = true,
            verifiedState = "Skill catalog queried",
            notes = "",
            outcome = ToolExecutionOutcome.EXECUTION_SUCCEEDED_VERIFIED
        )
    }
}

/**
 * Real Media & YouTube Playback Tool.
 * Truthfully reports when YouTube search is opened versus direct stream playback.
 */
class MediaPlayTool(
    private val context: Context
) : LichiTool {

    override val definition = ToolDefinition(
        id = "media.play",
        name = "Play Media / YouTube",
        description = "Searches and plays music or videos on YouTube or default media player.",
        purpose = "Launch playback for audio or video query.",
        category = ToolCategory.MEDIA,
        mappedCapability = LichiCapability.MEDIA_YOUTUBE,
        parameters = listOf(
            ToolParameter("query", "string", "Song, artist, or video title to play", required = true)
        ),
        riskLevel = ToolRiskLevel.LOW_RISK_STATE_CHANGE,
        requiresConfirmation = false,
        idempotent = false,
        timeoutMs = 10_000L,
        changesWorldState = true
    )

    override suspend fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val query = call.arguments["query"]?.trim() ?: context.userGoal
        if (query.isBlank()) {
            return ToolResult.failure(call.callId, definition.id, "Media query is empty.")
        }

        context.onProgress?.invoke(1, 2, "Launching YouTube search for '$query'...")

        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$encoded")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            this.context.startActivity(webIntent)
            ToolResult.success(
                callId = call.callId,
                toolId = definition.id,
                summary = "Opened YouTube search results for '$query'.",
                data = mapOf("query" to query, "action" to "YOUTUBE_SEARCH_OPENED"),
                outcome = ToolExecutionOutcome.EXECUTION_SUCCEEDED_UNVERIFIED
            )
        } catch (e: Exception) {
            ToolResult.failure(call.callId, definition.id, "Failed to launch media: ${e.message}")
        }
    }

    override suspend fun verify(call: ToolCall, result: ToolResult, context: ToolExecutionContext): VerificationResult {
        val isVerified = result.isSuccess
        return VerificationResult(
            isVerified = isVerified,
            verifiedState = if (isVerified) "YouTube activity launched" else "Launch failed",
            notes = "Intent dispatched to system package manager",
            outcome = if (isVerified) ToolExecutionOutcome.EXECUTION_SUCCEEDED_VERIFIED else ToolExecutionOutcome.VERIFICATION_FAILED
        )
    }
}
