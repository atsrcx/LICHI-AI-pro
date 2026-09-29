package com.lichiai.memory.pipeline

import android.util.Log
import com.lichiai.memory.db.dao.RawLedgerDao
import com.lichiai.memory.db.entity.ConversationRawLedgerEntity
import com.lichiai.memory.engine.AmnesiaTombstoneManager
import com.lichiai.memory.engine.BiTemporalConflictResolver
import com.lichiai.memory.model.EntityType
import com.lichiai.memory.model.MemoryCategory
import com.lichiai.memory.model.MemoryScope
import com.lichiai.memory.model.TrustLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import java.util.regex.Pattern

/**
 * Memory Ingestion Pipeline - Lichi Memory OS V5.
 *
 * Runs asynchronously off the main thread with optional synchronous commit for turn-completion.
 * Guarantees:
 * 1. Tier 0 Lossless Raw Ledger: Every message turn recorded verbatim into FTS5-backed ledger.
 * 2. Amnesia Detection: Parses explicit forget commands and executes Tombstones.
 * 3. Secret Scrubbing: Sensitive secrets redacted before semantic memory persistence.
 * 4. Memory Poisoning Defense: Rejects prompt injections or instruction-like inputs from entering long-term memory.
 * 5. Semantic Knowledge Extraction: Identifies user identity, residence/relocation, language preferences, projects, and entities.
 * 6. Bi-Temporal Conflict Resolution: Compares new facts against active facts and updates validity windows.
 * 7. Associative Indexing: Attaches semantic keys, synonyms, and query triggers to every stored fact.
 * 8. Concurrency Safety: Isolated with Mutex, CoroutineScope(Dispatchers.IO + SupervisorJob), zero main-thread blocking.
 */
