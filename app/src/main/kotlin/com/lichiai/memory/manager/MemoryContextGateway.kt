package com.lichiai.memory.manager

import android.content.Context
import com.lichiai.memory.identity.UserIdentityManager
import com.lichiai.memory.model.CoreProfileView
import com.lichiai.memory.model.MemoryPack
import com.lichiai.memory.model.TemporalIntent

/**
 * Canonical Cross-Conversation Memory Gateway - Lichi Memory OS V5.
 *
 * Single entry point for all subsystems (Chat, Voice, Orchestrator, Agent V2, Spy, Browser, Web, Terminal)
 * to retrieve and record long-term persistent memory without duplicating search or storage logic.
 */
object MemoryContextGateway {

    /**
     * Canonical retrieval entry point for all LLM-facing subsystems.
     */
    suspend fun retrieve(
        context: Context,
        query: String,
        conversationId: String? = null,
        userId: String? = null,
        activeTask: String? = null,
        projectContext: String? = null,
        temporalIntent: TemporalIntent? = null
    ): MemoryPack {
        val engine = LichiMemoryEngine.getInstance(context)
        val resolvedUserId = userId ?: UserIdentityManager.getStableUserId(context)
        return engine.getMemoryPack(
            query = query,
            conversationId = conversationId,
            userId = resolvedUserId,
            activeTask = activeTask,
            projectContext = projectContext,
            temporalIntent = temporalIntent
        )
    }

    /**
     * Synchronous turn recording guaranteeing read-after-write consistency before turn finishes.
     */
    suspend fun recordTurn(
        context: Context,
        conversationId: String,
        messageId: String,
        role: String,
        content: String,
        timestamp: Long = System.currentTimeMillis(),
        userId: String? = null
    ): com.lichiai.memory.model.MemoryWriteResult {
        val engine = LichiMemoryEngine.getInstance(context)
        val resolvedUserId = userId ?: UserIdentityManager.getStableUserId(context)
        return engine.recordTurn(
            conversationId = conversationId,
            messageId = messageId,
            role = role,
            content = content,
            timestamp = timestamp,
            userId = resolvedUserId
        )
    }

    /**
     * Non-blocking asynchronous turn recording.
     */
    fun recordTurnAsync(
        context: Context,
        conversationId: String,
        messageId: String,
        role: String,
        content: String,
        timestamp: Long = System.currentTimeMillis(),
        userId: String? = null
    ) {
        val engine = LichiMemoryEngine.getInstance(context)
        val resolvedUserId = userId ?: UserIdentityManager.getStableUserId(context)
        engine.recordTurnAsync(
            conversationId = conversationId,
            messageId = messageId,
            role = role,
            content = content,
            timestamp = timestamp,
            userId = resolvedUserId
        )
    }

    /**
     * Retrieves the materialized Core Profile View for the current user.
     */
    suspend fun getCoreProfile(
        context: Context,
        userId: String? = null
    ): CoreProfileView {
        val pack = retrieve(context = context, query = "", userId = userId)
        return pack.coreProfile ?: CoreProfileView(userId = userId ?: UserIdentityManager.getStableUserId(context))
    }

    /**
     * Executes an explicit user forget / amnesia command.
     */
    suspend fun executeForget(
        context: Context,
        target: String,
        conversationId: String? = null,
        userId: String? = null
    ): Boolean {
        val engine = LichiMemoryEngine.getInstance(context)
        val resolvedUserId = userId ?: UserIdentityManager.getStableUserId(context)
        return engine.executeForget(target = target, conversationId = conversationId, userId = resolvedUserId)
    }

    /**
     * Cascades deletion across all Room tables and vector indexes for a conversation.
     */
    suspend fun deleteConversation(
        context: Context,
        conversationId: String,
        userId: String? = null
    ) {
        val engine = LichiMemoryEngine.getInstance(context)
        val resolvedUserId = userId ?: UserIdentityManager.getStableUserId(context)
        engine.deleteConversation(conversationId = conversationId, userId = resolvedUserId)
    }

    /**
     * Deletes a single message turn from Room and vector index.
     */
    suspend fun deleteMessage(
        context: Context,
        messageId: String,
        userId: String? = null
    ) {
        val engine = LichiMemoryEngine.getInstance(context)
        val resolvedUserId = userId ?: UserIdentityManager.getStableUserId(context)
        engine.deleteMessage(messageId = messageId, userId = resolvedUserId)
    }

    /**
     * Updates message content in Room ledger and invalidates/regenerates vector embedding.
     */
    suspend fun updateMessageContent(
        context: Context,
        messageId: String,
        newContent: String,
        userId: String? = null
    ) {
        val engine = LichiMemoryEngine.getInstance(context)
        val resolvedUserId = userId ?: UserIdentityManager.getStableUserId(context)
        engine.updateMessageContent(messageId = messageId, newContent = newContent, userId = resolvedUserId)
    }

    /**
     * Tombstones a memory item by natural language description or entity target.
     */
    suspend fun tombstoneMemoryByDescription(
        context: Context,
        description: String,
        targetEntity: String? = null,
        conversationId: String? = null,
        userId: String? = null
    ): Boolean {
        val engine = LichiMemoryEngine.getInstance(context)
        val resolvedUserId = userId ?: UserIdentityManager.getStableUserId(context)
        return engine.tombstoneMemoryByDescription(
            description = description,
            targetEntity = targetEntity,
            conversationId = conversationId,
            userId = resolvedUserId
        )
    }
}
