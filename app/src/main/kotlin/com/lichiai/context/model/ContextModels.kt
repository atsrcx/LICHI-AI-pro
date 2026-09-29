package com.lichiai.context.model

import com.lichiai.intent.model.LichiCapability
import kotlinx.serialization.Serializable

/**
 * Types of semantic entities recognized in Lichi conversations.
 */
@Serializable
enum class EntityType {
    PROFILE,            // Social media / platform profile (Instagram, YouTube, GitHub, Reddit, Twitter, etc.)
    WEBSITE,            // Web URL / domain
    SEARCH_RESULT,      // Web search result / article
    CONTACT,            // Phone contact name
    PHONE_NUMBER,       // Extracted public or personal phone number
    MEDIA_ITEM,         // Video, song, reel, post
    TERMINAL_SESSION,   // Active terminal session / cwd
    TOPIC               // Conceptual topic (e.g. weather, general query)
}

/**
 * Dialogue acts representing what a user turn is doing.
 */
@Serializable
enum class DialogueAct {
    GREETING,
    QUESTION,
    FOLLOW_UP,
    REFERENCE_QUERY,
    COMMAND,
    TASK,
    CORRECTION,
    CLARIFICATION,
    CONFIRMATION,
    REJECTION,
    CANCELLATION,
    COMPARISON,
    REQUEST_MORE,
    REQUEST_DETAILS,
    REQUEST_SUMMARY,
    REQUEST_EXPLANATION,
    REQUEST_ANALYSIS,
    REQUEST_TRANSFORMATION,
    TOPIC_SWITCH,
    TOPIC_RETURN,
    CONTINUATION
}

/**
 * A discrete conversational topic thread in the conversation history.
 */
@Serializable
data class TopicThread(
    val topicId: String,
    val title: String,
    val subtopic: String? = null,
    val primaryEntityId: String? = null,
    val capability: LichiCapability? = null,
    val userGoal: String? = null,
    val verifiedFacts: Map<String, String> = emptyMap(),
    val lastActiveTimestamp: Long = System.currentTimeMillis()
)

/**
 * Salience indicates how prominently an entity is currently active in the user's mind.
 */
@Serializable
enum class SalienceLevel {
    HIGH,       // Currently focused entity / selected item / active task target
    MEDIUM,     // Recently discussed entity from previous turn
    LOW         // Decayed or background entity
}

/**
 * Canonical verified result record preserving authoritative executor outputs
 * across tasks and steps for multi-step continuity and anti-hallucination.
 */
@Serializable
data class VerifiedResultRecord(
    val taskId: String = "",
    val stepId: String? = null,
    val parentTaskId: String? = null,
    val capability: LichiCapability = LichiCapability.CHAT,
    val operation: String = "",
    val entityName: String? = null,
    val entityType: EntityType? = null,
    val extractedFacts: Map<String, String> = emptyMap(),
    val targetUrl: String? = null,
    val selectedResult: String? = null,
    val isVerified: Boolean = true,
    val verificationState: String = "VERIFIED", // "VERIFIED", "PARTIAL", "UNKNOWN", "FAILED", "BLOCKED"
    val provenance: String = "EXECUTOR",
    val timestamp: Long = System.currentTimeMillis(),
    val confidence: Float = 1.0f
)

/**
 * A discrete semantic entity within the conversation's Entity Graph.
 */
@Serializable
data class SemanticEntity(
    val id: String,
    val type: EntityType,
    val name: String,
    val displayName: String = "",
    val platform: String? = null,
    val url: String? = null,
    val website: String? = null,
    val publicEmail: String? = null,
    val publicPhone: String? = null,
    val followers: String? = null,
    val following: String? = null,
    val postCount: String? = null,
    val subscriberCount: String? = null,
    val bio: String? = null,
    val category: String? = null,
    val isVerified: Boolean? = null,
    val isPrivate: Boolean? = null,
    val metrics: Map<String, String> = emptyMap(),
    val childEntityIds: List<String> = emptyList(),
    val parentEntityId: String? = null,
    val sourceCapability: LichiCapability = LichiCapability.CHAT,
    val summary: String = "",
    val salience: SalienceLevel = SalienceLevel.HIGH,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val version: Long = 1L
) {
    /**
     * Checks if this entity has rich, verifiable profile information.
     */
    fun hasProfileDetails(): Boolean {
        return followers != null || bio != null || website != null || publicEmail != null ||
                publicPhone != null || subscriberCount != null || isVerified != null
    }

    /**
     * Produces a concise, high-signal semantic summary suitable for prompt injection without bloating context.
     */
    fun toCompactSummary(): String {
        return buildString {
            append("${displayName.ifBlank { name }} (@$name)")
            if (platform != null) append(" ($platform)")
            if (!followers.isNullOrBlank()) append(" | Followers: $followers")
            if (!subscriberCount.isNullOrBlank()) append(" | Subscribers: $subscriberCount")
            if (!bio.isNullOrBlank()) append(" | Bio: \"${bio.take(120)}\"")
            if (!website.isNullOrBlank()) append(" | Website: $website")
            if (!publicEmail.isNullOrBlank()) append(" | Email: $publicEmail")
            if (!publicPhone.isNullOrBlank()) append(" | Phone: $publicPhone")
        }
    }

    /**
     * Converts a profile SemanticEntity back to PlatformProfile.
     */
    fun toPlatformProfile(): com.lichiai.spy.model.PlatformProfile {
        val platformType = runCatching {
            com.lichiai.spy.core.PlatformType.valueOf(platform ?: "GENERIC_WEB")
        }.getOrDefault(com.lichiai.spy.core.PlatformType.GENERIC_WEB)

        return com.lichiai.spy.model.PlatformProfile(
            platform = platformType,
            username = name,
            displayName = displayName.ifBlank { name },
            profileUrl = url ?: "",
            website = website ?: "",
            publicEmail = publicEmail ?: "",
            publicPhone = publicPhone ?: "",
            followers = followers ?: "",
            following = following ?: "",
            postCount = postCount ?: "",
            subscriberCount = subscriberCount ?: "",
            bio = bio ?: "",
            isVerified = isVerified,
            isPrivate = isPrivate
        )
    }
}

