package com.lichiai.assistant.model

import com.lichiai.data.Assistant
import kotlinx.serialization.Serializable

/**
 * Authoritative runtime representation of the currently active assistant.
 * Contains all identity, persona, system prompt, temperature override,
 * and capability configuration.
 */
@Serializable
data class ActiveAssistant(
    val assistantId: String,
    val name: String,
    val avatar: String = "🤖",
    val systemPrompt: String = "You are a helpful assistant.",
    val temperatureOverride: Float? = null,
    val isActive: Boolean = true,
    val preferredProviderId: String? = null,
    val preferredModel: String? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun toAssistant(): Assistant = Assistant(
        id = assistantId,
        name = name,
        avatar = avatar,
        systemPrompt = systemPrompt,
        preferredProviderId = preferredProviderId,
        preferredModel = preferredModel,
        temperature = temperatureOverride,
        createdAt = createdAt
    )
}

fun Assistant.toActiveAssistant(isActive: Boolean = true): ActiveAssistant = ActiveAssistant(
    assistantId = id,
    name = name,
    avatar = avatar,
    systemPrompt = systemPrompt,
    temperatureOverride = temperature,
    isActive = isActive,
    preferredProviderId = preferredProviderId,
    preferredModel = preferredModel,
    createdAt = createdAt
)
