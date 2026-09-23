package com.lichiai.dynamicisland

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dynamicIslandDataStore by preferencesDataStore(name = "dynamic_island_settings")

class DynamicIslandRepository(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    private object Keys {
        val CONFIG_JSON = stringPreferencesKey("dynamic_island_config_json")
    }

    val configFlow: Flow<DynamicIslandConfig> = context.dynamicIslandDataStore.data.map { prefs ->
        val rawJson = prefs[Keys.CONFIG_JSON]
        if (rawJson.isNullOrBlank()) {
            DynamicIslandConfig()
        } else {
            try {
                json.decodeFromString<DynamicIslandConfig>(rawJson).sanitized()
            } catch (e: Exception) {
                DynamicIslandConfig()
            }
        }
    }

    val configState: StateFlow<DynamicIslandConfig> = configFlow.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = DynamicIslandConfig()
    )

    suspend fun updateConfig(transform: (DynamicIslandConfig) -> DynamicIslandConfig) {
        context.dynamicIslandDataStore.edit { prefs ->
            val currentRaw = prefs[Keys.CONFIG_JSON]
            val current = if (currentRaw.isNullOrBlank()) {
                DynamicIslandConfig()
            } else {
                try {
                    json.decodeFromString<DynamicIslandConfig>(currentRaw).sanitized()
                } catch (e: Exception) {
                    DynamicIslandConfig()
                }
            }
            val updated = transform(current).sanitized()
            prefs[Keys.CONFIG_JSON] = json.encodeToString(updated)
        }
    }

    suspend fun setConfig(config: DynamicIslandConfig) {
        context.dynamicIslandDataStore.edit { prefs ->
            prefs[Keys.CONFIG_JSON] = json.encodeToString(config.sanitized())
        }
    }

    suspend fun resetDefaults() {
        context.dynamicIslandDataStore.edit { prefs ->
            prefs[Keys.CONFIG_JSON] = json.encodeToString(DynamicIslandConfig())
        }
    }

    fun updatePosition(xFraction: Float, yFraction: Float, xOffsetDp: Int, yOffsetDp: Int) {
        scope.launch {
            updateConfig { current ->
                current.copy(
                    positionMode = PositionMode.FREE,
                    xFraction = xFraction.coerceIn(0f, 1f),
                    yFraction = yFraction.coerceIn(0f, 1f),
                    xOffsetDp = xOffsetDp,
                    yOffsetDp = yOffsetDp
                )
            }
        }
    }
}
