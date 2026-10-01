package com.lichiai.toolruntime.discovery

import com.lichiai.toolruntime.core.LichiTool
import com.lichiai.toolruntime.model.ToolDefinition
import com.lichiai.toolruntime.model.WorldRuntimeState
import com.lichiai.toolruntime.registry.UnifiedToolRegistry

/**
 * Two-stage tool discovery engine to control token budget.
 *
 * Stage 1: Compact Capability Family Index for high-level awareness.
 * Stage 2: Detailed parameter schema injection only for relevant capability families.
 */
class ToolDiscoveryEngine(
    private val toolRegistry: UnifiedToolRegistry
) {

    /**
     * STAGE 1: Formats an ultra-compact capability index.
     */
    fun formatCapabilityIndex(tools: List<LichiTool>): String {
        val grouped = tools.groupBy { it.definition.category }
        return buildString {
            appendLine("SYSTEM CAPABILITY FAMILIES:")
            for ((category, categoryTools) in grouped) {
                val toolIds = categoryTools.joinToString(", ") { it.definition.id }
                appendLine("- ${category.name} (${category.displayName}): $toolIds")
            }
        }
    }

    /**
     * STAGE 2: Selects only relevant tools based on goal semantics and world state.
     */
    fun selectRelevantTools(goal: String, worldState: WorldRuntimeState): List<ToolDefinition> {
        return toolRegistry.selectRelevantTools(goal, worldState)
    }
}
