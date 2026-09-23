package com.lichiai.calling.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SettingsPhone
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lichiai.ChatViewModel
import com.lichiai.calling.data.CallHandlingSettings
import com.lichiai.calling.data.IncomingCallsRule
import com.lichiai.calling.data.UnknownCallsRule
import com.lichiai.calling.permission.CallPermissionManager
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HandleMyCallsSettingsScreen(
    viewModel: ChatViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val callSettings by viewModel.callHandlingSettingsRepo.settings.collectAsState(initial = CallHandlingSettings())
    val permState by viewModel.callPermissionManager.permissionState.collectAsState()
    val callSession by viewModel.callStateMonitor.callSessionState.collectAsState()

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.callPermissionManager.checkPermissions()
    }

    LaunchedEffect(Unit) {
        viewModel.callPermissionManager.checkPermissions()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Handle My Calls", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Master Header Card
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (callSettings.callHandlingEnabled)
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                    else
                        MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Call,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Handle My Calls",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Let Lichi help manage your calls.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = callSettings.callHandlingEnabled,
                        onCheckedChange = { isEnabled ->
                            coroutineScope.launch {
                                viewModel.callHandlingSettingsRepo.update { it.copy(callHandlingEnabled = isEnabled) }
                            }
                        }
                    )
                }
            }

            // Permissions Card
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Security, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text("System Permissions", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                        if (!permState.fullCallHandlingGranted) {
                            FilledTonalButton(
                                onClick = {
                                    permissionLauncher.launch(CallPermissionManager.ALL_CALL_HANDLING_PERMISSIONS)
                                }
                            ) {
                                Text("Grant All")
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    PermissionItem("Contacts Access", permState.hasReadContacts)
                    PermissionItem("Make Calls", permState.hasCallPhone)
                    PermissionItem("Read Phone State", permState.hasReadPhoneState)
                    PermissionItem("Answer Phone Calls", permState.hasAnswerPhoneCalls)
                    PermissionItem("Microphone (Voice)", permState.hasRecordAudio)

                    if (!permState.fullCallHandlingGranted) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Note: Grant permissions so Lichi can inspect incoming caller names and control calls via natural language.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            // Incoming Calls Settings
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Incoming Calls", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                    SettingToggleRow(
                        title = "Show Call Controls",
                        subtitle = "Display interactive caller pill on screen and Dynamic Island",
                        checked = callSettings.showDynamicIslandControls,
                        onCheckedChange = { checked ->
                            coroutineScope.launch {
                                viewModel.callHandlingSettingsRepo.update { it.copy(showDynamicIslandControls = checked) }
                            }
                        }
                    )

                    HorizontalDivider()

                    SettingToggleRow(
                        title = "Allow \"Answer Call\" Voice Command",
                        subtitle = "Support \"Phone utha lo\", \"Answer\", \"Pick up\"",
                        checked = callSettings.allowAnswerVoiceCommand,
                        onCheckedChange = { checked ->
                            coroutineScope.launch {
                                viewModel.callHandlingSettingsRepo.update { it.copy(allowAnswerVoiceCommand = checked) }
                            }
                        }
                    )

                    HorizontalDivider()

                    SettingToggleRow(
                        title = "Allow \"Reject Call\" Voice Command",
                        subtitle = "Support \"Call reject kar do\", \"Mat uthao\"",
                        checked = callSettings.allowRejectVoiceCommand,
                        onCheckedChange = { checked ->
                            coroutineScope.launch {
                                viewModel.callHandlingSettingsRepo.update { it.copy(allowRejectVoiceCommand = checked) }
                            }
                        }
                    )
                }
            }

            // Active Calls Settings
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Active Calls", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                    SettingToggleRow(
                        title = "Voice Call Controls",
                        subtitle = "Support \"Speaker on/off\", \"Mute/unmute\", \"Hold\", \"End call\"",
                        checked = callSettings.allowVoiceCallControls,
                        onCheckedChange = { checked ->
                            coroutineScope.launch {
                                viewModel.callHandlingSettingsRepo.update { it.copy(allowVoiceCallControls = checked) }
                            }
                        }
                    )

                    HorizontalDivider()

                    SettingToggleRow(
                        title = "Ask Before Taking Action",
                        subtitle = "Lichi announces caller and confirms before answering/rejecting",
                        checked = callSettings.askBeforeAction,
                        onCheckedChange = { checked ->
                            coroutineScope.launch {
                                viewModel.callHandlingSettingsRepo.update { it.copy(askBeforeAction = checked) }
                            }
                        }
                    )
                }
            }

            // Automation & Rules
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Unknown Callers Rule", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("What should Lichi do when an unknown number calls?", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                    Spacer(Modifier.height(4.dp))

                    RuleSelectionRow(
                        title = "Always Ask Me",
                        subtitle = "Announce unknown number and wait for decision",
                        selected = callSettings.unknownCallsRule == UnknownCallsRule.ALWAYS_ASK,
                        onClick = {
                            coroutineScope.launch {
                                viewModel.callHandlingSettingsRepo.update { it.copy(unknownCallsRule = UnknownCallsRule.ALWAYS_ASK) }
                            }
                        }
                    )

                    RuleSelectionRow(
                        title = "Always Reject",
                        subtitle = "Automatically reject calls from unknown numbers",
                        selected = callSettings.unknownCallsRule == UnknownCallsRule.ALWAYS_REJECT,
                        onClick = {
                            coroutineScope.launch {
                                viewModel.callHandlingSettingsRepo.update { it.copy(unknownCallsRule = UnknownCallsRule.ALWAYS_REJECT) }
                            }
                        }
                    )

                    RuleSelectionRow(
                        title = "Never Interfere",
                        subtitle = "Do not show controls or prompt for unknown callers",
                        selected = callSettings.unknownCallsRule == UnknownCallsRule.NEVER_INTERFERE,
                        onClick = {
                            coroutineScope.launch {
                                viewModel.callHandlingSettingsRepo.update { it.copy(unknownCallsRule = UnknownCallsRule.NEVER_INTERFERE) }
                            }
                        }
                    )
                }
            }

            // Interactive Simulator & Test Deck
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("Live Call Simulator & Test Deck", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }

                    Text(
                        "Test call states, voice phrases, and Dynamic Island controllers right on this device without requiring a cellular network.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                viewModel.callStateMonitor.simulateIncomingCall(
                                    callerName = "Rahul Sharma",
                                    phoneNumber = "+91 98765 43210"
                                )
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Incoming Call", fontSize = 12.sp)
                        }

                        OutlinedButton(
                            onClick = {
                                viewModel.callStateMonitor.simulateActiveCall(
                                    callerName = "Rahul Sharma",
                                    phoneNumber = "+91 98765 43210"
                                )
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Active Call", fontSize = 12.sp)
                        }

                        OutlinedButton(
                            onClick = {
                                viewModel.callStateMonitor.simulateEndCall()
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("End Call", fontSize = 12.sp)
                        }
                    }

                    if (callSession.isCallActiveOrRinging) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surface)
                                .padding(12.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.PhoneInTalk, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text("Current State: ${callSession.callState.name}", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                    Text("Caller: ${callSession.displayTitle} (${callSession.formattedDuration})", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun PermissionItem(label: String, granted: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (granted) Icons.Default.CheckCircle else Icons.Default.Error,
            contentDescription = null,
            tint = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (granted) FontWeight.Normal else FontWeight.Medium
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = if (granted) "Granted" else "Missing",
            style = MaterialTheme.typography.bodySmall,
            color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )
    }
}

@Composable
private fun SettingToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun RuleSelectionRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
