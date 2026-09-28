package com.lichiai.browser.tabs

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.net.URI
import java.util.Locale
import java.util.UUID

enum class TabCategory(val displayName: String, val icon: String) {
    ALL("All", "🌐"),
    RESEARCH("Research", "📚"),
    SHOPPING("Shopping", "🛍️"),
    WORK("Work", "💼"),
    MEDIA("Media", "🎬"),
    GENERAL("General", "📄")
}

data class BrowserTab(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "New Tab",
    val url: String = "about:blank",
    val faviconUrl: String? = null,
    val isIncognito: Boolean = false,
    val isLoading: Boolean = false,
    val progress: Int = 0,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val isDesktopMode: Boolean = false,
    val category: TabCategory = TabCategory.GENERAL
) {
    val domain: String
        get() {
            if (url.isBlank() || url == "about:blank") return "Start Page"
            return try {
                val uri = URI(url)
                val host = uri.host ?: ""
                if (host.startsWith("www.")) host.substring(4) else host.ifBlank { url }
            } catch (_: Exception) {
                url
            }
        }
}

/**
 * Manages all open browser tabs (normal & private incognito), tab switching, grouping, and state.
 */
class BrowserTabManager {
    private val _tabs = MutableStateFlow<List<BrowserTab>>(
        listOf(BrowserTab(title = "Start Page", url = "about:blank"))
    )
    val tabs: StateFlow<List<BrowserTab>> = _tabs.asStateFlow()

    private val _activeTabId = MutableStateFlow<String>(_tabs.value.first().id)
    val activeTabId: StateFlow<String> = _activeTabId.asStateFlow()

    private val _selectedCategory = MutableStateFlow(TabCategory.ALL)
    val selectedCategory: StateFlow<TabCategory> = _selectedCategory.asStateFlow()

    val activeTab: BrowserTab?
        get() = _tabs.value.firstOrNull { it.id == _activeTabId.value }

    fun setCategoryFilter(category: TabCategory) {
        _selectedCategory.value = category
    }

    fun inferCategory(url: String, title: String): TabCategory {
        val lowerUrl = url.lowercase(Locale.ROOT)
        val lowerTitle = title.lowercase(Locale.ROOT)
        return when {
            lowerUrl.contains("amazon") || lowerUrl.contains("flipkart") || lowerUrl.contains("ebay") ||
                    lowerUrl.contains("store") || lowerUrl.contains("shop") || lowerTitle.contains("price") ||
                    lowerTitle.contains("buy") || lowerTitle.contains("cart") -> TabCategory.SHOPPING

            lowerUrl.contains("wikipedia") || lowerUrl.contains("arxiv") || lowerUrl.contains("scholar") ||
                    lowerUrl.contains("docs") || lowerUrl.contains("medium") || lowerUrl.contains("github") ||
                    lowerTitle.contains("research") || lowerTitle.contains("documentation") -> TabCategory.RESEARCH

            lowerUrl.contains("youtube") || lowerUrl.contains("netflix") || lowerUrl.contains("spotify") ||
                    lowerUrl.contains("twitch") || lowerUrl.contains("vimeo") || lowerTitle.contains("video") ||
                    lowerTitle.contains("movie") || lowerTitle.contains("music") -> TabCategory.MEDIA

            lowerUrl.contains("jira") || lowerUrl.contains("slack") || lowerUrl.contains("confluence") ||
                    lowerUrl.contains("trello") || lowerUrl.contains("notion") || lowerUrl.contains("figma") ||
                    lowerUrl.contains("linear") -> TabCategory.WORK

            else -> TabCategory.GENERAL
        }
    }

    fun createTab(url: String = "about:blank", isIncognito: Boolean = false): BrowserTab {
        val title = if (url == "about:blank") (if (isIncognito) "Private Tab" else "New Tab") else url
        val category = if (url != "about:blank") inferCategory(url, title) else TabCategory.GENERAL
        val newTab = BrowserTab(
            title = title,
            url = url,
            isIncognito = isIncognito,
            category = category
        )
        _tabs.update { it + newTab }
        _activeTabId.value = newTab.id
        return newTab
    }

    fun closeTab(tabId: String) {
        _tabs.update { current ->
            val remaining = current.filterNot { it.id == tabId }
            if (remaining.isEmpty()) {
                val fallback = BrowserTab(title = "New Tab", url = "about:blank")
                _activeTabId.value = fallback.id
                listOf(fallback)
            } else {
                if (_activeTabId.value == tabId) {
                    _activeTabId.value = remaining.last().id
                }
                remaining
            }
        }
    }

    fun selectTab(tabId: String) {
        if (_tabs.value.any { it.id == tabId }) {
            _activeTabId.value = tabId
        }
    }

    fun updateTab(tabId: String, transform: (BrowserTab) -> BrowserTab) {
        _tabs.update { current ->
            current.map { tab ->
                if (tab.id == tabId) {
                    val updated = transform(tab)
                    val category = if (updated.category == TabCategory.GENERAL && updated.url != "about:blank") {
                        inferCategory(updated.url, updated.title)
                    } else updated.category
                    updated.copy(category = category)
                } else tab
            }
        }
    }

    fun toggleDesktopMode(tabId: String): Boolean {
        var newState = false
        _tabs.update { current ->
            current.map {
                if (it.id == tabId) {
                    newState = !it.isDesktopMode
                    it.copy(isDesktopMode = newState)
                } else it
            }
        }
        return newState
    }
}
