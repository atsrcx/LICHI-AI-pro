package com.lichiai.memory.intelligence

import android.content.Context
import android.util.Log
import com.lichiai.memory.db.dao.MemoryItemDao
import com.lichiai.memory.db.dao.MemorySemanticVectorDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Background Consolidation Worker for Memory OS V6.
 * Performs maintenance, stale embedding reconciliation, and derived index compaction.
 */
class MemoryConsolidationWorker(
    private val memoryItemDao: MemoryItemDao,
    private val vectorDao: MemorySemanticVectorDao,
    private val indexManager: MemoryIndexManager,
    private val semanticEncoder: MemorySemanticEncoder
) {
    companion object {
        private const val TAG = "MemoryConsolidationWorker"
    }

    /**
     * Reconciles derived vector index with authoritative Room memory items in the background.
     */
    suspend fun performConsolidation(): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Starting background memory consolidation...")

            val activeItems = memoryItemDao.getActiveItems()
            var newlyIndexed = 0

            for (item in activeItems) {
                val existingVector = vectorDao.getVector(item.id)
                if (existingVector == null || existingVector.modelVersion != MemoryModelConfig.MODEL_VERSION) {
                    val encoded = semanticEncoder.encodeMemory(item.id, "${item.key}: ${item.value}")
                    if (encoded != null) {
                        indexManager.upsertVector(item.id, encoded.embedding)
                        newlyIndexed++
                    }
                }
            }

            indexManager.persist()
            Log.i(TAG, "Consolidation complete. Indexed $newlyIndexed new/refreshed memories.")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Consolidation failed", e)
            false
        }
    }
}
