package com.lichiai.memory.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.lichiai.memory.db.entity.ConversationRawLedgerEntity
import com.lichiai.memory.db.entity.ConversationSemanticVectorEntity
import com.lichiai.memory.db.entity.EntityRecordEntity
import com.lichiai.memory.db.entity.EntityRelationEntity
import com.lichiai.memory.db.entity.MemoryItemEntity
import com.lichiai.memory.db.entity.MemorySemanticVectorEntity
import com.lichiai.memory.db.entity.TombstoneRecordEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RawLedgerDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(turn: ConversationRawLedgerEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(turns: List<ConversationRawLedgerEntity>)

    @Query("SELECT * FROM raw_conversation_ledger WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    fun getTurnsFlow(conversationId: String): Flow<List<ConversationRawLedgerEntity>>

    @Query("SELECT * FROM raw_conversation_ledger WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    suspend fun getTurns(conversationId: String): List<ConversationRawLedgerEntity>

    @Query("SELECT * FROM raw_conversation_ledger WHERE conversationId = :conversationId AND userId = :userId ORDER BY timestamp ASC")
    suspend fun getTurnsForUser(conversationId: String, userId: String): List<ConversationRawLedgerEntity>

    @Query("SELECT * FROM raw_conversation_ledger ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentTurns(limit: Int): List<ConversationRawLedgerEntity>

    @Query("SELECT * FROM raw_conversation_ledger WHERE userId = :userId ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentTurnsForUser(userId: String, limit: Int): List<ConversationRawLedgerEntity>

    @Query("""
        SELECT raw_conversation_ledger.* 
        FROM raw_conversation_ledger 
        JOIN raw_conversation_ledger_fts ON raw_conversation_ledger.rowid = raw_conversation_ledger_fts.rowid 
        WHERE raw_conversation_ledger_fts MATCH :searchQuery 
        ORDER BY timestamp DESC LIMIT :limit
    """)
    suspend fun searchFts(searchQuery: String, limit: Int = 10): List<ConversationRawLedgerEntity>

    @Query("""
        SELECT raw_conversation_ledger.* 
        FROM raw_conversation_ledger 
        JOIN raw_conversation_ledger_fts ON raw_conversation_ledger.rowid = raw_conversation_ledger_fts.rowid 
        WHERE raw_conversation_ledger_fts MATCH :searchQuery AND raw_conversation_ledger.userId = :userId
        ORDER BY timestamp DESC LIMIT :limit
    """)
    suspend fun searchFtsForUser(searchQuery: String, userId: String, limit: Int = 10): List<ConversationRawLedgerEntity>

    @Query("DELETE FROM raw_conversation_ledger WHERE conversationId = :conversationId")
    suspend fun deleteConversation(conversationId: String)

    @Query("DELETE FROM raw_conversation_ledger WHERE conversationId = :conversationId AND userId = :userId")
    suspend fun deleteConversationForUser(conversationId: String, userId: String)

    @Query("SELECT * FROM raw_conversation_ledger WHERE messageId = :messageId LIMIT 1")
    suspend fun getByMessageId(messageId: String): ConversationRawLedgerEntity?

    @Query("DELETE FROM raw_conversation_ledger WHERE messageId = :messageId")
    suspend fun deleteByMessageId(messageId: String)

    @Query("UPDATE raw_conversation_ledger SET verbatimContent = :newContent WHERE messageId = :messageId")
    suspend fun updateMessageContent(messageId: String, newContent: String)
}

@Dao
interface EntityRecordDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: EntityRecordEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<EntityRecordEntity>)

    @Update
    suspend fun update(entity: EntityRecordEntity)

    @Query("SELECT * FROM memory_entities WHERE entityId = :entityId")
    suspend fun getById(entityId: String): EntityRecordEntity?

    @Query("SELECT * FROM memory_entities WHERE status = 'ACTIVE' ORDER BY salience DESC, observedAt DESC")
    fun getActiveEntitiesFlow(): Flow<List<EntityRecordEntity>>

    @Query("SELECT * FROM memory_entities WHERE status = 'ACTIVE' ORDER BY salience DESC, observedAt DESC")
    suspend fun getActiveEntities(): List<EntityRecordEntity>

    @Query("SELECT * FROM memory_entities WHERE userId = :userId AND status = 'ACTIVE' ORDER BY salience DESC, observedAt DESC")
    suspend fun getActiveEntitiesForUser(userId: String): List<EntityRecordEntity>

    @Query("""
        SELECT * FROM memory_entities 
        WHERE status = 'ACTIVE' 
        AND (canonicalName LIKE '%' || :query || '%' OR aliasesJson LIKE '%' || :query || '%')
        ORDER BY salience DESC, observedAt DESC LIMIT :limit
    """)
    suspend fun searchEntities(query: String, limit: Int = 10): List<EntityRecordEntity>

    @Query("""
        SELECT * FROM memory_entities 
        WHERE status = 'ACTIVE' AND userId = :userId
        AND (canonicalName LIKE '%' || :query || '%' OR aliasesJson LIKE '%' || :query || '%')
        ORDER BY salience DESC, observedAt DESC LIMIT :limit
    """)
    suspend fun searchEntitiesForUser(query: String, userId: String, limit: Int = 10): List<EntityRecordEntity>

    @Query("SELECT * FROM memory_entities WHERE entityId IN (:ids) AND status = 'ACTIVE'")
    suspend fun getByIds(ids: List<String>): List<EntityRecordEntity>

    @Query("SELECT * FROM memory_entities WHERE canonicalName = :canonicalName AND status = 'ACTIVE' LIMIT 1")
    suspend fun findByCanonicalName(canonicalName: String): EntityRecordEntity?

    @Query("SELECT * FROM memory_entities WHERE canonicalName = :canonicalName AND userId = :userId AND status = 'ACTIVE' LIMIT 1")
    suspend fun findByCanonicalNameForUser(canonicalName: String, userId: String): EntityRecordEntity?

    @Query("UPDATE memory_entities SET status = :newStatus WHERE entityId = :entityId")
    suspend fun updateStatus(entityId: String, newStatus: String)

    @Query("UPDATE memory_entities SET status = 'TOMBSTONE' WHERE canonicalName = :targetName OR aliasesJson LIKE '%' || :targetName || '%'")
    suspend fun markTombstoneByName(targetName: String)

    @Query("UPDATE memory_entities SET status = 'TOMBSTONE' WHERE userId = :userId AND (canonicalName = :targetName OR aliasesJson LIKE '%' || :targetName || '%')")
    suspend fun markTombstoneByNameForUser(targetName: String, userId: String)

    @Query("SELECT * FROM memory_entities WHERE entityType = :type AND status = 'ACTIVE' ORDER BY salience DESC")
    suspend fun getByType(type: String): List<EntityRecordEntity>

    @Query("SELECT * FROM memory_entities WHERE entityType = :type AND userId = :userId AND status = 'ACTIVE' ORDER BY salience DESC")
    suspend fun getByTypeForUser(type: String, userId: String): List<EntityRecordEntity>

    @Query("DELETE FROM memory_entities WHERE conversationId = :conversationId")
    suspend fun deleteByConversationId(conversationId: String)
}

@Dao
interface EntityRelationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(relation: EntityRelationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(relations: List<EntityRelationEntity>)

    @Query("""
        SELECT * FROM memory_relations 
        WHERE (sourceEntityId = :entityId OR targetEntityId = :entityId) 
        AND status = 'ACTIVE'
    """)
    suspend fun getRelationsForEntity(entityId: String): List<EntityRelationEntity>

    @Query("""
        SELECT * FROM memory_relations 
        WHERE userId = :userId AND (sourceEntityId = :entityId OR targetEntityId = :entityId) 
        AND status = 'ACTIVE'
    """)
    suspend fun getRelationsForEntity(entityId: String, userId: String): List<EntityRelationEntity>

    @Query("SELECT * FROM memory_relations WHERE status = 'ACTIVE' ORDER BY observedAt DESC")
    suspend fun getAllActiveRelations(): List<EntityRelationEntity>

    @Query("SELECT * FROM memory_relations WHERE userId = :userId AND status = 'ACTIVE' ORDER BY observedAt DESC")
    suspend fun getAllActiveRelationsForUser(userId: String): List<EntityRelationEntity>

    @Query("UPDATE memory_relations SET status = :newStatus WHERE relationId = :relationId")
    suspend fun updateStatus(relationId: String, newStatus: String)

    @Query("UPDATE memory_relations SET status = 'TOMBSTONE' WHERE sourceEntityId = :entityId OR targetEntityId = :entityId")
    suspend fun markTombstoneForEntity(entityId: String)

    @Query("UPDATE memory_relations SET status = 'TOMBSTONE' WHERE userId = :userId AND (sourceEntityId = :entityId OR targetEntityId = :entityId)")
    suspend fun markTombstoneForEntity(entityId: String, userId: String)
}

@Dao
interface MemoryItemDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: MemoryItemEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<MemoryItemEntity>)

    @Query("SELECT * FROM memory_items WHERE id = :id")
    suspend fun getById(id: String): MemoryItemEntity?

    @Query("SELECT * FROM memory_items WHERE status = 'ACTIVE' ORDER BY salience DESC, observedAt DESC")
    fun getActiveItemsFlow(): Flow<List<MemoryItemEntity>>

    @Query("SELECT * FROM memory_items WHERE status = 'ACTIVE' ORDER BY salience DESC, observedAt DESC")
    suspend fun getActiveItems(): List<MemoryItemEntity>

    @Query("SELECT * FROM memory_items WHERE category = :category AND status = 'ACTIVE' ORDER BY salience DESC, observedAt DESC")
    suspend fun getActiveByCategory(category: String): List<MemoryItemEntity>

    @Query("""
        SELECT * FROM memory_items 
        WHERE status = 'ACTIVE' 
        AND (`key` LIKE '%' || :query || '%' OR `value` LIKE '%' || :query || '%')
        ORDER BY salience DESC, observedAt DESC LIMIT :limit
    """)
    suspend fun searchItems(query: String, limit: Int = 10): List<MemoryItemEntity>

    @Query("SELECT * FROM memory_items WHERE `key` = :key AND userId = :userId AND status = 'ACTIVE' ORDER BY observedAt DESC LIMIT :limit")
    suspend fun getActiveByKeyForUser(key: String, userId: String, limit: Int = 10): List<MemoryItemEntity>

    @Query("SELECT * FROM memory_items WHERE `key` = :key AND userId = :userId AND status = 'SUPERSEDED' ORDER BY validUntil DESC, observedAt DESC LIMIT :limit")
    suspend fun getHistoricalByKeyForUser(key: String, userId: String, limit: Int = 10): List<MemoryItemEntity>

    @Query("SELECT * FROM memory_items WHERE dedupeKey = :dedupeKey AND userId = :userId AND status = 'ACTIVE' ORDER BY observedAt DESC")
    suspend fun getActiveByDedupeKeyForUser(dedupeKey: String, userId: String): List<MemoryItemEntity>

    @Query("SELECT * FROM memory_items WHERE category = :category AND userId = :userId AND status = 'ACTIVE' ORDER BY salience DESC, observedAt DESC LIMIT :limit")
    suspend fun getActiveByCategoryForUser(category: String, userId: String, limit: Int = 10): List<MemoryItemEntity>

    @Query("""
        SELECT memory_items.* 
        FROM memory_items 
        JOIN memory_items_fts ON memory_items.rowid = memory_items_fts.rowid 
        WHERE memory_items_fts MATCH :searchQuery AND memory_items.userId = :userId AND memory_items.status = 'ACTIVE'
        ORDER BY memory_items.salience DESC, memory_items.observedAt DESC LIMIT :limit
    """)
    suspend fun searchActiveFtsForUser(searchQuery: String, userId: String, limit: Int = 10): List<MemoryItemEntity>

    @Query("""
        SELECT * FROM memory_items 
        WHERE userId = :userId AND status = 'ACTIVE' 
        AND (`key` IN ('user_name', 'user_pref_language', 'user_residence', 'active_project') OR `key` LIKE 'user_pref_%' OR `key` LIKE 'project_%' OR `key` LIKE 'user_%')
        ORDER BY salience DESC
    """)
    suspend fun getActiveProfileItems(userId: String): List<MemoryItemEntity>

    @Query("SELECT * FROM memory_items WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<MemoryItemEntity>

    @Query("SELECT * FROM memory_items WHERE id IN (:ids) AND userId = :userId")
    suspend fun getByIdsForUser(ids: List<String>, userId: String): List<MemoryItemEntity>

    @Query("SELECT * FROM memory_items WHERE `key` = :key AND status = 'ACTIVE' ORDER BY observedAt DESC")
    suspend fun getActiveByKey(key: String): List<MemoryItemEntity>

    @Query("SELECT * FROM memory_items WHERE `key` = :key AND status = 'SUPERSEDED' ORDER BY validUntil DESC, observedAt DESC")
    suspend fun getHistoricalByKey(key: String): List<MemoryItemEntity>

    @Query("SELECT * FROM memory_items WHERE status IN ('ACTIVE', 'SUPERSEDED') ORDER BY observedAt DESC")
    suspend fun getAllValidAndHistoricalItems(): List<MemoryItemEntity>

    @Query("SELECT * FROM memory_items WHERE userId = :userId AND status = 'ACTIVE' ORDER BY salience DESC, observedAt DESC")
    suspend fun getActiveItemsForUser(userId: String): List<MemoryItemEntity>

    @Query("SELECT * FROM memory_items WHERE userId = :userId AND status = 'SUPERSEDED' ORDER BY validUntil DESC, observedAt DESC")
    suspend fun getHistoricalItemsForUser(userId: String): List<MemoryItemEntity>

    @Query("SELECT * FROM memory_items WHERE userId = :userId AND status IN ('ACTIVE', 'SUPERSEDED') ORDER BY observedAt DESC")
    suspend fun getAllItemsForUser(userId: String): List<MemoryItemEntity>

    @Query("SELECT * FROM memory_items WHERE status = 'SUPERSEDED' ORDER BY validUntil DESC, observedAt DESC")
    suspend fun getAllHistoricalItems(): List<MemoryItemEntity>

    @Query("UPDATE memory_items SET status = 'SUPERSEDED', validUntil = :supersededAt WHERE `key` = :key AND status = 'ACTIVE'")
    suspend fun supersedeActiveKey(key: String, supersededAt: Long)

    @Query("UPDATE memory_items SET status = 'SUPERSEDED', validUntil = :supersededAt WHERE `key` = :key AND userId = :userId AND status = 'ACTIVE'")
    suspend fun supersedeActiveKeyForUser(key: String, userId: String, supersededAt: Long)

    @Query("UPDATE memory_items SET status = 'TOMBSTONE' WHERE `key` = :key OR `value` LIKE '%' || :key || '%'")
    suspend fun markTombstoneByKey(key: String)

    @Query("UPDATE memory_items SET status = 'TOMBSTONE' WHERE userId = :userId AND (`key` = :key OR `value` LIKE '%' || :key || '%')")
    suspend fun markTombstoneByKeyForUser(key: String, userId: String)

    @Query("DELETE FROM memory_items WHERE conversationId = :conversationId")
    suspend fun deleteByConversationId(conversationId: String)

    @Query("DELETE FROM memory_items WHERE sourceMessageId = :messageId")
    suspend fun deleteBySourceMessageId(messageId: String)

    @Query("SELECT * FROM memory_items WHERE sourceMessageId = :messageId")
    suspend fun getBySourceMessageId(messageId: String): List<MemoryItemEntity>

    @Query("SELECT * FROM memory_items WHERE conversationId = :conversationId")
    suspend fun getByConversationId(conversationId: String): List<MemoryItemEntity>
}

@Dao
interface TombstoneDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(tombstone: TombstoneRecordEntity)

    @Query("SELECT * FROM memory_tombstones ORDER BY createdAt DESC")
    suspend fun getAllTombstones(): List<TombstoneRecordEntity>

    @Query("SELECT * FROM memory_tombstones WHERE userId = :userId ORDER BY createdAt DESC")
    suspend fun getAllTombstonesForUser(userId: String): List<TombstoneRecordEntity>

    @Query("SELECT * FROM memory_tombstones ORDER BY createdAt DESC")
    fun getTombstonesFlow(): Flow<List<TombstoneRecordEntity>>

    @Query("SELECT COUNT(*) FROM memory_tombstones WHERE targetIdentifier = :identifier")
    suspend fun countTombstone(identifier: String): Int

    @Query("SELECT COUNT(*) FROM memory_tombstones WHERE targetIdentifier = :identifier AND userId = :userId")
    suspend fun countTombstoneForUser(identifier: String, userId: String): Int

    @Query("DELETE FROM memory_tombstones WHERE targetIdentifier = :identifier")
    suspend fun deleteTombstone(identifier: String)

    @Query("DELETE FROM memory_tombstones WHERE targetIdentifier = :identifier AND userId = :userId")
    suspend fun deleteTombstoneForUser(identifier: String, userId: String)

    @Query("DELETE FROM memory_tombstones WHERE scopeConversationId = :conversationId")
    suspend fun deleteByScopeConversationId(conversationId: String)
}

