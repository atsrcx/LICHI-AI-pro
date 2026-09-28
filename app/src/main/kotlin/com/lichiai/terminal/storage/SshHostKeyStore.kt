package com.lichiai.terminal.storage

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lichiai.terminal.model.SshHostKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.hostKeyDataStore by preferencesDataStore(name = "lichi_ssh_host_keys")

class SshHostKeyStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val keyKnownHosts = stringPreferencesKey("known_host_keys")

    suspend fun getAcceptedKeys(): List<SshHostKey> {
        val raw = context.hostKeyDataStore.data.map { it[keyKnownHosts] ?: "" }.first()
        if (raw.isBlank()) return emptyList()
        return try {
            json.decodeFromString<List<SshHostKey>>(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun findHostKey(hostname: String, port: Int): SshHostKey? {
        val keys = getAcceptedKeys()
        return keys.firstOrNull { it.hostname.equals(hostname, ignoreCase = true) && it.port == port }
    }

    suspend fun saveHostKey(hostKey: SshHostKey) {
        val current = getAcceptedKeys().toMutableList()
        current.removeAll { it.hostname.equals(hostKey.hostname, ignoreCase = true) && it.port == hostKey.port }
        current.add(hostKey)
        val serialized = json.encodeToString(current)
        context.hostKeyDataStore.edit { prefs ->
            prefs[keyKnownHosts] = serialized
        }
    }

    suspend fun removeHostKey(hostname: String, port: Int) {
        val current = getAcceptedKeys().toMutableList()
        current.removeAll { it.hostname.equals(hostname, ignoreCase = true) && it.port == port }
        val serialized = json.encodeToString(current)
        context.hostKeyDataStore.edit { prefs ->
            prefs[keyKnownHosts] = serialized
        }
    }
}
