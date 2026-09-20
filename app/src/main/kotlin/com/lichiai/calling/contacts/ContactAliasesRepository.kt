package com.lichiai.calling.contacts

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.aliasDataStore by preferencesDataStore(name = "call_aliases_pref")

class ContactAliasesRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }
    private val keyAliases = stringPreferencesKey("custom_contact_aliases_v1")

    val aliasesFlow: Flow<Map<String, String>> = context.aliasDataStore.data.map { prefs ->
        val raw = prefs[keyAliases]
        if (raw.isNullOrBlank()) {
            defaultAliases
        } else {
            runCatching {
                json.decodeFromString<Map<String, String>>(raw)
            }.getOrDefault(defaultAliases)
        }
    }

    suspend fun getAliases(): Map<String, String> {
        return aliasesFlow.first()
    }

    suspend fun setAlias(aliasTerm: String, contactTargetName: String) {
        val normAlias = ContactNormalizer.normalize(aliasTerm)
        val current = getAliases().toMutableMap()
        current[normAlias] = contactTargetName.trim()
        val encoded = json.encodeToString(current)
        context.aliasDataStore.edit { prefs ->
            prefs[keyAliases] = encoded
        }
    }

    suspend fun removeAlias(aliasTerm: String) {
        val normAlias = ContactNormalizer.normalize(aliasTerm)
        val current = getAliases().toMutableMap()
        current.remove(normAlias)
        val encoded = json.encodeToString(current)
        context.aliasDataStore.edit { prefs ->
            prefs[keyAliases] = encoded
        }
    }

    companion object {
        val defaultAliases: Map<String, String> = mapOf(
            "bhaiya" to "bhaiya",
            "bhai" to "bhai",
            "brother" to "brother",
            "mummy" to "mummy",
            "mom" to "mom",
            "mother" to "mother",
            "maa" to "maa",
            "papa" to "papa",
            "dad" to "dad",
            "father" to "father",
            "pitaji" to "pitaji",
            "didi" to "didi",
            "sister" to "sister",
            "boss" to "boss",
            "wife" to "wife",
            "husband" to "husband"
        )
    }
}
