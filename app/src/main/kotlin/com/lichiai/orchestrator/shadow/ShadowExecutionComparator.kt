package com.lichiai.orchestrator.shadow

import android.util.Log
import com.lichiai.intent.model.ResolvedIntent
import com.lichiai.orchestrator.model.OrchestrationDecision
import com.lichiai.orchestrator.model.ShadowClassification
import com.lichiai.orchestrator.model.ShadowComparison
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Compares legacy routing decisions against Orchestrator V2 decisions.
 * CRITICAL SAFETY RULE: Never executes tools in shadow mode. Only compares semantic plans.
 */
class ShadowExecutionComparator {

    companion object {
        private const val TAG = "ShadowComparator"
        private const val MAX_HISTORY = 50
    }

    private val comparisonHistory = ConcurrentLinkedQueue<ShadowComparison>()

    fun compare(
        rawInput: String,
        legacyRoute: ResolvedIntent,
        v2Decision: OrchestrationDecision
    ): ShadowComparison {
        val legacyCap = legacyRoute.capability
        val v2Cap = v2Decision.plan.firstOrNull()?.capability

        val classification = when {
            // Both picked the exact same capability
            legacyCap == v2Cap -> ShadowClassification.MATCH

            // V2 planned a multi-step task when legacy only supported a single action
            v2Decision.plan.size > 1 -> ShadowClassification.SAFE_IMPROVEMENT

            // Legacy picked CHAT (fallback) but V2 identified a valid capability
            legacyCap == com.lichiai.intent.model.LichiCapability.CHAT && v2Cap != null && v2Cap != com.lichiai.intent.model.LichiCapability.CHAT ->
                ShadowClassification.SAFE_IMPROVEMENT

            // Legacy picked a specialized capability (e.g. CALLS) but V2 fell back to CHAT
            legacyCap != com.lichiai.intent.model.LichiCapability.CHAT && (v2Cap == null || v2Cap == com.lichiai.intent.model.LichiCapability.CHAT) ->
                ShadowClassification.REGRESSION

            else -> ShadowClassification.AMBIGUOUS
        }

        val notes = "Legacy: ${legacyCap.name}, V2: ${v2Cap?.name ?: v2Decision.mode.name}, Steps: ${v2Decision.plan.size}, Reasoning: ${v2Decision.reasoningSummary}"
        Log.i(TAG, "ShadowComparison [$classification] -> $notes")

        val comparison = ShadowComparison(
            input = rawInput,
            legacyCapability = legacyCap,
            v2Capability = v2Cap,
            classification = classification,
            notes = notes
        )

        comparisonHistory.add(comparison)
        while (comparisonHistory.size > MAX_HISTORY) {
            comparisonHistory.poll()
        }

        return comparison
    }

    fun getRecentComparisons(): List<ShadowComparison> = comparisonHistory.toList()
}
