package com.lichiai.ui.voice

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.lichiai.data.AppSettings
import com.lichiai.data.Assistant
import com.lichiai.data.ProviderConfig
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
            orchestrator.startSession(activeProvider, activeAssistant)
        }
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

    // Fullscreen Ambient Dark Background
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF0F172A),
                        Color(0xFF020617),
                        Color(0xFF000000)
                    )
                )
            )
    ) {
        if (!hasMicPermission) {
            // Permission Request State
            PermissionRequestCard(
                onRequestPermission = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                onClose = onClose
            )
        } else {
            // Active Voice Conversation View
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Top Header Pill & Controls
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Close button
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier
                            .size(44.dp)
                            .background(Color.White.copy(alpha = 0.1f), CircleShape)
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Exit Voice Mode",
                            tint = Color.White
                        )
                    }

                    // Model & Assistant Pill
                    Surface(
                        color = Color.White.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = activeAssistant?.name ?: "LICHI AI",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            val modelDisplay = activeSettings.activeModel.ifBlank { activeProvider?.models?.firstOrNull() ?: "Auto" }
                            Text(
                                text = " · $modelDisplay",
                                color = Color(0xFF94A3B8),
                                fontSize = 13.sp
                            )
                        }
                    }

                    // Voice Settings Gear
                    IconButton(
                        onClick = onOpenVoiceSettings,
                        modifier = Modifier
                            .size(44.dp)
                            .background(Color.White.copy(alpha = 0.1f), CircleShape)
                    ) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "Voice Settings",
                            tint = Color.White
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Center Animated Voice Orb
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
                        Box(
                            modifier = Modifier
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) {
                                    if (sessionState.state == VoiceState.SPEAKING || sessionState.state == VoiceState.THINKING) {
                                        orchestrator.interruptAndStartListening()
                                    }
                                }
                        ) {
                            VoiceOrb(
                                state = sessionState.state,
                                rms = sessionState.currentRms
                            )
                        }

                        Spacer(Modifier.height(20.dp))

                        // Status Headline
                        val statusText = when (sessionState.state) {
                            VoiceState.LISTENING -> "Listening..."
                            VoiceState.TRANSCRIBING -> "Listening to you..."
                            VoiceState.THINKING -> "Thinking..."
                            VoiceState.SPEAKING -> "Speaking (Tap orb to interrupt)"
                            VoiceState.INTERRUPTED -> "Interrupted"
                            VoiceState.ERROR -> sessionState.errorMessage ?: "Speech error"
                            VoiceState.PAUSED -> "Microphone Muted"
                            VoiceState.IDLE -> "Connecting..."
                        }

                        Text(
                            text = statusText,
                            color = when (sessionState.state) {
                                VoiceState.ERROR -> Color(0xFFFF5252)
                                VoiceState.SPEAKING -> Color(0xFFFFD700)
                                VoiceState.THINKING -> Color(0xFFE040FB)
                                VoiceState.LISTENING -> Color(0xFF00E5FF)
                                else -> Color(0xFFCBD5E1)
                            },
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                // Live Transcripts Floating Area
                AnimatedVisibility(
                    visible = sessionState.partialUserText.isNotBlank() || sessionState.activeAssistantText.isNotBlank(),
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = Color.White.copy(alpha = 0.08f)
                        ),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .padding(16.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            if (sessionState.partialUserText.isNotBlank()) {
                                Text(
                                    text = "You: ${sessionState.partialUserText}",
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Normal
                                )
                            }

                            if (sessionState.activeAssistantText.isNotBlank()) {
                                if (sessionState.partialUserText.isNotBlank()) {
                                    Spacer(Modifier.height(8.dp))
                                }
                                Text(
                                    text = sessionState.activeAssistantText,
                                    color = Color(0xFF93C5FD),
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }

                // Bottom Action Bar Controls
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Mute / Unmute Button
                    IconButton(
                        onClick = { orchestrator.toggleMute() },
                        modifier = Modifier
                            .size(56.dp)
                            .background(
                                if (sessionState.isMicMuted) Color(0xFFEF4444).copy(alpha = 0.25f)
                                else Color.White.copy(alpha = 0.12f),
                                CircleShape
                            )
                    ) {
                        Icon(
                            if (sessionState.isMicMuted) Icons.Default.MicOff else Icons.Default.Mic,
                            contentDescription = "Toggle Mute",
                            tint = if (sessionState.isMicMuted) Color(0xFFEF4444) else Color.White,
                            modifier = Modifier.size(26.dp)
                        )
                    }

                    // End Call Main Action Button
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier
                            .size(72.dp)
                            .background(Color(0xFFEF4444), CircleShape)
                    ) {
                        Icon(
                            Icons.Default.Phone,
                            contentDescription = "End Voice Conversation",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    // Switch to Keyboard / Text Chat Mode
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier
                            .size(56.dp)
                            .background(Color.White.copy(alpha = 0.12f), CircleShape)
                    ) {
                        Icon(
                            Icons.Default.Keyboard,
                            contentDescription = "Switch to Text Mode",
                            tint = Color.White,
                            modifier = Modifier.size(26.dp)
                        )
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
            shape = RoundedCornerShape(20.dp)
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
                    textAlign = TextAlign.Center
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
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Grant Permission")
                }

                Spacer(Modifier.height(8.dp))

                Button(
                    onClick = onClose,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Cancel")
                }
            }
        }
    }
}
