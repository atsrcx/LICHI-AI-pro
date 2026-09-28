package com.lichiai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Assistant
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.ui.platform.LocalContext
import com.lichiai.assistant.role.AssistantRoleHelper
import com.lichiai.agent.accessibility.LichiAccessibilityService
import com.lichiai.agent.data.AgentSettingsRepository
import androidx.compose.runtime.collectAsState
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import android.provider.Settings
import android.content.Intent
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lichiai.R
import com.lichiai.data.AppSettings
import com.lichiai.data.Assistant
import com.lichiai.data.ProviderConfig

@Composable
fun SettingsScreen(
    settings: AppSettings,
    providers: List<ProviderConfig>,
    assistants: List<Assistant>,
    onBack: () -> Unit,
    onChange: ((AppSettings) -> AppSettings) -> Unit,
    onOpenProviders: () -> Unit,
    onOpenAssistants: () -> Unit,
    onOpenVoiceSettings: () -> Unit = {},
    onOpenHandleMyCalls: () -> Unit = {},
    onOpenCallDiagnostics: () -> Unit = {},
    onOpenDynamicIsland: () -> Unit = {},
    onOpenSkills: () -> Unit = {},
    onOpenWebSearch: () -> Unit = {},
    onOpenNotificationSettings: () -> Unit = {},
    onOpenAgentVision: () -> Unit = {},
    onOpenReminders: () -> Unit = {},
    onOpenTerminal: () -> Unit = {}
) {
    val context = LocalContext.current
    var isDefaultAssistant by remember { mutableStateOf(AssistantRoleHelper.isDefaultAssistant(context)) }
    val assistantRoleLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) {
        isDefaultAssistant = AssistantRoleHelper.isDefaultAssistant(context)
    }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        isDefaultAssistant = AssistantRoleHelper.isDefaultAssistant(context)
    }

    val agentSettingsRepo = remember { AgentSettingsRepository.getInstance(context) }
    val agentSettings by agentSettingsRepo.settings.collectAsState()
    var agentKeyVisible by remember { mutableStateOf(false) }

    // Local state seeded once; LaunchedEffect resyncs only when external settings change.
    var system by rememberSaveable { mutableStateOf(settings.systemPrompt) }
    var temperature by rememberSaveable { mutableStateOf(settings.temperature) }
    val stream = settings.stream

    androidx.compose.runtime.LaunchedEffect(settings.systemPrompt) {
        if (settings.systemPrompt != system) system = settings.systemPrompt
    }
    androidx.compose.runtime.LaunchedEffect(settings.temperature) {
        if (settings.temperature != temperature) temperature = settings.temperature
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(WindowInsets.statusBars.asPaddingValues())
    ) {
        SettingsTopBar(title = stringResource(R.string.settings), onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            // Providers entry
            SectionCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenProviders)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Storage, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.providers),
                            style = MaterialTheme.typography.titleMedium)
                        Text("${providers.size} configured",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Default.ChevronRight, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // Assistants entry
            SectionCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenAssistants)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Person, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.assistants),
                            style = MaterialTheme.typography.titleMedium)
                        val active = assistants.firstOrNull { it.id == settings.activeAssistantId }
                        Text(
                            active?.let { "${it.avatar}  ${it.name}" }
                                ?: "${assistants.size} assistants",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(Icons.Default.ChevronRight, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // Voice & Conversation Mode entry
            SectionCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenVoiceSettings)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Mic, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Voice & Speech Recognition",
                            style = MaterialTheme.typography.titleMedium)
                        Text("STT engines, TTS voices, barge-in & pitch",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Default.ChevronRight, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // Notification Intelligence entry
            SectionCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenNotificationSettings)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Notifications,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Notification Intelligence",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            "Inspect notifications, messages, missed calls & OTP privacy",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Handle My Calls entry
            SectionCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenHandleMyCalls)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Call, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Handle My Calls",
                            style = MaterialTheme.typography.titleMedium)
                        Text("AI call control, Dynamic Island call controls & smart rules",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Default.ChevronRight, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // Universal Calling & Contacts entry
            SectionCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenCallDiagnostics)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Call, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Calling & Contacts System",
                            style = MaterialTheme.typography.titleMedium)
                        Text("SIM & device contacts, query tester, aliases & logs",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Default.ChevronRight, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // Lichi Time Engine entry
            SectionCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenReminders)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Notifications, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Reminders & Alarms (Time Engine)",
                            style = MaterialTheme.typography.titleMedium)
                        Text("Alarms, recurring routines, calendar timeline & task checklists",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Default.ChevronRight, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // Dynamic Island / Floating Assistant Surface entry
            SectionCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenDynamicIsland)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Dynamic Island / Floating Surface",
                            style = MaterialTheme.typography.titleMedium)
                        Text("Draggable overlay, live assistant states, styles & gestures",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Default.ChevronRight, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // Skills Library entry
            SectionCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenSkills)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Skills Library",
                            style = MaterialTheme.typography.titleMedium)
                        Text("Autonomous Agent V2 skills, workflows & Markdown manager",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Default.ChevronRight, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // Agent Vision entry
            SectionCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenAgentVision)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Visibility,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Agent Vision",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            "Desktop cursor pointer, target highlights, presets, speeds & live preview",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Web Intelligence & Search entry
            SectionCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenWebSearch)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Language, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Web Intelligence & Search",
                            style = MaterialTheme.typography.titleMedium)
                        Text("Exa, Tavily, Brave & Serper, fallbacks, deep research & live sources",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Default.ChevronRight, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // Terminal V2 & SSH Subsystem entry
            SectionCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenTerminal)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Terminal,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Terminal V2",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            "Termux local shell, SSH sessions, PTY emulator & SFTP client",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Universal LLM Task Orchestrator V2 entry
            SectionCard {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Task Orchestrator V2",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                "LLM-Native multi-step planning & capability coordination",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    val modes = listOf("ENABLED", "CANARY", "SHADOW", "DISABLED")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        modes.forEach { modeName ->
                            val selected = settings.orchestratorMode.equals(modeName, ignoreCase = true)
                            androidx.compose.material3.FilterChip(
                                selected = selected,
                                onClick = { onChange { it.copy(orchestratorMode = modeName) } },
                                label = {
                                    Text(
                                        modeName,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            )
                        }
                    }
                }
            }

            // Platform Intelligence (#Spy) section
            SectionHeader("Platform Intelligence (#Spy)")
            SectionCard {
                var apifyTokenVisible by rememberSaveable { mutableStateOf(false) }
                var tokenText by rememberSaveable { mutableStateOf(settings.apifyApiToken) }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Storage,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Apify Intelligence Runtime",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                "Public Actor scraping on #Spy trigger (Instagram, YouTube, Reddit, TikTok, X)",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    Text(
                        "Apify API Token",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BasicTextField(
                            value = tokenText,
                            onValueChange = {
                                tokenText = it
                                onChange { s -> s.copy(apifyApiToken = it) }
                            },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            visualTransformation = if (apifyTokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            textStyle = LocalTextStyle.current.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 14.sp
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary)
                        )
                        IconButton(
                            onClick = { apifyTokenVisible = !apifyTokenVisible },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = if (apifyTokenVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (apifyTokenVisible) "Hide token" else "Show token",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Free-First Actor Policy",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                "Prioritize free & pay-per-result Actors over monthly subscriptions",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = settings.spyFreeFirstOnly,
                            onCheckedChange = { chk ->
                                onChange { it.copy(spyFreeFirstOnly = chk) }
                            }
                        )
                    }
                }
            }

            // System Default Digital Assistant entry
            SectionHeader(stringResource(R.string.section_system_assistant))
            SectionCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val intent = AssistantRoleHelper.createDefaultAssistantRequestIntent(context)
                            try {
                                assistantRoleLauncher.launch(intent)
                            } catch (e: Exception) {
                                android.util.Log.e("SettingsScreen", "Failed launching assistant role intent", e)
                            }
                        }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Assistant,
                        contentDescription = null,
                        tint = if (isDefaultAssistant) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(R.string.system_assistant_title),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Spacer(Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(
                                        if (isDefaultAssistant) MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surfaceVariant
                                    )
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.system_assistant_role_tag),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isDefaultAssistant) MaterialTheme.colorScheme.onPrimaryContainer
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = if (isDefaultAssistant) stringResource(R.string.system_assistant_active)
                            else stringResource(R.string.system_assistant_inactive),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isDefaultAssistant) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        imageVector = if (isDefaultAssistant) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                        contentDescription = null,
                        tint = if (isDefaultAssistant) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                    )
                }
            }

            // Autonomous Agent V2 Section
            SectionHeader(stringResource(R.string.section_autonomous_agent))
            SectionCard {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.autonomous_agent_title),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = stringResource(R.string.autonomous_agent_desc),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = agentSettings.enabled,
                            onCheckedChange = { checked ->
                                agentSettingsRepo.updateSettings { it.copy(enabled = checked) }
                            }
                        )
                    }

                    if (agentSettings.enabled) {
                        Spacer(Modifier.height(14.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        Spacer(Modifier.height(14.dp))

                        // Reasoning Provider Mode
                        Text(
                            text = stringResource(R.string.autonomous_agent_reasoning_provider),
                            style = MaterialTheme.typography.labelLarge
                        )
                        Spacer(Modifier.height(6.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    agentSettingsRepo.updateSettings { it.copy(useCurrentProvider = !it.useCurrentProvider) }
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = agentSettings.useCurrentProvider,
                                onCheckedChange = { checked ->
                                    agentSettingsRepo.updateSettings { it.copy(useCurrentProvider = checked) }
                                }
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.autonomous_agent_use_current_provider),
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium)
                                )
                                val activeProv = providers.firstOrNull { it.id == settings.activeProviderId } ?: providers.firstOrNull()
                                val activeMdl = settings.activeModel.ifBlank { activeProv?.models?.firstOrNull() ?: "None" }
                                Text(
                                    text = if (agentSettings.useCurrentProvider) {
                                        "Active: ${activeProv?.name ?: "None"} ($activeMdl)"
                                    } else {
                                        stringResource(R.string.autonomous_agent_use_current_provider_desc)
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (agentSettings.useCurrentProvider) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Spacer(Modifier.height(14.dp))

                        if (!agentSettings.useCurrentProvider) {
                            Text(
                                text = stringResource(R.string.autonomous_agent_dedicated_gemini),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.height(8.dp))

                            // Gemini API Key for Agent
                            Text(
                                text = stringResource(R.string.autonomous_agent_api_key_label),
                                style = MaterialTheme.typography.labelMedium
                            )
                            Spacer(Modifier.height(6.dp))
                            OutlinedFieldBox {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    BasicTextField(
                                        value = agentSettings.apiKey,
                                        onValueChange = { newKey ->
                                            agentSettingsRepo.updateSettings { it.copy(apiKey = newKey) }
                                        },
                                        visualTransformation = if (agentKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                        modifier = Modifier.weight(1f),
                                        textStyle = LocalTextStyle.current.copy(
                                            color = LocalContentColor.current,
                                            fontSize = 14.sp
                                        ),
                                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                        singleLine = true,
                                        decorationBox = { innerTextField ->
                                            if (agentSettings.apiKey.isEmpty()) {
                                                Text(
                                                    stringResource(R.string.autonomous_agent_api_key_placeholder),
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                                )
                                            }
                                            innerTextField()
                                        }
                                    )
                                    IconButton(
                                        onClick = { agentKeyVisible = !agentKeyVisible },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (agentKeyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                            contentDescription = "Toggle API Key Visibility",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }

                            Spacer(Modifier.height(14.dp))

                            // Agent Model
                            Text(
                                text = stringResource(R.string.autonomous_agent_model_label),
                                style = MaterialTheme.typography.labelMedium
                            )
                            Spacer(Modifier.height(6.dp))
                            OutlinedFieldBox {
                                BasicTextField(
                                    value = agentSettings.model,
                                    onValueChange = { newModel ->
                                        agentSettingsRepo.updateSettings { it.copy(model = newModel) }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    textStyle = LocalTextStyle.current.copy(
                                        color = LocalContentColor.current,
                                        fontSize = 14.sp
                                    ),
                                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                    singleLine = true
                                )
                            }

                            Spacer(Modifier.height(14.dp))
                        }

                        // Accessibility Status Row
                        val isAccessibilityRunning = LichiAccessibilityService.isServiceRunning()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    try {
                                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                        })
                                    } catch (_: Exception) {}
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isAccessibilityRunning) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                contentDescription = null,
                                tint = if (isAccessibilityRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.autonomous_agent_accessibility_label),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = if (isAccessibilityRunning) stringResource(R.string.autonomous_agent_accessibility_active)
                                    else stringResource(R.string.autonomous_agent_accessibility_inactive),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isAccessibilityRunning) MaterialTheme.colorScheme.onSurfaceVariant
                                    else MaterialTheme.colorScheme.error
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.ChevronRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Behavior
            SectionHeader(stringResource(R.string.section_behavior))
            SectionCard {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Text(stringResource(R.string.setting_system),
                        style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(8.dp))
                    OutlinedFieldBox {
                        BasicTextField(
                            value = system,
                            onValueChange = {
                                system = it
                                onChange { s -> s.copy(systemPrompt = it) }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = LocalTextStyle.current.copy(
                                color = LocalContentColor.current,
                                fontSize = 14.sp,
                                lineHeight = 20.sp
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            minLines = 2,
                            maxLines = 5
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "${stringResource(R.string.setting_temperature)}: ${"%.2f".format(temperature)}",
                        style = MaterialTheme.typography.labelLarge
                    )
                    Slider(
                        value = temperature,
                        onValueChange = {
                            temperature = it
                            onChange { s -> s.copy(temperature = it) }
                        },
                        valueRange = 0f..2f,
                        steps = 19
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.setting_stream),
                                style = MaterialTheme.typography.bodyLarge)
                            Text("Server-Sent Events",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = stream,
                            onCheckedChange = {
                                onChange { s -> s.copy(stream = it) }
                            }
                        )
                    }
                }
            }

            // Appearance
            SectionHeader(stringResource(R.string.setting_appearance))
            SectionCard {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.setting_dynamic_color),
                                style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(R.string.setting_dynamic_color_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = settings.dynamicColor,
                            onCheckedChange = { v ->
                                onChange { s -> s.copy(dynamicColor = v) }
                            }
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.setting_theme_mode),
                        style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(6.dp))
                    SegmentedRow(
                        options = listOf(
                            "system" to stringResource(R.string.setting_theme_system),
                            "light" to stringResource(R.string.setting_theme_light),
                            "dark" to stringResource(R.string.setting_theme_dark)
                        ),
                        selectedKey = settings.themeMode,
                        onSelect = { k -> onChange { s -> s.copy(themeMode = k) } }
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.setting_language),
                        style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(6.dp))
                    SegmentedRow(
                        options = listOf(
                            "system" to stringResource(R.string.setting_language_system),
                            "en" to stringResource(R.string.setting_language_en)
                        ),
                        selectedKey = settings.language,
                        onSelect = { k -> onChange { s -> s.copy(language = k) } }
                    )
                }
            }

            SectionHeader(stringResource(R.string.section_about))
            SectionCard {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Text(stringResource(R.string.about_text),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun SegmentedRow(
    options: List<Pair<String, String>>,
    selectedKey: String,
    onSelect: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(3.dp)
    ) {
        options.forEach { (key, label) ->
            val selected = key == selectedKey
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (selected) MaterialTheme.colorScheme.surface
                        else androidx.compose.ui.graphics.Color.Transparent
                    )
                    .clickable { onSelect(key) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selected) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun SectionCard(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
    ) { content() }
}

@Composable
fun SectionHeader(text: String) {
    Text(
        text,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
fun OutlinedFieldBox(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) { content() }
}

@Composable
fun SettingsTopBar(title: String, onBack: () -> Unit) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "back")
            }
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
    }
}
