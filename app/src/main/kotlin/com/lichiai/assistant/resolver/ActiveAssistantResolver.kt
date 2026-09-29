package com.lichiai.assistant.resolver

import android.content.Context
import com.lichiai.assistant.model.ActiveAssistant
import com.lichiai.assistant.model.toActiveAssistant
import com.lichiai.data.AppSettings
import com.lichiai.data.Assistant
import com.lichiai.data.AssistantPresets
import com.lichiai.data.AssistantStore
import com.lichiai.data.SettingsRepository
import kotlinx.coroutines.flow.first

/**
 * Authoritative resolver for the Active Assistant.
 * Guarantees a single source of truth across UI, Chat, Voice, and Task Execution.
 */
object ActiveAssistantResolver {

    /**
     * Resolves the active assistant synchronously from provided memory/state snapshots.
     */
    fun resolve(
        activeAssistantId: String?,
        assistants: List<Assistant>
    ): ActiveAssistant {
        val list = if (assistants.isNotEmpty()) assistants else AssistantPresets.defaults()
        val matched = if (!activeAssistantId.isNullOrBlank()) {
            list.firstOrNull { it.id.equals(activeAssistantId, ignoreCase = true) }
        } else null

        val effective = matched
            ?: list.firstOrNull { it.id == "default" }
            ?: list.firstOrNull()
            ?: AssistantPresets.defaults().first()

        return effective.toActiveAssistant(isActive = true)
    }

    /**
     * Resolves from AppSettings and list of Assistants.
     */
    fun resolve(
        settings: AppSettings,
        assistants: List<Assistant>
    ): ActiveAssistant = resolve(settings.activeAssistantId, assistants)

    /**
     * Resolves asynchronously from persistent repositories.
     */
    suspend fun resolveActive(
        settingsRepo: SettingsRepository,
        assistantStore: AssistantStore
    ): ActiveAssistant {
        val settings = runCatching { settingsRepo.settings.first() }.getOrDefault(AppSettings())
        val assistants = runCatching { assistantStore.snapshot() }.getOrDefault(emptyList())
        return resolve(settings.activeAssistantId, assistants)
    }

    /**
     * Resolves directly using application context.
     */
    suspend fun resolveActive(context: Context): ActiveAssistant {
        val settingsRepo = SettingsRepository(context.applicationContext)
        val assistantStore = AssistantStore(context.applicationContext)
        return resolveActive(settingsRepo, assistantStore)
    }
}
