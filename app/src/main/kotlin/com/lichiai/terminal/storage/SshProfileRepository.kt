package com.lichiai.terminal.storage

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lichiai.terminal.model.SshProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.sshProfilesDataStore by preferencesDataStore(name = "lichi_ssh_profiles")

class SshProfileRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val keyProfiles = stringPreferencesKey("saved_ssh_profiles")

    val profilesFlow: Flow<List<SshProfile>> = context.sshProfilesDataStore.data.map { prefs ->
        val raw = prefs[keyProfiles] ?: ""
        if (raw.isBlank()) {
            emptyList()
        } else {
            try {
                json.decodeFromString<List<SshProfile>>(raw)
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    suspend fun saveProfile(profile: SshProfile) {
        context.sshProfilesDataStore.edit { prefs ->
            val raw = prefs[keyProfiles] ?: ""
            val list = if (raw.isBlank()) mutableListOf() else {
                try { json.decodeFromString<List<SshProfile>>(raw).toMutableList() } catch (_: Exception) { mutableListOf() }
            }
            list.removeAll { it.id == profile.id }
            list.add(0, profile)
            prefs[keyProfiles] = json.encodeToString(list)
        }
    }

    suspend fun deleteProfile(profileId: String) {
        context.sshProfilesDataStore.edit { prefs ->
            val raw = prefs[keyProfiles] ?: ""
            if (raw.isNotBlank()) {
                try {
                    val list = json.decodeFromString<List<SshProfile>>(raw).toMutableList()
                    list.removeAll { it.id == profileId }
                    prefs[keyProfiles] = json.encodeToString(list)
                } catch (_: Exception) {}
            }
        }
    }
}
