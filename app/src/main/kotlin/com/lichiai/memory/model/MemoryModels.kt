package com.lichiai.memory.model

import kotlinx.serialization.Serializable

@Serializable
enum class MemoryStatus {
    ACTIVE,
    SUPERSEDED,
    TOMBSTONE
}

@Serializable
enum class MemoryScope {
    USER,
    CONVERSATION,
    TASK,
    PROJECT,
    ENTITY,
    EPISODIC_RESULT
}

@Serializable
enum class TrustLevel {
    USER_EXPLICIT,
    USER_CONFIRMED,
    TOOL_VERIFIED,
    SYSTEM_OBSERVED,
    AGENT_ACTION_VERIFIED,
    EXTERNAL_SOURCE,
    MODEL_INFERRED,
    UNKNOWN
}

@Serializable
enum class TemporalIntent {
    CURRENT,
    HISTORICAL,
    TRANSITION,
    ALL
}

@Serializable
enum class MemoryCategory {
    PREFERENCE,
    FACT,
    EPISODIC,
    WORKFLOW,
    IDENTITY,
    GOAL,
    PROJECT,
    INSTRUCTION,
    RELATIONSHIP,
    EVENT,
    CONTACT,
    CONTEXT
}

@Serializable
enum class EntityType {
    PERSON,
    PROJECT,
    PROFILE,
    APP,
    DEVICE,
    TOPIC,
    LOCATION,
    ORGANIZATION,
    WEBSITE,
    CONTACT,
    CUSTOM
}

@Serializable
enum class RelationType {
    OWNS,
    USES,
    PART_OF,
    LOCATED_IN,
    WORKS_FOR,
    CREATED_BY,
    ASSOCIATED_WITH,
    FOLLOWS,
    PREFERS,
    HAS_CONTACT
}

@Serializable
data class BiTemporalWindow(
    val observedAt: Long = System.currentTimeMillis(),
    val validFrom: Long? = null,
    val validUntil: Long? = null
) {
    fun isValidAt(timestamp: Long = System.currentTimeMillis()): Boolean {
        val fromValid = validFrom?.let { timestamp >= it } ?: true
        val untilValid = validUntil?.let { timestamp <= it } ?: true
        return fromValid && untilValid
    }
}

@Serializable
data class ProvenanceRecord(
    val conversationId: String,
    val turnId: String,
    val messageId: String,
    val role: String,
    val confidence: Float = 1.0f,
    val extractionMethod: String = "HEURISTIC_STRUCTURER"
)

@Serializable
data class EntityRecord(
    val entityId: String,
    val entityType: EntityType,
    val canonicalName: String,
    val aliases: List<String> = emptyList(),
    val attributes: Map<String, String> = emptyMap(),
    val confidence: Float = 1.0f,
    val salience: Float = 0.5f,
    val temporalWindow: BiTemporalWindow = BiTemporalWindow(),
    val status: MemoryStatus = MemoryStatus.ACTIVE,
    val conversationId: String? = null
)

@Serializable
data class EntityRelation(
    val relationId: String,
    val sourceEntityId: String,
    val targetEntityId: String,
    val relationType: RelationType,
    val confidence: Float = 1.0f,
    val attributes: Map<String, String> = emptyMap(),
    val temporalWindow: BiTemporalWindow = BiTemporalWindow(),
    val status: MemoryStatus = MemoryStatus.ACTIVE
)

@Serializable
data class CoreProfileView(
    val userId: String = "default_user",
    val displayName: String? = null,
    val preferredLanguage: String? = null,
    val currentResidence: String? = null,
    val previousResidence: String? = null,
    val activeProjects: List<String> = emptyList(),
    val importantPreferences: Map<String, String> = emptyMap(),
    val lastUpdated: Long = System.currentTimeMillis()
)

data class MemoryQueryPlan(
    val rawQuery: String,
    val subject: String? = null,
    val predicate: String? = null,
    val temporalIntent: TemporalIntent = TemporalIntent.CURRENT,
    val targetScope: MemoryScope = MemoryScope.USER,
    val semanticConcepts: List<String> = emptyList()
)