/**
 * Canonical semantic conversation context / state scoped to a specific conversation ID.
 * Distinguishes Chat History from Task State, Entities, and Semantic Conversation Context.
 */
@Serializable
data class ConversationContext(
    val conversationId: String,
    val currentTurnId: String = "",
    val activeTopic: String? = null,
    val activeSubtopic: String? = null,
    val activeGoal: String? = null,
    val goalAspects: List<String> = emptyList(),
    val activeEntityId: String? = null,
    val secondaryEntityIds: List<String> = emptyList(),
    val selectedEntityId: String? = null,
    val selectedResult: String? = null,
    val entities: Map<String, SemanticEntity> = emptyMap(),
    val contextStack: List<String> = emptyList(), // Entity IDs ordered by recency/salience (most recent first)
    val topicHistory: List<TopicThread> = emptyList(), // Recoverable thread stack for topic switches / returns
    val activeCapability: LichiCapability? = null,
    val activeOperation: String? = null,
    val lastUserGoal: String? = null,
    val lastAssistantResponse: String? = null,
    val lastActionType: String? = null,
    val browserCandidates: List<String> = emptyList(),
    val currentBrowserUrl: String? = null,
    val currentBrowserTitle: String? = null,
    val terminalSessionId: String? = null,
    val terminalCwd: String? = null,
    val activeCallContact: String? = null,
    val activeCallNumber: String? = null,
    val openTasks: List<String> = emptyList(),
    val pausedTasks: List<String> = emptyList(),
    val pendingClarification: String? = null,
    val recentDialogueActs: List<DialogueAct> = emptyList(),
    val recentCorrections: List<String> = emptyList(),
    val verifiedFacts: Map<String, String> = emptyMap(),
    val lastVerifiedResult: VerifiedResultRecord? = null,
    val verifiedResultHistory: List<VerifiedResultRecord> = emptyList(),
    val recentTurns: List<Pair<String, String>> = emptyList(),
    val contextConfidence: Float = 1.0f,
    val updatedAt: Long = System.currentTimeMillis(),
    val version: Long = 1L
) {
    /**
     * Returns the currently active semantic entity, if any.
     */
    fun getActiveEntity(): SemanticEntity? {
        return activeEntityId?.let { entities[it] } ?: contextStack.firstOrNull()?.let { entities[it] }
    }

    /**
     * Returns all entities of a specific type ordered by the context stack (recency).
     */
    fun getEntitiesOfType(type: EntityType): List<SemanticEntity> {
        val stackOrdered = contextStack.mapNotNull { entities[it] }.filter { it.type == type }
        val remaining = entities.values.filter { it.type == type && it.id !in contextStack }
        return (stackOrdered + remaining).distinctBy { it.id }
    }

    /**
     * Returns all profile entities ordered by recency.
     */
    fun getProfiles(): List<SemanticEntity> = getEntitiesOfType(EntityType.PROFILE)
}

typealias ConversationState = ConversationContext

/**
 * Result of resolving a natural reference or follow-up in the conversation.
 */
data class ContextResolutionResult(
    val resolvedEntity: SemanticEntity? = null,
    val targetText: String? = null,
    val targetUrl: String? = null,
    val confidence: Float = 0f,
    val suggestedCapability: LichiCapability? = null,
    val isAmbiguous: Boolean = false,
    val ambiguityCandidates: List<SemanticEntity> = emptyList(),
    val clarificationQuestion: String? = null,
    val isCorrection: Boolean = false,
    val isTopicReturn: Boolean = false,
    val restoredTopic: TopicThread? = null,
    val dialogueAct: DialogueAct = DialogueAct.QUESTION,
    val reason: String = ""
)
