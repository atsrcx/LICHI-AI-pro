package com.lichiai.memory.manager

import android.content.Context
import com.lichiai.memory.db.LichiMemoryDatabase
import com.lichiai.memory.engine.AmnesiaTombstoneManager
import com.lichiai.memory.engine.BiTemporalConflictResolver
import com.lichiai.memory.intelligence.MemoryConsolidationWorker
import com.lichiai.memory.intelligence.MemoryIndexManager
import com.lichiai.memory.intelligence.MemoryIntelligenceController
import com.lichiai.memory.intelligence.MemorySemanticEncoder
import com.lichiai.memory.model.MemoryPack
import com.lichiai.memory.pipeline.MemoryIngestionPipeline
import com.lichiai.memory.retrieval.HybridMemoryRetriever
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Lichi Memory Engine - Unified On-Device Bi-Temporal Dual-Store Memory OS V6.
 *
 * Central owner of the persistent memory subsystem:
 * - Lossless Raw Ledger (Tier 0 with FTS5)
 * - Knowledge Graph & Structured Facts (Tier 1)
 * - Bi-Temporal conflict resolution & validity windows
 * - Amnesia & Tombstone barrier
 * - On-device Semantic Vector Indexing (MongoDB/mdbr-leaf-ir ONNX INT8)
 * - Deterministic Navigation & Token-Capped MemoryPack
 */
class LichiMemoryEngine private constructor(private val context: Context) {

    val database = LichiMemoryDatabase.getInstance(context)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val tombstoneManager = AmnesiaTombstoneManager(
        tombstoneDao = database.tombstoneDao(),
        memoryItemDao = database.memoryItemDao(),
        entityRecordDao = database.entityRecordDao(),
        entityRelationDao = database.entityRelationDao()
    )

    val biTemporalResolver = BiTemporalConflictResolver(
        memoryItemDao = database.memoryItemDao(),
        entityRecordDao = database.entityRecordDao(),
        entityRelationDao = database.entityRelationDao()
    )

    val ingestionPipeline = MemoryIngestionPipeline(
        rawLedgerDao = database.rawLedgerDao(),
        biTemporalResolver = biTemporalResolver,
        tombstoneManager = tombstoneManager,
        memoryItemDao = database.memoryItemDao(),
        entityRecordDao = database.entityRecordDao()
    )

    val semanticEncoder = MemorySemanticEncoder.getInstance(context)

    val indexManager = MemoryIndexManager(
        context = context,
        vectorDao = database.memorySemanticVectorDao(),
        memoryItemDao = database.memoryItemDao(),
        semanticEncoder = semanticEncoder,
        conversationVectorDao = database.conversationSemanticVectorDao()
    )

    val intelligenceController = MemoryIntelligenceController(
        memoryItemDao = database.memoryItemDao(),
        entityRecordDao = database.entityRecordDao(),
        entityRelationDao = database.entityRelationDao(),
        rawLedgerDao = database.rawLedgerDao(),
        tombstoneManager = tombstoneManager,
        indexManager = indexManager,
        semanticEncoder = semanticEncoder
    )

    val retriever = HybridMemoryRetriever(
        controller = intelligenceController
    )

    val consolidationWorker = MemoryConsolidationWorker(
        memoryItemDao = database.memoryItemDao(),
        vectorDao = database.memorySemanticVectorDao(),
        indexManager = indexManager,
        semanticEncoder = semanticEncoder
    )

