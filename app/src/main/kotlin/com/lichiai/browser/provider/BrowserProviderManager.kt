package com.lichiai.browser.provider

import android.content.Context
import com.lichiai.data.ProviderStore
import com.lichiai.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Manages LLM providers specifically and exclusively for the Browser Agent.
 * Completely independent of Lichi's chat or device agent providers, but supports
 * seamless "Use Current Provider" integration with Lichi's active provider.
 */
class BrowserProviderManager(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val file = File(File(context.filesDir, "browser_data").apply { mkdirs() }, "browser_providers.json")

    private val defaultProviders = listOf(
        BrowserProviderConfig(
            id = "gemini_browser",
            name = "Google Gemini (Browser)",
            type = BrowserProviderType.GEMINI,
            endpoint = "https://generativelanguage.googleapis.com/v1beta",
            activeModel = "gemini-2.5-flash",
            isEnabled = true
        ),
        BrowserProviderConfig(
            id = "openai_browser",
            name = "OpenAI (Browser)",
            type = BrowserProviderType.OPENAI,
            endpoint = "https://api.openai.com/v1",
            activeModel = "gpt-4o-mini",
            isEnabled = true
        ),
        BrowserProviderConfig(
            id = "anthropic_browser",
            name = "Anthropic Claude (Browser)",
            type = BrowserProviderType.ANTHROPIC,
            endpoint = "https://api.anthropic.com/v1",
            activeModel = "claude-3-5-sonnet-20241022",
            isEnabled = true
        ),
        BrowserProviderConfig(
            id = "groq_browser",
            name = "Groq Fast (Browser)",
            type = BrowserProviderType.GROQ,
            endpoint = "https://api.groq.com/openai/v1",
            activeModel = "llama-3.3-70b-versatile",
            isEnabled = true
        )
    )

    private val _providers = MutableStateFlow<List<BrowserProviderConfig>>(defaultProviders)
    val providers: StateFlow<List<BrowserProviderConfig>> = _providers.asStateFlow()

    private val _settings = MutableStateFlow(BrowserAgentProviderSettings())
    val settings: StateFlow<BrowserAgentProviderSettings> = _settings.asStateFlow()

    private val settingsFile = File(File(context.filesDir, "browser_data").apply { mkdirs() }, "browser_provider_settings.json")

    private val providerStore = ProviderStore(context)
    private val settingsRepository = SettingsRepository(context)

    suspend fun initialize() = withContext(Dispatchers.IO) {
        try {
            if (file.exists()) {
                val text = file.readText()
                val loaded = json.decodeFromString<List<BrowserProviderConfig>>(text)
                if (loaded.isNotEmpty()) {
                    _providers.value = loaded
                }
            }
        } catch (_: Exception) {}

        try {
            if (settingsFile.exists()) {
                val text = settingsFile.readText()
                val loadedSettings = json.decodeFromString<BrowserAgentProviderSettings>(text)
                _settings.value = loadedSettings
            }
        } catch (_: Exception) {}
    }

    suspend fun setUseCurrentProvider(enabled: Boolean) = withContext(Dispatchers.IO) {
        _settings.value = _settings.value.copy(useCurrentProvider = enabled)
        saveSettings()
    }

    suspend fun upsertProvider(provider: BrowserProviderConfig) = withContext(Dispatchers.IO) {
        val list = _providers.value.filterNot { it.id == provider.id } + provider
        _providers.value = list
        save()
    }

    suspend fun setActiveProvider(providerId: String, model: String) = withContext(Dispatchers.IO) {
        _settings.value = _settings.value.copy(activeProviderId = providerId, activeModel = model)
        saveSettings()
    }

    suspend fun setFallbackProvider(providerId: String?) = withContext(Dispatchers.IO) {
        _settings.value = _settings.value.copy(fallbackProviderId = providerId)
        saveSettings()
    }

    fun getDedicatedActiveConfig(): BrowserProviderConfig? {
        val activeId = _settings.value.activeProviderId
        val base = _providers.value.firstOrNull { it.id == activeId }
            ?: _providers.value.firstOrNull { it.isEnabled }
        return if (base != null && _settings.value.activeModel.isNotBlank()) {
            base.copy(activeModel = _settings.value.activeModel)
        } else {
            base
        }
    }

    suspend fun getActiveConfig(): BrowserProviderConfig? = withContext(Dispatchers.IO) {
        // 1. If Use Current Provider is enabled, check Lichi's global active provider
        if (_settings.value.useCurrentProvider) {
            try {
                val appSettings = settingsRepository.settings.firstOrNull()
                val lichiProviders = providerStore.snapshot()
                val activeLichiProvider = lichiProviders.firstOrNull { it.id == appSettings?.activeProviderId }
                    ?: lichiProviders.firstOrNull()

                if (activeLichiProvider != null && activeLichiProvider.apiKey.isNotBlank()) {
                    val pType = when {
                        activeLichiProvider.baseUrl.contains("anthropic", ignoreCase = true) -> BrowserProviderType.ANTHROPIC
                        activeLichiProvider.baseUrl.contains("googleapis", ignoreCase = true) || activeLichiProvider.baseUrl.contains("generativelanguage", ignoreCase = true) -> BrowserProviderType.GEMINI
                        activeLichiProvider.baseUrl.contains("groq", ignoreCase = true) -> BrowserProviderType.GROQ
                        else -> BrowserProviderType.OPENAI
                    }
                    val model = if (!appSettings?.activeModel.isNullOrBlank()) {
                        appSettings!!.activeModel
                    } else if (activeLichiProvider.models.isNotEmpty()) {
                        activeLichiProvider.models.first()
                    } else {
                        "gpt-4o-mini"
                    }
                    return@withContext BrowserProviderConfig(
                        id = "lichi_active_${activeLichiProvider.id}",
                        name = "${activeLichiProvider.name} (Global)",
                        type = pType,
                        endpoint = activeLichiProvider.baseUrl,
                        apiKey = activeLichiProvider.apiKey,
                        activeModel = model,
                        isEnabled = true
                    )
                }
            } catch (_: Exception) {}
        }

        // 2. Dedicated Browser Provider fallback
        val activeId = _settings.value.activeProviderId
        val base = _providers.value.firstOrNull { it.id == activeId }
            ?: _providers.value.firstOrNull { it.isEnabled }
        if (base != null && _settings.value.activeModel.isNotBlank()) {
            base.copy(activeModel = _settings.value.activeModel)
        } else {
            base
        }
    }

    suspend fun getFallbackConfig(): BrowserProviderConfig? = withContext(Dispatchers.IO) {
        val fallbackId = _settings.value.fallbackProviderId ?: return@withContext null
        _providers.value.firstOrNull { it.id == fallbackId && it.isEnabled }
    }

    private fun save() {
        try {
            file.writeText(json.encodeToString(_providers.value))
        } catch (_: Exception) {}
    }

    private fun saveSettings() {
        try {
            settingsFile.writeText(json.encodeToString(_settings.value))
        } catch (_: Exception) {}
    }
}
