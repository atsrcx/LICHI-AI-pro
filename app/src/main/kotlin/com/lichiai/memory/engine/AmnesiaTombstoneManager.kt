package com.lichiai.memory.engine

import android.util.Log
import com.lichiai.memory.db.dao.EntityRecordDao
import com.lichiai.memory.db.dao.EntityRelationDao
import com.lichiai.memory.db.dao.MemoryItemDao
import com.lichiai.memory.db.dao.TombstoneDao
import com.lichiai.memory.db.entity.TombstoneRecordEntity
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Amnesia & Tombstone Manager.
 *
 * Implements strict memory erasure (Amnesia) protocol:
 * - Detects explicit user forget/amnesia commands.
 * - Creates immutable Tombstone records.
 * - Soft-deletes / transitions active memory entries to TOMBSTONE status.
 * - Prevents resurrection across vector, FTS, and hybrid retrieval paths.
 */
class AmnesiaTombstoneManager(
    private val tombstoneDao: TombstoneDao,
    private val memoryItemDao: MemoryItemDao,
    private val entityRecordDao: EntityRecordDao,
    private val entityRelationDao: EntityRelationDao
) {
    companion object {
        private const val TAG = "AmnesiaTombstone"
    }

    private val cachedTombstones = ConcurrentHashMap<String, Boolean>()

    suspend fun initializeCache() {
        runCatching {
            val all = tombstoneDao.getAllTombstones()
            cachedTombstones.clear()
            all.forEach { t ->
                cachedTombstones[t.targetIdentifier.lowercase(Locale.ROOT)] = true
            }
        }.onFailure { Log.e(TAG, "Failed initializing tombstone cache", it) }
    }

    fun isTombstoned(identifier: String): Boolean {
        val lower = identifier.lowercase(Locale.ROOT).trim()
        if (cachedTombstones.containsKey(lower)) return true
        return cachedTombstones.keys.any { tomb ->
            lower.contains(tomb) || tomb.contains(lower)
        }
    }

    suspend fun executeForget(
        target: String,
        targetType: String = "IDENTIFIER",
        reason: String = "USER_REQUEST_FORGET",
        conversationId: String? = null
    ): Boolean {
        val cleanTarget = target.trim()
        if (cleanTarget.isBlank()) return false
        val lower = cleanTarget.lowercase(Locale.ROOT)

        Log.i(TAG, "Executing Amnesia / Tombstone on target: '$cleanTarget'")

        val targets = mutableSetOf(lower, cleanTarget)
        if (lower == "naam" || lower == "name" || lower.contains("naam") || lower.contains("name")) {
            targets.addAll(listOf("user_name", "name", "naam"))
        }
        if (lower == "sheher" || lower == "city" || lower == "location" || lower == "residence") {
            targets.addAll(listOf("user_residence", "city", "sheher", "location"))
        }
        if (lower == "email" || lower.contains("email")) {
            targets.addAll(listOf("user_email", "email"))
        }
        if (lower == "phone" || lower == "number" || lower.contains("phone")) {
            targets.addAll(listOf("user_phone", "phone"))
        }

        for (t in targets) {
            val tombstone = TombstoneRecordEntity(
                id = "tomb_${java.util.UUID.randomUUID()}",
                targetType = targetType,
                targetIdentifier = t.lowercase(Locale.ROOT),
                reason = reason,
                createdAt = System.currentTimeMillis(),
                scopeConversationId = conversationId
            )

            tombstoneDao.insert(tombstone)
            cachedTombstones[t.lowercase(Locale.ROOT)] = true

            memoryItemDao.markTombstoneByKey(t)
            entityRecordDao.markTombstoneByName(t)

            val entity = entityRecordDao.findByCanonicalName(t)
            if (entity != null) {
                entityRelationDao.markTombstoneForEntity(entity.entityId)
            }
        }

        return true
    }

    fun parseForgetIntent(input: String): String? {
        val lower = input.lowercase(Locale.ROOT).trim()
        val forgetPrefixes = listOf(
            "forget about ",
            "forget my ",
            "forget ",
            "delete memory of ",
            "delete info about ",
            "clear memory of ",
            "erase memory of ",
            "bhool jao mera ",
            "bhool jao meri ",
            "bhool jao ",
            "delete kar do mere ",
            "delete kar do meri "
        )

        for (prefix in forgetPrefixes) {
            if (lower.startsWith(prefix)) {
                val target = lower.substring(prefix.length).trim().removeSuffix(".").removeSuffix("!")
                if (target.isNotBlank()) return target
            }
        }

        if (lower.contains("bhool jao") || lower.contains("forget that") || lower.contains("forget this")) {
            val after = lower.substringAfter("bhool jao").substringAfter("forget").trim()
            if (after.isNotBlank() && after.length > 2) return after
        }

        return null
    }
}
