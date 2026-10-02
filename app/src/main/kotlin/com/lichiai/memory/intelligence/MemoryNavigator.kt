package com.lichiai.memory.intelligence

import com.lichiai.memory.engine.AmnesiaTombstoneManager
import com.lichiai.memory.model.BiTemporalWindow
import com.lichiai.memory.model.MemoryCategory
import com.lichiai.memory.model.MemoryItem
import com.lichiai.memory.model.MemoryScope
import com.lichiai.memory.model.MemoryStatus
import com.lichiai.memory.model.TemporalIntent
import com.lichiai.memory.model.TrustLevel
import kotlinx.serialization.json.Json
import java.util.Locale

data class ScoredMemoryItem(
    val item: MemoryItem,
    val finalScore: Float,
    val semanticScore: Float,
    val lexicalScore: Float,
    val entityScore: Float,
    val temporalScore: Float,
    val trustScore: Float
)

/**
 * Deterministic Memory Navigator for Memory OS V6.
 * Enforces Tombstone Barrier, Bi-Temporal Truth, Trust Provenance, and Score Fusion.
 */
class MemoryNavigator(
    private val tombstoneManager: AmnesiaTombstoneManager
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun navigateAndRank(
        candidates: List<MemoryCandidate>,
        decision: RoutedQueryDecision,
        currentTime: Long = System.currentTimeMillis()
    ): List<ScoredMemoryItem> {
        val scoredList = ArrayList<ScoredMemoryItem>()

        for (candidate in candidates) {
            val entity = candidate.entity

            // 1. Amnesia / Tombstone Barrier (Mandatory Section 28)
            if (tombstoneManager.isTombstoned(entity.key, entity.userId) ||
                tombstoneManager.isTombstoned(entity.value, entity.userId) ||
                entity.status == "TOMBSTONE") {
                continue
            }

            val domainItem = toDomainItem(entity)

            // 2. Bi-Temporal Filtering (Section 29)
            val isValidTime = domainItem.temporalWindow.isValidAt(currentTime)
            if (decision.temporalIntent == TemporalIntent.CURRENT && !isValidTime && domainItem.status != MemoryStatus.ACTIVE) {
                continue
            }

            // 3. Compute Normalized Component Scores
            val trustScore = MemoryScoreEngine.getTrustScore(domainItem.trustLevel)
            val temporalScore = MemoryScoreEngine.getTemporalScore(domainItem, decision.temporalIntent, currentTime)
            val salienceScore = domainItem.salience.coerceIn(0f, 1f)

            // 4. Deterministic Score Fusion (Section 26)
            val finalScore = MemoryScoreEngine.computeFusedScore(
                semanticScore = candidate.semanticScore,
                lexicalScore = candidate.lexicalScore,
                entityScore = candidate.entityScore,
                temporalScore = temporalScore,
                trustScore = trustScore,
                salienceScore = salienceScore
            )

            scoredList.add(
                ScoredMemoryItem(
                    item = domainItem,
                    finalScore = finalScore,
                    semanticScore = candidate.semanticScore,
                    lexicalScore = candidate.lexicalScore,
                    entityScore = candidate.entityScore,
                    temporalScore = temporalScore,
                    trustScore = trustScore
                )
            )
        }

        // Rank strictly by final fused score descending
        return scoredList.sortedByDescending { it.finalScore }
    }

    private fun toDomainItem(entity: com.lichiai.memory.db.entity.MemoryItemEntity): MemoryItem {
        val keys = runCatching {
            json.decodeFromString<List<String>>(entity.associativeKeysJson)
        }.getOrDefault(emptyList())

        return MemoryItem(
            id = entity.id,
            key = entity.key,
            value = entity.value,
            category = runCatching { MemoryCategory.valueOf(entity.category) }.getOrDefault(MemoryCategory.FACT),
            status = runCatching { MemoryStatus.valueOf(entity.status) }.getOrDefault(MemoryStatus.ACTIVE),
            confidence = entity.confidence,
            salience = entity.salience,
            temporalWindow = BiTemporalWindow(
                observedAt = entity.observedAt,
                validFrom = entity.validFrom,
                validUntil = entity.validUntil
            ),
            conversationId = entity.conversationId,
            sourceMessageId = entity.sourceMessageId,
            userId = entity.userId,
            scope = runCatching { MemoryScope.valueOf(entity.scope) }.getOrDefault(MemoryScope.USER),
            trustLevel = runCatching { TrustLevel.valueOf(entity.trustLevel) }.getOrDefault(TrustLevel.USER_EXPLICIT),
            associativeKeys = keys
        )
    }
}
