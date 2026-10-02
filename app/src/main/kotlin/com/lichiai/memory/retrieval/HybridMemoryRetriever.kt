package com.lichiai.memory.retrieval

import android.util.Log
import com.lichiai.memory.db.dao.EntityRecordDao
import com.lichiai.memory.db.dao.EntityRelationDao
import com.lichiai.memory.db.dao.MemoryItemDao
import com.lichiai.memory.db.dao.MemorySemanticVectorDao
import com.lichiai.memory.db.dao.RawLedgerDao
import com.lichiai.memory.db.entity.MemorySemanticVectorEntity
import com.lichiai.memory.engine.AmnesiaTombstoneManager
import com.lichiai.memory.intelligence.MemoryIndexManager
import com.lichiai.memory.intelligence.MemoryIntelligenceController
import com.lichiai.memory.intelligence.MemorySemanticEncoder
import com.lichiai.memory.intelligence.MemoryVectorIndex
import com.lichiai.memory.model.MemoryPack
import com.lichiai.memory.model.MemoryQueryPlan
import com.lichiai.memory.model.TemporalIntent

/**
 * Hybrid Memory Retriever - Lichi Memory OS V6.
 *
 * Exposes the canonical MemoryPack retrieval interface powered by on-device
 * semantic vector index (mdbr-leaf-ir ONNX INT8), FTS5 lexical matching, and deterministic navigation.
 * Eliminates all full-table database scans from the retrieval path.
 */
class HybridMemoryRetriever(
    private val controller: MemoryIntelligenceController
) {
    companion object {
        private const val TAG = "HybridMemoryRetriever"
    }

    /**
     * Compatibility constructor for testing & direct DAO wiring.
     */
    constructor(
        memoryItemDao: MemoryItemDao,
        entityRecordDao: EntityRecordDao,
        entityRelationDao: EntityRelationDao,
        rawLedgerDao: RawLedgerDao,
        tombstoneManager: AmnesiaTombstoneManager,
        indexManager: MemoryIndexManager? = null,
        semanticEncoder: MemorySemanticEncoder? = null
    ) : this(
        controller = MemoryIntelligenceController(
            memoryItemDao = memoryItemDao,
            entityRecordDao = entityRecordDao,
            entityRelationDao = entityRelationDao,
            rawLedgerDao = rawLedgerDao,
            tombstoneManager = tombstoneManager,
            indexManager = indexManager ?: createFallbackIndexManager(memoryItemDao),
            semanticEncoder = semanticEncoder ?: createFallbackSemanticEncoder()
        )
    )

    /**
     * Builds a bounded MemoryPack for the given user query, user identity, and conversation context.
     */
    suspend fun retrieveMemoryPack(
        query: String,
        conversationId: String? = null,
        userId: String = "user_primary_default",
        currentTime: Long = System.currentTimeMillis(),
        activeTask: String? = null,
        projectContext: String? = null,
        temporalIntent: TemporalIntent? = null
    ): MemoryPack = runCatching {
        controller.retrieve(
            query = query,
            conversationId = conversationId,
            userId = userId,
            currentTime = currentTime,
            activeTask = activeTask,
            projectContext = projectContext,
            temporalIntent = temporalIntent
        )
    }.getOrElse { e ->
        Log.e(TAG, "Failed to retrieve MemoryPack (fallback to empty)", e)
        MemoryPack()
    }

    /**
     * Derives query plan (preserved for legacy test/caller compatibility).
     */
    fun deriveQueryPlan(query: String): MemoryQueryPlan {
        val decision = com.lichiai.memory.intelligence.MemoryQueryRouter.routeQuery(query)
        return MemoryQueryPlan(
            rawQuery = query,
            subject = "CURRENT_USER",
            predicate = decision.directLookupKey,
            temporalIntent = decision.temporalIntent,
            targetScope = com.lichiai.memory.model.MemoryScope.USER,
            semanticConcepts = emptyList()
        )
    }
}

private fun createFallbackIndexManager(memoryItemDao: MemoryItemDao): MemoryIndexManager {
    val dummyDao = object : MemorySemanticVectorDao {
        override suspend fun upsertVector(vector: MemorySemanticVectorEntity) {}
        override suspend fun upsertVectors(vectors: List<MemorySemanticVectorEntity>) {}
        override suspend fun getVector(memoryId: String): MemorySemanticVectorEntity? = null
        override suspend fun getActiveVectors(): List<MemorySemanticVectorEntity> = emptyList()
        override suspend fun deleteVector(memoryId: String) {}
        override suspend fun markVectorInactive(memoryId: String) {}
        override suspend fun countVectors(): Int = 0
        override suspend fun getVectorsForRebuild(): List<MemorySemanticVectorEntity> = emptyList()
        override suspend fun getVectorsByIds(memoryIds: List<String>): List<MemorySemanticVectorEntity> = emptyList()
    }
    return MemoryIndexManager(
        context = android.content.ContextWrapper(null),
        vectorDao = dummyDao,
        memoryItemDao = memoryItemDao,
        semanticEncoder = createFallbackSemanticEncoder()
    )
}

private fun createFallbackSemanticEncoder(): MemorySemanticEncoder {
    return MemorySemanticEncoder(android.content.ContextWrapper(null))
}