@Dao
interface MemorySemanticVectorDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertVector(vector: MemorySemanticVectorEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertVectors(vectors: List<MemorySemanticVectorEntity>)

    @Query("SELECT * FROM memory_semantic_vectors WHERE memoryId = :memoryId LIMIT 1")
    suspend fun getVector(memoryId: String): MemorySemanticVectorEntity?

    @Query("SELECT * FROM memory_semantic_vectors WHERE status = 'ACTIVE'")
    suspend fun getActiveVectors(): List<MemorySemanticVectorEntity>

    @Query("DELETE FROM memory_semantic_vectors WHERE memoryId = :memoryId")
    suspend fun deleteVector(memoryId: String)

    @Query("UPDATE memory_semantic_vectors SET status = 'INACTIVE' WHERE memoryId = :memoryId")
    suspend fun markVectorInactive(memoryId: String)

    @Query("SELECT COUNT(*) FROM memory_semantic_vectors WHERE status = 'ACTIVE'")
    suspend fun countVectors(): Int

    @Query("SELECT * FROM memory_semantic_vectors WHERE status = 'ACTIVE' ORDER BY indexedAt ASC")
    suspend fun getVectorsForRebuild(): List<MemorySemanticVectorEntity>

    @Query("SELECT * FROM memory_semantic_vectors WHERE memoryId IN (:memoryIds)")
    suspend fun getVectorsByIds(memoryIds: List<String>): List<MemorySemanticVectorEntity>
}

