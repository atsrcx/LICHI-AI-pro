package com.lichiai.agent.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Isolated settings for Autonomous Agent V2.
 * Strictly decoupled from Lichi's multi-provider config and regular LLM settings.
 */
data class AgentSettings(
    val enabled: Boolean = false,
    val useCurrentProvider: Boolean = true,
    val apiKey: String = "",
    val model: String = "gemini-2.0-flash",
    val maxSteps: Int = 15,
    val verboseObservations: Boolean = true
) {
    fun isConfigured(hasActiveProvider: Boolean = true): Boolean =
        if (useCurrentProvider) enabled && hasActiveProvider
        else enabled && apiKey.isNotBlank()
}

class AgentSettingsRepository(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "lichi_agent_v2_settings"
        private const val KEY_ENABLED = "agent_enabled"
        private const val KEY_USE_CURRENT_PROVIDER = "agent_use_current_provider"
        private const val KEY_API_KEY = "agent_gemini_api_key"
        private const val KEY_MODEL = "agent_model"
        private const val KEY_MAX_STEPS = "agent_max_steps"

        @Volatile
        private var instance: AgentSettingsRepository? = null

        fun getInstance(context: Context): AgentSettingsRepository {
            return instance ?: synchronized(this) {
                instance ?: AgentSettingsRepository(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<AgentSettings> = _settings.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun loadSettings(): AgentSettings {
        return AgentSettings(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            useCurrentProvider = prefs.getBoolean(KEY_USE_CURRENT_PROVIDER, true),
            apiKey = prefs.getString(KEY_API_KEY, "") ?: "",
            model = prefs.getString(KEY_MODEL, "gemini-2.0-flash") ?: "gemini-2.0-flash",
            maxSteps = prefs.getInt(KEY_MAX_STEPS, 15)
        )
    }

    fun updateSettings(transform: (AgentSettings) -> AgentSettings) {
        val current = _settings.value
        val updated = transform(current)
        _settings.value = updated

        scope.launch {
            prefs.edit()
                .putBoolean(KEY_ENABLED, updated.enabled)
                .putBoolean(KEY_USE_CURRENT_PROVIDER, updated.useCurrentProvider)
                .putString(KEY_API_KEY, updated.apiKey)
                .putString(KEY_MODEL, updated.model)
                .putInt(KEY_MAX_STEPS, updated.maxSteps)
                .apply()
        }
    }

    fun isConfigValid(hasActiveProvider: Boolean = true): Boolean {
        val s = _settings.value
        return if (s.useCurrentProvider) {
            s.enabled && hasActiveProvider
        } else {
            s.enabled && s.apiKey.isNotBlank()
        }
    }
}
