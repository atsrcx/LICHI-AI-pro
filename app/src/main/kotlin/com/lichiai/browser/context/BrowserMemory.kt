package com.lichiai.browser.context

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class BrowserMemoryRecord(
    val key: String,
    val value: String,
    val domain: String? = null,
    val successCount: Int = 1,
    val lastUsed: Long = System.currentTimeMillis()
)

/**
 * Isolated browser-specific memory.
 * Stores navigation patterns, query shortcuts, and site hints.
 * Completely separate from Lichi device-agent memory repository.
 */
class BrowserMemory(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val memoryFile = File(File(context.filesDir, "browser_data").apply { mkdirs() }, "browser_memory.json")

    private val _records = MutableStateFlow<Map<String, BrowserMemoryRecord>>(emptyMap())
    val records: StateFlow<Map<String, BrowserMemoryRecord>> = _records.asStateFlow()

    suspend fun initialize() = withContext(Dispatchers.IO) {
        try {
            if (memoryFile.exists()) {
                val list = json.decodeFromString<List<BrowserMemoryRecord>>(memoryFile.readText())
                _records.value = list.associateBy { it.key }
            }
        } catch (_: Exception) {}
    }

    suspend fun recordSuccessfulAction(key: String, value: String, domain: String? = null) = withContext(Dispatchers.IO) {
        val current = _records.value[key]
        val updated = BrowserMemoryRecord(
            key = key,
            value = value,
            domain = domain,
            successCount = (current?.successCount ?: 0) + 1,
            lastUsed = System.currentTimeMillis()
        )
        val map = _records.value + (key to updated)
        _records.value = map
        try {
            memoryFile.writeText(json.encodeToString(map.values.toList()))
        } catch (_: Exception) {}
    }

    fun getMemory(key: String): BrowserMemoryRecord? = _records.value[key]

    suspend fun clear() = withContext(Dispatchers.IO) {
        _records.value = emptyMap()
        try {
            if (memoryFile.exists()) memoryFile.delete()
        } catch (_: Exception) {}
    }
}
