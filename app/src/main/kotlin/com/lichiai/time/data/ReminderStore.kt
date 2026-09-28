package com.lichiai.time.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lichiai.time.model.ReminderItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.remindersDataStore: DataStore<Preferences> by preferencesDataStore(name = "lichi_time_reminders")

class ReminderStore(private val context: Context) {
    private val key = stringPreferencesKey("reminders_json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val remindersFlow: Flow<List<ReminderItem>> =
        context.remindersDataStore.data.map { prefs ->
            val raw = prefs[key] ?: return@map emptyList()
            runCatching {
                json.decodeFromString(ListSerializer(ReminderItem.serializer()), raw)
            }.getOrDefault(emptyList())
        }

    suspend fun snapshot(): List<ReminderItem> = remindersFlow.first()

    suspend fun save(list: List<ReminderItem>) {
        context.remindersDataStore.edit { prefs ->
            prefs[key] = json.encodeToString(ListSerializer(ReminderItem.serializer()), list)
        }
    }

    suspend fun upsert(item: ReminderItem) {
        val list = snapshot().toMutableList()
        val idx = list.indexOfFirst { it.id == item.id }
        if (idx >= 0) {
            list[idx] = item.copy(updatedAtEpochMs = System.currentTimeMillis())
        } else {
            list.add(item)
        }
        save(list)
    }

    suspend fun delete(id: String) {
        save(snapshot().filterNot { it.id == id })
    }
}