@Serializable
data class MemoryItem(
    val id: String,
    val key: String,
    val value: String,
    val category: MemoryCategory,
    val status: MemoryStatus = MemoryStatus.ACTIVE,
    val confidence: Float = 1.0f,
    val salience: Float = 0.5f,
    val temporalWindow: BiTemporalWindow = BiTemporalWindow(),
    val conversationId: String? = null,
    val sourceMessageId: String? = null,
    val userId: String = "default_user",
    val scope: MemoryScope = MemoryScope.USER,
    val trustLevel: TrustLevel = TrustLevel.USER_EXPLICIT,
    val associativeKeys: List<String> = emptyList(),
    val dedupeKey: String = ""
)

@Serializable
data class ExtractedMemoryItem(
    val type: String = "FACT",
    val key: String,
    val value: String,
    val scope: String = "USER",
    val confidence: Float = 0.9f,
    val salience: Float = 0.7f,
    val temporalIntent: String = "CURRENT",
    val explicit: Boolean = true,
    val sourceMessageId: String? = null,
    val associativeKeys: List<String> = emptyList()
)

@Serializable
data class SemanticExtractionEnvelope(
    val memories: List<ExtractedMemoryItem> = emptyList()
)

@Serializable
data class TombstoneRecord(
    val id: String,
    val targetType: String, // "ENTITY", "KEY", "PATTERN"
    val targetIdentifier: String,
    val reason: String = "USER_REQUEST_FORGET",
    val createdAt: Long = System.currentTimeMillis(),
    val scopeConversationId: String? = null
)

@Serializable
data class MemoryPack(
    val coreProfile: CoreProfileView? = null,
    val activeEntities: List<EntityRecord> = emptyList(),
    val activeRelations: List<EntityRelation> = emptyList(),
    val relevantFacts: List<MemoryItem> = emptyList(),
    val historicalFacts: List<MemoryItem> = emptyList(),
    val relevantPreferences: List<MemoryItem> = emptyList(),
    val relevantLedgerSnippets: List<String> = emptyList(),
    val formattedPromptContext: String = "",
    val tokenEstimate: Int = 0,
    val generatedAt: Long = System.currentTimeMillis()
) {
    /**
     * Enforces a strict token ceiling (default 1000 tokens) on injected memory context.
     * Truncates low-scoring conversation turns and historical structured facts if budget exceeded.
     */
    fun withHardTokenCeiling(maxTokens: Int = 1000): MemoryPack {
        val maxChars = maxTokens * 4
        if (tokenEstimate <= maxTokens && formattedPromptContext.length <= maxChars) {
            return this
        }
        var truncatedHistorical = historicalFacts
        var truncatedLedger = relevantLedgerSnippets
        var truncatedFacts = relevantFacts

        if (truncatedLedger.isNotEmpty()) {
            truncatedLedger = truncatedLedger.take(1)
        }
        if (formattedPromptContext.length > maxChars && truncatedHistorical.isNotEmpty()) {
            truncatedHistorical = emptyList()
        }
        if (formattedPromptContext.length > maxChars && truncatedFacts.size > 4) {
            truncatedFacts = truncatedFacts.take(4)
        }

        val boundedText = if (formattedPromptContext.length > maxChars) {
            formattedPromptContext.take(maxChars).substringBeforeLast('\n') + "\n[Memory context truncated to budget]"
        } else {
            formattedPromptContext
        }

        return this.copy(
            historicalFacts = truncatedHistorical,
            relevantLedgerSnippets = truncatedLedger,
            relevantFacts = truncatedFacts,
            formattedPromptContext = boundedText,
            tokenEstimate = boundedText.length / 4
        )
    }
}

@Serializable
data class MemoryWriteResult(
    val rawTurnRecorded: Boolean,
    val structuredMemoriesPersisted: Int = 0,
    val persistedKeys: Set<String> = emptySet(),
    val forgottenTargets: Set<String> = emptySet(),
    val verified: Boolean = false,
    val failureReason: String? = null
) {
    val isSuccess: Boolean get() = (rawTurnRecorded || structuredMemoriesPersisted > 0 || forgottenTargets.isNotEmpty()) && failureReason == null
}
