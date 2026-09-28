package com.lichiai.agent.history

import com.lichiai.agent.model.AgentAction
import com.lichiai.agent.model.AgentStepRecord

/**
 * Manages the step-by-step history of the Autonomous Agent.
 */
class AgentHistoryManager {

    private val records = mutableListOf<AgentStepRecord>()

    fun clear() {
        records.clear()
    }

    fun addStep(
        stepNumber: Int,
        nextGoal: String,
        thinking: String,
        actions: List<AgentAction>,
        observation: String
    ) {
        records.add(
            AgentStepRecord(
                stepNumber = stepNumber,
                nextGoal = nextGoal,
                thinking = thinking,
                actions = actions,
                observation = observation
            )
        )
    }

    fun getHistorySummary(): String {
        if (records.isEmpty()) {
            return "(No previous agent steps yet. This is Step 1.)"
        }

        val sb = StringBuilder()
        val total = records.size
        // Compact older steps if history grows beyond 6 steps
        val compactThreshold = if (total > 6) total - 4 else 0

        for ((idx, rec) in records.withIndex()) {
            if (idx < compactThreshold) {
                val actNames = rec.actions.joinToString(", ") { it.name }
                sb.appendLine("--- Step ${rec.stepNumber} (Past) --- Goal: ${rec.nextGoal} | Actions: $actNames | Obs: ${rec.observation.take(80)}")
            } else {
                sb.appendLine("--- Step ${rec.stepNumber} ---")
                sb.appendLine("Goal: ${rec.nextGoal}")
                if (rec.thinking.isNotBlank()) {
                    sb.appendLine("Thinking: ${rec.thinking}")
                }
                val actNames = rec.actions.joinToString(", ") { act ->
                    val p = act.params.entries.joinToString(" ") { "${it.key}=\"${it.value}\"" }
                    "${act.name}($p)"
                }
                sb.appendLine("Actions Taken: $actNames")
                sb.appendLine("Observation: ${rec.observation}")
            }
        }
        return sb.toString()
    }

    fun stepCount(): Int = records.size
}
