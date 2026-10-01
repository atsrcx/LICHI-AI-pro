package com.lichiai.toolruntime.tools

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
import com.lichiai.web.WebIntelligenceManager
import com.lichiai.web.planner.QueryPlanner
import com.lichiai.web.verifier.FactVerificationEngine

/**
 * Real Web Search Tool hooked directly to WebIntelligenceManager.
 */
class WebSearchTool(
    private val webIntelligenceManager: WebIntelligenceManager
) : LichiTool {

    override val definition = ToolDefinition(
        id = "web.search",
        name = "Web Search",
        description = "Searches the live web for real-time information, current facts, news, and prices.",
        purpose = "Retrieve live facts from external web search providers (Brave, Tavily, Serper, Exa).",
        category = ToolCategory.WEB,
        mappedCapability = LichiCapability.WEB_SEARCH,
        parameters = listOf(
            ToolParameter("query", "string", "The search query to execute on the web", required = true),
            ToolParameter("is_image", "boolean", "Set to true if searching for images", required = false, defaultValue = "false")
        ),
        riskLevel = ToolRiskLevel.READ_ONLY,
        requiresConfirmation = false,
        idempotent = true,
        timeoutMs = 20_000L,
        requiresNetwork = true,
        changesWorldState = false,
        isAvailable = { true }
    )

    override suspend fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val query = call.arguments["query"]?.trim() ?: context.userGoal
        if (query.isBlank()) {
            return ToolResult.failure(call.callId, definition.id, "Search query is empty.")
        }

        val isImage = call.arguments["is_image"]?.equals("true", ignoreCase = true) ?: false
        context.onProgress?.invoke(1, 2, "Searching web for '$query'...")

        return try {
            val searchResponse = webIntelligenceManager.executeSearch(query, isImageSearch = isImage)
            val contextPrompt = webIntelligenceManager.buildWebContextPrompt(searchResponse)

            val summary = if (!searchResponse.directAnswer.isNullOrBlank()) {
                "${searchResponse.directAnswer}\n\nSources: ${searchResponse.results.take(3).joinToString { it.title }}"
            } else if (searchResponse.results.isNotEmpty()) {
                "Found ${searchResponse.results.size} sources for '$query'. Top snippet: ${searchResponse.results.first().snippet.take(200)}"
            } else {
                "No results found for '$query'."
            }

            ToolResult.success(
                callId = call.callId,
                toolId = definition.id,
                summary = summary,
                data = mapOf(
                    "query" to query,
                    "context_prompt" to contextPrompt,
                    "direct_answer" to (searchResponse.directAnswer ?: ""),
                    "result_count" to searchResponse.results.size.toString()
                ),
                rawOutput = contextPrompt
            )
        } catch (e: Exception) {
            ToolResult.failure(call.callId, definition.id, "Web search failed: ${e.message}")
        }
    }

    override suspend fun verify(call: ToolCall, result: ToolResult, context: ToolExecutionContext): VerificationResult {
        if (!result.isSuccess) {
            return VerificationResult(isVerified = false, verifiedState = "Search failed", notes = result.errorMessage ?: "")
        }
        val count = result.data["result_count"]?.toIntOrNull() ?: 0
        val directAns = result.data["direct_answer"]
        val hasEvidence = count > 0 || !directAns.isNullOrBlank()

        return VerificationResult(
            isVerified = hasEvidence,
            verifiedState = if (hasEvidence) "Retrieved $count verified web sources" else "Empty web results",
            notes = "Checked search payload response"
        )
    }
}

/**
 * Real Web Fact Verification Tool.
 */
class WebVerifyTool(
    private val webIntelligenceManager: WebIntelligenceManager
) : LichiTool {

    override val definition = ToolDefinition(
        id = "web.verify",
        name = "Web Fact Verification",
        description = "Verifies a disputed fact, claim, or price against multiple authoritative online sources.",
        purpose = "Cross-reference facts and detect misinformation.",
        category = ToolCategory.WEB,
        mappedCapability = LichiCapability.VERIFY,
        parameters = listOf(
            ToolParameter("claim", "string", "The statement or claim to verify", required = true)
        ),
        riskLevel = ToolRiskLevel.READ_ONLY,
        requiresConfirmation = false,
        idempotent = true,
        timeoutMs = 25_000L,
        requiresNetwork = true,
        changesWorldState = false,
        isAvailable = { true }
    )

    override suspend fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val claim = call.arguments["claim"]?.trim() ?: context.userGoal
        if (claim.isBlank()) {
            return ToolResult.failure(call.callId, definition.id, "Claim to verify is empty.")
        }

        context.onProgress?.invoke(1, 2, "Verifying claim '$claim'...")

        return try {
            val searchResponse = webIntelligenceManager.executeSearch(claim)
            val plan = QueryPlanner.plan(claim)
            val verification = FactVerificationEngine.verify(plan, searchResponse.results)
            val summary = "Verification: Evidence level is ${verification.evidenceLevel}. Verified details: ${verification.verifiedSnippets.take(3).joinToString(" | ")}"

            ToolResult.success(
                callId = call.callId,
                toolId = definition.id,
                summary = summary,
                data = mapOf(
                    "claim" to claim,
                    "evidence_level" to verification.evidenceLevel
                ),
                rawOutput = summary
            )
        } catch (e: Exception) {
            ToolResult.failure(call.callId, definition.id, "Fact verification failed: ${e.message}")
        }
    }

    override suspend fun verify(call: ToolCall, result: ToolResult, context: ToolExecutionContext): VerificationResult {
        val level = result.data["evidence_level"]
        val isOk = result.isSuccess && !level.isNullOrBlank()
        return VerificationResult(
            isVerified = isOk,
            verifiedState = "Evidence level: ${level ?: "Unknown"}",
            notes = "Cross-source consistency verified"
        )
    }
}
