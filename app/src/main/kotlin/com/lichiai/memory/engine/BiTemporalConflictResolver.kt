package com.lichiai.memory.engine

import android.util.Log
import com.lichiai.memory.db.dao.EntityRecordDao
import com.lichiai.memory.db.dao.EntityRelationDao
import com.lichiai.memory.db.dao.MemoryItemDao
import com.lichiai.memory.db.entity.EntityRecordEntity
import com.lichiai.memory.db.entity.EntityRelationEntity
import com.lichiai.memory.db.entity.MemoryItemEntity
import com.lichiai.memory.model.MemoryStatus
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Bi-Temporal Conflict Resolver.
 *
 * Enforces dual timeline consistency:
 * 1. Observation Time (observedAt): When the AI learned the information.
 * 2. Real-World Validity Window (validFrom, validUntil): When the state is true in reality.
 *
 * Handles conflicting facts by setting prior facts to SUPERSEDED with validUntil = newObservedAt,
 * preserving full audit history while ensuring the active state reflects current reality.
 */
class BiTemporalConflictResolver(
    private val memoryItemDao: MemoryItemDao,
    private val entityRecordDao: EntityRecordDao,
    private val entityRelationDao: EntityRelationDao
) {
    companion object {
        private const val TAG = "BiTemporalConflict"
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * Resolves and persists a new memory item with bi-temporal conflict resolution.
     */
    suspend fun resolveAndSaveItem(
        key: String,
        value: String,
        category: String,
        observedAt: Long = System.currentTimeMillis(),
        validFrom: Long? = null,
        validUntil: Long? = null,
        conversationId: String? = null,
        sourceMessageId: String? = null,
        confidence: Float = 1.0f,
        salience: Float = 0.5f,
        userId: String = "user_primary_default",
        scope: String = "USER",
        trustLevel: String = "USER_EXPLICIT",
        associativeKeys: List<String> = emptyList()
    ): MemoryItemEntity {
        // Find existing active items with same key
        val existingActive = memoryItemDao.getActiveByKey(key)

        for (oldItem in existingActive) {
            if (oldItem.value.trim().equals(value.trim(), ignoreCase = true)) {
                // Same value, refresh observation time and salience if higher
                val mergedKeys = runCatching {
                    json.decodeFromString<List<String>>(oldItem.associativeKeysJson)
                }.getOrDefault(emptyList()).toMutableList().apply {
                    addAll(associativeKeys)
                }.distinct()

                val refreshed = oldItem.copy(
                    observedAt = observedAt,
                    salience = maxOf(oldItem.salience, salience),
                    confidence = maxOf(oldItem.confidence, confidence),
                    userId = if (oldItem.userId.isNotBlank()) oldItem.userId else userId,
                    associativeKeysJson = json.encodeToString(mergedKeys)
                )
                memoryItemDao.upsert(refreshed)
                return refreshed
            } else {
                // Different value for same key -> supersede previous fact
                Log.d(TAG, "Superseding old fact for key '$key': '${oldItem.value}' -> '$value'")
                val superseded = oldItem.copy(
                    status = MemoryStatus.SUPERSEDED.name,
                    validUntil = validFrom ?: observedAt
                )
                memoryItemDao.upsert(superseded)
            }
        }

        val associativeJson = json.encodeToString(associativeKeys.map { it.lowercase().trim() }.filter { it.isNotBlank() }.distinct())
        val newItem = MemoryItemEntity(
            id = java.util.UUID.randomUUID().toString(),
            key = key,
            value = value,
            category = category,
            status = MemoryStatus.ACTIVE.name,
            confidence = confidence,
            salience = salience,
            observedAt = observedAt,
            validFrom = validFrom ?: observedAt,
            validUntil = validUntil,
            conversationId = conversationId,
            sourceMessageId = sourceMessageId,
            userId = userId,
            scope = scope,
            trustLevel = trustLevel,
            associativeKeysJson = associativeJson
        )

        memoryItemDao.upsert(newItem)
        return newItem
    }

    /**
     * Resolves and updates an entity record with merged attributes.
     */
    suspend fun resolveAndSaveEntity(
        canonicalName: String,
        entityType: String,
        newAliases: List<String> = emptyList(),
        newAttributes: Map<String, String> = emptyMap(),
        observedAt: Long = System.currentTimeMillis(),
        confidence: Float = 1.0f,
        salience: Float = 0.5f,
        conversationId: String? = null,
        userId: String = "user_primary_default",
        scope: String = "USER"
    ): EntityRecordEntity {
        val existing = entityRecordDao.findByCanonicalName(canonicalName)

        if (existing != null) {
            val existingAliases = runCatching {
                json.decodeFromString<List<String>>(existing.aliasesJson)
            }.getOrDefault(emptyList())

            val existingAttrs = runCatching {
                json.decodeFromString<Map<String, String>>(existing.attributesJson)
            }.getOrDefault(emptyMap())

            val mergedAliases = (existingAliases + newAliases).distinct()
            val mergedAttrs = existingAttrs.toMutableMap().apply {
                putAll(newAttributes)
            }

            val updated = existing.copy(
                aliasesJson = json.encodeToString(mergedAliases),
                attributesJson = json.encodeToString(mergedAttrs),
                confidence = maxOf(existing.confidence, confidence),
                salience = maxOf(existing.salience, salience),
                observedAt = observedAt,
                status = MemoryStatus.ACTIVE.name,
                userId = if (existing.userId.isNotBlank()) existing.userId else userId,
                scope = scope
            )
            entityRecordDao.upsert(updated)
            return updated
        } else {
            val newEntity = EntityRecordEntity(
                entityId = "entity_${java.util.UUID.randomUUID()}",
                entityType = entityType,
                canonicalName = canonicalName,
                aliasesJson = json.encodeToString(newAliases.distinct()),
                attributesJson = json.encodeToString(newAttributes),
                confidence = confidence,
                salience = salience,
                observedAt = observedAt,
                validFrom = observedAt,
                status = MemoryStatus.ACTIVE.name,
                conversationId = conversationId,
                userId = userId,
                scope = scope
            )
            entityRecordDao.upsert(newEntity)
            return newEntity
        }
    }

    /**
     * Resolves and records a relationship between two entities.
     */
    suspend fun resolveAndSaveRelation(
        sourceEntityId: String,
        targetEntityId: String,
        relationType: String,
        attributes: Map<String, String> = emptyMap(),
        confidence: Float = 1.0f,
        observedAt: Long = System.currentTimeMillis()
    ): EntityRelationEntity {
        val existingRelations = entityRelationDao.getRelationsForEntity(sourceEntityId)
        val matching = existingRelations.firstOrNull {
            it.targetEntityId == targetEntityId && it.relationType == relationType
        }

        if (matching != null) {
            val updated = matching.copy(
                observedAt = observedAt,
                confidence = maxOf(matching.confidence, confidence),
                status = MemoryStatus.ACTIVE.name
            )
            entityRelationDao.upsert(updated)
            return updated
        } else {
            val newRelation = EntityRelationEntity(
                relationId = "rel_${java.util.UUID.randomUUID()}",
                sourceEntityId = sourceEntityId,
                targetEntityId = targetEntityId,
                relationType = relationType,
                confidence = confidence,
                attributesJson = json.encodeToString(attributes),
                observedAt = observedAt,
                validFrom = observedAt,
                status = MemoryStatus.ACTIVE.name
            )
            entityRelationDao.upsert(newRelation)
            return newRelation
        }
    }
}