class MemoryIngestionPipeline(
    private val rawLedgerDao: RawLedgerDao,
    private val biTemporalResolver: BiTemporalConflictResolver,
    private val tombstoneManager: AmnesiaTombstoneManager
) {
    companion object {
        private const val TAG = "MemoryIngestionPipeline"

        // Anti-Poisoning & Injection patterns: Text attempting to subvert agent safety or issue commands
        private val SUSPICIOUS_INJECTION_PATTERNS = listOf(
            Pattern.compile("""(?i)\b(?:ignore all (?:previous|system)?\s*rules|disregard (?:safety|guidelines)|you are now|override instructions)\b"""),
            Pattern.compile("""(?i)\b(?:system instructions|developer message|expose (?:api|secret|key|token)|bypass authorization)\b"""),
            Pattern.compile("""(?i)\b(?:whenever you see|automatically execute|always call|send payment|delete all)\b""")
        )
    }

    private val pipelineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val ingestionMutex = Mutex()

    fun ingestTurnAsync(
        conversationId: String,
        messageId: String,
        role: String,
        content: String,
        timestamp: Long = System.currentTimeMillis(),
        userId: String = "user_primary_default"
    ) {
        pipelineScope.launch {
            ingestTurn(conversationId, messageId, role, content, timestamp, userId)
        }
    }

    suspend fun ingestTurn(
        conversationId: String,
        messageId: String,
        role: String,
        content: String,
        timestamp: Long = System.currentTimeMillis(),
        userId: String = "user_primary_default"
    ) = runCatching {
        ingestionMutex.withLock {
            val safeConversationId = conversationId.ifBlank { "default_session" }
            val safeTurnId = "turn_${safeConversationId}_${messageId.ifBlank { timestamp.toString() }}"

            // 1. TIER 0: LOSSLESS RAW RECORD
            val rawEntity = ConversationRawLedgerEntity(
                turnId = safeTurnId,
                conversationId = safeConversationId,
                messageId = messageId,
                role = role,
                verbatimContent = content,
                timestamp = timestamp
            )
            rawLedgerDao.insert(rawEntity)

            // Only analyze user messages for user preferences, facts, and amnesia
            if (role.equals("user", ignoreCase = true)) {
                // 2. CHECK AMNESIA / FORGET INTENTS
                val forgetTarget = tombstoneManager.parseForgetIntent(content)
                if (forgetTarget != null) {
                    Log.i(TAG, "Forget intent detected for: '$forgetTarget'")
                    tombstoneManager.executeForget(
                        target = forgetTarget,
                        conversationId = safeConversationId
                    )
                    return@withLock
                }

                // 3. SECURITY / ANTI-POISONING CHECK
                if (isSuspiciousInjection(content)) {
                    Log.w(TAG, "Memory poisoning defense: Rejected suspicious instruction-like input from durable memory.")
                    return@withLock
                }

                // 4. EXTRACT SEMANTIC FACTS & PREFERENCES
                extractAndPersistSemanticMemory(
                    conversationId = safeConversationId,
                    messageId = messageId,
                    content = content,
                    timestamp = timestamp,
                    userId = userId
                )
            }
        }
    }.onFailure { e ->
        Log.e(TAG, "Error in Memory Ingestion Pipeline (non-fatal)", e)
    }

    private fun isSuspiciousInjection(content: String): Boolean {
        return SUSPICIOUS_INJECTION_PATTERNS.any { it.matcher(content).find() }
    }

    private suspend fun extractAndPersistSemanticMemory(
        conversationId: String,
        messageId: String,
        content: String,
        timestamp: Long,
        userId: String
    ) {
        val scrubbed = SecretFilter.scrub(content)
        val lower = scrubbed.lowercase(Locale.ROOT)

        // Pattern 1: User Identity / Name
        extractIdentityFact(scrubbed, lower, conversationId, messageId, timestamp, userId)

        // Pattern 2: User Residence / Location / Relocation ("Delhi mein rehta hoon", "Noida shift ho gaya")
        extractResidenceFact(scrubbed, lower, conversationId, messageId, timestamp, userId)

        // Pattern 3: Language Preference ("I prefer Hindi", "Hindi mein baat karo")
        extractLanguagePreference(scrubbed, lower, conversationId, messageId, timestamp, userId)

        // Pattern 4: General User Preferences ("I prefer ...", "I like ...", "Mujhe ... pasand hai")
        extractPreferenceFact(scrubbed, lower, conversationId, messageId, timestamp, userId)

        // Pattern 5: Contact details / social handles (e.g. "@username", email, phone)
        extractContactDetails(scrubbed, lower, conversationId, messageId, timestamp, userId)

        // Pattern 6: Project / Tech context ("I work on ...", "Project name is ...", "Agent V2 uses ...")
        extractProjectContext(scrubbed, lower, conversationId, messageId, timestamp, userId)
    }

    private suspend fun extractIdentityFact(
        original: String,
        lower: String,
        conversationId: String,
        messageId: String,
        timestamp: Long,
        userId: String
    ) {
        val namePatterns = listOf(
            Pattern.compile("""(?i)\b(?:my name is|call me|i am|i'm)\s+([A-Za-z0-9_-]{2,25})\b"""),
            Pattern.compile("""(?i)\b(?:mera naam|mujhe)\s+([A-Za-z0-9_-]{2,25})\s+(?:bulao|hai|kaho)\b"""),
            Pattern.compile("""(?i)\bnaam\s+([A-Za-z0-9_-]{2,25})\s+hai\b""")
        )

        for (pattern in namePatterns) {
            val m = pattern.matcher(original)
            if (m.find()) {
                val name = m.group(1)?.trim() ?: continue
                if (name.equals("a", ignoreCase = true) || name.equals("the", ignoreCase = true)) continue
                if (tombstoneManager.isTombstoned(name) || tombstoneManager.isTombstoned("user_name")) continue

                val assocKeys = listOf(
                    "name", "naam", "identity", "who am i", "user_name", "user", "call me", name.lowercase(Locale.ROOT)
                )

                biTemporalResolver.resolveAndSaveItem(
                    key = "user_name",
                    value = name,
                    category = MemoryCategory.FACT.name,
                    observedAt = timestamp,
                    conversationId = conversationId,
                    sourceMessageId = messageId,
                    confidence = 0.99f,
                    salience = 0.98f,
                    userId = userId,
                    scope = MemoryScope.USER.name,
                    trustLevel = TrustLevel.USER_EXPLICIT.name,
                    associativeKeys = assocKeys
                )

                biTemporalResolver.resolveAndSaveEntity(
                    canonicalName = name,
                    entityType = EntityType.PERSON.name,
                    newAttributes = mapOf("role" to "user", "primaryName" to name),
                    observedAt = timestamp,
                    confidence = 0.99f,
                    salience = 0.98f,
                    conversationId = conversationId,
                    userId = userId,
                    scope = MemoryScope.USER.name
                )
                break
            }
        }
    }

    private suspend fun extractResidenceFact(
        original: String,
        lower: String,
        conversationId: String,
        messageId: String,
        timestamp: Long,
        userId: String
    ) {
        val residencePatterns = listOf(
            // Hindi / Hinglish live: "Main Delhi mein rehta hoon", "Main Delhi me rehta hu", "Delhi mein rehta hoon"
            Pattern.compile("""(?i)\b(?:main|hum)?\s*([A-Za-z0-9_-]{2,25})\s+(?:mein|me)\s+(?:rehta|rehti)\s+(?:hoon|hu|hain)?\b"""),
            // Hindi / Hinglish shifted: "Main Noida shift ho gaya hoon", "Noida shift ho gaya"
            Pattern.compile("""(?i)\b(?:main|hum)?\s*([A-Za-z0-9_-]{2,25})\s+shift\s+ho\s+gaya(?:\s+hoon|\s+hu)?\b"""),
            // English live: "I live in Delhi", "I am living in Delhi", "based in Delhi"
            Pattern.compile("""(?i)\b(?:i live in|i am living in|i'm based in|based in|residing in)\s+([A-Za-z0-9_-]{2,25})\b"""),
            // English shifted: "I moved to Noida", "I shifted to Noida", "moved to Noida", "relocated to Noida"
            Pattern.compile("""(?i)\b(?:i moved to|i shifted to|moved to|relocated to)\s+([A-Za-z0-9_-]{2,25})\b"""),
            // "My city is Delhi", "current city is Noida"
            Pattern.compile("""(?i)\b(?:my city is|current city is|my hometown is|city is)\s+([A-Za-z0-9_-]{2,25})\b"""),
            // "ab Noida mein rehta hoon", "currently in Noida"
            Pattern.compile("""(?i)\b(?:ab|currently|now living in)\s+([A-Za-z0-9_-]{2,25})\s*(?:mein|me)?\b""")
        )

        for (pattern in residencePatterns) {
            val m = pattern.matcher(original)
            if (m.find()) {
                val city = m.group(1)?.trim() ?: continue
                if (city.equals("a", ignoreCase = true) || city.equals("the", ignoreCase = true) || city.equals("main", ignoreCase = true)) continue
                if (tombstoneManager.isTombstoned(city) || tombstoneManager.isTombstoned("user_residence")) continue

                val assocKeys = listOf(
                    "residence", "city", "location", "live", "living", "rehta", "kahan", "ghar",
                    "shift", "moved", "relocated", "stay", "address", "current city", "user_residence",
                    city.lowercase(Locale.ROOT)
                )

                biTemporalResolver.resolveAndSaveItem(
                    key = "user_residence",
                    value = city,
                    category = MemoryCategory.FACT.name,
                    observedAt = timestamp,
                    conversationId = conversationId,
                    sourceMessageId = messageId,
                    confidence = 0.98f,
                    salience = 0.95f,
                    userId = userId,
                    scope = MemoryScope.USER.name,
                    trustLevel = TrustLevel.USER_EXPLICIT.name,
                    associativeKeys = assocKeys
                )

                biTemporalResolver.resolveAndSaveEntity(
                    canonicalName = city,
                    entityType = EntityType.LOCATION.name,
                    newAttributes = mapOf("role" to "user_residence", "cityName" to city),
                    observedAt = timestamp,
                    confidence = 0.95f,
                    salience = 0.90f,
                    conversationId = conversationId,
                    userId = userId,
                    scope = MemoryScope.USER.name
                )
                break
            }
        }
    }

    private suspend fun extractLanguagePreference(
        original: String,
        lower: String,
        conversationId: String,
        messageId: String,
        timestamp: Long,
        userId: String
    ) {
        val langPatterns = listOf(
            Pattern.compile("""(?i)\b(?:i prefer|prefer)\s+(hindi|english|hinglish|spanish|french|german)\b"""),
            Pattern.compile("""(?i)\b(hindi|english|hinglish)\s+(?:mein baat|me baat|mein bolo|me bolo|responses?)\b"""),
            Pattern.compile("""(?i)\b(?:speak in|talk in|language is)\s+(hindi|english|hinglish)\b""")
        )

        for (pattern in langPatterns) {
            val m = pattern.matcher(original)
            if (m.find()) {
                val lang = m.group(1)?.trim()?.replaceFirstChar { it.uppercase() } ?: continue
                if (tombstoneManager.isTombstoned(lang)) continue

                val assocKeys = listOf(
                    "language", "bhasha", "tongue", "speak", "communication", "prefer", lang.lowercase(Locale.ROOT)
                )

                biTemporalResolver.resolveAndSaveItem(
                    key = "user_pref_language",
                    value = lang,
                    category = MemoryCategory.PREFERENCE.name,
                    observedAt = timestamp,
                    conversationId = conversationId,
                    sourceMessageId = messageId,
                    confidence = 0.95f,
                    salience = 0.90f,
                    userId = userId,
                    scope = MemoryScope.USER.name,
                    trustLevel = TrustLevel.USER_EXPLICIT.name,
                    associativeKeys = assocKeys
                )
                break
            }
        }
    }

    private suspend fun extractPreferenceFact(
        original: String,
        lower: String,
        conversationId: String,
        messageId: String,
        timestamp: Long,
        userId: String
    ) {
        val prefPatterns = listOf(
            Pattern.compile("""(?i)\b(?:i prefer|i like|i love|always use|my favorite is|my preference is)\s+([^.,;\n]{3,60})\b"""),
            Pattern.compile("""(?i)\b(?:mujhe)\s+([^.,;\n]{3,50})\s+(?:pasand hai|achha lagta hai|use karo)\b""")
        )

        for (pattern in prefPatterns) {
            val m = pattern.matcher(original)
            if (m.find()) {
                val pref = m.group(1)?.trim() ?: continue
                if (tombstoneManager.isTombstoned(pref)) continue

                val prefKey = "user_pref_${pref.take(20).replace(" ", "_").lowercase(Locale.ROOT)}"
                val assocKeys = listOf("preference", "prefer", "like", "favorite", pref.lowercase(Locale.ROOT))

                biTemporalResolver.resolveAndSaveItem(
                    key = prefKey,
                    value = pref,
                    category = MemoryCategory.PREFERENCE.name,
                    observedAt = timestamp,
                    conversationId = conversationId,
                    sourceMessageId = messageId,
                    confidence = 0.90f,
                    salience = 0.80f,
                    userId = userId,
                    scope = MemoryScope.USER.name,
                    trustLevel = TrustLevel.USER_EXPLICIT.name,
                    associativeKeys = assocKeys
                )
            }
        }
    }

    private suspend fun extractContactDetails(
        original: String,
        lower: String,
        conversationId: String,
        messageId: String,
        timestamp: Long,
        userId: String
    ) {
        val emailPattern = Pattern.compile("""\b([A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,})\b""")
        val emailMatcher = emailPattern.matcher(original)
        if (emailMatcher.find()) {
            val email = emailMatcher.group(1) ?: ""
            if (!tombstoneManager.isTombstoned(email) && !tombstoneManager.isTombstoned("email")) {
                biTemporalResolver.resolveAndSaveItem(
                    key = "user_email",
                    value = email,
                    category = MemoryCategory.FACT.name,
                    observedAt = timestamp,
                    conversationId = conversationId,
                    sourceMessageId = messageId,
                    confidence = 0.95f,
                    salience = 0.85f,
                    userId = userId,
                    scope = MemoryScope.USER.name,
                    trustLevel = TrustLevel.USER_EXPLICIT.name,
                    associativeKeys = listOf("email", "contact", "mail", email.lowercase(Locale.ROOT))
                )
            }
        }

        val phonePattern = Pattern.compile("""\b(?:\+?\d{1,3}[- ]?)?\(?\d{3,4}\)?[- ]?\d{3,4}[- ]?\d{3,4}\b""")
        val phoneMatcher = phonePattern.matcher(original)
        if (phoneMatcher.find()) {
            val phone = phoneMatcher.group(0)?.trim() ?: ""
            if (phone.length in 8..18 && !tombstoneManager.isTombstoned(phone) && !tombstoneManager.isTombstoned("phone")) {
                biTemporalResolver.resolveAndSaveItem(
                    key = "user_phone",
                    value = phone,
                    category = MemoryCategory.FACT.name,
                    observedAt = timestamp,
                    conversationId = conversationId,
                    sourceMessageId = messageId,
                    confidence = 0.95f,
                    salience = 0.85f,
                    userId = userId,
                    scope = MemoryScope.USER.name,
                    trustLevel = TrustLevel.USER_EXPLICIT.name,
                    associativeKeys = listOf("phone", "number", "mobile", "contact", phone)
                )
            }
        }

        val handlePattern = Pattern.compile("""(?i)@([a-zA-Z0-9_]{3,30})\b""")
        val handleMatcher = handlePattern.matcher(original)
        while (handleMatcher.find()) {
            val handle = handleMatcher.group(1) ?: continue
            if (tombstoneManager.isTombstoned(handle)) continue

            biTemporalResolver.resolveAndSaveEntity(
                canonicalName = handle,
                entityType = EntityType.PROFILE.name,
                newAliases = listOf("@$handle"),
                newAttributes = mapOf("handle" to "@$handle"),
                observedAt = timestamp,
                confidence = 0.95f,
                salience = 0.85f,
                conversationId = conversationId,
                userId = userId,
                scope = MemoryScope.ENTITY.name
            )
        }
    }

    private suspend fun extractProjectContext(
        original: String,
        lower: String,
        conversationId: String,
        messageId: String,
        timestamp: Long,
        userId: String
    ) {
        val projectPattern = Pattern.compile("""(?i)\b(?:working on|project name is|building|developing)\s+([A-Za-z0-9 _-]{3,40})\b""")
        val m = projectPattern.matcher(original)
        if (m.find()) {
            val proj = m.group(1)?.trim() ?: return
            if (tombstoneManager.isTombstoned(proj)) return

            biTemporalResolver.resolveAndSaveEntity(
                canonicalName = proj,
                entityType = EntityType.PROJECT.name,
                newAttributes = mapOf("status" to "active"),
                observedAt = timestamp,
                confidence = 0.90f,
                salience = 0.85f,
                conversationId = conversationId,
                userId = userId,
                scope = MemoryScope.PROJECT.name
            )

            biTemporalResolver.resolveAndSaveItem(
                key = "active_project",
                value = proj,
                category = MemoryCategory.FACT.name,
                observedAt = timestamp,
                conversationId = conversationId,
                sourceMessageId = messageId,
                confidence = 0.90f,
                salience = 0.85f,
                userId = userId,
                scope = MemoryScope.PROJECT.name,
                trustLevel = TrustLevel.USER_EXPLICIT.name,
                associativeKeys = listOf("project", "work", "building", "developing", "app", proj.lowercase(Locale.ROOT))
            )
        }

        // Architecture statements (e.g. "Lichi Agent V2 uses UniversalTaskOrchestratorV2")
        if (lower.contains("uses") || lower.contains("integrates") || lower.contains("built with")) {
            val archKey = "project_architecture_${System.currentTimeMillis() % 10000}"
            biTemporalResolver.resolveAndSaveItem(
                key = archKey,
                value = original.trim(),
                category = MemoryCategory.FACT.name,
                observedAt = timestamp,
                conversationId = conversationId,
                sourceMessageId = messageId,
                confidence = 0.90f,
                salience = 0.80f,
                userId = userId,
                scope = MemoryScope.PROJECT.name,
                trustLevel = TrustLevel.USER_EXPLICIT.name,
                associativeKeys = listOf("architecture", "integration", "orchestrator", "agent", "connect")
            )
        }
    }
}
