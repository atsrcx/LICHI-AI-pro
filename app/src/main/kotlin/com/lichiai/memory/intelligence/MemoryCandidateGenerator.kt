package com.lichiai.memory.intelligence

import android.util.Log
import com.lichiai.memory.db.dao.EntityRecordDao
import com.lichiai.memory.db.dao.MemoryItemDao
import com.lichiai.memory.db.entity.EntityRecordEntity
import com.lichiai.memory.db.entity.MemoryItemEntity
import com.lichiai.memory.model.TemporalIntent
import java.util.Locale

data class MemoryCandidate(
    val entity: MemoryItemEntity,
    val semanticScore: Float = 0f,
    val lexicalScore: Float = 0f,
    val entityScore: Float = 0f
)

/**
 * Bounded Candidate Generator for Memory OS V6.
 * Merges multi-source candidate signals without scanning the complete memory table.
 */
class MemoryCandidateGenerator(
    private val memoryItemDao: MemoryItemDao,
    private val entityRecordDao: EntityRecordDao,
    private val indexManager: MemoryIndexManager,
    private val semanticEncoder: MemorySemanticEncoder
) {
    companion object {
        private const val TAG = "MemoryCandidateGenerator"
    }

    suspend fun generateCandidates(
        cleanQuery: String,
        decision: RoutedQueryDecision,
        userId: String
    ): Pair<List<MemoryCandidate>, List<EntityRecordEntity>> {
        val candidateMap = HashMap<String, MemoryCandidate>()
        val matchedEntities = ArrayList<EntityRecordEntity>()

        // 1. Direct Lookup Optimization
        if (decision.directLookupKey != null) {
            val directItems = if (decision.temporalIntent == TemporalIntent.HISTORICAL) {
                memoryItemDao.getHistoricalByKeyForUser(decision.directLookupKey, userId, limit = 5)
            } else {
                memoryItemDao.getActiveByKeyForUser(decision.directLookupKey, userId, limit = 5)
            }
            for (item in directItems) {
                candidateMap[item.id] = MemoryCandidate(
                    entity = item,
                    lexicalScore = 1.0f,
                    semanticScore = 0.95f
                )
            }
            if (decision.isTrivialDirectLookup) {
                return Pair(candidateMap.values.toList(), emptyList())
            }
        }

        // 2. FTS5 Lexical Candidate Search (Always executes as primary or fallback if semantic unavailable)
        val tokens = cleanQuery.lowercase(Locale.ROOT)
            .split(Regex("""[\s,?.!@#]+"""))
            .filter { it.length > 1 }

        val semanticAvailable = semanticEncoder.isModelAvailable() && indexManager.isReady()
        val shouldRunLexical = decision.requiresLexicalSearch || !semanticAvailable

        if (shouldRunLexical && tokens.isNotEmpty()) {
            val ftsQuery = tokens.take(6).joinToString(" OR ") { "$it*" }
            var ftsResults = runCatching {
                memoryItemDao.searchActiveFtsForUser(ftsQuery, userId, limit = if (!semanticAvailable) 30 else 20)
            }.getOrDefault(emptyList())

            // Fallback substring query if FTS returns no results
            if (ftsResults.isEmpty()) {
                val subResults = mutableListOf<MemoryItemEntity>()
                for (token in tokens.take(3)) {
                    subResults.addAll(memoryItemDao.searchItems(token, limit = 10).filter { it.userId == userId })
                }
                ftsResults = subResults.distinctBy { it.id }
            }

            for (item in ftsResults) {
                val existing = candidateMap[item.id]
                val currentLexScore = computeLexicalOverlap(item, tokens)
                candidateMap[item.id] = MemoryCandidate(
                    entity = item,
                    lexicalScore = maxOf(existing?.lexicalScore ?: 0f, currentLexScore),
                    semanticScore = existing?.semanticScore ?: (if (!semanticAvailable) currentLexScore * 0.8f else 0f),
                    entityScore = existing?.entityScore ?: 0f
                )
            }
        }

        // 3. Semantic Vector Search (HNSW)
        if (decision.requiresSemanticSearch && indexManager.isReady()) {
            val encodedQuery = semanticEncoder.encodeQuery(cleanQuery)
            if (encodedQuery != null) {
                val vectorHits = indexManager.search(encodedQuery.embedding, topK = MemoryModelConfig.DEFAULT_TOP_K)
                if (vectorHits.isNotEmpty()) {
                    val hitIds = vectorHits.map { it.first }
                    val hitMap = vectorHits.toMap()
                    val loadedItems = memoryItemDao.getByIdsForUser(hitIds, userId)

                    for (item in loadedItems) {
                        val sim = hitMap[item.id] ?: 0f
                        val existing = candidateMap[item.id]
                        candidateMap[item.id] = MemoryCandidate(
                            entity = item,
                            semanticScore = maxOf(existing?.semanticScore ?: 0f, sim),
                            lexicalScore = existing?.lexicalScore ?: 0f,
                            entityScore = existing?.entityScore ?: 0f
                        )
                    }
                }
            }
        }

        // 4. Entity Graph Candidate Search
        if (decision.requiresEntitySearch && tokens.isNotEmpty()) {
            for (token in tokens) {
                if (token.length > 1) {
                    val entities = entityRecordDao.searchEntitiesForUser(token, userId, limit = 5)
                    matchedEntities.addAll(entities)
                }
            }

            for (e in matchedEntities) {
                // Link entities to relevant memory items
                val entityItems = memoryItemDao.getActiveByKeyForUser(e.canonicalName, userId, limit = 3)
                for (item in entityItems) {
                    val existing = candidateMap[item.id]
                    candidateMap[item.id] = MemoryCandidate(
                        entity = item,
                        semanticScore = existing?.semanticScore ?: 0f,
                        lexicalScore = existing?.lexicalScore ?: 0f,
                        entityScore = 0.90f
                    )
                }
            }
        }

        // 5. Cap candidates at MAX_CANDIDATES (64, hard limit 100)
        val boundedCandidates = candidateMap.values
            .take(MemoryModelConfig.HARD_MAX_CANDIDATES)
            .take(MemoryModelConfig.MAX_CANDIDATES)

        return Pair(boundedCandidates, matchedEntities)
    }

    private fun computeLexicalOverlap(item: MemoryItemEntity, tokens: List<String>): Float {
        var matches = 0
        val text = "${item.key} ${item.value}".lowercase(Locale.ROOT)
        for (token in tokens) {
            if (text.contains(token)) {
                matches++
            }
        }
        return if (tokens.isEmpty()) 0f else (matches.toFloat() / tokens.size).coerceIn(0f, 1f)
    }
}
