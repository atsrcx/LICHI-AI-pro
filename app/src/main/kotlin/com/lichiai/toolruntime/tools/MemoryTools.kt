package com.lichiai.toolruntime.tools

import com.lichiai.intent.model.LichiCapability
import com.lichiai.memory.manager.LichiMemoryEngine
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
 * Real Long-Term Memory Search Tool.
 */
class MemorySearchTool(
    private val memoryEngine: LichiMemoryEngine
) : LichiTool {

    override val definition = ToolDefinition(
        id = "memory.search",
        name = "Search Memory",
        description = "Searches persistent long-term memory for past user facts, preferences, and conversations.",
        purpose = "Retrieve stored user knowledge and preferences on demand.",
        category = ToolCategory.MEMORY,
        mappedCapability = LichiCapability.CHAT,
        parameters = listOf(
            ToolParameter("query", "string", "Keywords or topic to search in memory (e.g. 'favorite food', 'name', 'birthday')", required = true)
        ),
        riskLevel = ToolRiskLevel.READ_ONLY,
        requiresConfirmation = false,
        idempotent = true,
        timeoutMs = 5_000L
    )

    override suspend fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val query = call.arguments["query"]?.trim() ?: context.userGoal
        if (query.isBlank()) {
            return ToolResult.failure(call.callId, definition.id, "Query is empty.")
        }

        return try {
            val pack = memoryEngine.getMemoryPack(query, context.conversationId)
            val facts = pack.relevantFacts
            val summary = if (facts.isEmpty()) {
                "No past memories found matching '$query'."
            } else {
                "Retrieved memories for '$query':\n" + facts.take(5).joinToString("\n") { "• ${it.key}: ${it.value}" }
            }
            ToolResult.success(
                callId = call.callId,
                toolId = definition.id,
                summary = summary,
                data = mapOf("count" to facts.size.toString()),
                rawOutput = pack.formattedPromptContext
            )
        } catch (e: Exception) {
            ToolResult.failure(call.callId, definition.id, "Memory search failed: ${e.message}")
        }
    }

    override suspend fun verify(call: ToolCall, result: ToolResult, context: ToolExecutionContext): VerificationResult {
        return VerificationResult(
            isVerified = result.isSuccess,
            verifiedState = "Queried LichiMemoryEngine",
            notes = "Memory engine search verified"
        )
    }
}

/**
 * Real Long-Term Memory Store Tool.
 */
class MemoryStoreTool(
    private val memoryEngine: LichiMemoryEngine
) : LichiTool {

    override val definition = ToolDefinition(
        id = "memory.store",
        name = "Store Memory Fact",
        description = "Stores a specific fact, preference, or piece of knowledge into persistent long-term memory.",
        purpose = "Remember user information across conversations.",
        category = ToolCategory.MEMORY,
        mappedCapability = LichiCapability.CHAT,
        parameters = listOf(
            ToolParameter("fact", "string", "The statement or preference to remember", required = true)
        ),
        riskLevel = ToolRiskLevel.LOW_RISK_STATE_CHANGE,
        requiresConfirmation = false,
        idempotent = false,
        timeoutMs = 5_000L,
        changesWorldState = true
    )

    override suspend fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val fact = call.arguments["fact"]?.trim() ?: ""
        if (fact.isBlank()) {
            return ToolResult.failure(call.callId, definition.id, "Fact to store is empty.")
        }

        return try {
            memoryEngine.recordTurn(
                conversationId = context.conversationId,
                messageId = java.util.UUID.randomUUID().toString(),
                role = "user",
                content = fact
            )
            ToolResult.success(
                callId = call.callId,
                toolId = definition.id,
                summary = "Remembered: '$fact'",
                data = mapOf("stored_fact" to fact)
            )
        } catch (e: Exception) {
            ToolResult.failure(call.callId, definition.id, "Failed to store memory: ${e.message}")
        }
    }

    override suspend fun verify(call: ToolCall, result: ToolResult, context: ToolExecutionContext): VerificationResult {
        if (!result.isSuccess) {
            return VerificationResult(
                isVerified = false,
                verifiedState = "FAILED_TO_EXECUTE",
                notes = "MemoryStoreTool execution was not marked as successful"
            )
        }
        val fact = call.arguments["fact"]?.trim().orEmpty()
        return try {
            val userTurns = memoryEngine.database.rawLedgerDao().getTurns(context.conversationId)
            val foundInLedger = userTurns.any { it.verbatimContent.contains(fact, ignoreCase = true) }
            val pack = memoryEngine.getMemoryPack(fact, context.conversationId)
            val foundInItems = pack.relevantFacts.any { it.value.contains(fact, ignoreCase = true) || fact.contains(it.value, ignoreCase = true) } ||
                    pack.relevantPreferences.any { it.value.contains(fact, ignoreCase = true) || fact.contains(it.value, ignoreCase = true) }

            if (foundInLedger || foundInItems || pack.coreProfile?.displayName?.equals(fact, ignoreCase = true) == true) {
                VerificationResult(
                    isVerified = true,
                    verifiedState = "PERSISTED_AND_VERIFIED",
                    notes = "Deterministic database read-back confirmed storage of memory fact."
                )
            } else {
                VerificationResult(
                    isVerified = false,
                    verifiedState = "READ_BACK_MISMATCH",
                    notes = "Deterministic database read-back could not find stored memory in raw ledger or active memory items."
                )
            }
        } catch (e: Exception) {
            VerificationResult(
                isVerified = false,
                verifiedState = "VERIFICATION_ERROR",
                notes = "Error verifying memory database read-back: ${e.message}"
            )
        }
    }
}

