package com.lichiai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.lichiai.ChatViewModel
import com.lichiai.R
import com.lichiai.agentvision.settings.AgentVisionSettingsRepository
import com.lichiai.agentvision.ui.AgentVisionSettingsScreen
import com.lichiai.calling.ui.CallDiagnosticsScreen
import com.lichiai.calling.ui.CallDisambiguationDialog
import com.lichiai.data.ProviderConfig
import com.lichiai.ui.dynamicisland.DynamicIslandSettingsScreen
import com.lichiai.ui.voice.VoiceConversationScreen
import com.lichiai.ui.voice.VoiceSettingsScreen
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

private enum class Screen { Chat, Settings, Providers, ProviderEdit, Assistants, Voice, VoiceSettings, HandleMyCalls, CallDiagnostics, DynamicIsland, Skills, WebSearchSettings, AgentVision, Browser, Reminders, Terminal }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(vm: ChatViewModel) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val browserController = vm.browserController
    var screen by rememberSaveable { mutableStateOf(Screen.Chat) }
    var showModelPicker by rememberSaveable { mutableStateOf(false) }
    var editingProvider by remember { mutableStateOf<ProviderConfig?>(null) }

    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        vm.callPermissionManager.checkPermissions()
        if (results[android.Manifest.permission.READ_CONTACTS] == true) {
            vm.contactRepository.registerContentObserver()
            scope.launch { vm.contactRepository.loadAllContacts(forceRefresh = true) }
        }
    }

    androidx.activity.compose.BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }
    androidx.activity.compose.BackHandler(enabled = showModelPicker) {
        showModelPicker = false
    }
    androidx.activity.compose.BackHandler(enabled = screen == Screen.Settings) {
        screen = Screen.Chat
    }
    androidx.activity.compose.BackHandler(enabled = screen == Screen.Providers) {
        screen = Screen.Settings
    }
    androidx.activity.compose.BackHandler(enabled = screen == Screen.ProviderEdit) {
        screen = Screen.Providers
    }
    androidx.activity.compose.BackHandler(enabled = screen == Screen.Assistants) {
        screen = Screen.Settings
    }
    androidx.activity.compose.BackHandler(enabled = screen == Screen.Voice) {
        screen = Screen.Chat
    }
    androidx.activity.compose.BackHandler(enabled = screen == Screen.VoiceSettings) {
        screen = Screen.Settings
    }
    androidx.activity.compose.BackHandler(enabled = screen == Screen.HandleMyCalls) {
        screen = Screen.Settings
    }
    androidx.activity.compose.BackHandler(enabled = screen == Screen.CallDiagnostics) {
        screen = Screen.Settings
    }
    androidx.activity.compose.BackHandler(enabled = screen == Screen.DynamicIsland) {
        screen = Screen.Settings
    }
    androidx.activity.compose.BackHandler(enabled = screen == Screen.Skills) {
        screen = Screen.Settings
    }
    androidx.activity.compose.BackHandler(enabled = screen == Screen.WebSearchSettings) {
        screen = Screen.Settings
    }
    androidx.activity.compose.BackHandler(enabled = screen == Screen.AgentVision) {
        screen = Screen.Settings
    }
    androidx.activity.compose.BackHandler(enabled = screen == Screen.Browser) {
        screen = Screen.Chat
    }
    androidx.activity.compose.BackHandler(enabled = screen == Screen.Reminders) {
        screen = Screen.Chat
    }
    androidx.activity.compose.BackHandler(enabled = screen == Screen.Terminal) {
        screen = Screen.Chat
    }

    val conversations by vm.conversations.collectAsState()
    val activeId by vm.activeId.collectAsState()
    val settings by vm.settings.collectAsState()
    val providers by vm.providers.collectAsState()
    val isStreaming by vm.isStreaming.collectAsState()
    val streamingOverlay by vm.streamingOverlay.collectAsState()
    val error by vm.error.collectAsState()
    val toast by vm.toast.collectAsState()
    val fetchingId by vm.fetchingModelsFor.collectAsState()
    val agentLiveStatus by vm.agentLiveStatus.collectAsState()
    val webActivityState by vm.webActivityState.collectAsState()
    val liveActivityState by vm.liveActivityState.collectAsState()
    val activeSpeakingMessageId by vm.activeSpeakingMessageId.collectAsState()

    val activeConv = conversations.firstOrNull { it.id == activeId }
    val activeProvider = providers.firstOrNull { it.id == settings.activeProviderId }
    val assistants by vm.assistants.collectAsState()
    val disambiguationRequest by vm.universalCallEngine.activeDisambiguation.collectAsState()

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(toast) {
        toast?.let {
            snackbar.showSnackbar(it, duration = SnackbarDuration.Short)
            vm.clearToast()
        }
    }

    // Auto-navigate to Voice Conversation Mode when any wake word is triggered
    LaunchedEffect(vm) {
        vm.wakeDetectedEvent.collect { phrase ->
            if (screen != Screen.Voice) {
                screen = Screen.Voice
            }
        }
    }

    // Auto-navigate to Browser Screen when browser intent is triggered
    LaunchedEffect(vm) {
        vm.browserNavigationEvent.collect {
            if (screen != Screen.Browser) {
                screen = Screen.Browser
            }
        }
    }

    // Auto-navigate to Reminders Screen when reminder list/schedule intent is triggered
    LaunchedEffect(vm) {
        vm.reminderNavigationEvent.collect {
            if (screen != Screen.Reminders) {
                screen = Screen.Reminders
            }
        }
    }

    // Auto-navigate to Terminal Screen when terminal intent is triggered
    LaunchedEffect(vm) {
        vm.terminalNavigationEvent.collect {
            if (screen != Screen.Terminal) {
                screen = Screen.Terminal
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        when (screen) {
            Screen.Chat -> {
                ModalNavigationDrawer(
                    drawerState = drawerState,
                    drawerContent = {
                        GlassDrawer(
                            conversations = conversations,
                            activeId = activeId,
                            onSelect = { id ->
                                vm.selectConversation(id)
                                scope.launch { drawerState.close() }
                            },
                            onNew = {
                                vm.newConversation()
                                scope.launch { drawerState.close() }
                            },
                            onDelete = { vm.deleteConversation(it) },
                            onRename = { id, t -> vm.renameConversation(id, t) },
                            onOpenSettings = {
                                screen = Screen.Settings
                                scope.launch { drawerState.close() }
                            },
                            onOpenVoiceMode = {
                                screen = Screen.Voice
                                scope.launch { drawerState.close() }
                            },
                            onOpenCallingSystem = {
                                screen = Screen.CallDiagnostics
                                scope.launch { drawerState.close() }
                            },
                            onOpenBrowser = {
                                screen = Screen.Browser
                                scope.launch { drawerState.close() }
                            },
                            onOpenReminders = {
                                screen = Screen.Reminders
                                scope.launch { drawerState.close() }
                            },
                            onOpenTerminal = {
                                screen = Screen.Terminal
                                scope.launch { drawerState.close() }
                            }
                        )
                    }
                ) {
                    ChatScreen(
                        conversation = activeConv,
                        settings = settings,
                        activeProvider = activeProvider,
                        isStreaming = isStreaming,
                        streamingOverlay = streamingOverlay,
                        agentLiveStatus = agentLiveStatus,
                        webActivityState = webActivityState,
                        liveActivityState = liveActivityState,
                        activeSpeakingMessageId = activeSpeakingMessageId,
                        onMenu = { scope.launch { drawerState.open() } },
                        onSend = { text, atts -> vm.sendMessage(text, atts) },
                        onStop = { vm.stopStreaming() },
                        onRegenerate = { vm.regenerate() },
                        onRegenerateFrom = { msgId -> vm.regenerateFrom(msgId) },
                        onDeleteMessage = { msgId -> vm.deleteMessage(msgId) },
                        onEditMessage = { msgId, newText -> vm.editMessage(msgId, newText) },
                        onToggleSpeak = { msgId, text -> vm.toggleSpeakMessage(msgId, text) },
                        onNew = { vm.newConversation() },
                        onOpenSettings = { screen = Screen.Settings },
                        onPickModel = {
                            if (providers.isEmpty()) {
                                editingProvider = null
                                screen = Screen.ProviderEdit
                            } else showModelPicker = true
                        },
                        onOpenVoiceMode = { screen = Screen.Voice },
                        onOpenBrowser = { screen = Screen.Browser }
                    )
                }
            }
            Screen.Settings -> {
                SettingsScreen(
                    settings = settings,
                    providers = providers,
                    assistants = assistants,
                    onBack = { screen = Screen.Chat },
                    onChange = { vm.updateSettings(it) },
                    onOpenProviders = { screen = Screen.Providers },
                    onOpenAssistants = { screen = Screen.Assistants },
                    onOpenVoiceSettings = { screen = Screen.VoiceSettings },
                    onOpenHandleMyCalls = { screen = Screen.HandleMyCalls },
                    onOpenCallDiagnostics = { screen = Screen.CallDiagnostics },
                    onOpenDynamicIsland = { screen = Screen.DynamicIsland },
                    onOpenSkills = { screen = Screen.Skills },
                    onOpenWebSearch = { screen = Screen.WebSearchSettings },
                    onOpenAgentVision = { screen = Screen.AgentVision },
                    onOpenReminders = { screen = Screen.Reminders },
                    onOpenTerminal = { screen = Screen.Terminal }
                )
            }
            Screen.AgentVision -> {
                val avRepo = remember { AgentVisionSettingsRepository.getInstance(vm.getApplication()) }
                AgentVisionSettingsScreen(
                    repository = avRepo,
                    onBack = { screen = Screen.Settings }
                )
            }
            Screen.WebSearchSettings -> {
                WebSearchSettingsScreen(
                    webManager = vm.webIntelligenceManager,
                    onBack = { screen = Screen.Settings }
                )
            }
            Screen.Skills -> {
                com.lichiai.ui.skill.SkillsScreen(
                    onBack = { screen = Screen.Settings }
                )
            }
            Screen.HandleMyCalls -> {
                com.lichiai.calling.ui.HandleMyCallsSettingsScreen(
                    viewModel = vm,
                    onBack = { screen = Screen.Settings }
                )
            }
            Screen.Assistants -> {
                AssistantsScreen(
                    assistants = assistants,
                    activeId = settings.activeAssistantId,
                    onBack = { screen = Screen.Settings },
                    onSelect = { vm.selectAssistant(it) },
                    onUpsert = { vm.upsertAssistant(it) },
                    onDelete = { vm.deleteAssistant(it) }
                )
            }
            Screen.Providers -> {
                ProvidersScreen(
                    providers = providers,
                    fetchingId = fetchingId,
                    activeProviderId = settings.activeProviderId,
                    activeModel = settings.activeModel,
                    onBack = { screen = Screen.Settings },
                    onCreate = {
                        editingProvider = null
                        screen = Screen.ProviderEdit
                    },
                    onEdit = { p ->
                        editingProvider = p
                        screen = Screen.ProviderEdit
                    },
                    onDelete = { vm.deleteProvider(it) },
                    onFetchModels = { vm.fetchModels(it) },
                    onAddManualModel = { id, m -> vm.addManualModel(id, m) },
                    onRemoveModel = { id, m -> vm.removeModel(id, m) },
                    onSelectModel = { id, m -> vm.selectModel(id, m) }
                )
            }
            Screen.ProviderEdit -> {
                ProviderEditorScreen(
                    initial = editingProvider,
                    onCancel = { screen = Screen.Providers },
                    onSave = { p ->
                        vm.upsertProvider(p)
                        screen = Screen.Providers
                    }
                )
            }
            Screen.Voice -> {
                val activeAssistant = com.lichiai.assistant.resolver.ActiveAssistantResolver.resolve(settings.activeAssistantId, assistants).toAssistant()
                VoiceConversationScreen(
                    orchestrator = vm.voiceOrchestrator,
                    activeProvider = activeProvider,
                    activeAssistant = activeAssistant,
                    activeSettings = settings,
                    onOpenVoiceSettings = { screen = Screen.VoiceSettings },
                    onClose = { screen = Screen.Chat }
                )
            }
            Screen.VoiceSettings -> {
                VoiceSettingsScreen(
                    voiceSettingsRepository = vm.voiceSettingsRepo,
                    orchestrator = vm.voiceOrchestrator,
                    wakeWordManager = vm.wakeWordManager,
                    onBack = { screen = Screen.Settings }
                )
            }
            Screen.CallDiagnostics -> {
                CallDiagnosticsScreen(
                    viewModel = vm,
                    onRequestPermissions = {
                        permissionLauncher.launch(com.lichiai.calling.permission.CallPermissionManager.REQUIRED_PERMISSIONS)
                    },
                    onBack = { screen = Screen.Settings }
                )
            }
            Screen.DynamicIsland -> {
                DynamicIslandSettingsScreen(
                    controller = vm.dynamicIslandController,
                    onBack = { screen = Screen.Settings }
                )
            }
            Screen.Browser -> {
                com.lichiai.browser.ui.BrowserScreen(
                    controller = browserController,
                    onOpenDrawer = {
                        screen = Screen.Chat
                        scope.launch { drawerState.open() }
                    }
                )
            }
            Screen.Reminders -> {
                com.lichiai.time.ui.ReminderScreen(
                    manager = vm.reminderManager,
                    onBack = { screen = Screen.Chat }
                )
            }
            Screen.Terminal -> {
                com.lichiai.terminal.ui.TerminalScreen(
                    terminalManager = vm.terminalManager,
                    onBack = { screen = Screen.Chat }
                )
            }
        }

        SnackbarHost(hostState = snackbar, modifier = Modifier.fillMaxSize())
    }

    if (showModelPicker) {
        ModelPickerSheet(
            providers = providers,
            activeProviderId = settings.activeProviderId,
            activeModel = settings.activeModel,
            onPick = { pid, m -> vm.selectModel(pid, m) },
            onDismiss = { showModelPicker = false }
        )
    }

    disambiguationRequest?.let { candidatesList ->
        CallDisambiguationDialog(
            candidates = candidatesList,
            onSelectCandidate = { candidate, phone ->
                vm.universalCallEngine.resolveDisambiguation(candidate, phone)
            },
            onDismiss = {
                vm.universalCallEngine.cancelDisambiguation()
            }
        )
    }

    error?.let { msg ->
        val needsProviderFix = msg.contains("provider", ignoreCase = true)
            || msg.contains("api key", ignoreCase = true)
            || msg.contains("model", ignoreCase = true)
            || msg.contains("401")
            || msg.contains("403")
        AlertDialog(
            onDismissRequest = { vm.clearError() },
            confirmButton = {
                if (needsProviderFix) {
                    TextButton(onClick = {
                        vm.clearError()
                        screen = Screen.Providers
                    }) { Text(stringResource(R.string.error_open_providers)) }
                } else {
                    TextButton(onClick = { vm.clearError() }) {
                        Text(stringResource(R.string.ok))
                    }
                }
            },
            dismissButton = if (needsProviderFix) {
                {
                    TextButton(onClick = { vm.clearError() }) {
                        Text(stringResource(R.string.ok))
                    }
                }
            } else null,
            title = { Text(stringResource(R.string.error_dialog_title)) },
            text = { Text(msg) }
        )
    }
}
