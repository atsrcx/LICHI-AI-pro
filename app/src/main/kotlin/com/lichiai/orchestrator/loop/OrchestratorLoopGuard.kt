package com.lichiai.orchestrator.loop

import com.lichiai.orchestrator.model.PlanStep

/**
 * Guards against runaway orchestration loops.
 * Detects identical repeated steps without progress and caps max plan iterations.
 */
class OrchestratorLoopGuard(
    private val maxIterations: Int = 5
) {
    private val executedStepSignatures = mutableListOf<String>()
    private var totalExecutions: Int = 0

    fun canExecuteStep(step: PlanStep, stateFingerprint: String = ""): Boolean {
        if (totalExecutions >= maxIterations) {
            return false
        }

        val signature = "${step.capability.name}|${step.action}|${step.arguments.entries.sortedBy { it.key }.joinToString { "${it.key}=${it.value}" }}|$stateFingerprint"

        // Count how many times this exact step has been executed in the same state
        val occurrences = executedStepSignatures.count { it == signature }
        return occurrences < 2 // Allow at most 1 retry of an identical step
    }

    fun recordStep(step: PlanStep, stateFingerprint: String = "") {
        val signature = "${step.capability.name}|${step.action}|${step.arguments.entries.sortedBy { it.key }.joinToString { "${it.key}=${it.value}" }}|$stateFingerprint"
        executedStepSignatures.add(signature)
        totalExecutions++
    }

    fun reset() {
        executedStepSignatures.clear()
        totalExecutions = 0
    }
}