@Dao
interface ConversationSemanticVectorDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(vector: ConversationSemanticVectorEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(vectors: List<ConversationSemanticVectorEntity>)

    @Query("SELECT * FROM conversation_semantic_vectors WHERE turnId = :turnId LIMIT 1")
    suspend fun getVector(turnId: String): ConversationSemanticVectorEntity?

    @Query("SELECT * FROM conversation_semantic_vectors WHERE turnId IN (:turnIds)")
    suspend fun getVectorsByIds(turnIds: List<String>): List<ConversationSemanticVectorEntity>

    @Query("DELETE FROM conversation_semantic_vectors WHERE turnId = :turnId")
    suspend fun deleteVector(turnId: String)

    @Query("DELETE FROM conversation_semantic_vectors WHERE conversationId = :conversationId")
    suspend fun deleteByConversationId(conversationId: String)

    @Query("SELECT * FROM conversation_semantic_vectors WHERE conversationId = :conversationId")
    suspend fun getVectorsByConversationId(conversationId: String): List<ConversationSemanticVectorEntity>

    @Query("DELETE FROM conversation_semantic_vectors WHERE userId = :userId")
    suspend fun deleteVectorsForUser(userId: String)

    @Query("UPDATE conversation_semantic_vectors SET status = 'INACTIVE' WHERE turnId = :turnId")
    suspend fun markInactive(turnId: String)

    @Query("SELECT * FROM conversation_semantic_vectors WHERE userId = :userId AND status = 'ACTIVE' ORDER BY indexedAt DESC")
    suspend fun getActiveVectorsForUser(userId: String): List<ConversationSemanticVectorEntity>

    @Query("SELECT * FROM conversation_semantic_vectors WHERE status = 'ACTIVE' ORDER BY indexedAt DESC")
    suspend fun getActiveVectors(): List<ConversationSemanticVectorEntity>

    @Query("SELECT COUNT(*) FROM conversation_semantic_vectors WHERE status = 'ACTIVE'")
    suspend fun count(): Int
}

