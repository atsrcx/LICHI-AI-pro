package com.lichiai.memory.intelligence

import android.content.Context
import android.util.Log
import com.lichiai.memory.db.dao.MemoryItemDao
import com.lichiai.memory.db.dao.MemorySemanticVectorDao
import com.lichiai.memory.db.entity.MemorySemanticVectorEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages in-memory HNSW index lifecycle, persistence, incremental updates, and background rebuilds.
 */
class MemoryIndexManager(
    private val context: Context,
    private val vectorDao: MemorySemanticVectorDao,
    private val memoryItemDao: MemoryItemDao,
    private val semanticEncoder: MemorySemanticEncoder,
    private val conversationVectorDao: com.lichiai.memory.db.dao.ConversationSemanticVectorDao? = null
) {
    companion object {
        private const val TAG = "MemoryIndexManager"
        private const val INDEX_FILE = "memory_index.bin"
    }

    private val index = MemoryVectorIndex()
    private val indexFile = File(context.filesDir, INDEX_FILE)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mutex = Mutex()
    private val isReadyFlag = AtomicBoolean(false)
    private var persistJob: kotlinx.coroutines.Job? = null

    fun isReady(): Boolean = isReadyFlag.get()

    /**
     * Initializes the vector index asynchronously: loads persisted snapshot, or schedules rebuild if absent.
     */
    fun initializeAsync() {
        scope.launch {
            initialize()
        }
    }

    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (isReadyFlag.get()) return@withContext true

            // 1. Try loading existing persisted index file
            var loadedFromSnapshot = false
            if (indexFile.exists() && indexFile.length() > 0L) {
                loadedFromSnapshot = index.load(indexFile)
                if (!loadedFromSnapshot) {
                    Log.w(TAG, "Snapshot invalid or corrupt, clearing index.")
                    index.clear()
                }
            }

            // 2. Reconcile against authoritative Room vectors (both structured and conversation turns)
            val activeRoomVectors = vectorDao.getActiveVectors()
            val activeConvVectors = conversationVectorDao?.getActiveVectors() ?: emptyList()
            val totalAuthoritativeCount = activeRoomVectors.size + activeConvVectors.size

            if (loadedFromSnapshot && index.size() > 0 && index.size() == totalAuthoritativeCount) {
                isReadyFlag.set(true)
                Log.i(TAG, "MemoryVectorIndex verified from snapshot (${index.size()} active nodes matching Room).")
                return@withContext true
            }

            // 3. Discrepancy detected or no valid snapshot: rebuild cleanly from authoritative Room database
            Log.i(TAG, "Reconciling vector index from authoritative Room (snapshot size=${index.size()}, authoritative count=$totalAuthoritativeCount)...")
            val rebuilt = rebuildIndexInternal()
            if (rebuilt) {
                index.persist(indexFile)
            }
            isReadyFlag.set(rebuilt)
            rebuilt
        }
    }

    /**
     * Searches top-k relevant memory IDs using HNSW vector index.
     */
    suspend fun search(queryVector: ByteArray, topK: Int = MemoryModelConfig.DEFAULT_TOP_K): List<Pair<String, Float>> {
        if (!isReadyFlag.get()) {
            return emptyList()
        }
        return withContext(Dispatchers.Default) {
            index.search(queryVector, topK)
        }
    }

    /**
     * Adds or updates a vector in both Room and the in-memory HNSW index.
     */
    suspend fun upsertVector(memoryId: String, vectorBytes: ByteArray) = withContext(Dispatchers.IO) {
        val entity = MemorySemanticVectorEntity(
            memoryId = memoryId,
            embeddingInt8 = vectorBytes,
            dimension = MemoryModelConfig.DIMENSIONS,
            modelVersion = MemoryModelConfig.MODEL_VERSION,
            quantizationVersion = 1,
            indexedAt = System.currentTimeMillis(),
            status = "ACTIVE"
        )
        vectorDao.upsertVector(entity)

        mutex.withLock {
            index.insert(memoryId, vectorBytes)
            if (!isReadyFlag.get()) {
                isReadyFlag.set(true)
            }
            scheduleSnapshotPersist()
        }
    }

    /**
     * Removes a vector from index and marks inactive in Room.
     */
    suspend fun deleteVector(memoryId: String) = withContext(Dispatchers.IO) {
        vectorDao.deleteVector(memoryId)
        mutex.withLock {
            index.delete(memoryId)
            scheduleSnapshotPersist()
        }
    }

    private fun scheduleSnapshotPersist() {
        persistJob?.cancel()
        persistJob = scope.launch {
            kotlinx.coroutines.delay(500)
            mutex.withLock {
                if (isReadyFlag.get()) {
                    index.persist(indexFile)
                }
            }
        }
    }

    /**
     * Rebuilds the entire vector index from Room vectors or active memory items.
     */
    suspend fun rebuildIndex(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            rebuildIndexInternal()
        }
    }

    private suspend fun rebuildIndexInternal(): Boolean {
        return try {
            index.clear()
            val existingVectors = vectorDao.getVectorsForRebuild()

            if (existingVectors.isNotEmpty()) {
                for (vec in existingVectors) {
                    if (vec.status == "ACTIVE" && vec.dimension == MemoryModelConfig.DIMENSIONS &&
                        vec.modelVersion == MemoryModelConfig.MODEL_VERSION) {
                        index.insert(vec.memoryId, vec.embeddingInt8)
                    }
                }
            } else {
                // Generate embeddings for existing active memory items
                val activeItems = memoryItemDao.getActiveItems()
                for (item in activeItems) {
                    val encoded = semanticEncoder.encodeMemory(item.id, "${item.key}: ${item.value}")
                    if (encoded != null) {
                        val entity = MemorySemanticVectorEntity(
                            memoryId = item.id,
                            embeddingInt8 = encoded.embedding,
                            dimension = MemoryModelConfig.DIMENSIONS,
                            modelVersion = MemoryModelConfig.MODEL_VERSION,
                            quantizationVersion = 1,
                            indexedAt = System.currentTimeMillis(),
                            status = "ACTIVE"
                        )
                        vectorDao.upsertVector(entity)
                        index.insert(item.id, encoded.embedding)
                    }
                }
            }

            // Also load active conversation vectors
            val convVectors = conversationVectorDao?.getActiveVectors() ?: emptyList()
            for (cv in convVectors) {
                if (cv.dimension == MemoryModelConfig.DIMENSIONS &&
                    cv.modelVersion == MemoryModelConfig.MODEL_VERSION) {
                    index.insert(cv.turnId, cv.embeddingInt8)
                }
            }

            // Save snapshot
            index.persist(indexFile)
            isReadyFlag.set(true)
            Log.i(TAG, "Rebuild completed. Vector index ready with ${index.size()} nodes.")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to rebuild vector index", e)
            false
        }
    }

    /**
     * Persists current in-memory index state to disk.
     */
    suspend fun persist() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (isReadyFlag.get()) {
                index.persist(indexFile)
            }
        }
    }
}
