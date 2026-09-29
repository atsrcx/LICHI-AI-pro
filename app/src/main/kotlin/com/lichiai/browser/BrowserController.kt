package com.lichiai.browser

import android.content.Context
import com.lichiai.browser.agent.BrowserAgent
import com.lichiai.browser.api.BrowserCapabilityAPI
import com.lichiai.browser.api.ScrollDirection
import com.lichiai.browser.context.BrowserMemory
import com.lichiai.browser.context.BrowserTaskContext
import com.lichiai.browser.downloads.BrowserDownloadManager
import com.lichiai.browser.engine.BrowserEngine
import com.lichiai.browser.engine.ChromiumWebViewEngine
import com.lichiai.browser.events.BrowserActionLog
import com.lichiai.browser.events.BrowserEvent
import com.lichiai.browser.events.BrowserEventBus
import com.lichiai.browser.executor.BrowserExecutor
import com.lichiai.browser.llm.BrowserLLMClient
import com.lichiai.browser.permissions.BrowserPermissionManager
import com.lichiai.browser.provider.BrowserProviderManager
import com.lichiai.browser.recovery.BrowserRecovery
import com.lichiai.browser.storage.BrowserStorageManager
import com.lichiai.browser.tabs.BrowserTab
import com.lichiai.browser.tabs.BrowserTabManager
import com.lichiai.browser.tools.BrowserToolRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Unified Controller and Session Coordinator for Lichi Browser.
 * Implements BrowserCapabilityAPI and maintains complete architectural isolation.
 */
