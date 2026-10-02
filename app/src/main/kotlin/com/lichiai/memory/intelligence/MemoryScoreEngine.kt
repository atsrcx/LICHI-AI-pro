package com.lichiai.memory.intelligence

import com.lichiai.memory.model.MemoryItem
import com.lichiai.memory.model.MemoryStatus
import com.lichiai.memory.model.TemporalIntent
import com.lichiai.memory.model.TrustLevel
import kotlin.math.max
import kotlin.math.min

/**
 * Deterministic Score Fusion and Normalization Engine for Memory OS V6.
 */
object MemoryScoreEngine {

    // Authoritative Score Fusion Weights (Sum = 1.00)
    const val WEIGHT_SEMANTIC = 0.40f
    const val WEIGHT_LEXICAL = 0.20f
    const val WEIGHT_ENTITY = 0.15f
    const val WEIGHT_TEMPORAL = 0.10f
    const val WEIGHT_TRUST = 0.10f
    const val WEIGHT_SALIENCE = 0.05f

    /**
     * Computes the normalized trust score [0.0, 1.0] from TrustLevel.
     */
    fun getTrustScore(trustLevel: TrustLevel): Float {
        return when (trustLevel) {
            TrustLevel.USER_EXPLICIT -> 1.0f
            TrustLevel.USER_CONFIRMED -> 0.95f
            TrustLevel.TOOL_VERIFIED -> 0.90f
            TrustLevel.AGENT_ACTION_VERIFIED -> 0.85f
            TrustLevel.SYSTEM_OBSERVED -> 0.75f
            TrustLevel.EXTERNAL_SOURCE -> 0.60f
            TrustLevel.MODEL_INFERRED -> 0.40f
            TrustLevel.UNKNOWN -> 0.20f
        }
    }

    /**
     * Computes temporal validity score [0.0, 1.0].
     */
    fun getTemporalScore(
        item: MemoryItem,
        temporalIntent: TemporalIntent,
        currentTime: Long = System.currentTimeMillis()
    ): Float {
        val isTimeValid = item.temporalWindow.isValidAt(currentTime)
        val isActive = item.status == MemoryStatus.ACTIVE

        return when (temporalIntent) {
            TemporalIntent.CURRENT -> {
                if (isActive && isTimeValid) 1.0f else 0.15f
            }
            TemporalIntent.HISTORICAL -> {
                if (item.status == MemoryStatus.SUPERSEDED || !isTimeValid) 1.0f else 0.40f
            }
            TemporalIntent.TRANSITION, TemporalIntent.ALL -> {
                0.90f
            }
        }
    }

    /**
     * Combines all normalized signals using deterministic score fusion.
     */
    fun computeFusedScore(
        semanticScore: Float,
        lexicalScore: Float,
        entityScore: Float,
        temporalScore: Float,
        trustScore: Float,
        salienceScore: Float
    ): Float {
        val normSemantic = max(0f, min(1f, semanticScore))
        val normLexical = max(0f, min(1f, lexicalScore))
        val normEntity = max(0f, min(1f, entityScore))
        val normTemporal = max(0f, min(1f, temporalScore))
        val normTrust = max(0f, min(1f, trustScore))
        val normSalience = max(0f, min(1f, salienceScore))

        return (normSemantic * WEIGHT_SEMANTIC) +
                (normLexical * WEIGHT_LEXICAL) +
                (normEntity * WEIGHT_ENTITY) +
                (normTemporal * WEIGHT_TEMPORAL) +
                (normTrust * WEIGHT_TRUST) +
                (normSalience * WEIGHT_SALIENCE)
    }
}
