package com.lichiai.browser.storage

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
import java.util.UUID

@Serializable
data class BrowserBookmark(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val url: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Serializable
data class BrowserHistoryItem(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val url: String,
    val visitedAt: Long = System.currentTimeMillis()
)

@Serializable
data class BrowserSettings(
    val searchEngineUrl: String = "https://www.google.com/search?q=",
    val searchEngineName: String = "Google",
    val javaScriptEnabled: Boolean = true,
    val domStorageEnabled: Boolean = true,
    val blockPopups: Boolean = true,
    val desktopModeDefault: Boolean = false,
    val clearCookiesOnExit: Boolean = false,
    val agentAutoSearch: Boolean = true
)

/**
 * Isolated storage for Browser bookmarks, history, and browser configuration.
 * Kept strictly inside browser_data/ directory.
 */
class BrowserStorageManager(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val browserDir = File(context.filesDir, "browser_data").apply { mkdirs() }

    private val bookmarksFile = File(browserDir, "bookmarks.json")
    private val historyFile = File(browserDir, "history.json")
    private val settingsFile = File(browserDir, "settings.json")

    private val _bookmarks = MutableStateFlow<List<BrowserBookmark>>(emptyList())
    val bookmarks: StateFlow<List<BrowserBookmark>> = _bookmarks.asStateFlow()

    private val _history = MutableStateFlow<List<BrowserHistoryItem>>(emptyList())
    val history: StateFlow<List<BrowserHistoryItem>> = _history.asStateFlow()

    private val _settings = MutableStateFlow(BrowserSettings())
    val settings: StateFlow<BrowserSettings> = _settings.asStateFlow()

    suspend fun initialize() = withContext(Dispatchers.IO) {
        try {
            if (bookmarksFile.exists()) {
                val text = bookmarksFile.readText()
                _bookmarks.value = json.decodeFromString(text)
            }
            if (historyFile.exists()) {
                val text = historyFile.readText()
                _history.value = json.decodeFromString(text)
            }
            if (settingsFile.exists()) {
                val text = settingsFile.readText()
                _settings.value = json.decodeFromString(text)
            }
        } catch (_: Exception) {}
    }

    suspend fun addBookmark(title: String, url: String) = withContext(Dispatchers.IO) {
        if (url.isBlank() || url == "about:blank") return@withContext
        val list = _bookmarks.value.filterNot { it.url == url }
        val updated = listOf(BrowserBookmark(title = title.ifBlank { url }, url = url)) + list
        _bookmarks.value = updated
        try {
            bookmarksFile.writeText(json.encodeToString(updated))
        } catch (_: Exception) {}
    }

    suspend fun removeBookmark(id: String) = withContext(Dispatchers.IO) {
        val updated = _bookmarks.value.filterNot { it.id == id }
        _bookmarks.value = updated
        try {
            bookmarksFile.writeText(json.encodeToString(updated))
        } catch (_: Exception) {}
    }

    suspend fun addHistory(title: String, url: String, isIncognito: Boolean) = withContext(Dispatchers.IO) {
        if (isIncognito || url.isBlank() || url == "about:blank") return@withContext
        val list = _history.value.filterNot { it.url == url }
        val updated = (listOf(BrowserHistoryItem(title = title.ifBlank { url }, url = url)) + list).take(200)
        _history.value = updated
        try {
            historyFile.writeText(json.encodeToString(updated))
        } catch (_: Exception) {}
    }

    suspend fun clearHistory() = withContext(Dispatchers.IO) {
        _history.value = emptyList()
        try {
            if (historyFile.exists()) historyFile.delete()
        } catch (_: Exception) {}
    }

    suspend fun updateSettings(newSettings: BrowserSettings) = withContext(Dispatchers.IO) {
        _settings.value = newSettings
        try {
            settingsFile.writeText(json.encodeToString(newSettings))
        } catch (_: Exception) {}
    }

    fun buildSearchUrl(query: String, explicitEngine: String? = null): String {
        val engine = explicitEngine?.lowercase()
        val template = when {
            engine == "google" -> "https://www.google.com/search?q="
            engine == "duckduckgo" || engine == "ddg" -> "https://duckduckgo.com/?q="
            engine == "bing" -> "https://www.bing.com/search?q="
            engine == "youtube" -> "https://www.youtube.com/results?search_query="
            else -> _settings.value.searchEngineUrl
        }
        return template + java.net.URLEncoder.encode(query, "UTF-8")
    }
}
