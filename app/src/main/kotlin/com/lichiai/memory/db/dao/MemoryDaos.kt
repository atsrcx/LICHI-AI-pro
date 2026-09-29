package com.lichiai.memory.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.lichiai.memory.db.entity.ConversationRawLedgerEntity
import com.lichiai.memory.db.entity.EntityRecordEntity
import com.lichiai.memory.db.entity.EntityRelationEntity
import com.lichiai.memory.db.entity.MemoryItemEntity
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

    @Query("SELECT * FROM raw_conversation_ledger ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentTurns(limit: Int): List<ConversationRawLedgerEntity>

    @Query("""
        SELECT raw_conversation_ledger.* 
        FROM raw_conversation_ledger 
        JOIN raw_conversation_ledger_fts ON raw_conversation_ledger.rowid = raw_conversation_ledger_fts.rowid 
        WHERE raw_conversation_ledger_fts MATCH :searchQuery 
        ORDER BY timestamp DESC LIMIT :limit
    """)
    suspend fun searchFts(searchQuery: String, limit: Int = 10): List<ConversationRawLedgerEntity>

    @Query("DELETE FROM raw_conversation_ledger WHERE conversationId = :conversationId")
    suspend fun deleteConversation(conversationId: String)
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

    @Query("""
        SELECT * FROM memory_entities 
        WHERE status = 'ACTIVE' 
        AND (canonicalName LIKE '%' || :query || '%' OR aliasesJson LIKE '%' || :query || '%')
        ORDER BY salience DESC, observedAt DESC LIMIT :limit
    """)
    suspend fun searchEntities(query: String, limit: Int = 10): List<EntityRecordEntity>

    @Query("SELECT * FROM memory_entities WHERE canonicalName = :canonicalName AND status = 'ACTIVE' LIMIT 1")
    suspend fun findByCanonicalName(canonicalName: String): EntityRecordEntity?

    @Query("UPDATE memory_entities SET status = :newStatus WHERE entityId = :entityId")
    suspend fun updateStatus(entityId: String, newStatus: String)

    @Query("UPDATE memory_entities SET status = 'TOMBSTONE' WHERE canonicalName = :targetName OR aliasesJson LIKE '%' || :targetName || '%'")
    suspend fun markTombstoneByName(targetName: String)

    @Query("SELECT * FROM memory_entities WHERE entityType = :type AND status = 'ACTIVE' ORDER BY salience DESC")
    suspend fun getByType(type: String): List<EntityRecordEntity>
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

    @Query("SELECT * FROM memory_relations WHERE status = 'ACTIVE' ORDER BY observedAt DESC")
    suspend fun getAllActiveRelations(): List<EntityRelationEntity>

    @Query("UPDATE memory_relations SET status = :newStatus WHERE relationId = :relationId")
    suspend fun updateStatus(relationId: String, newStatus: String)

    @Query("UPDATE memory_relations SET status = 'TOMBSTONE' WHERE sourceEntityId = :entityId OR targetEntityId = :entityId")
    suspend fun markTombstoneForEntity(entityId: String)
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

    @Query("SELECT * FROM memory_items WHERE `key` = :key AND status = 'ACTIVE' ORDER BY observedAt DESC")
    suspend fun getActiveByKey(key: String): List<MemoryItemEntity>

    @Query("SELECT * FROM memory_items WHERE `key` = :key AND status = 'SUPERSEDED' ORDER BY validUntil DESC, observedAt DESC")
    suspend fun getHistoricalByKey(key: String): List<MemoryItemEntity>

    @Query("SELECT * FROM memory_items WHERE status IN ('ACTIVE', 'SUPERSEDED') ORDER BY observedAt DESC")
    suspend fun getAllValidAndHistoricalItems(): List<MemoryItemEntity>

    @Query("SELECT * FROM memory_items WHERE userId = :userId AND status = 'ACTIVE' ORDER BY salience DESC, observedAt DESC")
    suspend fun getActiveItemsForUser(userId: String): List<MemoryItemEntity>

    @Query("SELECT * FROM memory_items WHERE userId = :userId AND status IN ('ACTIVE', 'SUPERSEDED') ORDER BY observedAt DESC")
    suspend fun getAllItemsForUser(userId: String): List<MemoryItemEntity>

    @Query("SELECT * FROM memory_items WHERE status = 'SUPERSEDED' ORDER BY validUntil DESC, observedAt DESC")
    suspend fun getAllHistoricalItems(): List<MemoryItemEntity>

    @Query("UPDATE memory_items SET status = 'SUPERSEDED', validUntil = :supersededAt WHERE `key` = :key AND status = 'ACTIVE'")
    suspend fun supersedeActiveKey(key: String, supersededAt: Long)

    @Query("UPDATE memory_items SET status = 'TOMBSTONE' WHERE `key` = :key OR `value` LIKE '%' || :key || '%'")
    suspend fun markTombstoneByKey(key: String)
}

@Dao
interface TombstoneDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(tombstone: TombstoneRecordEntity)

    @Query("SELECT * FROM memory_tombstones ORDER BY createdAt DESC")
    suspend fun getAllTombstones(): List<TombstoneRecordEntity>

    @Query("SELECT * FROM memory_tombstones ORDER BY createdAt DESC")
    fun getTombstonesFlow(): Flow<List<TombstoneRecordEntity>>

    @Query("SELECT COUNT(*) FROM memory_tombstones WHERE targetIdentifier = :identifier")
    suspend fun countTombstone(identifier: String): Int

    @Query("DELETE FROM memory_tombstones WHERE targetIdentifier = :identifier")
    suspend fun deleteTombstone(identifier: String)
}