class BrowserController(
    val context: Context,
    val coroutineScope: CoroutineScope
) : BrowserCapabilityAPI {

    val eventBus = BrowserEventBus()
    val actionLog = BrowserActionLog()
    val tabManager = BrowserTabManager()
    val storageManager = BrowserStorageManager(context)
    val downloadManager = BrowserDownloadManager(context)
    val permissionManager = BrowserPermissionManager()
    val providerManager = BrowserProviderManager(context)
    val memory = BrowserMemory(context)
    val inspectionCoordinator = com.lichiai.browser.inspection.BrowserInspectionCoordinator(context, coroutineScope, eventBus)

    private val llmClient = BrowserLLMClient(providerManager)
    val toolRegistry = BrowserToolRegistry(this)
    private val recovery = BrowserRecovery(this, eventBus)
    private val executor = BrowserExecutor(toolRegistry, this, eventBus, actionLog, recovery)

    val agent = BrowserAgent(
        capabilityApi = this,
        executor = executor,
        llmClient = llmClient,
        storageManager = storageManager,
        memory = memory,
        actionLog = actionLog,
        eventBus = eventBus,
        scope = coroutineScope
    )

    // Map of tabId -> ChromiumWebViewEngine
    private val engines = mutableMapOf<String, ChromiumWebViewEngine>()

    private val _activeEngine = MutableStateFlow<ChromiumWebViewEngine?>(null)
    val activeEngine: StateFlow<ChromiumWebViewEngine?> = _activeEngine.asStateFlow()

    init {
        coroutineScope.launch {
            storageManager.initialize()
            providerManager.initialize()
            memory.initialize()

            // Initialize default tab engine on Main thread
            withContext(Dispatchers.Main) {
                val initialTab = tabManager.activeTab ?: tabManager.createTab()
                val eng = getOrCreateEngine(initialTab)
                _activeEngine.value = eng
            }

            // Monitor active tab changes
            tabManager.activeTabId.collect { tabId ->
                val tab = tabManager.tabs.value.firstOrNull { it.id == tabId }
                if (tab != null) {
                    withContext(Dispatchers.Main) {
                        val eng = getOrCreateEngine(tab)
                        _activeEngine.value = eng
                    }
                }
            }
        }

        // Listen for browser events to update tabs and storage
        coroutineScope.launch {
            eventBus.events.collect { ev ->
                when (ev) {
                    is BrowserEvent.PageLoaded -> {
                        val activeId = tabManager.activeTabId.value
                        val currentTab = tabManager.activeTab
                        tabManager.updateTab(activeId) {
                            it.copy(
                                title = ev.title.ifBlank { ev.url },
                                url = ev.url,
                                isLoading = false,
                                progress = 100,
                                canGoBack = _activeEngine.value?.canGoBack() ?: false,
                                canGoForward = _activeEngine.value?.canGoForward() ?: false
                            )
                        }
                        storageManager.addHistory(ev.title, ev.url, currentTab?.isIncognito ?: false)
                    }
                    is BrowserEvent.NavigationStarted -> {
                        val activeId = tabManager.activeTabId.value
                        tabManager.updateTab(activeId) {
                            it.copy(url = ev.url, isLoading = true, progress = 10)
                        }
                    }
                    is BrowserEvent.PageLoadProgress -> {
                        val activeId = tabManager.activeTabId.value
                        tabManager.updateTab(activeId) {
                            it.copy(progress = ev.progress, isLoading = ev.progress < 100)
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    @androidx.annotation.MainThread
    fun getOrCreateEngine(tab: BrowserTab): ChromiumWebViewEngine {
        val existing = engines[tab.id]
        if (existing != null) {
            _activeEngine.value = existing
            return existing
        }

        val newEngine = ChromiumWebViewEngine(
            context = context,
            eventBus = eventBus,
            permissionManager = permissionManager,
            isIncognito = tab.isIncognito,
            instrumentationHub = inspectionCoordinator.hub
        ).apply {
            webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, contentLength ->
                inspectionCoordinator.hub.onDownloadTriggered(url, userAgent, contentDisposition, mimetype, contentLength)
                downloadManager.startDownload(url, userAgent, contentDisposition, mimetype, contentLength)
            }
        }

        engines[tab.id] = newEngine
        _activeEngine.value = newEngine

        if (tab.url != "about:blank" && tab.url.isNotBlank()) {
            newEngine.loadUrl(tab.url)
        }
        return newEngine
    }

    // --- BrowserCapabilityAPI Implementation ---

    override suspend fun openBrowser(): Boolean {
        eventBus.emit(BrowserEvent.BrowserOpened)
        return true
    }

    override suspend fun navigate(url: String): Boolean = withContext(Dispatchers.Main) {
        val activeId = tabManager.activeTabId.value
        val eng = _activeEngine.value
            ?: (tabManager.activeTab?.let { getOrCreateEngine(it) })?.also { _activeEngine.value = it }
            ?: return@withContext false
        tabManager.updateTab(activeId) {
            it.copy(url = url, isLoading = true, progress = 15)
        }
        eng.loadUrl(url)
        true
    }

    override suspend fun search(query: String, engine: String?): Boolean = withContext(Dispatchers.Main) {
        val searchUrl = storageManager.buildSearchUrl(query, engine)
        eventBus.emit(BrowserEvent.SearchStarted(query, engine ?: "default"))
        navigate(searchUrl)
    }

    override suspend fun clickCandidate(index: Int, targetUrl: String?): Boolean = withContext(Dispatchers.Main) {
        val eng = _activeEngine.value ?: return@withContext false
        val candidates = if (targetUrl.isNullOrBlank()) eng.extractCandidateLinks() else emptyList()
        val candidate = candidates.firstOrNull { it.index == index }
        val finalUrl = targetUrl ?: candidate?.url

        val clicked = eng.clickCandidateByIndex(index, finalUrl)
        if (!finalUrl.isNullOrBlank() && finalUrl.startsWith("http")) {
            kotlinx.coroutines.delay(400)
            if (eng.getUrl() != finalUrl) {
                navigate(finalUrl)
                return@withContext true
            }
        }
        clicked
    }

    override suspend fun clickElement(index: Int): Boolean = withContext(Dispatchers.Main) {
        val eng = _activeEngine.value ?: return@withContext false
        eng.clickElementByIndex(index)
    }

    override suspend fun clickSelector(cssSelector: String): Boolean = withContext(Dispatchers.Main) {
        val eng = _activeEngine.value ?: return@withContext false
        eng.clickCandidateByText(cssSelector)
    }

    override suspend fun typeText(selector: String, text: String): Boolean = withContext(Dispatchers.Main) {
        val eng = _activeEngine.value ?: return@withContext false
        eng.typeText(index = null, selector = selector, text = text, submit = false)
    }

    override suspend fun typeText(index: Int?, selector: String?, text: String, submit: Boolean): Boolean = withContext(Dispatchers.Main) {
        val eng = _activeEngine.value ?: return@withContext false
        eng.typeText(index = index, selector = selector, text = text, submit = submit)
    }

    override suspend fun scroll(direction: ScrollDirection, amount: Int): Boolean = withContext(Dispatchers.Main) {
        val eng = _activeEngine.value ?: return@withContext false
        eng.scroll(direction, amount)
    }

    override suspend fun goBack(): Boolean = withContext(Dispatchers.Main) {
        val eng = _activeEngine.value ?: return@withContext false
        eng.goBack()
    }

    override suspend fun goForward(): Boolean = withContext(Dispatchers.Main) {
        val eng = _activeEngine.value ?: return@withContext false
        eng.goForward()
    }

    override suspend fun reload(): Boolean = withContext(Dispatchers.Main) {
        val eng = _activeEngine.value ?: return@withContext false
        eng.reload()
        true
    }

    override suspend fun openTab(url: String?, isIncognito: Boolean): String = withContext(Dispatchers.Main) {
        val targetUrl = url ?: "about:blank"
        val newTab = tabManager.createTab(targetUrl, isIncognito)
        val eng = getOrCreateEngine(newTab)
        if (targetUrl != "about:blank") {
            eng.loadUrl(targetUrl)
        }
        eventBus.emit(BrowserEvent.TabCreated(newTab.id, isIncognito))
        newTab.id
    }

    override suspend fun closeTab(tabId: String): Boolean = withContext(Dispatchers.Main) {
        val engineToDestroy = engines.remove(tabId)
        engineToDestroy?.destroy()
        tabManager.closeTab(tabId)
        eventBus.emit(BrowserEvent.TabClosed(tabId))
        true
    }

    override suspend fun switchTab(tabId: String): Boolean = withContext(Dispatchers.Main) {
        tabManager.selectTab(tabId)
        val tab = tabManager.tabs.value.firstOrNull { it.id == tabId }
        if (tab != null) {
            val eng = getOrCreateEngine(tab)
            _activeEngine.value = eng
            eventBus.emit(BrowserEvent.TabChanged(tabId, tab.url))
            true
        } else false
    }

    override suspend fun getPageContext(): BrowserTaskContext = withContext(Dispatchers.Main) {
        val eng = _activeEngine.value
        val currentUrl = eng?.getUrl() ?: "about:blank"
        val currentTitle = eng?.getTitle() ?: ""
        val candidates = eng?.extractCandidateLinks() ?: emptyList()
        val interactiveEls = eng?.extractInteractiveElements() ?: emptyList()
        val pageState = eng?.getPageState() ?: com.lichiai.browser.context.BrowserPageState()
        val prices = eng?.extractPrices() ?: emptyList()
        val tables = eng?.extractTables() ?: emptyList()
        val snapshotId = java.util.UUID.randomUUID().toString()

        BrowserTaskContext(
            taskId = "",
            perceptionGenerationId = snapshotId,
            perceptionTimestamp = System.currentTimeMillis(),
            currentTabId = tabManager.activeTabId.value,
            currentUrl = currentUrl,
            currentTitle = currentTitle,
            extractedCandidates = candidates,
            interactiveElements = interactiveEls,
            pageMetrics = pageState,
            candidatePrices = prices,
            extractedTables = tables,
            openTabs = tabManager.tabs.value.map {
                com.lichiai.browser.context.BrowserTabSummary(
                    tabId = it.id,
                    title = it.title,
                    url = it.url,
                    isIncognito = it.isIncognito,
                    isActive = it.id == tabManager.activeTabId.value,
                    category = it.category.displayName
                )
            }
        )
    }

    override suspend fun getInteractiveElements(): List<com.lichiai.browser.context.BrowserInteractiveElement> = withContext(Dispatchers.Main) {
        val eng = _activeEngine.value
        eng?.extractInteractiveElements() ?: emptyList()
    }

    override suspend fun extractPageSummary(): String = withContext(Dispatchers.Main) {
        val eng = _activeEngine.value
        eng?.extractTextSnippet() ?: ""
    }

    override suspend fun extractPrices(): List<String> = withContext(Dispatchers.Main) {
        val eng = _activeEngine.value
        eng?.extractPrices() ?: emptyList()
    }

    override suspend fun extractTables(): List<com.lichiai.browser.context.BrowserTableData> = withContext(Dispatchers.Main) {
        val eng = _activeEngine.value
        eng?.extractTables() ?: emptyList()
    }

    override suspend fun highlightElement(index: Int): Boolean = withContext(Dispatchers.Main) {
        val eng = _activeEngine.value
        eng?.highlightElement(index) ?: false
    }

    override suspend fun captureScreenshot(): android.graphics.Bitmap? = withContext(Dispatchers.Main) {
        val eng = _activeEngine.value
        eng?.captureScreenshot()
    }

    override suspend fun stopTask(): Boolean {
        agent.stopActiveTask()
        return true
    }

    // --- DevTools & Browser Inspection Capabilities ---

    suspend fun inspectCurrentPage(
        mode: com.lichiai.browser.inspection.model.InspectionMode = com.lichiai.browser.inspection.model.InspectionMode.FULL_INSPECTION,
        taskId: String = "",
        messageId: String = "",
        conversationId: String = ""
    ): com.lichiai.browser.inspection.model.ComprehensiveInspectionReport = withContext(Dispatchers.Main) {
        val activeTab = tabManager.activeTab
        val tabId = activeTab?.id ?: tabManager.activeTabId.value
        val eng = _activeEngine.value ?: (activeTab?.let { getOrCreateEngine(it) })
        inspectionCoordinator.inspectCurrentPage(
            engine = eng,
            tabId = tabId,
            mode = mode,
            taskId = taskId,
            messageId = messageId,
            conversationId = conversationId
        )
    }

    suspend fun discoverEndpoints(): List<com.lichiai.browser.inspection.endpoint.EndpointRecord> = withContext(Dispatchers.Main) {
        val eng = _activeEngine.value
        inspectionCoordinator.discoverEndpoints(eng)
    }

    suspend fun discoverDownloads(): List<com.lichiai.browser.inspection.download.DownloadCandidate> = withContext(Dispatchers.Main) {
        val eng = _activeEngine.value
        inspectionCoordinator.discoverDownloads(eng)
    }

    suspend fun discoverResources(): List<com.lichiai.browser.inspection.resources.ResourceRecord> = withContext(Dispatchers.Main) {
        val eng = _activeEngine.value
        inspectionCoordinator.discoverResources(eng)
    }

    suspend fun discoverLinks(): List<com.lichiai.browser.inspection.resources.DiscoveredUrlRecord> = withContext(Dispatchers.Main) {
        val eng = _activeEngine.value
        inspectionCoordinator.discoverLinks(eng)
    }

    suspend fun analyzeNetwork(): com.lichiai.browser.inspection.network.NetworkAnalysisReport = withContext(Dispatchers.Main) {
        inspectionCoordinator.analyzeNetwork()
    }
}

