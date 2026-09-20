package com.lichiai.ui.voice

import android.content.Context
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
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import com.lichiai.data.SttMode
import com.lichiai.data.TtsMode
import com.lichiai.data.VoiceSettings
import com.lichiai.data.VoiceSettingsRepository
import com.lichiai.voice.VoiceConversationOrchestrator
import com.lichiai.voice.stt.SttProviderDiscovery
import com.lichiai.voice.stt.SttProviderInfo
import com.lichiai.voice.tts.TtsEngineDiscovery
import com.lichiai.voice.tts.TtsEngineInfo
import com.lichiai.voice.tts.TtsVoiceInfo
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceSettingsScreen(
    voiceSettingsRepository: VoiceSettingsRepository,
    orchestrator: VoiceConversationOrchestrator,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val voiceSettings by voiceSettingsRepository.settings.collectAsState(initial = VoiceSettings())

    var sttProviders by remember { mutableStateOf<List<SttProviderInfo>>(emptyList()) }
    var ttsEngines by remember { mutableStateOf<List<TtsEngineInfo>>(emptyList()) }
    var availableVoices by remember { mutableStateOf<List<TtsVoiceInfo>>(emptyList()) }

    fun refreshDiscovery() {
        sttProviders = SttProviderDiscovery.discoverProviders(context)
        ttsEngines = TtsEngineDiscovery.discoverEngines(context)
        availableVoices = orchestrator.getAvailableTtsVoices()
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
