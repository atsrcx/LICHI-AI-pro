package com.lichiai.toolruntime.tools

import com.lichiai.intent.model.LichiCapability
import com.lichiai.memory.data.UserMemoryDao
import com.lichiai.memory.data.UserMemoryEntity
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
 * Real Long-Term Memory Search Tool using Online LLM Memory DAO.
 */
class MemorySearchTool(
    private val memoryDao: UserMemoryDao
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
            val allMemories = memoryDao.getAllMemories("default_user")
            val filtered = allMemories.filter {
                it.key.contains(query, ignoreCase = true) ||
                it.value.contains(query, ignoreCase = true) ||
                it.category.contains(query, ignoreCase = true)
            }
            val summary = if (filtered.isEmpty()) {
                "No past memories found matching '$query'."
            } else {
                "Retrieved memories for '$query':\n" + filtered.take(5).joinToString("\n") { "• ${it.key}: ${it.value}" }
            }
            ToolResult.success(
                callId = call.callId,
                toolId = definition.id,
                summary = summary,
                data = mapOf("count" to filtered.size.toString()),
                rawOutput = summary
            )
        } catch (e: Exception) {
            ToolResult.failure(call.callId, definition.id, "Memory search failed: ${e.message}")
        }
    }

    override suspend fun verify(call: ToolCall, result: ToolResult, context: ToolExecutionContext): VerificationResult {
        return VerificationResult(
            isVerified = result.isSuccess,
            verifiedState = "Queried UserMemoryDao",
            notes = "Memory search verified"
        )
    }
}

/**
 * Real Long-Term Memory Store Tool using Online LLM Memory DAO.
 */
class MemoryStoreTool(
    private val memoryDao: UserMemoryDao
) : LichiTool {

    override val definition = ToolDefinition(
        id = "memory.store",
        name = "Store Memory Fact",
        description = "Stores a specific fact, preference, or piece of knowledge into persistent long-term memory.",
        purpose = "Remember user information across conversations.",
        category = ToolCategory.MEMORY,
        mappedCapability = LichiCapability.CHAT,
        parameters = listOf(
            ToolParameter("key", "string", "Key or canonical name of the fact (e.g. 'name', 'favorite_color', 'residence')", required = true),
            ToolParameter("value", "string", "The value or statement to remember", required = true),
            ToolParameter("category", "string", "Optional category: identity, preference, project, work", required = false)
        ),
        riskLevel = ToolRiskLevel.LOW_RISK_STATE_CHANGE,
        requiresConfirmation = false,
        idempotent = false,
        timeoutMs = 5_000L,
        changesWorldState = true
    )

    override suspend fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val key = call.arguments["key"]?.trim()?.lowercase()?.replace(" ", "_") ?: ""
        val value = call.arguments["value"]?.trim() ?: call.arguments["fact"]?.trim() ?: ""
        val category = call.arguments["category"]?.trim() ?: "general"
        val userId = "default_user"

        if (key.isBlank() && value.isBlank()) {
            return ToolResult.failure(call.callId, definition.id, "Fact or key to store is empty.")
        }

        val effectiveKey = if (key.isBlank()) "fact_${System.currentTimeMillis()}" else key

        return try {
            val entity = UserMemoryEntity(
                id = "${userId}_$effectiveKey",
                userId = userId,
                key = effectiveKey,
                value = value,
                category = category,
                updatedAt = System.currentTimeMillis()
            )
            memoryDao.upsertMemory(entity)
            ToolResult.success(
                callId = call.callId,
                toolId = definition.id,
                summary = "Remembered: '$effectiveKey' = '$value'",
                data = mapOf("key" to effectiveKey, "value" to value)
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
        val key = call.arguments["key"]?.trim()?.lowercase()?.replace(" ", "_") ?: ""
        val userId = "default_user"
        return try {
            val stored = memoryDao.getMemoryByKey(userId, key)
            if (stored != null) {
                VerificationResult(
                    isVerified = true,
                    verifiedState = "PERSISTED_AND_VERIFIED",
                    notes = "Deterministic database read-back confirmed storage of memory fact."
                )
            } else {
                VerificationResult(
                    isVerified = false,
                    verifiedState = "READ_BACK_MISMATCH",
                    notes = "Deterministic database read-back could not find stored memory."
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
 * Real Long-Term Memory Forget Tool.
 */
class MemoryForgetTool(
    private val memoryDao: UserMemoryDao
) : LichiTool {

    override val definition = ToolDefinition(
        id = "memory.forget",
        name = "Forget Memory Fact",
        description = "Deletes or forgets a personal fact, residence, preference, or detail from long-term memory.",
        purpose = "Execute explicit user forget request and remove persistent fact.",
        category = ToolCategory.MEMORY,
        mappedCapability = LichiCapability.CHAT,
        parameters = listOf(
            ToolParameter("key", "string", "Key or description of the fact to forget (e.g. 'favorite_color', 'residence')", required = true)
        ),
        riskLevel = ToolRiskLevel.LOW_RISK_STATE_CHANGE,
        requiresConfirmation = false,
        idempotent = true,
        timeoutMs = 5_000L,
        changesWorldState = true
    )

    override suspend fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val key = (call.arguments["key"] ?: call.arguments["query"] ?: call.arguments["target_entity"])?.trim()?.lowercase()?.replace(" ", "_") ?: ""
        val userId = "default_user"
        if (key.isBlank()) {
            return ToolResult.failure(call.callId, definition.id, "Target memory key to forget is empty.")
        }

        return try {
            val deleted = memoryDao.deleteMemoryByKey(userId, key)
            ToolResult.success(
                callId = call.callId,
                toolId = definition.id,
                summary = "Successfully forgotten '$key'. ($deleted records removed)",
                data = mapOf("deleted_key" to key, "count" to deleted.toString())
            )
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
        val key = (call.arguments["key"] ?: call.arguments["query"] ?: call.arguments["target_entity"])?.trim()?.lowercase()?.replace(" ", "_") ?: ""
        val userId = "default_user"
        val remaining = memoryDao.getMemoryByKey(userId, key)
        return VerificationResult(
            isVerified = remaining == null,
            verifiedState = if (remaining == null) "FORGOTTEN_AND_VERIFIED" else "STILL_EXISTS",
            notes = if (remaining == null) "Confirmed memory key is removed" else "Memory key still exists in DAO"
        )
    }
}
