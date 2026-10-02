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

    suspend fun initializeCache(userId: String? = null) {
        runCatching {
            val all = if (!userId.isNullOrBlank()) {
                tombstoneDao.getAllTombstonesForUser(userId)
            } else {
                tombstoneDao.getAllTombstones()
            }
            if (userId != null) {
                // Clear only this user's cache
                val prefix = "$userId::"
                cachedTombstones.keys.filter { it.startsWith(prefix) }.forEach { cachedTombstones.remove(it) }
            } else {
                cachedTombstones.clear()
            }
            all.forEach { t ->
                val uId = if (t.userId.isNotBlank()) t.userId else "user_primary_default"
                cachedTombstones["$uId::${t.targetIdentifier.lowercase(Locale.ROOT)}"] = true
            }
        }.onFailure { Log.e(TAG, "Failed initializing tombstone cache", it) }
    }

    fun isTombstoned(identifier: String, userId: String = "user_primary_default"): Boolean {
        val lower = identifier.lowercase(Locale.ROOT).trim()
        if (lower.isBlank()) return false
        val userKey = "$userId::$lower"
        if (cachedTombstones.containsKey(userKey)) return true
        val prefix = "$userId::"
        return cachedTombstones.keys.any { key ->
            if (key.startsWith(prefix)) {
                val tomb = key.substring(prefix.length)
                lower == tomb || lower.contains(tomb) || tomb.contains(lower)
            } else false
        }
    }

    suspend fun executeForget(
        target: String,
        targetType: String = "IDENTIFIER",
        reason: String = "USER_REQUEST_FORGET",
        conversationId: String? = null,
        userId: String = "user_primary_default"
    ): Boolean {
        val cleanTarget = target.trim()
        if (cleanTarget.isBlank()) return false
        val lower = cleanTarget.lowercase(Locale.ROOT)

        Log.i(TAG, "Executing Amnesia / Tombstone on target: '$cleanTarget' for userId: '$userId'")

        val targets = mutableSetOf(lower, cleanTarget)
        if (lower == "naam" || lower == "name" || lower.contains("naam") || lower.contains("name")) {
            targets.addAll(listOf("user_name", "name", "naam"))
        }
        if (lower.contains("language") || lower.contains("bhasha") || lower.contains("boli")) {
            targets.addAll(listOf("user_pref_language", "language", "bhasha", "pref_language", "language preference"))
        }
        if (lower == "sheher" || lower == "city" || lower == "location" || lower == "residence" || lower.contains("rehta")) {
            targets.addAll(listOf("user_residence", "city", "sheher", "location", "residence"))
        }
        if (lower == "email" || lower.contains("email") || lower.contains("mail")) {
            targets.addAll(listOf("user_email", "email"))
        }
        if (lower == "phone" || lower == "number" || lower.contains("phone") || lower.contains("mobile")) {
            targets.addAll(listOf("user_phone", "phone"))
        }
        if (lower.contains("project")) {
            targets.addAll(listOf("active_project", "project"))
        }
        if (lower.contains("ui") || lower.contains("theme") || lower.contains("mode")) {
            targets.addAll(listOf("user_pref_ui_theme", "user_pref_dark_ui", "ui", "theme", "dark ui", "light ui"))
        }

        for (t in targets) {
            val tombstone = TombstoneRecordEntity(
                id = "tomb_${java.util.UUID.randomUUID()}",
                targetType = targetType,
                targetIdentifier = t.lowercase(Locale.ROOT),
                reason = reason,
                createdAt = System.currentTimeMillis(),
                scopeConversationId = conversationId,
                userId = userId
            )

            tombstoneDao.insert(tombstone)
            cachedTombstones["$userId::${t.lowercase(Locale.ROOT)}"] = true

            memoryItemDao.markTombstoneByKeyForUser(t, userId)
            entityRecordDao.markTombstoneByNameForUser(t, userId)

            val entity = entityRecordDao.findByCanonicalNameForUser(t, userId)
            if (entity != null) {
                entityRelationDao.markTombstoneForEntity(entity.entityId, userId)
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
            "delete kar do meri ",
            "delete kar do mera ",
            "memory mein se mera ",
            "memory mein se meri ",
            "memory se mera ",
            "memory se meri ",
            "memory me se mera ",
            "memory me se meri "
        )

        for (prefix in forgetPrefixes) {
            if (lower.startsWith(prefix)) {
                var target = lower.substring(prefix.length).trim()
                target = target.replace(Regex("""\b(?:delete karo|delete kar do|hata do|bhool jao)\b"""), "").trim()
                target = target.removeSuffix(".").removeSuffix("!").trim()
                if (target.isNotBlank()) return target
            }
        }

        if (lower.contains("bhool jao") || lower.contains("forget that") || lower.contains("forget this") || lower.contains("delete kar do")) {
            var after = lower
            if (after.contains("bhool jao")) {
                val before = after.substringBefore("bhool jao").trim()
                val rem = after.substringAfter("bhool jao").trim()
                val candidate = if (rem.isNotBlank() && rem.length > 2) rem else before
                var cleanCand = candidate.replace(Regex("""\b(?:mera|meri|mere|apni|memory mein se|memory se)\b"""), "").trim()
                cleanCand = cleanCand.removeSuffix(".").removeSuffix("!").trim()
                if (cleanCand.isNotBlank() && cleanCand.length > 2) return cleanCand
            }
            if (after.contains("delete kar do") || after.contains("delete karo")) {
                var cand = after.replace(Regex("""\b(?:delete kar do|delete karo|memory mein se|memory me se|memory se|mera|meri|mere)\b"""), "").trim()
                cand = cand.removeSuffix(".").removeSuffix("!").trim()
                if (cand.isNotBlank() && cand.length > 2) return cand
            }
            val afterForget = lower.substringAfter("forget").trim()
            var cleanForget = afterForget.replace(Regex("""\b(?:my|the|about)\b"""), "").trim()
            cleanForget = cleanForget.removeSuffix(".").removeSuffix("!").trim()
            if (cleanForget.isNotBlank() && cleanForget.length > 2) return cleanForget
        }

        return null
    }
}
