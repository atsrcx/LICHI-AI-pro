package com.lichiai.context.engine

import android.util.Log
import com.lichiai.context.model.ContextResolutionResult
import com.lichiai.context.model.ConversationContext
import com.lichiai.context.model.DialogueAct
import com.lichiai.context.model.EntityType
import com.lichiai.context.model.SalienceLevel
import com.lichiai.context.model.SemanticEntity
import com.lichiai.context.model.TopicThread
import com.lichiai.context.model.VerifiedResultRecord
import com.lichiai.intent.model.LichiCapability
import com.lichiai.spy.core.PlatformType
import com.lichiai.spy.model.PlatformProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Universal Context Continuity Engine for Lichi AI.
 *
 * Implements a closed-loop, graph-based context and entity memory system:
 * - Distinguishes Chat History, Task State, and Semantic Conversation Context.
 * - Handles reference resolution across English, Hindi, Hinglish, and Roman Hindi.
 * - Supports entity relationships (e.g. Profile -> Website -> Public Contact -> Recent Media).
 * - Implements Salience Scoring, Recency Stacks, Context Decay, and Correction Handling.
 * - Manages topic threads: active topic + topic stack for seamless topic switching and topic returning.
 * - Maintains active goals and accumulated goal aspects.
 * - Strictly enforces Conversation and Multi-Task Isolation (zero context bleed).
 * - Enforces hard safety boundaries (e.g. phone lookup never routes to phone calls).
 */
class UniversalContextContinuityEngine private constructor() {

