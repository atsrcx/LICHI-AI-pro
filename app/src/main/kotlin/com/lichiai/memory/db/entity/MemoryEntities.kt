package com.lichiai.memory.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "raw_conversation_ledger",
    indices = [
        Index(value = ["conversationId"]),
        Index(value = ["timestamp"])
    ]
)
data class ConversationRawLedgerEntity(
    @PrimaryKey
    val turnId: String,
    val conversationId: String,
    val messageId: String,
    val role: String,
    val verbatimContent: String,
    val timestamp: Long = System.currentTimeMillis(),
    val metadataJson: String = "{}"
)

@Entity(tableName = "raw_conversation_ledger_fts")
@Fts4(contentEntity = ConversationRawLedgerEntity::class)
data class ConversationRawLedgerFts(
    @ColumnInfo(name = "rowid")
    @PrimaryKey
    val rowid: Int = 0,
    val verbatimContent: String,
    val role: String,
    val conversationId: String
)

@Entity(
    tableName = "memory_entities",
    indices = [
        Index(value = ["canonicalName"]),
        Index(value = ["entityType"]),
        Index(value = ["status"]),
        Index(value = ["conversationId"]),
        Index(value = ["userId"])
    ]
)
data class EntityRecordEntity(
    @PrimaryKey
    val entityId: String,
    val entityType: String,
    val canonicalName: String,
    val aliasesJson: String = "[]",
    val attributesJson: String = "{}",
    val confidence: Float = 1.0f,
    val salience: Float = 0.5f,
    val observedAt: Long = System.currentTimeMillis(),
    val validFrom: Long? = null,
    val validUntil: Long? = null,
    val status: String = "ACTIVE",
    val conversationId: String? = null,
    val userId: String = "user_primary_default",
    val scope: String = "USER"
)

@Entity(
    tableName = "memory_relations",
    indices = [
        Index(value = ["sourceEntityId"]),
        Index(value = ["targetEntityId"]),
        Index(value = ["relationType"]),
        Index(value = ["status"])
    ]
)
data class EntityRelationEntity(
    @PrimaryKey
    val relationId: String,
    val sourceEntityId: String,
    val targetEntityId: String,
    val relationType: String,
    val confidence: Float = 1.0f,
    val attributesJson: String = "{}",
    val observedAt: Long = System.currentTimeMillis(),
    val validFrom: Long? = null,
    val validUntil: Long? = null,
    val status: String = "ACTIVE"
)

@Entity(
    tableName = "memory_items",
    indices = [
        Index(value = ["key"]),
        Index(value = ["category"]),
        Index(value = ["status"]),
        Index(value = ["conversationId"]),
        Index(value = ["userId"]),
        Index(value = ["scope"])
    ]
)
data class MemoryItemEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "key")
    val key: String,
    val value: String,
    val category: String,
    val status: String = "ACTIVE",
    val confidence: Float = 1.0f,
    val salience: Float = 0.5f,
    val observedAt: Long = System.currentTimeMillis(),
    val validFrom: Long? = null,
    val validUntil: Long? = null,
    val conversationId: String? = null,
    val sourceMessageId: String? = null,
    val userId: String = "user_primary_default",
    val scope: String = "USER",
    val trustLevel: String = "USER_EXPLICIT",
    val associativeKeysJson: String = "[]"
)

@Entity(
    tableName = "memory_tombstones",
    indices = [
        Index(value = ["targetIdentifier"]),
        Index(value = ["targetType"]),
        Index(value = ["scopeConversationId"])
    ]
)
data class TombstoneRecordEntity(
    @PrimaryKey
    val id: String,
    val targetType: String,
    val targetIdentifier: String,
    val reason: String = "USER_REQUEST_FORGET",
    val createdAt: Long = System.currentTimeMillis(),
    val scopeConversationId: String? = null
)
