package com.lichiai.toolruntime.core

import com.lichiai.toolruntime.model.ToolCall
import com.lichiai.toolruntime.model.ToolDefinition
import com.lichiai.toolruntime.model.ToolExecutionContext
import com.lichiai.toolruntime.model.ToolResult
import com.lichiai.toolruntime.model.VerificationResult

/**
 * Standard contract for any executable tool in the Lichi Unified Tool Runtime.
 *
 * Enforces:
 * 1. Definitive capability metadata (`definition`)
 * 2. Real execution (`execute`)
 * 3. Real verification of actual system/world state change (`verify`)
 */
interface LichiTool {
    val definition: ToolDefinition

    /**
     * Executes the requested action against the underlying Android subsystem.
     */
    suspend fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult

    /**
     * Verifies that the requested action actually occurred in reality.
     * Uses deterministic subsystem queries whenever available.
     */
    suspend fun verify(call: ToolCall, result: ToolResult, context: ToolExecutionContext): VerificationResult
}