    companion object {
        private const val TAG = "ContextContinuityEngine"
        private const val MAX_CONTEXT_STACK_SIZE = 15
        private const val MAX_RECENT_TURNS = 12
        private const val MAX_TOPIC_HISTORY = 10

        @Volatile
        private var instance: UniversalContextContinuityEngine? = null

        fun getInstance(): UniversalContextContinuityEngine {
            return instance ?: synchronized(this) {
                instance ?: UniversalContextContinuityEngine().also { instance = it }
            }
        }
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // Per-conversation isolated context states
    private val conversationContexts = ConcurrentHashMap<String, MutableStateFlow<ConversationContext>>()

    /**
     * Gets or creates an isolated context for a specific conversation ID.
     */
    fun getContextFlow(conversationId: String): StateFlow<ConversationContext> {
        val safeId = conversationId.ifBlank { "default_session" }
        return conversationContexts.computeIfAbsent(safeId) {
            MutableStateFlow(ConversationContext(conversationId = safeId))
        }.asStateFlow()
    }

    /**
     * Returns a snapshot of the conversation context.
     */
    fun getContext(conversationId: String): ConversationContext {
        val safeId = conversationId.ifBlank { "default_session" }
        return conversationContexts.computeIfAbsent(safeId) {
            MutableStateFlow(ConversationContext(conversationId = safeId))
        }.value
    }

    // =========================================================================
    // RESULT -> SEMANTIC CONTEXT INGESTION
    // =========================================================================

    /**
     * Ingests a completed #Spy execution into the semantic Entity Graph.
     * Links Profile -> Website, Profile -> Public Contacts, Profile -> Media.
     * Automatically extracts verified facts and pushes a TopicThread.
     */
    fun recordSpyExecution(
        conversationId: String,
        primaryProfile: PlatformProfile?,
        allProfiles: List<PlatformProfile>,
        userGoal: String,
        assistantResponse: String
    ) {
        val safeId = conversationId.ifBlank { "default_session" }
        val flow = conversationContexts.computeIfAbsent(safeId) {
            MutableStateFlow(ConversationContext(conversationId = safeId))
        }

        flow.update { curr ->
            val updatedEntities = curr.entities.toMutableMap()
            val newStack = curr.contextStack.toMutableList()
            val verifiedFacts = curr.verifiedFacts.toMutableMap()

            val profilesToIngest = (listOfNotNull(primaryProfile) + allProfiles).distinctBy { it.username to it.platform }
            val primaryEntity = primaryProfile ?: profilesToIngest.firstOrNull()
            val primaryEntityId = primaryEntity?.let {
                "profile_${it.platform.name.lowercase(Locale.ROOT)}_${it.username.lowercase(Locale.ROOT)}"
            }

            // Ingest in reverse order so that profilesToIngest[0] ends up at newStack[0]
            for (prof in profilesToIngest.reversed()) {
                val entityId = "profile_${prof.platform.name.lowercase(Locale.ROOT)}_${prof.username.lowercase(Locale.ROOT)}"
                val isPrimary = (entityId == primaryEntityId)

                val childIds = mutableListOf<String>()

                // Link Website sub-entity if available
                if (prof.website.isNotBlank()) {
                    val webEntityId = "web_${entityId}"
                    childIds.add(webEntityId)
                    updatedEntities[webEntityId] = SemanticEntity(
                        id = webEntityId,
                        type = EntityType.WEBSITE,
                        name = prof.website,
                        displayName = "${prof.displayName.ifBlank { prof.username }}'s Website",
                        url = prof.website,
                        parentEntityId = entityId,
                        sourceCapability = LichiCapability.WEB_SEARCH,
                        summary = "Website for ${prof.username}: ${prof.website}",
                        salience = if (isPrimary) SalienceLevel.HIGH else SalienceLevel.MEDIUM
                    )
                }

                // Link Public Phone sub-entity if available
                if (prof.publicPhone.isNotBlank()) {
                    val phoneEntityId = "phone_${entityId}"
                    childIds.add(phoneEntityId)
                    updatedEntities[phoneEntityId] = SemanticEntity(
                        id = phoneEntityId,
                        type = EntityType.PHONE_NUMBER,
                        name = prof.publicPhone,
                        displayName = "Public phone of ${prof.username}",
                        parentEntityId = entityId,
                        sourceCapability = LichiCapability.CHAT,
                        summary = "Public phone number for ${prof.username}: ${prof.publicPhone}",
                        salience = if (isPrimary) SalienceLevel.HIGH else SalienceLevel.MEDIUM
                    )
                }

                // Link Main Profile Entity
                val summary = buildString {
                    append("${prof.platform.displayName} profile of @${prof.username}")
                    if (prof.displayName.isNotBlank()) append(" (${prof.displayName})")
                    if (prof.followers.isNotBlank()) append(". Followers: ${prof.followers}")
                    if (prof.following.isNotBlank()) append(", Following: ${prof.following}")
                    if (prof.bio.isNotBlank()) append(". Bio: ${prof.bio}")
                    if (prof.website.isNotBlank()) append(". Website: ${prof.website}")
                    if (prof.publicEmail.isNotBlank()) append(". Public Email: ${prof.publicEmail}")
                    if (prof.publicPhone.isNotBlank()) append(". Public Phone: ${prof.publicPhone}")
                }

                val profileEntity = SemanticEntity(
                    id = entityId,
                    type = EntityType.PROFILE,
                    name = prof.username,
                    displayName = prof.displayName.ifBlank { prof.username },
                    platform = prof.platform.name,
                    url = prof.profileUrl,
                    website = prof.website.takeIf { it.isNotBlank() },
                    publicEmail = prof.publicEmail.takeIf { it.isNotBlank() },
                    publicPhone = prof.publicPhone.takeIf { it.isNotBlank() },
                    followers = prof.followers.takeIf { it.isNotBlank() },
                    following = prof.following.takeIf { it.isNotBlank() },
                    postCount = prof.postCount.takeIf { it.isNotBlank() },
                    subscriberCount = prof.subscriberCount.takeIf { it.isNotBlank() },
                    bio = prof.bio.takeIf { it.isNotBlank() },
                    category = prof.category.takeIf { it.isNotBlank() },
                    isVerified = prof.isVerified,
                    isPrivate = prof.isPrivate,
                    childEntityIds = childIds,
                    sourceCapability = LichiCapability.CHAT,
                    summary = summary,
                    salience = if (isPrimary) SalienceLevel.HIGH else SalienceLevel.MEDIUM,
                    updatedAt = System.currentTimeMillis()
                )

                updatedEntities[entityId] = profileEntity
                newStack.remove(entityId)
                newStack.add(0, entityId)

                // Populate verified facts for primary profile
                if (isPrimary) {
                    verifiedFacts["platform"] = prof.platform.displayName
                    verifiedFacts["username"] = prof.username
                    if (prof.displayName.isNotBlank()) verifiedFacts["displayName"] = prof.displayName
                    if (prof.followers.isNotBlank()) verifiedFacts["followers"] = prof.followers
                    if (prof.following.isNotBlank()) verifiedFacts["following"] = prof.following
                    if (prof.postCount.isNotBlank()) verifiedFacts["posts"] = prof.postCount
                    if (prof.subscriberCount.isNotBlank()) verifiedFacts["subscribers"] = prof.subscriberCount
                    if (prof.bio.isNotBlank()) verifiedFacts["bio"] = prof.bio
                    if (prof.website.isNotBlank()) verifiedFacts["website"] = prof.website
                    if (prof.publicEmail.isNotBlank()) verifiedFacts["email"] = prof.publicEmail
                    if (prof.publicPhone.isNotBlank()) verifiedFacts["phone"] = prof.publicPhone
                }
            }

            // Decay previous non-active entities
            decayEntities(updatedEntities, activeId = primaryEntityId)

            val updatedTurns = (curr.recentTurns + (userGoal to assistantResponse)).takeLast(MAX_RECENT_TURNS)

            val topicTitle = primaryEntity?.let { "${it.platform.displayName} @${it.username}" } ?: "Platform Profile"
            val newTopicThread = TopicThread(
                topicId = "topic_${primaryEntityId ?: "spy"}",
                title = topicTitle,
                primaryEntityId = primaryEntityId,
                capability = LichiCapability.CHAT,
                userGoal = userGoal,
                verifiedFacts = verifiedFacts.toMap(),
                lastActiveTimestamp = System.currentTimeMillis()
            )
            val updatedTopicHistory = (listOf(newTopicThread) + curr.topicHistory.filterNot { it.topicId == newTopicThread.topicId }).take(MAX_TOPIC_HISTORY)

            val verifiedResult = VerifiedResultRecord(
                taskId = "spy_${System.currentTimeMillis()}",
                capability = LichiCapability.CHAT,
                operation = "SPY_PROFILE_LOOKUP",
                entityName = primaryEntity?.username,
                entityType = EntityType.PROFILE,
                extractedFacts = verifiedFacts.toMap(),
                targetUrl = primaryEntity?.profileUrl,
                selectedResult = primaryEntity?.displayName,
                isVerified = true,
                verificationState = "VERIFIED",
                provenance = "APIFY_EXECUTOR",
                timestamp = System.currentTimeMillis(),
                confidence = 1.0f
            )

            curr.copy(
                activeEntityId = primaryEntityId ?: curr.activeEntityId,
                secondaryEntityIds = profilesToIngest.mapNotNull {
                    val id = "profile_${it.platform.name.lowercase(Locale.ROOT)}_${it.username.lowercase(Locale.ROOT)}"
                    if (id != primaryEntityId) id else null
                },
                activeTopic = topicTitle,
                activeGoal = "Inspect public profile of @${primaryEntity?.username ?: ""}",
                goalAspects = listOf("overview", "statistics"),
                topicHistory = updatedTopicHistory,
                entities = updatedEntities,
                contextStack = newStack.take(MAX_CONTEXT_STACK_SIZE),
                activeCapability = LichiCapability.CHAT,
                activeOperation = "SPY_PROFILE_LOOKUP",
                lastUserGoal = userGoal,
                lastAssistantResponse = assistantResponse,
                lastActionType = "SPY_PROFILE_LOOKUP",
                verifiedFacts = verifiedFacts,
                lastVerifiedResult = verifiedResult,
                verifiedResultHistory = (listOf(verifiedResult) + curr.verifiedResultHistory).take(10),
                recentTurns = updatedTurns,
                updatedAt = System.currentTimeMillis(),
                version = curr.version + 1
            )
        }
    }

    /**
     * Ingests a general execution turn (Web search, Terminal, Browser, Calls, etc.)
     */
    fun recordExecution(
        conversationId: String,
        capability: LichiCapability,
        userGoal: String,
        assistantResponse: String,
        searchQuery: String? = null,
        results: List<String> = emptyList(),
        entities: Map<String, String> = emptyMap(),
        actionType: String? = null
    ) {
        val safeId = conversationId.ifBlank { "default_session" }
        val flow = conversationContexts.computeIfAbsent(safeId) {
            MutableStateFlow(ConversationContext(conversationId = safeId))
        }

        flow.update { curr ->
            val updatedEntities = curr.entities.toMutableMap()
            val newStack = curr.contextStack.toMutableList()
            var newActiveEntityId = curr.activeEntityId
            val verifiedFacts = curr.verifiedFacts.toMutableMap()

            // If contact entity present
            val contactName = entities["contact"] ?: entities["recipient"]
            if (!contactName.isNullOrBlank()) {
                val contactId = "contact_${contactName.lowercase(Locale.ROOT).replace(" ", "_")}"
                val contactEntity = SemanticEntity(
                    id = contactId,
                    type = EntityType.CONTACT,
                    name = contactName,
                    displayName = contactName,
                    sourceCapability = capability,
                    summary = "Contact: $contactName",
                    salience = SalienceLevel.HIGH
                )
                updatedEntities[contactId] = contactEntity
                newStack.remove(contactId)
                newStack.add(0, contactId)
                newActiveEntityId = contactId
                verifiedFacts["contact"] = contactName
            }

            // If search query entity present
            val queryStr = searchQuery ?: entities["query"] ?: if (capability == LichiCapability.WEB_SEARCH) userGoal else null
            if (!queryStr.isNullOrBlank()) {
                val searchId = "search_${queryStr.hashCode()}"
                val searchEntity = SemanticEntity(
                    id = searchId,
                    type = EntityType.SEARCH_RESULT,
                    name = queryStr,
                    displayName = "Search: $queryStr",
                    sourceCapability = capability,
                    summary = "Search query: $queryStr. Results: ${results.take(3).joinToString(" | ")}",
                    salience = SalienceLevel.HIGH
                )
                updatedEntities[searchId] = searchEntity
                newStack.remove(searchId)
                newStack.add(0, searchId)
                newActiveEntityId = searchId
                verifiedFacts["searchQuery"] = queryStr
                if (results.isNotEmpty()) {
                    verifiedFacts["firstResult"] = results.first()
                }
            }

            val updatedTurns = (curr.recentTurns + (userGoal to assistantResponse)).takeLast(MAX_RECENT_TURNS)

            // Update topic and topic history
            val newTopicTitle = when (capability) {
                LichiCapability.WEB_SEARCH -> queryStr?.let { "Web: $it" } ?: "Web Search"
                LichiCapability.BROWSER -> curr.currentBrowserTitle ?: curr.currentBrowserUrl ?: "Browser"
                LichiCapability.TERMINAL -> "Terminal (${curr.terminalCwd ?: "shell"})"
                LichiCapability.CALLS -> contactName?.let { "Call: $it" } ?: "Calls"
                LichiCapability.TIME_REMINDER -> "Reminders"
                else -> curr.activeTopic ?: "Conversation"
            }

            val newTopicThread = TopicThread(
                topicId = "topic_${capability.name.lowercase(Locale.ROOT)}_${System.currentTimeMillis()}",
                title = newTopicTitle,
                primaryEntityId = newActiveEntityId,
                capability = capability,
                userGoal = userGoal,
                verifiedFacts = verifiedFacts.toMap(),
                lastActiveTimestamp = System.currentTimeMillis()
            )
            val updatedTopicHistory = (listOf(newTopicThread) + curr.topicHistory.filterNot { it.title == newTopicTitle }).take(MAX_TOPIC_HISTORY)

            val verifiedResult = VerifiedResultRecord(
                taskId = "exec_${System.currentTimeMillis()}",
                capability = capability,
                operation = actionType ?: capability.name,
                entityName = contactName ?: queryStr,
                entityType = if (contactName != null) EntityType.CONTACT else if (queryStr != null) EntityType.SEARCH_RESULT else null,
                extractedFacts = verifiedFacts.toMap(),
                targetUrl = if (capability == LichiCapability.BROWSER) curr.currentBrowserUrl else null,
                selectedResult = results.firstOrNull(),
                isVerified = true,
                verificationState = "VERIFIED",
                provenance = "CAPABILITY_EXECUTOR",
                timestamp = System.currentTimeMillis(),
                confidence = 1.0f
            )

            curr.copy(
                activeEntityId = newActiveEntityId,
                activeTopic = newTopicTitle,
                activeGoal = userGoal,
                topicHistory = updatedTopicHistory,
                entities = updatedEntities,
                contextStack = newStack.take(MAX_CONTEXT_STACK_SIZE),
                activeCapability = capability,
                lastUserGoal = userGoal,
                lastAssistantResponse = assistantResponse,
                lastActionType = actionType ?: curr.lastActionType,
                browserCandidates = if (results.isNotEmpty()) results else curr.browserCandidates,
                verifiedFacts = verifiedFacts,
                lastVerifiedResult = verifiedResult,
                verifiedResultHistory = (listOf(verifiedResult) + curr.verifiedResultHistory).take(10),
                recentTurns = updatedTurns,
                updatedAt = System.currentTimeMillis(),
                version = curr.version + 1
            )
        }
    }

    /**
     * Ingests active Browser page context (URL, title, candidate links).
     */
    fun recordBrowserState(
        conversationId: String,
        url: String?,
        title: String?,
        candidates: List<String>
    ) {
        val safeId = conversationId.ifBlank { "default_session" }
        val flow = conversationContexts.computeIfAbsent(safeId) {
            MutableStateFlow(ConversationContext(conversationId = safeId))
        }

        flow.update { curr ->
            val updatedEntities = curr.entities.toMutableMap()
            val newStack = curr.contextStack.toMutableList()
            var pageEntityId: String? = null
            val verifiedFacts = curr.verifiedFacts.toMutableMap()

            if (!url.isNullOrBlank() && url != "about:blank") {
                pageEntityId = "web_${url.hashCode()}"
                val webEntity = SemanticEntity(
                    id = pageEntityId,
                    type = EntityType.WEBSITE,
                    name = title ?: url,
                    displayName = title ?: url,
                    url = url,
                    website = url,
                    sourceCapability = LichiCapability.BROWSER,
                    summary = "Browser page: \"${title ?: ""}\" ($url)",
                    salience = SalienceLevel.HIGH
                )
                updatedEntities[pageEntityId] = webEntity
                newStack.remove(pageEntityId)
                newStack.add(0, pageEntityId)
                verifiedFacts["activeUrl"] = url
                if (!title.isNullOrBlank()) verifiedFacts["pageTitle"] = title
            }

            val newTopicTitle = title?.takeIf { it.isNotBlank() } ?: url ?: "Browser Navigation"
            val newTopicThread = TopicThread(
                topicId = "topic_browser_${url?.hashCode() ?: System.currentTimeMillis()}",
                title = newTopicTitle,
                primaryEntityId = pageEntityId,
                capability = LichiCapability.BROWSER,
                userGoal = curr.lastUserGoal,
                verifiedFacts = verifiedFacts.toMap(),
                lastActiveTimestamp = System.currentTimeMillis()
            )
            val updatedTopicHistory = (listOf(newTopicThread) + curr.topicHistory.filterNot { it.title == newTopicTitle }).take(MAX_TOPIC_HISTORY)

            curr.copy(
                activeEntityId = pageEntityId ?: curr.activeEntityId,
                activeTopic = newTopicTitle,
                topicHistory = updatedTopicHistory,
                currentBrowserUrl = url ?: curr.currentBrowserUrl,
                currentBrowserTitle = title ?: curr.currentBrowserTitle,
                browserCandidates = if (candidates.isNotEmpty()) candidates else curr.browserCandidates,
                entities = updatedEntities,
                contextStack = newStack.take(MAX_CONTEXT_STACK_SIZE),
                activeCapability = LichiCapability.BROWSER,
                verifiedFacts = verifiedFacts,
                updatedAt = System.currentTimeMillis(),
                version = curr.version + 1
            )
        }
    }

    /**
     * Ingests terminal session state.
     */
    fun recordTerminalState(
        conversationId: String,
        sessionId: String?,
        cwd: String?,
        lastOutput: String?
    ) {
        val safeId = conversationId.ifBlank { "default_session" }
        val flow = conversationContexts.computeIfAbsent(safeId) {
            MutableStateFlow(ConversationContext(conversationId = safeId))
        }

        flow.update { curr ->
            val verifiedFacts = curr.verifiedFacts.toMutableMap()
            if (!cwd.isNullOrBlank()) verifiedFacts["terminalCwd"] = cwd
            if (!lastOutput.isNullOrBlank()) verifiedFacts["lastTerminalOutput"] = lastOutput.take(500)

            curr.copy(
                terminalSessionId = sessionId ?: curr.terminalSessionId,
                terminalCwd = cwd ?: curr.terminalCwd,
                verifiedFacts = verifiedFacts,
                updatedAt = System.currentTimeMillis(),
                version = curr.version + 1
            )
        }
    }

    /**
     * Ingests call state.
     */
    fun recordCallState(
        conversationId: String,
        contact: String?,
        number: String?
    ) {
        val safeId = conversationId.ifBlank { "default_session" }
        val flow = conversationContexts.computeIfAbsent(safeId) {
            MutableStateFlow(ConversationContext(conversationId = safeId))
        }

        flow.update { curr ->
            val verifiedFacts = curr.verifiedFacts.toMutableMap()
            if (!contact.isNullOrBlank()) verifiedFacts["callContact"] = contact
            if (!number.isNullOrBlank()) verifiedFacts["callNumber"] = number

            curr.copy(
                activeCallContact = contact ?: curr.activeCallContact,
                activeCallNumber = number ?: curr.activeCallNumber,
                verifiedFacts = verifiedFacts,
                updatedAt = System.currentTimeMillis(),
                version = curr.version + 1
            )
        }
    }

    // =========================================================================
    // REFERENCE & TOPIC RESOLUTION ENGINE (English, Hindi, Hinglish, Roman Hindi)
    // =========================================================================

    /**
     * Resolves natural pronouns, demonstratives, ordinals, corrections, and topic returns
     * against the active conversation state.
     */
    fun resolveReference(
        rawInput: String,
        conversationId: String
    ): ContextResolutionResult {
        val safeId = conversationId.ifBlank { "default_session" }
        val context = getContext(safeId)
        val lower = rawInput.lowercase(Locale.ROOT).trim()

        // 1. Explicit new targets take precedence over context references
        if (isExplicitNewTarget(lower)) {
            return ContextResolutionResult(
                confidence = 0f,
                dialogueAct = DialogueAct.TASK,
                reason = "Explicit new target present; bypassing context reference."
            )
        }

        // 2. Topic return check ("wapis us account pe aao", "jo pehle profile dekhi thi", "Instagram wale pe wapis chalo")
        val topicReturnResult = handleTopicReturn(lower, context)
        if (topicReturnResult != null) {
            return topicReturnResult
        }

        // 3. User corrections ("nahi doosra wala", "nahi Instagram wala", "mera matlab YouTube wala tha")
        val correctionResult = handleCorrection(lower, context)
        if (correctionResult != null) {
            return correctionResult
        }

        // 4. Platform-specific qualifiers ("Instagram wala", "YouTube wala", "Twitter wala")
        val platformQualifierResult = handlePlatformQualifier(lower, context)
        if (platformQualifierResult != null) {
            return platformQualifierResult
        }

        // 5. Ordinal and positional references ("pehla", "doosra", "second", "last wala", "upar wala", "neeche wala")
        val ordinalResult = handleOrdinalReference(lower, context)
        if (ordinalResult != null) {
            return ordinalResult
        }

        // 6. Demonstratives, Pronouns, and Direct Attribute Inquiries:
        // English: this account, that account, this profile, this person, this website, same one, previous one
        // Hindi/Hinglish: ye, yeh, woh, wo, iska, iske, iski, uska, uske, uski, is account, us account, is profile, wahi wala, usi ka
        val pronounResult = handlePronounReference(lower, context)
        if (pronounResult != null) {
            return pronounResult
        }

        // 7. Cross-capability references (e.g. "Iski website analyze karo", "Isko browser mein kholo")
        val crossCapResult = handleCrossCapabilityReference(lower, context)
        if (crossCapResult != null) {
            return crossCapResult
        }

        return ContextResolutionResult(
            confidence = 0f,
            dialogueAct = DialogueAct.QUESTION,
            reason = "No reference pattern detected."
        )
    }

    /**
     * Determines if the user input explicitly supplies a fresh target (overriding context).
     */
    private fun isExplicitNewTarget(lower: String): Boolean {
        // e.g. "#spy @xyz" or explicit new phone number or contact
        if (lower.startsWith("#spy ") && (lower.contains("@") || lower.contains("http") || lower.contains(".com"))) return true
        if (lower.contains("ko call karo") && !lower.startsWith("nahi") && !lower.startsWith("isko") && !lower.startsWith("usko")) {
            val contact = lower.substringBefore("ko call").trim()
            if (contact.length > 2 && !contact.contains("is") && !contact.contains("us") && !contact.contains("ye") && !contact.contains("wo")) {
                return true
            }
        }
        return false
    }

    /**
     * Handles explicit return to an earlier topic thread in history.
     */
    private fun handleTopicReturn(lower: String, context: ConversationContext): ContextResolutionResult? {
        val returnTriggers = listOf(
            "wapis us account", "wapas us account", "wapis pehle wale", "wapas pehle wale",
            "jo pehle profile", "pehle wali profile", "pehle wala profile", "jo pehle dekhi thi",
            "instagram wale pe wapis", "instagram wale par wapas", "wapis chalo", "wapas chalo",
            "back to previous account", "return to profile", "jo pehle account", "pehle account pe"
        )

        val matchesTopicReturn = returnTriggers.any { lower.contains(it) } ||
                ((lower.contains("wapis") || lower.contains("wapas") || lower.contains("back")) &&
                        (lower.contains("account") || lower.contains("profile") || lower.contains("pehle") || lower.contains("previous")))

        if (!matchesTopicReturn) return null

        // Search topic history for previous profile thread
        val previousProfileThread = context.topicHistory.firstOrNull { thread ->
            thread.primaryEntityId?.startsWith("profile_") == true
        }

        if (previousProfileThread != null) {
            val entity = previousProfileThread.primaryEntityId?.let { context.entities[it] }
            return ContextResolutionResult(
                resolvedEntity = entity,
                targetText = entity?.name ?: previousProfileThread.title,
                targetUrl = entity?.url,
                confidence = 0.98f,
                isTopicReturn = true,
                restoredTopic = previousProfileThread,
                dialogueAct = DialogueAct.TOPIC_RETURN,
                reason = "Restored previous topic thread: ${previousProfileThread.title}."
            )
        }

        val firstProfile = context.getProfiles().firstOrNull()
        if (firstProfile != null) {
            return ContextResolutionResult(
                resolvedEntity = firstProfile,
                targetText = firstProfile.name,
                targetUrl = firstProfile.url,
                confidence = 0.95f,
                isTopicReturn = true,
                dialogueAct = DialogueAct.TOPIC_RETURN,
                reason = "Restored first profile in memory @${firstProfile.name}."
            )
        }

        return null
    }

    /**
     * Handles explicit user corrections ("nahi Instagram wala", "nahi doosra", "mera matlab YouTube tha").
     */
    private fun handleCorrection(lower: String, context: ConversationContext): ContextResolutionResult? {
        val correctionTriggers = listOf("nahi", "arre nahi", "are nahi", "no", "not this", "galat", "wrong", "mera matlab")
        val isCorrection = correctionTriggers.any { lower.startsWith(it) || lower.contains(" nahi ") || lower.contains("instead") }
        if (!isCorrection) return null

        val profiles = context.getProfiles()

        // "Nahi Instagram wala" or "Nahi, Instagram wale profile ki baat kar raha hoon"
        if (lower.contains("instagram") || lower.contains("insta")) {
            val target = profiles.firstOrNull { it.platform?.contains("INSTAGRAM", true) == true }
                ?: context.entities.values.firstOrNull { it.type == EntityType.PROFILE && it.platform?.contains("INSTAGRAM", true) == true }
            if (target != null) {
                return ContextResolutionResult(
                    resolvedEntity = target,
                    targetText = target.name,
                    targetUrl = target.url,
                    confidence = 0.98f,
                    isCorrection = true,
                    dialogueAct = DialogueAct.CORRECTION,
                    reason = "User corrected to Instagram profile @${target.name}."
                )
            }
        }

        // "Nahi YouTube wala"
        if (lower.contains("youtube") || lower.contains("yt")) {
            val target = profiles.firstOrNull { it.platform?.contains("YOUTUBE", true) == true }
                ?: context.entities.values.firstOrNull { it.type == EntityType.PROFILE && it.platform?.contains("YOUTUBE", true) == true }
            if (target != null) {
                return ContextResolutionResult(
                    resolvedEntity = target,
                    targetText = target.name,
                    targetUrl = target.url,
                    confidence = 0.98f,
                    isCorrection = true,
                    dialogueAct = DialogueAct.CORRECTION,
                    reason = "User corrected to YouTube profile @${target.name}."
                )
            }
        }

        // "Nahi doosra wala"
        if (lower.contains("doosra") || lower.contains("doosre") || lower.contains("dusra") || lower.contains("dusre") || lower.contains("second")) {
            val target = profiles.getOrNull(1)
            if (target != null) {
                return ContextResolutionResult(
                    resolvedEntity = target,
                    targetText = target.name,
                    targetUrl = target.url,
                    confidence = 0.98f,
                    isCorrection = true,
                    dialogueAct = DialogueAct.CORRECTION,
                    reason = "User corrected to second profile @${target.name}."
                )
            }
        }

        // "Nahi pehla wala"
        if (lower.contains("pehla") || lower.contains("pehle") || lower.contains("first")) {
            val target = profiles.getOrNull(0)
            if (target != null) {
                return ContextResolutionResult(
                    resolvedEntity = target,
                    targetText = target.name,
                    targetUrl = target.url,
                    confidence = 0.98f,
                    isCorrection = true,
                    dialogueAct = DialogueAct.CORRECTION,
                    reason = "User corrected to first profile @${target.name}."
                )
            }
        }

        // General correction referencing active profile entity (e.g. "nahi mera matlab following tha", "nahi bio batao")
        val activeOrFirst = context.getActiveEntity()?.takeIf { it.type == EntityType.PROFILE } ?: profiles.firstOrNull()
        if (activeOrFirst != null) {
            return ContextResolutionResult(
                resolvedEntity = activeOrFirst,
                targetText = activeOrFirst.name,
                targetUrl = activeOrFirst.url,
                confidence = 0.98f,
                isCorrection = true,
                dialogueAct = DialogueAct.CORRECTION,
                reason = "User correction applied to active profile @${activeOrFirst.name}."
            )
        }

        return null
    }

    /**
     * Handles platform-specific qualifiers ("Instagram wala", "YouTube wala").
     */
    private fun handlePlatformQualifier(lower: String, context: ConversationContext): ContextResolutionResult? {
        val profiles = context.getProfiles()
        if (profiles.isEmpty()) return null

        val isInsta = lower.contains("instagram wal") || lower.contains("insta wal") ||
                lower.contains("instagram profile") || lower.contains("insta profile")
        if (isInsta) {
            val match = profiles.firstOrNull { it.platform?.contains("INSTAGRAM", true) == true }
            if (match != null) {
                return ContextResolutionResult(
                    resolvedEntity = match,
                    targetText = match.name,
                    targetUrl = match.url,
                    confidence = 0.98f,
                    dialogueAct = DialogueAct.REFERENCE_QUERY,
                    reason = "Matched Instagram qualifier."
                )
            }
        }

        val isYt = lower.contains("youtube wal") || lower.contains("yt wal") ||
                lower.contains("youtube channel") || lower.contains("yt channel")
        if (isYt) {
            val match = profiles.firstOrNull { it.platform?.contains("YOUTUBE", true) == true }
            if (match != null) {
                return ContextResolutionResult(
                    resolvedEntity = match,
                    targetText = match.name,
                    targetUrl = match.url,
                    confidence = 0.98f,
                    dialogueAct = DialogueAct.REFERENCE_QUERY,
                    reason = "Matched YouTube qualifier."
                )
            }
        }

        val isTwitter = lower.contains("twitter wal") || lower.contains("x wal") ||
                lower.contains("twitter profile") || lower.contains("x profile")
        if (isTwitter) {
            val match = profiles.firstOrNull { it.platform?.contains("TWITTER", true) == true }
            if (match != null) {
                return ContextResolutionResult(
                    resolvedEntity = match,
                    targetText = match.name,
                    targetUrl = match.url,
                    confidence = 0.98f,
                    dialogueAct = DialogueAct.REFERENCE_QUERY,
                    reason = "Matched Twitter qualifier."
                )
            }
        }

        return null
    }

    /**
     * Handles ordinal and positional references ("pehla", "doosra", "second", "upar wala", "neeche wala").
     */
    private fun handleOrdinalReference(lower: String, context: ConversationContext): ContextResolutionResult? {
        val profiles = context.getProfiles()
        val browserCandidates = context.browserCandidates

        val ordinalGroups = listOf(
            listOf("pehla", "pehle", "pehli", "first", "1st", "upar wala", "upar wale", "top wala") to 0,
            listOf("doosra", "doosre", "doosri", "dusra", "dusre", "second", "2nd") to 1,
            listOf("teesra", "teesre", "teesri", "tisra", "tisre", "third", "3rd") to 2,
            listOf("chautha", "chauthe", "fourth", "4th") to 3
        )

        for ((phraseList, idx) in ordinalGroups) {
            if (phraseList.any { lower.contains(it) }) {
                // If profiles in context match the ordinal
                if (profiles.size > idx) {
                    val entity = profiles[idx]
                    return ContextResolutionResult(
                        resolvedEntity = entity,
                        targetText = entity.name,
                        targetUrl = entity.url,
                        confidence = 0.98f,
                        dialogueAct = DialogueAct.REFERENCE_QUERY,
                        reason = "Matched ordinal index $idx to profile @${entity.name}."
                    )
                }

                // If browser candidates match the ordinal
                if (browserCandidates.size > idx) {
                    val candidate = browserCandidates[idx]
                    return ContextResolutionResult(
                        targetText = candidate,
                        confidence = 0.98f,
                        suggestedCapability = LichiCapability.BROWSER,
                        dialogueAct = DialogueAct.COMMAND,
                        reason = "Matched ordinal index $idx to browser candidate \"$candidate\"."
                    )
                }
            }
        }

        // Positional "neeche wala" / "aakhri wala"
        if (lower.contains("neeche wala") || lower.contains("neeche wale") || lower.contains("last wala") || lower.contains("aakhri wala")) {
            if (profiles.isNotEmpty()) {
                val entity = profiles.last()
                return ContextResolutionResult(
                    resolvedEntity = entity,
                    targetText = entity.name,
                    targetUrl = entity.url,
                    confidence = 0.95f,
                    dialogueAct = DialogueAct.REFERENCE_QUERY,
                    reason = "Matched bottom profile @${entity.name}."
                )
            }
            if (browserCandidates.isNotEmpty()) {
                val candidate = browserCandidates.last()
                return ContextResolutionResult(
                    targetText = candidate,
                    confidence = 0.95f,
                    suggestedCapability = LichiCapability.BROWSER,
                    dialogueAct = DialogueAct.COMMAND,
                    reason = "Matched bottom browser candidate."
                )
            }
        }

        return null
    }

    /**
     * Handles demonstratives and pronouns across English, Hindi, and Hinglish:
     * English: this account, that account, this profile, this person, this website, same one, previous one
     * Hindi/Hinglish: ye, yeh, woh, wo, iska, iske, iski, uska, uske, uski, is account, us account, is profile, wahi wala, usi ka
     * Also handles direct attribute queries (followers, bio, specific cheezein).
     */
    private fun handlePronounReference(lower: String, context: ConversationContext): ContextResolutionResult? {
        val pronounSubstrings = listOf(
            "this account", "that account", "this profile", "that profile", "this person", "that person",
            "this user", "that user", "same one", "the previous one", "the last one", "same account", "previous account",
            "is account", "us account", "is profile", "us profile", "is person", "us person",
            "is bande", "us bande", "is aadmi", "us aadmi", "wahi wala", "wahi wale", "usi wale", "same profile",
            "is result", "us result", "previous wala", "pichhla", "pichhle"
        )
        val pronounWords = listOf("ye", "yeh", "wo", "woh", "wahi", "iska", "iske", "iski", "uska", "uske", "uski", "isi", "usi")

        val matchesPronoun = pronounSubstrings.any { lower.contains(it) } ||
                pronounWords.any { lower == it || lower.startsWith("$it ") || lower.contains(" $it ") || lower.endsWith(" $it") }

        val attributeQueries = listOf("follower", "following", "bio", "post", "reel", "video", "specific cheeze", "specific", "detail", "info", "email", "phone", "number", "baare mein")
        val isAttributeQuery = attributeQueries.any { lower.contains(it) } &&
                (lower.contains("kitne") || lower.contains("kya") || lower.contains("batao") || lower.contains("dikhao") || lower.contains("hai") || lower.contains("aur"))

        if (!matchesPronoun && !isAttributeQuery) {
            return null
        }

        val profiles = context.getProfiles()
        val activeEntity = context.getActiveEntity()

        // CRITICAL CONVERSATIONAL LOGIC:
        // Ambiguity should ONLY be triggered if there are multiple competing candidates from DIFFERENT accounts
        // AND there is NO clear primary active entity from the immediate prior turn!
        // If the user just looked up @axeel_dubin in Turn 1, @axeel_dubin IS the primary active entity,
        // so "is account" or "iske followers" is 100% UNAMBIGUOUS!
        if (profiles.size > 1 && !lower.contains("pehla") && !lower.contains("doosra") && !lower.contains("instagram") && !lower.contains("youtube")) {
            // Check if there is a clear active entity
            val hasDistinctActiveEntity = (activeEntity != null && activeEntity.salience == SalienceLevel.HIGH &&
                    profiles.count { it.salience == SalienceLevel.HIGH } <= 1)

            if (!hasDistinctActiveEntity) {
                val candidateDescriptions = profiles.take(2).map {
                    "${it.platform ?: "Profile"} (@${it.name})"
                }
                return ContextResolutionResult(
                    isAmbiguous = true,
                    ambiguityCandidates = profiles.take(2),
                    clarificationQuestion = "Aap kis account ki baat kar rahe hain — ${candidateDescriptions.joinToString(" ya ")}?",
                    confidence = 0.7f,
                    dialogueAct = DialogueAct.CLARIFICATION,
                    reason = "Multiple equally salient profiles found without platform qualifier."
                )
            }
        }

        // Resolve to active profile
        val targetProfile = if (activeEntity?.type == EntityType.PROFILE) activeEntity else profiles.firstOrNull()

        if (targetProfile != null) {
            return ContextResolutionResult(
                resolvedEntity = targetProfile,
                targetText = targetProfile.name,
                targetUrl = targetProfile.url,
                confidence = 0.99f,
                dialogueAct = if (isAttributeQuery) DialogueAct.REFERENCE_QUERY else DialogueAct.FOLLOW_UP,
                reason = "Resolved pronoun reference to active profile @${targetProfile.name}."
            )
        }

        // If active entity is a website or browser page
        val activeWebsite = context.getEntitiesOfType(EntityType.WEBSITE).firstOrNull()
        if (activeWebsite != null && (lower.contains("website") || lower.contains("site") || lower.contains("page") || lower.contains("isme"))) {
            return ContextResolutionResult(
                resolvedEntity = activeWebsite,
                targetText = activeWebsite.name,
                targetUrl = activeWebsite.url,
                confidence = 0.96f,
                suggestedCapability = LichiCapability.BROWSER,
                dialogueAct = DialogueAct.FOLLOW_UP,
                reason = "Resolved pronoun reference to active website ${activeWebsite.url}."
            )
        }

        return null
    }

    /**
     * Handles cross-capability entity transitions:
     * - Profile -> Website -> Web Intelligence / Browser
     * - Profile -> Browser open
     * - HARD SAFETY: Phone info lookup never routes to phone calls!
     */
    private fun handleCrossCapabilityReference(lower: String, context: ConversationContext): ContextResolutionResult? {
        val activeProfile = context.getProfiles().firstOrNull() ?: context.getActiveEntity()

        // 1. Profile -> Website -> Web Intelligence / Browser
        if (lower.contains("website") || lower.contains("web site") || lower.contains("site")) {
            val websiteUrl = activeProfile?.website ?: context.getEntitiesOfType(EntityType.WEBSITE).firstOrNull()?.url
            if (!websiteUrl.isNullOrBlank()) {
                val isBrowserNavigate = lower.contains("kholo") || lower.contains("open") || lower.contains("browser")
                return ContextResolutionResult(
                    resolvedEntity = activeProfile,
                    targetText = websiteUrl,
                    targetUrl = websiteUrl,
                    confidence = 0.98f,
                    suggestedCapability = if (isBrowserNavigate) LichiCapability.BROWSER else LichiCapability.WEB_SEARCH,
                    dialogueAct = DialogueAct.COMMAND,
                    reason = "Cross-capability transition: Active profile website -> ${if (isBrowserNavigate) "Browser" else "Web Search"}."
                )
            }
        }

        // 2. Profile -> YouTube Channel / Browser open
        if (lower.contains("youtube") && (lower.contains("channel") || lower.contains("kholo") || lower.contains("open") || lower.contains("jao"))) {
            val ytEntity = context.entities.values.firstOrNull { it.type == EntityType.PROFILE && it.platform?.contains("YOUTUBE", true) == true }
            val targetUrl = ytEntity?.url ?: "https://www.youtube.com/@${activeProfile?.name ?: ""}"
            return ContextResolutionResult(
                resolvedEntity = ytEntity ?: activeProfile,
                targetText = targetUrl,
                targetUrl = targetUrl,
                confidence = 0.98f,
                suggestedCapability = LichiCapability.BROWSER,
                dialogueAct = DialogueAct.COMMAND,
                reason = "Cross-capability transition: Active profile -> YouTube channel navigation."
            )
        }

        // 3. Profile -> Browser open
        if ((lower.contains("browser mein kholo") || lower.contains("browser me open") || lower.contains("browser par kholo") || lower.contains("browser mein")) && activeProfile != null) {
            val targetUrl = activeProfile.website ?: activeProfile.url ?: "https://instagram.com/${activeProfile.name}"
            return ContextResolutionResult(
                resolvedEntity = activeProfile,
                targetText = targetUrl,
                targetUrl = targetUrl,
                confidence = 0.98f,
                suggestedCapability = LichiCapability.BROWSER,
                dialogueAct = DialogueAct.COMMAND,
                reason = "Cross-capability transition: Active profile -> Browser navigation."
            )
        }

        // 3. HARD SAFETY BOUNDARY: Phone lookup must NEVER route to CALLS!
        // E.g. "#Spy +919927881086" or "Is number ka information batao"
        val isLookingUpNumberItself = (lower.contains("is number") || lower.contains("us number") || lower.contains("unknown number") || lower.contains("kiska number")) &&
                !lower.contains("iska") && !lower.contains("uski") && !lower.contains("uska") && !lower.contains("iske")
        if (isLookingUpNumberItself && (lower.contains("information") || lower.contains("detail") || lower.contains("kiska") || lower.contains("batao"))) {
            if (!lower.contains("call karo") && !lower.contains("call mila")) {
                val phone = activeProfile?.publicPhone ?: context.getEntitiesOfType(EntityType.PHONE_NUMBER).firstOrNull()?.name
                if (!phone.isNullOrBlank()) {
                    return ContextResolutionResult(
                        resolvedEntity = activeProfile,
                        targetText = phone,
                        confidence = 0.98f,
                        suggestedCapability = LichiCapability.WEB_SEARCH, // Real lookup, NOT CALLS!
                        dialogueAct = DialogueAct.REFERENCE_QUERY,
                        reason = "Public phone info lookup: Preserving safety boundary (NOT routed to Calls)."
                    )
                }
            }
        }

        return null
    }

    /**
     * Decays salience of non-active entities when new turns occur.
     */
    private fun decayEntities(entities: MutableMap<String, SemanticEntity>, activeId: String?) {
        for ((id, entity) in entities) {
            if (id != activeId && entity.salience == SalienceLevel.HIGH) {
                entities[id] = entity.copy(salience = SalienceLevel.MEDIUM)
            }
        }
    }

    // =========================================================================
    // PERSISTENCE & RESTORATION
    // =========================================================================

    /**
     * Exports semantic context to a compact JSON string for conversation persistence.
     */
    fun exportContext(conversationId: String): String {
        val ctx = getContext(conversationId)
        return runCatching { json.encodeToString(ConversationContext.serializer(), ctx) }.getOrDefault("")
    }

    /**
     * Imports and reconstructs semantic context from stored conversation data.
     */
    fun importContext(conversationId: String, serializedJson: String) {
        if (serializedJson.isBlank()) return
        val restored = runCatching {
            json.decodeFromString(ConversationContext.serializer(), serializedJson)
        }.getOrNull() ?: return

        val safeId = conversationId.ifBlank { restored.conversationId }
        val flow = conversationContexts.computeIfAbsent(safeId) {
            MutableStateFlow(restored.copy(conversationId = safeId))
        }
        flow.value = restored.copy(conversationId = safeId)
        Log.d(TAG, "Restored context for $safeId with ${restored.entities.size} entities.")
    }

    /**
     * Restores context directly from a list of platform profiles (e.g. from existing Conversation messages).
     */
    fun restoreFromProfiles(conversationId: String, profiles: List<PlatformProfile>) {
        if (profiles.isEmpty()) return
        val primary = profiles.first()
        recordSpyExecution(
            conversationId = conversationId,
            primaryProfile = primary,
            allProfiles = profiles,
            userGoal = "Restored from conversation history",
            assistantResponse = "Restored context for @${primary.username}"
        )
    }

    /**
     * Clears context for a specific conversation.
     */
    fun clearContext(conversationId: String) {
        val safeId = conversationId.ifBlank { "default_session" }
        conversationContexts.remove(safeId)
    }
}
