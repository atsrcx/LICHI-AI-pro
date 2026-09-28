package com.lichiai.terminal.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.VerticalSplit
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lichiai.terminal.agent.TerminalAgentAdapter
import com.lichiai.terminal.core.TerminalCommandRiskAnalyzer
import com.lichiai.terminal.core.TerminalManager
import com.lichiai.terminal.model.SshHostKey
import com.lichiai.terminal.model.TerminalRiskLevel
import com.lichiai.terminal.model.TerminalSplitMode
import com.lichiai.terminal.model.TerminalThemes
import com.lichiai.terminal.task.TerminalTaskManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    terminalManager: TerminalManager,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current

    val sessions by terminalManager.sessions.collectAsState()
    val activeSessionId by terminalManager.activeSessionId.collectAsState()
    val secondarySessionId by terminalManager.secondarySessionId.collectAsState()
    val splitMode by terminalManager.splitMode.collectAsState()
    val settings by terminalManager.settings.collectAsState()
    val history by terminalManager.preferences.historyFlow.collectAsState(initial = emptyList())
    val isDark = MaterialTheme.colorScheme.background.red < 0.5f
    val theme = remember(settings.activeThemeId, isDark) {
        TerminalThemes.getEffectiveTheme(settings.activeThemeId, isDark)
    }

    val activeSession = sessions.firstOrNull { it.id == activeSessionId }
    val secondarySession = sessions.firstOrNull { it.id == secondarySessionId }

    var showCommandPalette by remember { mutableStateOf(false) }
    var showSshProfilesScreen by remember { mutableStateOf(false) }
    var showSettingsScreen by remember { mutableStateOf(false) }
    var showSftpSheet by remember { mutableStateOf(false) }
    var showTaskActivitySheet by remember { mutableStateOf(false) }
    var toolsMenuOpen by remember { mutableStateOf(false) }
    var newSessionMenuOpen by remember { mutableStateOf(false) }

    // Search Bar State
    var isSearchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    // Host Key Verification Dialog State
    var pendingHostKey by remember { mutableStateOf<SshHostKey?>(null) }
    var hostKeyDeferred by remember { mutableStateOf<CompletableDeferred<Boolean>?>(null) }

    // Command Risk Confirmation Dialog State
    var pendingRiskCommand by remember { mutableStateOf<Pair<String, TerminalRiskLevel>?>(null) }

    // AI Copilot Explanation Dialog State
    var copilotExplanation by remember { mutableStateOf<String?>(null) }

    // Wire up Host Key prompt listener to the manager
    LaunchedEffect(terminalManager) {
        terminalManager.sessionManager.hostKeyPromptHandler = { hostKey ->
            val deferred = CompletableDeferred<Boolean>()
            pendingHostKey = hostKey
            hostKeyDeferred = deferred
            deferred.await()
        }
    }

    // Auto-create local session if completely empty
    LaunchedEffect(sessions.isEmpty()) {
        if (sessions.isEmpty()) {
            terminalManager.createLocalSession()
        }
    }

    if (showSshProfilesScreen) {
        SshProfilesScreen(
            terminalManager = terminalManager,
            onConnectProfile = { profile ->
                showSshProfilesScreen = false
                terminalManager.createSshSession(profile)
            },
            onBack = { showSshProfilesScreen = false }
        )
        return
    }

    if (showSettingsScreen) {
        TerminalSettingsScreen(
            terminalManager = terminalManager,
            onBack = { showSettingsScreen = false }
        )
        return
    }

    Scaffold(
        contentWindowInsets = WindowInsets.statusBars,
        containerColor = Color(theme.backgroundHex),
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .navigationBarsPadding()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f))
            ) {
                // 1. Inline Search Bar (if active)
                AnimatedVisibility(
                    visible = isSearchActive,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            BasicTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                singleLine = true,
                                textStyle = LocalTextStyle.current.copy(
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 13.sp,
                                    fontFamily = FontFamily.Monospace
                                ),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                modifier = Modifier.weight(1f),
                                decorationBox = { inner ->
                                    if (searchQuery.isEmpty()) {
                                        Text(
                                            "Search terminal output...",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                            fontSize = 13.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                    inner()
                                }
                            )
                            if (searchQuery.isNotEmpty()) {
                                IconButton(
                                    onClick = { searchQuery = "" },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Clear,
                                        contentDescription = "Clear search",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                            IconButton(
                                onClick = {
                                    isSearchActive = false
                                    searchQuery = ""
                                },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Close search",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }

                // 2. Control Toolbar Row: Left Actions (Tools, Search, Copy, AI, Settings) + Right Session Pill + New
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 2.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Left Toolbar Actions
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            // Back button
                            IconButton(onClick = onBack, modifier = Modifier.size(34.dp)) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            // Tools Dropdown (SFTP, Tasks, Split, Clear)
                            Box {
                                IconButton(onClick = { toolsMenuOpen = true }, modifier = Modifier.size(34.dp)) {
                                    Icon(
                                        Icons.Default.Terminal,
                                        contentDescription = "Terminal tools",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }

                                DropdownMenu(expanded = toolsMenuOpen, onDismissRequest = { toolsMenuOpen = false }) {
                                    DropdownMenuItem(
                                        text = { Text("AI Command Palette") },
                                        leadingIcon = { Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary) },
                                        onClick = { toolsMenuOpen = false; showCommandPalette = true }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Task Activity & Timeline") },
                                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.Assignment, null) },
                                        onClick = { toolsMenuOpen = false; showTaskActivitySheet = true }
                                    )
                                    if (activeSession?.backendType == com.lichiai.terminal.model.TerminalBackendType.SSH) {
                                        DropdownMenuItem(
                                            text = { Text("SFTP File Manager") },
                                            leadingIcon = { Icon(Icons.Default.Folder, null) },
                                            onClick = { toolsMenuOpen = false; showSftpSheet = true }
                                        )
                                    }
                                    DropdownMenuItem(
                                        text = { Text(if (splitMode == TerminalSplitMode.NONE) "Split Screen (Horizontal)" else "Single Window") },
                                        leadingIcon = { Icon(Icons.Default.VerticalSplit, null) },
                                        onClick = {
                                            toolsMenuOpen = false
                                            val next = if (splitMode == TerminalSplitMode.NONE) TerminalSplitMode.HORIZONTAL else TerminalSplitMode.NONE
                                            terminalManager.setSplitMode(next)
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Clear Buffer") },
                                        leadingIcon = { Icon(Icons.Default.Clear, null) },
                                        onClick = {
                                            toolsMenuOpen = false
                                            activeSession?.buffer?.clearScreen(2)
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Reconnect Session") },
                                        leadingIcon = { Icon(Icons.Default.Refresh, null) },
                                        onClick = {
                                            toolsMenuOpen = false
                                            activeSession?.triggerReconnect()
                                        }
                                    )
                                }
                            }

                            // Search button
                            IconButton(
                                onClick = {
                                    isSearchActive = !isSearchActive
                                    if (!isSearchActive) searchQuery = ""
                                },
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(
                                    Icons.Default.Search,
                                    contentDescription = "Search terminal",
                                    tint = if (isSearchActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            // Copy output button
                            IconButton(
                                onClick = {
                                    val text = activeSession?.buffer?.getPlainText() ?: ""
                                    clipboardManager.setText(AnnotatedString(text))
                                },
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(
                                    Icons.Default.ContentCopy,
                                    contentDescription = "Copy terminal output",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            // AI Copilot Action button
                            IconButton(
                                onClick = {
                                    val text = activeSession?.getRecentPlainText(40) ?: ""
                                    val adapter = TerminalAgentAdapter(terminalManager)
                                    copilotExplanation = adapter.explainOutput(text)
                                },
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(
                                    Icons.Default.AutoAwesome,
                                    contentDescription = "AI terminal assistant",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            // Settings button
                            IconButton(onClick = { showSettingsScreen = true }, modifier = Modifier.size(34.dp)) {
                                Icon(
                                    Icons.Default.Settings,
                                    contentDescription = "Terminal settings",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        // Right Session Pill + New (+) button
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (activeSession != null) {
                                val state by activeSession.state.collectAsState()
                                val title by activeSession.title.collectAsState()
                                val stateDotColor = when (state) {
                                    com.lichiai.terminal.model.TerminalSessionState.CONNECTED,
                                    com.lichiai.terminal.model.TerminalSessionState.ACTIVE -> Color(0xFF22C55E)
                                    com.lichiai.terminal.model.TerminalSessionState.CONNECTING,
                                    com.lichiai.terminal.model.TerminalSessionState.RECONNECTING,
                                    com.lichiai.terminal.model.TerminalSessionState.STARTING_SHELL -> Color(0xFFEAB308)
                                    com.lichiai.terminal.model.TerminalSessionState.DISCONNECTED,
                                    com.lichiai.terminal.model.TerminalSessionState.FAILED -> Color(0xFFEF4444)
                                    else -> Color.Gray
                                }

                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                                    border = androidx.compose.foundation.BorderStroke(
                                        0.5.dp,
                                        MaterialTheme.colorScheme.outlineVariant
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(6.dp)
                                                .clip(androidx.compose.foundation.shape.CircleShape)
                                                .background(stateDotColor)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            text = title.ifBlank { "Local Shell" },
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.widthIn(max = 100.dp)
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        IconButton(
                                            onClick = { terminalManager.closeSession(activeSession.id) },
                                            modifier = Modifier.size(16.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Close,
                                                contentDescription = "Close terminal",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(12.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            // Plus button for new session
                            Box {
                                IconButton(
                                    onClick = { newSessionMenuOpen = true },
                                    modifier = Modifier.size(34.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Add,
                                        contentDescription = "New terminal",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }

                                DropdownMenu(
                                    expanded = newSessionMenuOpen,
                                    onDismissRequest = { newSessionMenuOpen = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Local Shell / Termux") },
                                        leadingIcon = { Icon(Icons.Default.Terminal, null) },
                                        onClick = {
                                            newSessionMenuOpen = false
                                            terminalManager.createLocalSession()
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("New SSH Connection...") },
                                        leadingIcon = { Icon(Icons.Default.Dns, null) },
                                        onClick = {
                                            newSessionMenuOpen = false
                                            showSshProfilesScreen = true
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                // 3. Keyboard Shortcut Toolbar
                if (settings.showKeyboardToolbar) {
                    TerminalKeyboardToolbar(
                        onSendKey = { key ->
                            activeSession?.sendInput(key)
                        }
                    )
                }

                // 4. Command Input Bar
                TerminalInputBar(
                    commandHistory = history,
                    onSendCommand = { command ->
                        val risk = TerminalCommandRiskAnalyzer.analyzeRisk(command)
                        if (settings.confirmDestructiveCommands && TerminalCommandRiskAnalyzer.isConfirmationRequired(risk)) {
                            pendingRiskCommand = command to risk
                        } else {
                            scope.launch {
                                terminalManager.executeCommand(command)
                            }
                        }
                    }
                )
            }
        }
    ) { contentPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .background(Color(theme.backgroundHex))
        ) {
            if (activeSession != null) {
                when (splitMode) {
                    TerminalSplitMode.NONE, null -> {
                        TerminalOutputView(
                            session = activeSession,
                            theme = theme,
                            settings = settings,
                            searchQuery = searchQuery,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    TerminalSplitMode.HORIZONTAL -> {
                        Column(modifier = Modifier.fillMaxSize()) {
                            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                TerminalOutputView(
                                    session = activeSession,
                                    theme = theme,
                                    settings = settings,
                                    searchQuery = searchQuery,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                            Box(modifier = Modifier.height(2.dp).fillMaxWidth().background(MaterialTheme.colorScheme.primary))
                            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                if (secondarySession != null) {
                                    TerminalOutputView(
                                        session = secondarySession,
                                        theme = theme,
                                        settings = settings,
                                        searchQuery = searchQuery,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                        Text("No secondary session selected", color = Color.Gray, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                    TerminalSplitMode.VERTICAL -> {
                        Row(modifier = Modifier.fillMaxSize()) {
                            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                TerminalOutputView(
                                    session = activeSession,
                                    theme = theme,
                                    settings = settings,
                                    searchQuery = searchQuery,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                            Box(modifier = Modifier.width(2.dp).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
                            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                if (secondarySession != null) {
                                    TerminalOutputView(
                                        session = secondarySession,
                                        theme = theme,
                                        settings = settings,
                                        searchQuery = searchQuery,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                        Text("No secondary session selected", color = Color.Gray, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No terminal sessions active", color = Color.Gray)
                }
            }
        }
    }

    // Task Activity Sheet
    if (showTaskActivitySheet) {
        val context = LocalContext.current
        val taskManager = remember(context) { TerminalTaskManager.getInstance(context) }
        TerminalTaskActivitySheet(
            taskManager = taskManager,
            onDismiss = { showTaskActivitySheet = false }
        )
    }

    // SFTP Sheet
    if (showSftpSheet && activeSession != null && activeSession.sftpManager != null) {
        SftpExplorerBottomSheet(
            sftpManager = activeSession.sftpManager,
            onDismiss = { showSftpSheet = false }
        )
    }

    // Command Palette Dialog
    if (showCommandPalette) {
        val paletteActions = remember(activeSession, splitMode) {
            listOf(
                PaletteAction("New Local Shell", "Start Termux / Android process", Icons.Default.Terminal) {
                    terminalManager.createLocalSession()
                },
                PaletteAction("Connect via SSH", "Open server profiles", Icons.Default.Dns) {
                    showSshProfilesScreen = true
                },
                PaletteAction("Task Activity & Timeline", "View active and completed task history", Icons.AutoMirrored.Filled.Assignment) {
                    showTaskActivitySheet = true
                },
                PaletteAction("Clear Screen", "Clear active terminal buffer", Icons.Default.Clear) {
                    activeSession?.buffer?.clearScreen(2)
                },
                PaletteAction("Copy Terminal Output", "Copy full buffer to clipboard", Icons.Default.ContentCopy) {
                    val text = activeSession?.buffer?.getPlainText() ?: ""
                    clipboardManager.setText(AnnotatedString(text))
                },
                PaletteAction("Reconnect Session", "Re-establish active connection", Icons.Default.Refresh) {
                    activeSession?.triggerReconnect()
                },
                PaletteAction("Toggle Split Layout", "Side-by-side terminal windows", Icons.Default.VerticalSplit) {
                    val next = if (splitMode == TerminalSplitMode.NONE) TerminalSplitMode.HORIZONTAL else TerminalSplitMode.NONE
                    terminalManager.setSplitMode(next)
                },
                PaletteAction("AI Copilot: Analyze Output", "Explain errors and suggest fixes", Icons.Default.AutoAwesome) {
                    val text = activeSession?.getRecentPlainText(40) ?: ""
                    val adapter = TerminalAgentAdapter(terminalManager)
                    copilotExplanation = adapter.explainOutput(text)
                },
                PaletteAction("Terminal Settings", "Themes, fonts, and buffer controls", Icons.Default.Settings) {
                    showSettingsScreen = true
                }
            )
        }

        TerminalCommandPaletteDialog(
            actions = paletteActions,
            onDismiss = { showCommandPalette = false }
        )
    }

    // Host Key Verification Alert Dialog
    pendingHostKey?.let { hostKey ->
        AlertDialog(
            onDismissRequest = {
                hostKeyDeferred?.complete(false)
                pendingHostKey = null
            },
            title = { Text("Verify SSH Host Fingerprint") },
            text = {
                Column {
                    Text(
                        "Connecting to an unverified SSH server for the first time. Please confirm this fingerprint matches your server:",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("Host: ${hostKey.hostname}:${hostKey.port}", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text("Key Type: ${hostKey.keyType}", fontSize = 12.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        hostKey.fingerprintSha256,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        hostKeyDeferred?.complete(true)
                        pendingHostKey = null
                    }
                ) {
                    Text("Accept & Save")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        hostKeyDeferred?.complete(false)
                        pendingHostKey = null
                    }
                ) {
                    Text("Reject & Disconnect")
                }
            }
        )
    }

    // Command Risk Confirmation Alert Dialog
    pendingRiskCommand?.let { (command, risk) ->
        AlertDialog(
            onDismissRequest = { pendingRiskCommand = null },
            title = { Text("Confirm Destructive Command") },
            text = {
                Column {
                    Text(
                        "This command has been classified as ${risk.name} by Terminal Policy. It may permanently alter or delete data:",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = command,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Are you sure you want to proceed with execution?",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val cmdToRun = command
                        pendingRiskCommand = null
                        scope.launch {
                            terminalManager.executeCommand(cmdToRun)
                        }
                    }
                ) {
                    Text("Execute Anyway", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRiskCommand = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // AI Copilot Explanation Dialog
    copilotExplanation?.let { explanation ->
        AlertDialog(
            onDismissRequest = { copilotExplanation = null },
            title = { Text("AI Terminal Copilot") },
            text = {
                Column {
                    Text(
                        explanation,
                        style = MaterialTheme.typography.bodyMedium,
                        lineHeight = 20.sp
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { copilotExplanation = null }) {
                    Text("Got It")
                }
            }
        )
    }
}