    init {
        // Wire up asynchronous semantic indexing on newly saved memory items
        biTemporalResolver.onItemSavedListener = { item ->
            scope.launch {
                intelligenceController.indexMemoryItemAsync(item)
            }
        }

        // Asynchronous non-blocking warmup and index initialization
        scope.launch {
            tombstoneManager.initializeCache()
            semanticEncoder.initialize()
            indexManager.initializeAsync()
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
    ): com.lichiai.memory.model.MemoryWriteResult {
        val resolvedUserId = userId ?: com.lichiai.memory.identity.UserIdentityManager.getStableUserId(context)
        return ingestionPipeline.ingestTurn(
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
        userId: String? = null,
        activeTask: String? = null,
        projectContext: String? = null,
        temporalIntent: com.lichiai.memory.model.TemporalIntent? = null
    ): MemoryPack {
        val resolvedUserId = userId ?: com.lichiai.memory.identity.UserIdentityManager.getStableUserId(context)
        return retriever.retrieveMemoryPack(
            query = query,
            conversationId = conversationId,
            userId = resolvedUserId,
            activeTask = activeTask,
            projectContext = projectContext,
            temporalIntent = temporalIntent
        )
    }

    /**
     * Performs background consolidation and maintenance.
     */
    suspend fun performMaintenance(): Boolean {
        return consolidationWorker.performConsolidation()
    }

    /**
     * Executes explicit user forget request.
     */
    suspend fun executeForget(target: String, conversationId: String? = null, userId: String? = null): Boolean {
        val resolvedUserId = userId ?: com.lichiai.memory.identity.UserIdentityManager.getStableUserId(context)
        val forgotten = tombstoneManager.executeForget(target = target, conversationId = conversationId, userId = resolvedUserId)
        if (forgotten) {
            scope.launch {
                intelligenceController.onMemoryForgotten(target)
            }
        }
        return forgotten
    }

    /**
     * Tombstones a memory item by natural language description or entity target.
     */
    suspend fun tombstoneMemoryByDescription(
        description: String,
        targetEntity: String? = null,
        conversationId: String? = null,
        userId: String? = null
    ): Boolean {
        val resolvedUserId = userId ?: com.lichiai.memory.identity.UserIdentityManager.getStableUserId(context)
        val target = targetEntity?.ifBlank { null } ?: description
        val success = tombstoneManager.executeForget(target = target, conversationId = conversationId, userId = resolvedUserId)
        if (target != description) {
            tombstoneManager.executeForget(target = description, conversationId = conversationId, userId = resolvedUserId)
        }
        scope.launch {
            intelligenceController.onMemoryForgotten(target)
            if (target != description) {
                intelligenceController.onMemoryForgotten(description)
            }
        }
        return success
    }

    /**
     * Cascades deletion across all Room tables and vector indexes for a conversation.
     */
    suspend fun deleteConversation(conversationId: String, userId: String? = null) = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val resolvedUserId = userId ?: com.lichiai.memory.identity.UserIdentityManager.getStableUserId(context)
        database.rawLedgerDao().deleteConversationForUser(conversationId, resolvedUserId)
        database.rawLedgerDao().deleteConversation(conversationId)

        val vectors = database.conversationSemanticVectorDao().getVectorsByConversationId(conversationId)
        for (v in vectors) {
            indexManager.deleteVector(v.turnId)
        }
        database.conversationSemanticVectorDao().deleteByConversationId(conversationId)

        val items = database.memoryItemDao().getByConversationId(conversationId)
        for (item in items) {
            indexManager.deleteVector(item.id)
        }
        database.memoryItemDao().deleteByConversationId(conversationId)

        database.tombstoneDao().deleteByScopeConversationId(conversationId)
        database.entityRecordDao().deleteByConversationId(conversationId)
    }

    /**
     * Deletes a single message turn from Room ledger and vector index.
     */
    suspend fun deleteMessage(messageId: String, userId: String? = null) = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val turn = database.rawLedgerDao().getByMessageId(messageId)
        if (turn != null) {
            database.rawLedgerDao().deleteByMessageId(messageId)
            database.conversationSemanticVectorDao().deleteVector(turn.turnId)
            indexManager.deleteVector(turn.turnId)
        }
        val items = database.memoryItemDao().getBySourceMessageId(messageId)
        for (item in items) {
            indexManager.deleteVector(item.id)
        }
        database.memoryItemDao().deleteBySourceMessageId(messageId)
    }

    /**
     * Updates message content in Room ledger and invalidates/regenerates vector embedding.
     */
    suspend fun updateMessageContent(messageId: String, newContent: String, userId: String? = null) = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val turn = database.rawLedgerDao().getByMessageId(messageId)
        database.rawLedgerDao().updateMessageContent(messageId, newContent)
        if (turn != null) {
            val encoded = semanticEncoder.encodeMemory(turn.turnId, newContent)
            if (encoded != null) {
                indexManager.upsertVector(turn.turnId, encoded.embedding)
            } else {
                indexManager.deleteVector(turn.turnId)
            }
        }
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
