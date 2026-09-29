package com.lichiai.memory.manager

import android.content.Context
import com.lichiai.memory.db.LichiMemoryDatabase
import com.lichiai.memory.engine.AmnesiaTombstoneManager
import com.lichiai.memory.engine.BiTemporalConflictResolver
import com.lichiai.memory.model.MemoryPack
import com.lichiai.memory.pipeline.MemoryIngestionPipeline
import com.lichiai.memory.retrieval.HybridMemoryRetriever
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Lichi Memory Engine - Unified On-Device Bi-Temporal Dual-Store Memory OS.
 *
 * Provides a clean interface for:
 * - Lossless Raw Ledger (Tier 0 with FTS5)
 * - Knowledge Graph & Semantic Memory (Tier 1)
 * - Bi-Temporal conflict resolution & validity windows
 * - Amnesia & Tombstone management
 * - Hybrid token-capped MemoryPack retrieval
 */
class LichiMemoryEngine private constructor(private val context: Context) {

    private val db = LichiMemoryDatabase.getInstance(context)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val tombstoneManager = AmnesiaTombstoneManager(
        tombstoneDao = db.tombstoneDao(),
        memoryItemDao = db.memoryItemDao(),
        entityRecordDao = db.entityRecordDao(),
        entityRelationDao = db.entityRelationDao()
    )

    val biTemporalResolver = BiTemporalConflictResolver(
        memoryItemDao = db.memoryItemDao(),
        entityRecordDao = db.entityRecordDao(),
        entityRelationDao = db.entityRelationDao()
    )

    val ingestionPipeline = MemoryIngestionPipeline(
        rawLedgerDao = db.rawLedgerDao(),
        biTemporalResolver = biTemporalResolver,
        tombstoneManager = tombstoneManager
    )

    val retriever = HybridMemoryRetriever(
        memoryItemDao = db.memoryItemDao(),
        entityRecordDao = db.entityRecordDao(),
        entityRelationDao = db.entityRelationDao(),
        rawLedgerDao = db.rawLedgerDao(),
        tombstoneManager = tombstoneManager
    )

    init {
        scope.launch {
            tombstoneManager.initializeCache()
        }
    }

    /**
     * Records a turn synchronously, guaranteeing read-after-write consistency before turn finishes.
     */
    suspend fun recordTurn(
        conversationId: String,
        messageId: String,
        role: String,
        content: String,
        timestamp: Long = System.currentTimeMillis(),
        userId: String? = null
    ) {
        val resolvedUserId = userId ?: com.lichiai.memory.identity.UserIdentityManager.getStableUserId(context)
        ingestionPipeline.ingestTurn(
            conversationId = conversationId,
            messageId = messageId,
            role = role,
            content = content,
            timestamp = timestamp,
            userId = resolvedUserId
        )
    }

    /**
     * Records a turn asynchronously in the background.
     */
    fun recordTurnAsync(
        conversationId: String,
        messageId: String,
        role: String,
        content: String,
        timestamp: Long = System.currentTimeMillis(),
        userId: String? = null
    ) {
        val resolvedUserId = userId ?: com.lichiai.memory.identity.UserIdentityManager.getStableUserId(context)
        ingestionPipeline.ingestTurnAsync(
            conversationId = conversationId,
            messageId = messageId,
            role = role,
            content = content,
            timestamp = timestamp,
            userId = resolvedUserId
        )
    }

    /**
     * Builds bounded MemoryPack for prompt context injection.
     */
    suspend fun getMemoryPack(
        query: String,
        conversationId: String? = null,
        userId: String? = null
    ): MemoryPack {
        val resolvedUserId = userId ?: com.lichiai.memory.identity.UserIdentityManager.getStableUserId(context)
        return retriever.retrieveMemoryPack(
            query = query,
            conversationId = conversationId,
            userId = resolvedUserId
        )
    }

    /**
     * Executes explicit user forget request.
     */
    suspend fun executeForget(target: String, conversationId: String? = null): Boolean {
        return tombstoneManager.executeForget(target = target, conversationId = conversationId)
    }

    companion object {
        @Volatile
        private var INSTANCE: LichiMemoryEngine? = null

        fun getInstance(context: Context): LichiMemoryEngine {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: LichiMemoryEngine(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
