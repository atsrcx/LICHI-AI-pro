package com.lichiai.ui.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.lichiai.data.SttMode
import com.lichiai.data.TtsMode
import com.lichiai.data.VoiceSettings
import com.lichiai.data.VoiceSettingsRepository
import com.lichiai.voice.VoiceConversationOrchestrator
import com.lichiai.voice.stt.SttProviderDiscovery
import com.lichiai.voice.stt.SttProviderInfo
import com.lichiai.voice.tts.TtsEngineDiscovery
import com.lichiai.voice.tts.TtsEngineInfo
import com.lichiai.voice.arbitration.MicrophoneArbitrator
import com.lichiai.voice.arbitration.MicrophoneOwner
import com.lichiai.voice.arbitration.MicrophoneState
import com.lichiai.voice.arbitration.OwnershipRecord
import com.lichiai.voice.tts.TtsVoiceInfo
import com.lichiai.voice.wakeword.VoskModelManager
import com.lichiai.voice.wakeword.VoskWakeWordEngine
import com.lichiai.voice.wakeword.VoskModelState
import com.lichiai.voice.wakeword.WakeWordEngineState
import com.lichiai.voice.wakeword.WakeWordForegroundService
import com.lichiai.voice.wakeword.WakeWordManager
import com.lichiai.voice.wakeword.WakeWordSensitivity
import com.lichiai.voice.wakeword.WakeWordSettings
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceSettingsScreen(
    voiceSettingsRepository: VoiceSettingsRepository,
    orchestrator: VoiceConversationOrchestrator,
    wakeWordManager: WakeWordManager? = null,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val voiceSettings by voiceSettingsRepository.settings.collectAsState(initial = VoiceSettings())
    val wakeWordSettings by voiceSettingsRepository.wakeWordSettings.collectAsState(initial = WakeWordSettings())
    val wakeEngineState by (wakeWordManager?.engineState ?: kotlinx.coroutines.flow.MutableStateFlow(WakeWordEngineState.READY)).collectAsState()
    val modelState by (wakeWordManager?.modelState ?: VoskModelManager.modelState).collectAsState()
    val arbitrationRecord by MicrophoneArbitrator.ownershipRecord.collectAsState()

    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasMicPermission = isGranted
        if (isGranted) {
            wakeWordManager?.restartListening()
        }
    }

    var newPhraseText by remember { mutableStateOf("") }
    var showAddPhraseRow by remember { mutableStateOf(false) }

    var sttProviders by remember { mutableStateOf<List<SttProviderInfo>>(emptyList()) }
    var ttsEngines by remember { mutableStateOf<List<TtsEngineInfo>>(emptyList()) }
    var availableVoices by remember { mutableStateOf<List<TtsVoiceInfo>>(emptyList()) }

    fun refreshDiscovery() {
        sttProviders = SttProviderDiscovery.discoverProviders(context)
        ttsEngines = TtsEngineDiscovery.discoverEngines(context)
        availableVoices = orchestrator.getAvailableTtsVoices()
        VoskModelManager.checkModelState(context)
    }

    LaunchedEffect(Unit) {
        refreshDiscovery()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Voice & Speech Settings", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { refreshDiscovery() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh Providers")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { Spacer(Modifier.height(4.dp)) }

            // Section 0: Vosk Multi-Wake-Word System
            item {
                SectionHeader(title = "Vosk Multi-Wake-Word System", icon = Icons.Default.Hearing)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Wake Word Detection",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    "Hands-free voice trigger using local Vosk acoustic engine.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = wakeWordSettings.enabled,
                                onCheckedChange = { isChecked ->
                                    if (isChecked && !hasMicPermission) {
                                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                    if (isChecked && modelState is VoskModelState.NotInstalled) {
                                        wakeWordManager?.downloadModel()
                                    }
                                    scope.launch {
                                        voiceSettingsRepository.updateWakeWordEnabled(isChecked)
                                    }
                                }
                            )
                        }

                        // Engine Status Badge
                        Spacer(Modifier.height(8.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                                    RoundedCornerShape(8.dp)
                                )
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            val (badgeColor, statusText) = when {
                                !wakeWordSettings.enabled -> MaterialTheme.colorScheme.outline to "Disabled"
                                !hasMicPermission -> MaterialTheme.colorScheme.error to "Microphone Permission Required"
                                modelState is VoskModelState.Downloading -> MaterialTheme.colorScheme.tertiary to "Downloading Acoustic Model..."
                                modelState is VoskModelState.Extracting -> MaterialTheme.colorScheme.tertiary to "Extracting Acoustic Model..."
                                modelState is VoskModelState.NotInstalled -> MaterialTheme.colorScheme.error to "Model Not Downloaded (Action Required)"
                                modelState is VoskModelState.Error -> MaterialTheme.colorScheme.error to "Model Error: ${(modelState as VoskModelState.Error).message}"
                                wakeEngineState == WakeWordEngineState.LISTENING -> MaterialTheme.colorScheme.primary to "Active • Listening for wake words"
                                wakeEngineState == WakeWordEngineState.CONVERSATION_ACTIVE -> MaterialTheme.colorScheme.secondary to "Conversation Mode Active (Mic in Use)"
                                wakeEngineState == WakeWordEngineState.LOADING_MODEL -> MaterialTheme.colorScheme.tertiary to "Loading Acoustic Model..."
                                wakeEngineState == WakeWordEngineState.MIC_UNAVAILABLE -> MaterialTheme.colorScheme.error to "Microphone In Use / Unavailable"
                                wakeEngineState == WakeWordEngineState.PAUSED -> MaterialTheme.colorScheme.outline to "Standby"
                                else -> MaterialTheme.colorScheme.primary to "Engine Ready"
                            }
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(badgeColor)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                statusText,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        // Microphone Permission Alert
                        if (!hasMicPermission && wakeWordSettings.enabled) {
                            Spacer(Modifier.height(10.dp))
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        modifier = Modifier.weight(1f),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Default.Warning,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            "Microphone permission is needed to listen for wake words.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onErrorContainer
                                        )
                                    }
                                    Button(
                                        onClick = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text("Grant", style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                        }

                        // Acoustic Model Management Card
                        Spacer(Modifier.height(12.dp))
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Default.CloudDownload,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            "Acoustic Model (vosk-model-small-en-us)",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }

                                Spacer(Modifier.height(6.dp))

                                when (val state = modelState) {
                                    is VoskModelState.Ready -> {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Icon(
                                                    Icons.Default.CheckCircle,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                                Spacer(Modifier.width(6.dp))
                                                Text(
                                                    "Offline model installed & ready (~40 MB)",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            IconButton(
                                                onClick = { wakeWordManager?.deleteModel() },
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Icon(
                                                    Icons.Default.Delete,
                                                    contentDescription = "Delete Model",
                                                    tint = MaterialTheme.colorScheme.outline,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                    }
                                    is VoskModelState.Downloading -> {
                                        Column(modifier = Modifier.fillMaxWidth()) {
                                            Text(
                                                "Downloading: ${state.statusText}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                            Spacer(Modifier.height(6.dp))
                                            LinearProgressIndicator(
                                                progress = { state.progress },
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(6.dp)
                                                    .clip(RoundedCornerShape(3.dp))
                                            )
                                        }
                                    }
                                    is VoskModelState.Extracting -> {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(16.dp),
                                                strokeWidth = 2.dp
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                state.statusText,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                    is VoskModelState.NotInstalled -> {
                                        Column(modifier = Modifier.fillMaxWidth()) {
                                            Text(
                                                "Offline model is not on device. Download it to activate wake-word recognition (~40 MB, one-time).",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Spacer(Modifier.height(8.dp))
                                            Button(
                                                onClick = {
                                                    if (!hasMicPermission) {
                                                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                                    }
                                                    wakeWordManager?.downloadModel()
                                                },
                                                modifier = Modifier.fillMaxWidth(),
                                                shape = RoundedCornerShape(8.dp)
                                            ) {
                                                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                                                Spacer(Modifier.width(8.dp))
                                                Text("Download Acoustic Model (~40 MB)")
                                            }
                                        }
                                    }
                                    is VoskModelState.Error -> {
                                        Column(modifier = Modifier.fillMaxWidth()) {
                                            Text(
                                                "Download failed: ${state.message}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.error
                                            )
                                            Spacer(Modifier.height(8.dp))
                                            OutlinedButton(
                                                onClick = { wakeWordManager?.downloadModel() },
                                                modifier = Modifier.fillMaxWidth(),
                                                shape = RoundedCornerShape(8.dp)
                                            ) {
                                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                                Spacer(Modifier.width(8.dp))
                                                Text("Retry Download")
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if (wakeWordSettings.enabled) {
                            Spacer(Modifier.height(16.dp))
                            HorizontalDivider()
                            Spacer(Modifier.height(12.dp))

                            // Background Listening Toggle
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "Continuous Background Listening",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        "Keep listening when app is minimized via foreground service.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = wakeWordSettings.backgroundListening,
                                    onCheckedChange = { isChecked ->
                                        if (isChecked && !hasMicPermission) {
                                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                        }
                                        scope.launch {
                                            voiceSettingsRepository.updateWakeWordBackground(isChecked)
                                        }
                                    }
                                )
                            }

                            Spacer(Modifier.height(12.dp))
                            HorizontalDivider()
                            Spacer(Modifier.height(12.dp))

                            // Sensitivity Selection
                            Text(
                                "Recognition Strictness",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        scope.launch {
                                            voiceSettingsRepository.updateWakeWordSensitivity(WakeWordSensitivity.STRICT)
                                        }
                                    },
                                    colors = if (wakeWordSettings.sensitivity == WakeWordSensitivity.STRICT) {
                                        ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                                    } else {
                                        ButtonDefaults.filledTonalButtonColors()
                                    },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("Strict (No False Hits)")
                                }
                                Button(
                                    onClick = {
                                        scope.launch {
                                            voiceSettingsRepository.updateWakeWordSensitivity(WakeWordSensitivity.BALANCED)
                                        }
                                    },
                                    colors = if (wakeWordSettings.sensitivity == WakeWordSensitivity.BALANCED) {
                                        ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                                    } else {
                                        ButtonDefaults.filledTonalButtonColors()
                                    },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("Balanced")
                                }
                            }

                            Spacer(Modifier.height(16.dp))
                            HorizontalDivider()
                            Spacer(Modifier.height(12.dp))

                            // Configured Multi-Wake-Words List
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text(
                                        "Simultaneous Wake Phrases",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        "Say any active phrase to trigger voice mode immediately.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(
                                    onClick = { showAddPhraseRow = !showAddPhraseRow }
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = "Add Phrase")
                                }
                            }

                            Spacer(Modifier.height(8.dp))

                            if (showAddPhraseRow) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedTextField(
                                        value = newPhraseText,
                                        onValueChange = { newPhraseText = it },
                                        placeholder = { Text("e.g. Jarvis, Computer") },
                                        modifier = Modifier.weight(1f),
                                        singleLine = true
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Button(
                                        onClick = {
                                            if (newPhraseText.isNotBlank()) {
                                                scope.launch {
                                                    voiceSettingsRepository.addWakeWordPhrase(newPhraseText.trim())
                                                    newPhraseText = ""
                                                    showAddPhraseRow = false
                                                }
                                            }
                                        }
                                    ) {
                                        Text("Add")
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                            }

                            wakeWordSettings.phrases.forEach { phraseConfig ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(
                                            MaterialTheme.colorScheme.surface.copy(alpha = 0.4f),
                                            RoundedCornerShape(8.dp)
                                        )
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Switch(
                                        checked = phraseConfig.isEnabled,
                                        onCheckedChange = { checked ->
                                            scope.launch {
                                                voiceSettingsRepository.toggleWakeWordPhrase(phraseConfig.id, checked)
                                            }
                                        }
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Text(
                                        "\"${phraseConfig.phrase}\"",
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = if (phraseConfig.isEnabled) FontWeight.Medium else FontWeight.Normal,
                                        color = if (phraseConfig.isEnabled) {
                                            MaterialTheme.colorScheme.onSurface
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                        },
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (wakeWordSettings.phrases.size > 1) {
                                        IconButton(
                                            onClick = {
                                                scope.launch {
                                                    voiceSettingsRepository.removeWakeWordPhrase(phraseConfig.id)
                                                }
                                            }
                                        ) {
                                            Icon(
                                                Icons.Default.Delete,
                                                contentDescription = "Delete Phrase",
                                                tint = MaterialTheme.colorScheme.outline
                                            )
                                        }
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                            }
                        }
                    }
                }
            }

            // Section: Live Microphone Arbitration & Audio Pipeline Status
            item {
                SectionHeader(title = "Microphone Arbitration System", icon = Icons.Default.Tune)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Arbitration & Hardware Coordination",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            "Guarantees conflict-free microphone handoff between Wake Word, STT, and System.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))

                        // Status grid items
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Current Mic Owner:", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                arbitrationRecord.owner.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = when (arbitrationRecord.owner) {
                                    MicrophoneOwner.WAKE_WORD -> MaterialTheme.colorScheme.primary
                                    MicrophoneOwner.VOICE_STT, MicrophoneOwner.OTHER_STT -> MaterialTheme.colorScheme.secondary
                                    MicrophoneOwner.PHONE_CALL, MicrophoneOwner.EXTERNAL_APP -> MaterialTheme.colorScheme.error
                                    MicrophoneOwner.NONE -> MaterialTheme.colorScheme.onSurface
                                }
                            )
                        }
                        Spacer(Modifier.height(6.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Arbitrator State:", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                arbitrationRecord.state.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = when (arbitrationRecord.state) {
                                    MicrophoneState.ACTIVE -> MaterialTheme.colorScheme.primary
                                    MicrophoneState.ERROR, MicrophoneState.UNAVAILABLE -> MaterialTheme.colorScheme.error
                                    MicrophoneState.BLOCKED -> MaterialTheme.colorScheme.tertiary
                                    else -> MaterialTheme.colorScheme.onSurface
                                }
                            )
                        }
                        Spacer(Modifier.height(6.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Foreground Service:", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                if (WakeWordForegroundService.isServiceRunning) "RUNNING (PERSISTENT)" else "STOPPED",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = if (WakeWordForegroundService.isServiceRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                            )
                        }
                        Spacer(Modifier.height(6.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Last Event Reason:", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                arbitrationRecord.reason.take(30),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    MicrophoneArbitrator.release(arbitrationRecord.owner, "Manual diagnostic reset")
                                    wakeWordManager?.restartListening()
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Force Reset & Reacquire Microphone", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }

            // Section 1: Speech-To-Text (STT) Provider Selection
            item {
                SectionHeader(title = "Speech Recognition (STT)", icon = Icons.Default.Mic)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Discovered Recognition Services",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            "Choose your preferred speech-to-text recognition provider.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))

                        sttProviders.forEach { provider ->
                            val isSelected = when {
                                provider.isSystemDefault -> voiceSettings.sttMode == SttMode.SYSTEM_DEFAULT
                                provider.isOnDevice -> voiceSettings.sttMode == SttMode.ON_DEVICE
                                else -> voiceSettings.sttMode == SttMode.SPECIFIC_PROVIDER && voiceSettings.sttComponent == provider.id
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        scope.launch {
                                            when {
                                                provider.isSystemDefault -> voiceSettingsRepository.updateSttMode(SttMode.SYSTEM_DEFAULT)
                                                provider.isOnDevice -> voiceSettingsRepository.updateSttMode(SttMode.ON_DEVICE)
                                                else -> voiceSettingsRepository.updateSttMode(SttMode.SPECIFIC_PROVIDER, provider.id)
                                            }
                                        }
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = {
                                        scope.launch {
                                            when {
                                                provider.isSystemDefault -> voiceSettingsRepository.updateSttMode(SttMode.SYSTEM_DEFAULT)
                                                provider.isOnDevice -> voiceSettingsRepository.updateSttMode(SttMode.ON_DEVICE)
                                                else -> voiceSettingsRepository.updateSttMode(SttMode.SPECIFIC_PROVIDER, provider.id)
                                            }
                                        }
                                    }
                                )
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(
                                        provider.displayName,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                                    )
                                    if (provider.description.isNotBlank()) {
                                        Text(
                                            provider.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                        // STT Language
                        LanguageDropdown(
                            label = "Recognition Language",
                            selectedTag = voiceSettings.sttLanguage,
                            onSelect = { tag -> scope.launch { voiceSettingsRepository.updateSttLanguage(tag) } }
                        )

                        Spacer(Modifier.height(12.dp))

                        // Partial Results Switch
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Real-time Partial Transcripts", style = MaterialTheme.typography.bodyMedium)
                                Text("Show speech text as you speak", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = voiceSettings.sttPartialResults,
                                onCheckedChange = { checked -> scope.launch { voiceSettingsRepository.updateSttPartialResults(checked) } }
                            )
                        }

                        Spacer(Modifier.height(8.dp))

                        // Auto-restart Switch
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Continuous Conversation Listening", style = MaterialTheme.typography.bodyMedium)
                                Text("Automatically resume listening after assistant finishes", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = voiceSettings.sttAutoRestart,
                                onCheckedChange = { checked -> scope.launch { voiceSettingsRepository.updateSttAutoRestart(checked) } }
                            )
                        }
                    }
                }
            }

            // Section 2: Text-To-Speech (TTS) Engine & Voice
            item {
                SectionHeader(title = "Text-to-Speech (TTS)", icon = Icons.Default.RecordVoiceOver)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Installed TTS Engines",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            "Select the synthesis engine used to generate spoken audio.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))

                        ttsEngines.forEach { engine ->
                            val isSelected = if (engine.isSystemDefault) {
                                voiceSettings.ttsMode == TtsMode.SYSTEM_DEFAULT
                            } else {
                                voiceSettings.ttsMode == TtsMode.SPECIFIC_ENGINE && voiceSettings.ttsEnginePackage == engine.packageName
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        scope.launch {
                                            if (engine.isSystemDefault) {
                                                voiceSettingsRepository.updateTtsMode(TtsMode.SYSTEM_DEFAULT)
                                            } else {
                                                voiceSettingsRepository.updateTtsMode(TtsMode.SPECIFIC_ENGINE, engine.packageName ?: "")
                                            }
                                            refreshDiscovery()
                                        }
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = {
                                        scope.launch {
                                            if (engine.isSystemDefault) {
                                                voiceSettingsRepository.updateTtsMode(TtsMode.SYSTEM_DEFAULT)
                                            } else {
                                                voiceSettingsRepository.updateTtsMode(TtsMode.SPECIFIC_ENGINE, engine.packageName ?: "")
                                            }
                                            refreshDiscovery()
                                        }
                                    }
                                )
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(
                                        engine.displayName,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                                    )
                                    if (engine.description.isNotBlank()) {
                                        Text(
                                            engine.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                        // TTS Language
                        LanguageDropdown(
                            label = "Speech Language",
                            selectedTag = voiceSettings.ttsLanguage,
                            onSelect = { tag ->
                                scope.launch {
                                    voiceSettingsRepository.updateTtsLanguage(tag)
                                    refreshDiscovery()
                                }
                            }
                        )

                        Spacer(Modifier.height(16.dp))

                        // Speech Rate Slider
                        Text("Speech Rate: ${"%.2f".format(voiceSettings.speechRate)}x", style = MaterialTheme.typography.bodyMedium)
                        Slider(
                            value = voiceSettings.speechRate,
                            onValueChange = { rate -> scope.launch { voiceSettingsRepository.updateSpeechRate(rate) } },
                            valueRange = 0.5f..2.0f,
                            steps = 15
                        )

                        Spacer(Modifier.height(8.dp))

                        // Pitch Slider
                        Text("Speech Pitch: ${"%.2f".format(voiceSettings.pitch)}x", style = MaterialTheme.typography.bodyMedium)
                        Slider(
                            value = voiceSettings.pitch,
                            onValueChange = { pitch -> scope.launch { voiceSettingsRepository.updatePitch(pitch) } },
                            valueRange = 0.5f..2.0f,
                            steps = 15
                        )

                        Spacer(Modifier.height(12.dp))

                        // Test Voice Button
                        Button(
                            onClick = {
                                orchestrator.testVoice("Hello! I am LICHI AI. How can I help you today?")
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Test Voice Audio")
                        }
                    }
                }
            }

            // Section 3: Conversational Intelligence & Barge-In
            item {
                SectionHeader(title = "Conversation Dynamics", icon = Icons.Default.Tune)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        // Barge In
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Barge-In (User Interruption)", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                Text("Instantly halt AI speaking when you speak or tap the orb", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = voiceSettings.bargeInEnabled,
                                onCheckedChange = { checked -> scope.launch { voiceSettingsRepository.updateBargeIn(checked) } }
                            )
                        }

                        Spacer(Modifier.height(12.dp))

                        // Safe Echo Protection
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Echo Protection", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                Text("Mute microphone while assistant speaks to avoid self-pickup", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = voiceSettings.safeEchoProtection,
                                onCheckedChange = { checked -> scope.launch { voiceSettingsRepository.updateSafeEchoProtection(checked) } }
                            )
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun SectionHeader(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(bottom = 8.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguageDropdown(
    label: String,
    selectedTag: String,
    onSelect: (String) -> Unit
) {
    val languages = TtsEngineDiscovery.getStandardLanguages()
    var expanded by remember { mutableStateOf(false) }
    val currentLabel = languages.firstOrNull { it.first == selectedTag }?.second ?: selectedTag

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = currentLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            languages.forEach { (tag, name) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        onSelect(tag)
                        expanded = false
                    }
                )
            }
        }
    }
}
