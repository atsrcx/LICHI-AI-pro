package com.lichiai.browser.ui

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NightlightRound
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PriceCheck
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.lichiai.agentvision.renderer.AgentVisionOverlay
import com.lichiai.agentvision.settings.AgentVisionSettingsRepository
import com.lichiai.agentvision.telemetry.AgentVisionTelemetryHub
import com.lichiai.browser.BrowserController
import kotlinx.coroutines.launch

@Composable
fun BrowserScreen(
    controller: BrowserController,
    onOpenDrawer: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val tabs by controller.tabManager.tabs.collectAsState()
    val activeTabId by controller.tabManager.activeTabId.collectAsState()
    val activeTab = tabs.firstOrNull { it.id == activeTabId }
    val activeEngine by controller.activeEngine.collectAsState()

    val bookmarks by controller.storageManager.bookmarks.collectAsState()
    val history by controller.storageManager.history.collectAsState()
    val permissionPrompt by controller.permissionManager.currentPrompt.collectAsState()
    val isAgentBusy by controller.agent.isBusy.collectAsState()
    val pendingConfirmation by controller.agent.pendingConfirmation.collectAsState()

    var showTabSheet by remember { mutableStateOf(false) }
    var showMenuSheet by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showAiCopilotSheet by remember { mutableStateOf(false) }
    var showResearchWorkspace by remember { mutableStateOf(false) }
    var showInspectionWorkspace by remember { mutableStateOf(false) }

    val activeInspectionReport by controller.inspectionCoordinator.activeReport.collectAsState()
    val isInspecting by controller.inspectionCoordinator.isInspecting.collectAsState()

    var agentPromptInput by remember { mutableStateOf("") }

    val context = LocalContext.current
    val agentVisionRepo = remember { AgentVisionSettingsRepository.getInstance(context) }
    val agentVisionSettings by agentVisionRepo.settings.collectAsState(initial = com.lichiai.agentvision.model.AgentVisionSettings())
    val activeVisualSession by AgentVisionTelemetryHub.activeSessionState.collectAsState()

    // Intercept back button to navigate WebView back if possible
    BackHandler(enabled = activeTab?.canGoBack == true) {
        scope.launch { controller.goBack() }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White)
            ) {
                // ROW 1: [ ≡ ]  [ ←  → ]  [ 🔍 Search or ask Lichi... ]  [ ⋮ ]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onOpenDrawer,
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            Icons.Default.Menu,
                            contentDescription = "Open Navigation Menu",
                            tint = Color(0xFF1E293B),
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    Spacer(Modifier.width(4.dp))

                    // Capsule with Back and Forward buttons
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = Color(0xFFF1F5F9),
                        border = BorderStroke(0.8.dp, Color(0xFFE2E8F0)),
                        modifier = Modifier.height(36.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = { scope.launch { controller.goBack() } },
                                enabled = activeTab?.canGoBack == true,
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back",
                                    tint = if (activeTab?.canGoBack == true) Color(0xFF1E293B) else Color(0xFF94A3B8),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            IconButton(
                                onClick = { scope.launch { controller.goForward() } },
                                enabled = activeTab?.canGoForward == true,
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = "Forward",
                                    tint = if (activeTab?.canGoForward == true) Color(0xFF1E293B) else Color(0xFF94A3B8),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }

                    Spacer(Modifier.width(8.dp))

                    // Search or ask Lichi Capsule Omnibox
                    var topBarSearch by remember(activeTab?.url) {
                        mutableStateOf(activeTab?.url?.takeIf { it != "about:blank" } ?: "")
                    }

                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = Color(0xFFF1F5F9),
                        border = BorderStroke(0.8.dp, Color(0xFFE2E8F0)),
                        modifier = Modifier
                            .weight(1f)
                            .height(36.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = null,
                                tint = Color(0xFF64748B),
                                modifier = Modifier.size(17.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            BasicTextField(
                                value = topBarSearch,
                                onValueChange = { topBarSearch = it },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyMedium.copy(
                                    color = Color(0xFF0F172A),
                                    fontSize = 13.5.sp
                                ),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(
                                    onSearch = {
                                        if (topBarSearch.isNotBlank()) {
                                            scope.launch {
                                                when (val target = com.lichiai.browser.api.BrowserUrlResolver.resolve(topBarSearch.trim())) {
                                                    is com.lichiai.browser.api.BrowserUrlResolver.ResolvedTarget.DirectUrl -> {
                                                        controller.navigate(target.url)
                                                    }
                                                    is com.lichiai.browser.api.BrowserUrlResolver.ResolvedTarget.SearchQuery -> {
                                                        controller.search(target.query, target.explicitEngine)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                ),
                                modifier = Modifier.weight(1f),
                                decorationBox = { innerTextField ->
                                    if (topBarSearch.isEmpty()) {
                                        Text(
                                            text = "Search or ask Lichi...",
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                color = Color(0xFF94A3B8),
                                                fontSize = 13.5.sp
                                            ),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    innerTextField()
                                }
                            )
                        }
                    }

                    Spacer(Modifier.width(4.dp))

                    // Three Dots Menu
                    IconButton(
                        onClick = { showMenuSheet = true },
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = "Options",
                            tint = Color(0xFF1E293B),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // ROW 2: [ ⌂ ]  [ + Tab ]  [ 🗂 2 ]  [ 🌙 ]  ... [ ✨ Copilot ]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Home
                        IconButton(
                            onClick = { scope.launch { controller.navigate("about:blank") } },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.Default.Home,
                                contentDescription = "Home",
                                tint = Color(0xFF334155),
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        // + Tab (Light blue tinted pill)
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = Color(0xFFE0E7FF),
                            modifier = Modifier.clickable { scope.launch { controller.openTab() } }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Icon(
                                    Icons.Default.Add,
                                    contentDescription = "New Tab",
                                    tint = Color(0xFF2563EB),
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = "Tab",
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        color = Color(0xFF2563EB),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp
                                    )
                                )
                            }
                        }

                        // Tabs Count Badge
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color.Transparent,
                            modifier = Modifier.clickable { showTabSheet = true }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                            ) {
                                Icon(
                                    Icons.Default.Layers,
                                    contentDescription = "Tabs",
                                    tint = Color(0xFF334155),
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Box(
                                    modifier = Modifier
                                        .size(18.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF0F172A)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "${tabs.size}",
                                        color = Color.White,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontSize = 10.5.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    )
                                }
                            }
                        }

                        // Night / Mode Toggle (Moon)
                        IconButton(
                            onClick = {
                                activeTab?.let {
                                    val newMode = controller.tabManager.toggleDesktopMode(it.id)
                                    activeEngine?.setDesktopMode(newMode)
                                }
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.Default.NightlightRound,
                                contentDescription = "Night Mode",
                                tint = Color(0xFF334155),
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        // DevTools / Inspect Button
                        IconButton(
                            onClick = {
                                showInspectionWorkspace = true
                                scope.launch {
                                    runCatching {
                                        controller.inspectCurrentPage()
                                    }.onFailure { err ->
                                        android.util.Log.e("BrowserScreen", "Failed to inspect page", err)
                                    }
                                }
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.Default.BugReport,
                                contentDescription = "Inspect DevTools",
                                tint = Color(0xFF334155),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    // Copilot Pill Button (Dark Navy)
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = Color(0xFF1E293B),
                        modifier = Modifier.clickable { showAiCopilotSheet = true }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                        ) {
                            Icon(
                                Icons.Default.AutoAwesome,
                                contentDescription = "Copilot",
                                tint = Color(0xFF93C5FD),
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "Copilot",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            )
                        }
                    }
                }

                if (activeTab?.isLoading == true) {
                    LinearProgressIndicator(
                        progress = { (activeTab.progress / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.5.dp),
                        color = Color(0xFF2563EB),
                        trackColor = Color.Transparent
                    )
                }
            }
        },
        bottomBar = {
            if (activeTab != null && activeTab.url != "about:blank" && activeTab.url.isNotBlank()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .imePadding()
                        .background(MaterialTheme.colorScheme.surface)
                ) {
                    // Live Browser Agent Activity Card
                    BrowserAgentActivityCard(
                        actionLog = controller.actionLog,
                        isBusy = isAgentBusy,
                        onStop = { controller.agent.stopActiveTask() }
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            val isBlank = activeTab == null || activeTab.url == "about:blank" || activeTab.url.isBlank()

            // 1. Keep active tab's Chromium WebView attached for immediate rendering
            androidx.compose.runtime.key(activeTabId) {
                if (activeEngine != null) {
                    AndroidView(
                        factory = {
                            val wv = activeEngine!!.webView
                            (wv.parent as? ViewGroup)?.removeView(wv)
                            wv.apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                            }
                        },
                        update = { wv ->
                            if (wv.parent == null) {
                                wv.layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // 2. Start Page overlay shown when tab is on about:blank
            if (isBlank) {
                BrowserModernStartPage(
                    onSearch = { query ->
                        scope.launch { controller.search(query) }
                    },
                    onOpenUrl = { url ->
                        scope.launch { controller.navigate(url) }
                    },
                    onOpenCopilot = { showAiCopilotSheet = true },
                    onOpenResearch = { showResearchWorkspace = true },
                    modifier = Modifier.fillMaxSize()
                )
            }

            // 3. Isolated Agent Vision Visualizer Layer
            if (agentVisionSettings.enabled && !isBlank) {
                AgentVisionOverlay(
                    session = activeVisualSession,
                    config = agentVisionSettings.config,
                    showTimelinePanel = true,
                    onStopOrTakeControl = { controller.agent.stopActiveTask() }
                )
            }
        }
    }

    // Modal Tab Sheet
    if (showTabSheet) {
        BrowserTabSheet(
            tabs = tabs,
            activeTabId = activeTabId,
            onSelectTab = { scope.launch { controller.switchTab(it) } },
            onCloseTab = { scope.launch { controller.closeTab(it) } },
            onNewTab = { scope.launch { controller.openTab() } },
            onNewIncognitoTab = { scope.launch { controller.openTab(isIncognito = true) } },
            onDismiss = { showTabSheet = false }
        )
    }

    // Modal Options Menu Sheet
    if (showMenuSheet) {
        BrowserMenuSheet(
            activeTab = activeTab,
            bookmarks = bookmarks,
            history = history,
            onToggleDesktop = {
                activeTab?.let {
                    val newMode = controller.tabManager.toggleDesktopMode(it.id)
                    activeEngine?.setDesktopMode(newMode)
                }
            },
            onAddBookmark = {
                activeTab?.let {
                    scope.launch {
                        controller.storageManager.addBookmark(it.title, it.url)
                    }
                }
            },
            onOpenUrl = { scope.launch { controller.navigate(it) } },
            onDeleteBookmark = { scope.launch { controller.storageManager.removeBookmark(it) } },
            onClearHistory = { scope.launch { controller.storageManager.clearHistory() } },
            onClearCache = { activeEngine?.clearCacheAndCookies() },
            onOpenSettings = { showSettingsDialog = true },
            onOpenInspection = {
                showInspectionWorkspace = true
                scope.launch {
                    runCatching {
                        controller.inspectCurrentPage()
                    }.onFailure { err ->
                        android.util.Log.e("BrowserScreen", "Failed to inspect page from menu", err)
                    }
                }
            },
            onDismiss = { showMenuSheet = false }
        )
    }

    // AI Copilot Sheet
    if (showAiCopilotSheet) {
        BrowserAiCopilotSheet(
            activeTab = activeTab,
            isAgentBusy = isAgentBusy,
            onExecuteAction = { prompt ->
                controller.agent.submitInstruction(prompt)
            },
            onDismiss = { showAiCopilotSheet = false }
        )
    }

    // Research Workspace Overlay
    if (showResearchWorkspace) {
        var activeContext by remember { mutableStateOf<com.lichiai.browser.context.BrowserTaskContext?>(null) }
        androidx.compose.runtime.LaunchedEffect(Unit) {
            activeContext = controller.getPageContext()
        }
        activeContext?.let { ctx ->
            BrowserResearchWorkspace(
                taskContext = ctx,
                onOpenUrl = { url ->
                    scope.launch {
                        controller.navigate(url)
                        showResearchWorkspace = false
                    }
                },
                onDismiss = { showResearchWorkspace = false }
            )
        }
    }

    // DevTools & Browser Inspection Workspace Sheet
    if (showInspectionWorkspace) {
        com.lichiai.browser.inspection.ui.InspectionWorkspaceSheet(
            report = activeInspectionReport,
            isInspecting = isInspecting,
            onRefresh = { scope.launch { controller.inspectCurrentPage() } },
            onDownloadTrigger = { url -> controller.downloadManager.startDownload(url, null, null, null, 0L) },
            onDismiss = { showInspectionWorkspace = false }
        )
    }

    // High-Risk Confirmation Dialog
    pendingConfirmation?.let { req ->
        BrowserConfirmationDialog(
            request = req,
            onConfirm = { controller.agent.approveConfirmation() },
            onDismiss = { controller.agent.dismissConfirmation() }
        )
    }

    // Independent Settings Dialog
    if (showSettingsDialog) {
        BrowserSettingsDialog(
            storageManager = controller.storageManager,
            providerManager = controller.providerManager,
            onDismiss = { showSettingsDialog = false }
        )
    }

    // Website Permission Dialog
    permissionPrompt?.let { prompt ->
        BrowserPermissionDialog(
            prompt = prompt,
            onDismiss = { controller.permissionManager.dismiss() }
        )
    }
}

@Composable
fun BrowserStartPage(
    onSearch: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    onOpenCopilot: () -> Unit,
    onOpenResearch: () -> Unit,
    bookmarks: List<com.lichiai.browser.storage.BrowserBookmark>,
    modifier: Modifier = Modifier
) {
    var queryInput by remember { mutableStateOf("") }

    val quickLinks = listOf(
        "Google" to "https://www.google.com",
        "YouTube" to "https://www.youtube.com",
        "Wikipedia" to "https://www.wikipedia.org",
        "GitHub" to "https://www.github.com",
        "Reddit" to "https://www.reddit.com",
        "Amazon" to "https://www.amazon.in"
    )

    Column(
        modifier = modifier
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Spacer(Modifier.height(16.dp))

        Box(
            modifier = Modifier
                .size(60.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(32.dp)
            )
        }

        Spacer(Modifier.height(10.dp))

        Text(
            text = "Lichi Browser",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        Text(
            text = "Autonomous AI-Native Web Intelligence",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(18.dp))

        // Search bar
        OutlinedTextField(
            value = queryInput,
            onValueChange = { queryInput = it },
            placeholder = { Text("Search web or ask Lichi...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = {
                    if (queryInput.isNotBlank()) onSearch(queryInput.trim())
                }
            )
        )

        Spacer(Modifier.height(16.dp))

        // Quick Action Chips Row
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            item {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.clickable(onClick = onOpenCopilot)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = "Ask Lichi", tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("🤖 Ask Lichi", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
                    }
                }
            }

            item {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.clickable(onClick = onOpenResearch)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.PriceCheck, contentDescription = "Research", tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("📚 Research", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
                    }
                }
            }

            item {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.clickable { onOpenUrl("https://www.google.com") }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.Search, contentDescription = "Web Search", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("🔎 Web Search", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        // Quick Links Section
        Text(
            text = "FAVORITES",
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 1.sp
            ),
            modifier = Modifier.align(Alignment.Start)
        )

        Spacer(Modifier.height(8.dp))

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(quickLinks) { (name, url) ->
                Card(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onOpenUrl(url) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Icon(Icons.Default.Language, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = name,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        // Agent Assistant Guide Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp)),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Autonomous Browser Tasks",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "• \"Google par PUBG official website search karo aur open karo\"\n• \"Doosra result kholo\"\n• \"Is page ko summarize karo\"\n• \"Price verify karo aur compare karo\"",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 20.sp
                )
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}
