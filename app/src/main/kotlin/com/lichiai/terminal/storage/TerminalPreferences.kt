package com.lichiai.terminal.storage

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lichiai.terminal.core.TerminalResourceGovernor
import com.lichiai.terminal.model.TerminalSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.terminalPrefsDataStore by preferencesDataStore(name = "lichi_terminal_preferences")

class TerminalPreferences(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    private val keySettings = stringPreferencesKey("terminal_settings")
    private val keyHistory = stringPreferencesKey("terminal_command_history")

    val settingsFlow: Flow<TerminalSettings> = context.terminalPrefsDataStore.data.map { prefs ->
        val raw = prefs[keySettings] ?: ""
        if (raw.isBlank()) {
            TerminalSettings()
        } else {
            try {
                json.decodeFromString<TerminalSettings>(raw)
            } catch (_: Exception) {
                TerminalSettings()
            }
        }
    }

    suspend fun updateSettings(settings: TerminalSettings) {
        context.terminalPrefsDataStore.edit { prefs ->
            prefs[keySettings] = json.encodeToString(settings)
        }
    }

    val historyFlow: Flow<List<String>> = context.terminalPrefsDataStore.data.map { prefs ->
        val raw = prefs[keyHistory] ?: ""
        if (raw.isBlank()) emptyList()
        else {
            try { json.decodeFromString<List<String>>(raw) } catch (_: Exception) { emptyList() }
        }
    }

    suspend fun appendCommandHistory(command: String) {
        val trimmed = command.trim()
        if (trimmed.isBlank()) return
        val sanitized = TerminalResourceGovernor.redactSecrets(trimmed)

        context.terminalPrefsDataStore.edit { prefs ->
            val raw = prefs[keyHistory] ?: ""
            val list = if (raw.isBlank()) mutableListOf() else {
                try { json.decodeFromString<List<String>>(raw).toMutableList() } catch (_: Exception) { mutableListOf() }
            }
            list.remove(sanitized)
            list.add(0, sanitized)
            while (list.size > 200) {
                list.removeAt(list.size - 1)
            }
            prefs[keyHistory] = json.encodeToString(list)
        }
    }

    suspend fun clearHistory() {
        context.terminalPrefsDataStore.edit { prefs ->
            prefs.remove(keyHistory)
        }
    }
}
