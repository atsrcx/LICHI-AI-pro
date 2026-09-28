package com.lichiai.web.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lichiai.web.model.WebProviderType
import com.lichiai.web.model.WebSearchMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.webSearchDataStore: DataStore<Preferences> by preferencesDataStore(name = "web_search_settings")

@Serializable
data class WebSearchSettings(
    val enabled: Boolean = true,
    val activeProvider: WebProviderType = WebProviderType.EXA,
    val searchMode: WebSearchMode = WebSearchMode.SINGLE_PROVIDER,
    val fallbackEnabled: Boolean = true,
    val multiProviderResearch: Boolean = false,
    val apiKeys: Map<String, String> = emptyMap(),
    val safeSearch: Boolean = true
) {
    fun getApiKey(type: WebProviderType): String = apiKeys[type.id]?.trim() ?: ""
    fun hasApiKey(type: WebProviderType): Boolean = getApiKey(type).isNotBlank()
}

class WebSearchSettingsRepository private constructor(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private object Keys {
        val ENABLED = booleanPreferencesKey("web_search_enabled")
        val ACTIVE_PROVIDER = stringPreferencesKey("web_search_active_provider")
        val SEARCH_MODE = stringPreferencesKey("web_search_mode")
        val FALLBACK_ENABLED = booleanPreferencesKey("web_search_fallback_enabled")
        val MULTI_PROVIDER = booleanPreferencesKey("web_search_multi_provider")
        val API_KEYS_JSON = stringPreferencesKey("web_search_api_keys_json")
        val SAFE_SEARCH = booleanPreferencesKey("web_search_safe_search")
    }

    val settings: Flow<WebSearchSettings> = context.webSearchDataStore.data.map { prefs ->
        val activeProvStr = prefs[Keys.ACTIVE_PROVIDER] ?: WebProviderType.EXA.name
        val activeProv = runCatching { WebProviderType.valueOf(activeProvStr) }.getOrDefault(WebProviderType.EXA)

        val searchModeStr = prefs[Keys.SEARCH_MODE] ?: WebSearchMode.SINGLE_PROVIDER.name
        val mode = runCatching { WebSearchMode.valueOf(searchModeStr) }.getOrDefault(WebSearchMode.SINGLE_PROVIDER)

        val rawKeys = prefs[Keys.API_KEYS_JSON] ?: "{}"
        val parsedKeys = runCatching { json.decodeFromString<Map<String, String>>(rawKeys) }.getOrDefault(emptyMap())

        WebSearchSettings(
            enabled = prefs[Keys.ENABLED] ?: true,
            activeProvider = activeProv,
            searchMode = mode,
            fallbackEnabled = prefs[Keys.FALLBACK_ENABLED] ?: true,
            multiProviderResearch = prefs[Keys.MULTI_PROVIDER] ?: false,
            apiKeys = parsedKeys,
            safeSearch = prefs[Keys.SAFE_SEARCH] ?: true
        )
    }

    suspend fun getSnapshot(): WebSearchSettings = settings.first()

    suspend fun updateSettings(transform: (WebSearchSettings) -> WebSearchSettings) {
        val current = getSnapshot()
        val next = transform(current)
        context.webSearchDataStore.edit { prefs ->
            prefs[Keys.ENABLED] = next.enabled
            prefs[Keys.ACTIVE_PROVIDER] = next.activeProvider.name
            prefs[Keys.SEARCH_MODE] = next.searchMode.name
            prefs[Keys.FALLBACK_ENABLED] = next.fallbackEnabled
            prefs[Keys.MULTI_PROVIDER] = next.multiProviderResearch
            prefs[Keys.API_KEYS_JSON] = json.encodeToString(next.apiKeys)
            prefs[Keys.SAFE_SEARCH] = next.safeSearch
        }
    }

    suspend fun setApiKey(type: WebProviderType, key: String) {
        updateSettings { curr ->
            val updated = curr.apiKeys.toMutableMap()
            if (key.isBlank()) updated.remove(type.id) else updated[type.id] = key.trim()
            curr.copy(apiKeys = updated)
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: WebSearchSettingsRepository? = null

        fun getInstance(context: Context): WebSearchSettingsRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: WebSearchSettingsRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