/**
 * Real Long-Term Memory Forget / Tombstone Tool.
 */
class MemoryForgetTool(
    private val memoryEngine: LichiMemoryEngine
) : LichiTool {

    override val definition = ToolDefinition(
        id = "memory.forget",
        name = "Forget Memory Fact",
        description = "Deletes, forgets, or tombstones a personal fact, residence, preference, or detail from long-term memory.",
        purpose = "Execute explicit user forget request and prevent data resurrection across memory.",
        category = ToolCategory.MEMORY,
        mappedCapability = LichiCapability.CHAT,
        parameters = listOf(
            ToolParameter("query", "string", "Text or description of the fact/detail to forget", required = true),
            ToolParameter("target_entity", "string", "Optional specific entity canonical name or key", required = false)
        ),
        riskLevel = ToolRiskLevel.LOW_RISK_STATE_CHANGE,
        requiresConfirmation = false,
        idempotent = true,
        timeoutMs = 5_000L,
        changesWorldState = true
    )

    override suspend fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val query = call.arguments["query"]?.trim() ?: context.userGoal
        val targetEntity = call.arguments["target_entity"]?.trim()
        if (query.isBlank() && targetEntity.isNullOrBlank()) {
            return ToolResult.failure(call.callId, definition.id, "Target memory to forget is empty.")
        }

        return try {
            val success = memoryEngine.tombstoneMemoryByDescription(
                description = query,
                targetEntity = targetEntity,
                conversationId = context.conversationId
            )
            if (success) {
                ToolResult.success(
                    callId = call.callId,
                    toolId = definition.id,
                    summary = "Successfully forgotten: '${targetEntity ?: query}'.",
                    data = mapOf("forgotten" to (targetEntity ?: query))
                )
            } else {
                ToolResult.failure(call.callId, definition.id, "Could not process forget request.")
            }
        } catch (e: Exception) {
            ToolResult.failure(call.callId, definition.id, "Failed to forget memory: ${e.message}")
        }
    }

    override suspend fun verify(call: ToolCall, result: ToolResult, context: ToolExecutionContext): VerificationResult {
        if (!result.isSuccess) {
            return VerificationResult(
                isVerified = false,
                verifiedState = "FORGET_FAILED",
                notes = "MemoryForgetTool execution was not successful"
            )
        }
        val query = call.arguments["query"]?.trim() ?: ""
        val target = call.arguments["target_entity"]?.trim() ?: query
        val isTombstoned = memoryEngine.tombstoneManager.isTombstoned(target)
        return VerificationResult(
            isVerified = isTombstoned,
            verifiedState = if (isTombstoned) "TOMBSTONED_AND_VERIFIED" else "TOMBSTONE_VERIFICATION_FAILED",
            notes = if (isTombstoned) "Amnesia tombstone verified in memory cache" else "Tombstone record not found in cache"
        )
    }
}
