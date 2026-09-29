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
    WORKFLOW
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
    val associativeKeys: List<String> = emptyList()
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
)
