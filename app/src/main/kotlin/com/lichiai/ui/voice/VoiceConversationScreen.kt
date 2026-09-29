package com.lichiai.ui.voice

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.lichiai.data.AppSettings
import com.lichiai.data.Assistant
import com.lichiai.data.ProviderConfig
import com.lichiai.ui.LichiVisualTokens
import com.lichiai.voice.VoiceConversationOrchestrator
import com.lichiai.voice.conversation.VoiceState

@Composable
fun VoiceConversationScreen(
    orchestrator: VoiceConversationOrchestrator,
    activeProvider: ProviderConfig?,
    activeAssistant: Assistant?,
    activeSettings: AppSettings,
    onOpenVoiceSettings: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val sessionState by orchestrator.sessionState.collectAsState()
    val agentStatus by orchestrator.agentLiveStatus.collectAsState()
    val webActivityState by orchestrator.webActivityState.collectAsState()
    val voiceSettings by orchestrator.voiceSettingsRepository.settings.collectAsState(initial = com.lichiai.data.VoiceSettings())

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
    }

    LaunchedEffect(hasMicPermission) {
        if (hasMicPermission) {
            orchestrator.startSession(activeProvider, activeAssistant)
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    LaunchedEffect(activeProvider, activeAssistant) {
        orchestrator.setContextInfo(activeProvider, activeAssistant)
    }

    DisposableEffect(Unit) {
        onDispose {
            orchestrator.stopSession()
        }
    }

    // Clean, premium white / light background (supporting dark theme gracefully)
    val isDark = MaterialTheme.colorScheme.background.red < 0.5f
    val bgBrush = if (isDark) {
        Brush.verticalGradient(
            colors = listOf(
                Color(0xFF0F172A),
                Color(0xFF0B1120),
                Color(0xFF020617)
            )
        )
    } else {
        Brush.verticalGradient(
            colors = listOf(
                Color(0xFFFFFFFF),
                Color(0xFFFBFBFE),
                Color(0xFFF3F1FA)
            )
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgBrush)
            .padding(WindowInsets.statusBars.asPaddingValues())
            .padding(WindowInsets.navigationBars.asPaddingValues())
    ) {
        if (!hasMicPermission) {
            // Permission Request State
            PermissionRequestCard(
                onRequestPermission = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                onClose = onClose
            )
        } else {
            // Active Voice Conversation View matching reference design
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // 1. Top Header Bar: Model Selector on Left, Controls on Right
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Top-Left: Model & Assistant Pill
                    val modelDisplay = if (voiceSettings.voiceEngine == com.lichiai.data.VoiceEngine.GEMINI_LIVE) {
                        "Gemini Live (${voiceSettings.geminiLiveVoice})"
                    } else {
                        activeSettings.activeModel.ifBlank {
                            activeProvider?.models?.firstOrNull() ?: "Auto"
                        }
                    }
                    val brandName = activeAssistant?.name ?: "LICHI-AI"

                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(20.dp),
                        shadowElevation = 2.dp,
                        border = androidx.compose.foundation.BorderStroke(
                            0.5.dp,
                            MaterialTheme.colorScheme.outlineVariant
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(LichiVisualTokens.BrandPurple)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = brandName,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = " · $modelDisplay",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    // Top-Right: Quick Controls (Mic Mute toggle + Voice Settings + Close)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Mic Mute Status Toggle
                        IconButton(
                            onClick = { orchestrator.toggleMute() },
                            modifier = Modifier
                                .size(40.dp)
                                .shadow(2.dp, CircleShape)
                                .background(
                                    if (sessionState.isMicMuted) MaterialTheme.colorScheme.errorContainer
                                    else MaterialTheme.colorScheme.surface,
                                    CircleShape
                                )
                                .border(
                                    0.5.dp,
                                    if (sessionState.isMicMuted) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.outlineVariant,
                                    CircleShape
                                )
                        ) {
                            Icon(
                                imageVector = if (sessionState.isMicMuted) Icons.Default.MicOff else Icons.Default.Mic,
                                contentDescription = if (sessionState.isMicMuted) "Unmute Microphone" else "Mute Microphone",
                                tint = if (sessionState.isMicMuted) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        // Voice Settings Gear
                        IconButton(
                            onClick = onOpenVoiceSettings,
                            modifier = Modifier
                                .size(40.dp)
                                .shadow(2.dp, CircleShape)
                                .background(MaterialTheme.colorScheme.surface, CircleShape)
                                .border(
                                    0.5.dp,
                                    MaterialTheme.colorScheme.outlineVariant,
                                    CircleShape
                                )
                        ) {
                            Icon(
                                Icons.Default.Settings,
                                contentDescription = "Voice Settings",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        // Exit / Close button
                        IconButton(
                            onClick = onClose,
                            modifier = Modifier
                                .size(40.dp)
                                .shadow(2.dp, CircleShape)
                                .background(MaterialTheme.colorScheme.surface, CircleShape)
                                .border(
                                    0.5.dp,
                                    MaterialTheme.colorScheme.outlineVariant,
                                    CircleShape
                                )
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Exit Voice Mode",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                // 2. Center Animated Voice Orb & Real-time Status Text
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        // Interactive Voice Orb (Tap to Interrupt / Speak)
                        val orbInteractionSource = remember { MutableInteractionSource() }
                        val isOrbPressed by orbInteractionSource.collectIsPressedAsState()

                        Box(
                            modifier = Modifier
                                .clickable(
                                    interactionSource = orbInteractionSource,
                                    indication = null
                                ) {
                                    if (sessionState.state == VoiceState.SPEAKING || sessionState.state == VoiceState.THINKING) {
                                        orchestrator.interruptAndStartListening()
                                    }
                                }
                        ) {
                            VoiceOrb(
                                state = sessionState.state,
                                rms = sessionState.currentRms,
                                baseColor = LichiVisualTokens.BrandPurple,
                                agentStatus = agentStatus,
                                isPressed = isOrbPressed
                            )
                        }

                        Spacer(Modifier.height(24.dp))

                        // Status Headline
                        val isAgentRunning = agentStatus.state != com.lichiai.agent.model.AgentExecutionState.IDLE
                        val statusText = when {
                            isAgentRunning -> {
                                if (agentStatus.activityText.isNotBlank()) "Agent: ${agentStatus.activityText}"
                                else "Agent: ${agentStatus.state.name.lowercase().replaceFirstChar { it.uppercase() }}..."
                            }
                            sessionState.isMicMuted -> "Microphone Muted"
                            sessionState.state == VoiceState.LISTENING -> "Listening..."
                            sessionState.state == VoiceState.TRANSCRIBING -> "Listening to you..."
                            sessionState.state == VoiceState.THINKING -> sessionState.activeAssistantText.ifBlank { "Connecting..." }
                            sessionState.state == VoiceState.SPEAKING -> "Speaking (Tap orb to interrupt)"
                            sessionState.state == VoiceState.INTERRUPTED -> "Interrupted"
                            sessionState.state == VoiceState.ERROR -> sessionState.errorMessage ?: "Connection error"
                            sessionState.state == VoiceState.PAUSED -> "Paused"
                            else -> "Connecting..."
                        }

                        Text(
                            text = statusText,
                            color = when {
                                sessionState.state == VoiceState.ERROR -> Color(0xFFEF4444)
                                sessionState.isMicMuted -> Color(0xFFEF4444)
                                sessionState.state == VoiceState.SPEAKING -> LichiVisualTokens.BrandPurple
                                sessionState.state == VoiceState.THINKING -> LichiVisualTokens.BrandPurple.copy(alpha = 0.85f)
                                sessionState.state == VoiceState.LISTENING -> LichiVisualTokens.BrandPurple
                                isDark -> Color(0xFFE2E8F0)
                                else -> LichiVisualTokens.TextNavy
                            },
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center
                        )

                        if (sessionState.state == VoiceState.ERROR) {
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick = { orchestrator.startSession(activeProvider, activeAssistant) },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                            ) {
                                Text("Retry Connection", color = MaterialTheme.colorScheme.onError)
                            }
                        }
                    }
                }

                // 3. Lower Live Transcripts & Status Card
                val hasActiveText = sessionState.partialUserText.isNotBlank() || sessionState.activeAssistantText.isNotBlank()
                val lastTurn = sessionState.historyTurns.lastOrNull()
                val showTranscriptCard = hasActiveText || (lastTurn != null && lastTurn.userText.isNotBlank()) || webActivityState.status != com.lichiai.web.model.WebActivityStatus.IDLE

                AnimatedVisibility(
                    visible = showTranscriptCard,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp)
                    ) {
                        if (webActivityState.status != com.lichiai.web.model.WebActivityStatus.IDLE) {
                            com.lichiai.web.ui.WebActivityCard(
                                activityState = webActivityState,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                        }

                        if (hasActiveText || (lastTurn != null && lastTurn.userText.isNotBlank())) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surface
                                ),
                                shape = RoundedCornerShape(18.dp),
                                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                                border = androidx.compose.foundation.BorderStroke(
                                    0.5.dp,
                                    MaterialTheme.colorScheme.outlineVariant
                                )
                            ) {
                                Column(
                                    modifier = Modifier
                                        .padding(horizontal = 16.dp, vertical = 14.dp)
                                        .heightIn(max = 160.dp)
                                        .verticalScroll(rememberScrollState())
                                ) {
                                    if (hasActiveText) {
                                        if (sessionState.partialUserText.isNotBlank()) {
                                            Text(
                                                text = "You: ${sessionState.partialUserText}",
                                                color = MaterialTheme.colorScheme.onSurface,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Medium
                                            )
                                        }

                                        if (sessionState.activeAssistantText.isNotBlank()) {
                                            if (sessionState.partialUserText.isNotBlank()) {
                                                Spacer(Modifier.height(6.dp))
                                            }
                                            Text(
                                                text = sessionState.activeAssistantText,
                                                color = MaterialTheme.colorScheme.primary,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Medium
                                            )
                                        }
                                    } else if (lastTurn != null) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "✓ Saved to chat history",
                                                color = Color(0xFF10B981),
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                        Spacer(Modifier.height(6.dp))
                                        if (lastTurn.userText.isNotBlank()) {
                                            Text(
                                                text = "You: ${lastTurn.userText}",
                                                color = MaterialTheme.colorScheme.onSurface,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Normal
                                            )
                                        }
                                        if (lastTurn.assistantText.isNotBlank()) {
                                            Spacer(Modifier.height(6.dp))
                                            Text(
                                                text = "${activeAssistant?.name ?: "LICHI AI"}: ${lastTurn.assistantText}",
                                                color = MaterialTheme.colorScheme.primary,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Medium
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 4. Bottom Centered Action Pill (3 Controls: Mic, Main Action / End, Keyboard / Text)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(32.dp),
                        shadowElevation = 3.dp,
                        border = androidx.compose.foundation.BorderStroke(
                            0.5.dp,
                            MaterialTheme.colorScheme.outlineVariant
                        ),
                        modifier = Modifier.padding(horizontal = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(20.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Left: Microphone / Voice Input Toggle
                            IconButton(
                                onClick = { orchestrator.toggleMute() },
                                modifier = Modifier
                                    .size(48.dp)
                                    .background(
                                        if (sessionState.isMicMuted) MaterialTheme.colorScheme.errorContainer
                                        else MaterialTheme.colorScheme.surfaceVariant,
                                        CircleShape
                                    )
                            ) {
                                Icon(
                                    imageVector = if (sessionState.isMicMuted) Icons.Default.MicOff else Icons.Default.Mic,
                                    contentDescription = if (sessionState.isMicMuted) "Unmute Microphone" else "Mute Microphone",
                                    tint = if (sessionState.isMicMuted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }

                            // Center: Primary Voice Mode Action (End Call / Stop button)
                            IconButton(
                                onClick = onClose,
                                modifier = Modifier
                                    .size(58.dp)
                                    .background(Color(0xFFEF4444), CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Phone,
                                    contentDescription = "End Voice Conversation",
                                    tint = Color.White,
                                    modifier = Modifier.size(28.dp)
                                )
                            }

                            // Right: Keyboard / Switch to Text Chat Mode
                            IconButton(
                                onClick = onClose,
                                modifier = Modifier
                                    .size(48.dp)
                                    .background(
                                        MaterialTheme.colorScheme.surfaceVariant,
                                        CircleShape
                                    )
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Keyboard,
                                    contentDescription = "Switch to Text Mode",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionRequestCard(
    onRequestPermission: () -> Unit,
    onClose: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .padding(24.dp)
                .fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(20.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Mic,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp)
                    )
                }

                Spacer(Modifier.height(16.dp))

                Text(
                    "Microphone Permission Required",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(Modifier.height(8.dp))

                Text(
                    "To enable continuous voice conversations with LICHI AI, please grant microphone recording permission.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(24.dp))

                Button(
                    onClick = onRequestPermission,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("Grant Permission", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
                }

                Spacer(Modifier.height(8.dp))

                Button(
                    onClick = onClose,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
