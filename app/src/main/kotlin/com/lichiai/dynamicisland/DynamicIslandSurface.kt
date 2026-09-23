package com.lichiai.dynamicisland

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.PhonePaused
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsPhone
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lichiai.calling.action.StructuredCallAction
import com.lichiai.calling.state.CallSessionInfo
import com.lichiai.calling.state.CallState
import kotlinx.coroutines.delay

fun parseColorHex(hex: String, defaultColor: Color): Color {
    return try {
        val clean = hex.trim().removePrefix("#")
        when (clean.length) {
            6 -> Color(android.graphics.Color.parseColor("#$clean"))
            8 -> Color(android.graphics.Color.parseColor("#$clean"))
            else -> defaultColor
        }
    } catch (_: Exception) {
        defaultColor
    }
}

fun getIslandShape(shape: IslandShape, cornerRadiusDp: Int): Shape {
    return when (shape) {
        IslandShape.PILL -> CircleShape
        IslandShape.ROUNDED_RECT -> RoundedCornerShape(cornerRadiusDp.dp)
        IslandShape.CIRCLE -> CircleShape
        IslandShape.SQUARE -> RoundedCornerShape(4.dp)
        IslandShape.CUT_CORNER -> CutCornerShape(cornerRadiusDp.dp)
    }
}

@Composable
fun DynamicIslandSurface(
    config: DynamicIslandConfig,
    state: LichiAssistantState,
    isExpanded: Boolean,
    onExpandChanged: (Boolean) -> Unit,
    onOpenApp: () -> Unit,
    onOpenSettings: () -> Unit,
    onToggleVoice: () -> Unit,
    onStopSession: () -> Unit,
    onCallAction: (StructuredCallAction) -> Unit = {},
    onOpenCallDiagnostics: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val callSession by LichiAssistantStateHub.callSession.collectAsState()

    // Auto collapse timer
    LaunchedEffect(isExpanded, state.uiState, callSession.callState, config.autoCollapse, config.autoCollapseDelaySec) {
        if (isExpanded && config.autoCollapse) {
            val nonCollapsingStates = setOf(
                LichiUiState.LISTENING,
                LichiUiState.THINKING,
                LichiUiState.SPEAKING,
                LichiUiState.CALLING
            )
            if (state.uiState !in nonCollapsingStates && !callSession.isCallActiveOrRinging) {
                delay(config.autoCollapseDelaySec * 1000L)
                onExpandChanged(false)
            }
        }
    }

    val baseBgColor = parseColorHex(config.backgroundColorHex, Color(0xFF1E1A22))
    val primaryColor = parseColorHex(config.primaryColorHex, Color(0xFFD0BCFF))
    val textColor = parseColorHex(config.textColorHex, Color.White)
    val iconColor = parseColorHex(config.iconColorHex, primaryColor)
    val borderColor = parseColorHex(config.borderColorHex, Color(0xFF4A4458))
    val glowColor = parseColorHex(config.glowColorHex, primaryColor)

    val shape = getIslandShape(config.shape, config.cornerRadiusDp)

    val targetWidth = if (isExpanded) config.expandedWidthDp.dp else config.widthDp.dp
    val targetHeight = if (isExpanded) config.expandedHeightDp.dp else config.heightDp.dp

    val islandWidth by animateDpAsState(
        targetValue = targetWidth,
        animationSpec = spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow),
        label = "IslandWidth"
    )
    val islandHeight by animateDpAsState(
        targetValue = targetHeight,
        animationSpec = spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow),
        label = "IslandHeight"
    )

    val glowModifier = if (config.glowEnabled && !config.lowPowerMode) {
        Modifier.drawBehind {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        glowColor.copy(alpha = 0.45f * config.glowIntensity.multiplier),
                        Color.Transparent
                    ),
                    radius = (size.maxDimension / 1.5f) + (config.glowRadiusDp * density)
                )
            )
        }
    } else Modifier

    val borderModifier = if (config.borderEnabled) {
        Modifier.border(
            width = config.borderWidthDp.dp,
            color = borderColor.copy(alpha = config.borderAlpha),
            shape = shape
        )
    } else Modifier

    Box(
        modifier = modifier
            .width(islandWidth)
            .height(islandHeight)
            .graphicsLayer {
                alpha = config.overallAlpha
            }
            .then(glowModifier)
            .shadow(
                elevation = if (config.lowPowerMode) 2.dp else 8.dp,
                shape = shape,
                clip = false
            )
            .clip(shape)
            .background(baseBgColor.copy(alpha = config.backgroundAlpha))
            .then(borderModifier)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        when (config.tapAction) {
                            IslandAction.EXPAND -> onExpandChanged(!isExpanded)
                            IslandAction.OPEN_LICHI -> onOpenApp()
                            IslandAction.TOGGLE_VOICE -> onToggleVoice()
                            IslandAction.OPEN_SETTINGS -> onOpenSettings()
                            else -> onExpandChanged(!isExpanded)
                        }
                    },
                    onDoubleTap = {
                        when (config.doubleTapAction) {
                            IslandAction.TOGGLE_VOICE -> onToggleVoice()
                            IslandAction.EXPAND -> onExpandChanged(!isExpanded)
                            IslandAction.OPEN_LICHI -> onOpenApp()
                            IslandAction.OPEN_SETTINGS -> onOpenSettings()
                            else -> {}
                        }
                    },
                    onLongPress = {
                        when (config.longPressAction) {
                            IslandAction.OPEN_SETTINGS -> onOpenSettings()
                            IslandAction.EXPAND -> onExpandChanged(!isExpanded)
                            IslandAction.TOGGLE_VOICE -> onToggleVoice()
                            IslandAction.OPEN_LICHI -> onOpenApp()
                            else -> onOpenSettings()
                        }
                    }
                )
            }
            .padding(horizontal = if (isExpanded) 16.dp else 12.dp, vertical = if (isExpanded) 14.dp else 6.dp)
    ) {
        AnimatedContent(
            targetState = isExpanded,
            transitionSpec = {
                (fadeIn(animationSpec = tween(220)) + scaleIn(initialScale = 0.92f)) togetherWith
                        (fadeOut(animationSpec = tween(180)) + scaleOut(targetScale = 0.92f))
            },
            label = "IslandExpansion"
        ) { expanded ->
            if (expanded) {
                ExpandedIslandContent(
                    state = state,
                    callSession = callSession,
                    config = config,
                    textColor = textColor,
                    primaryColor = primaryColor,
                    iconColor = iconColor,
                    onCollapse = { onExpandChanged(false) },
                    onOpenApp = onOpenApp,
                    onOpenSettings = onOpenSettings,
                    onToggleVoice = onToggleVoice,
                    onStopSession = onStopSession,
                    onCallAction = onCallAction,
                    onOpenCallDiagnostics = onOpenCallDiagnostics
                )
            } else {
                CollapsedIslandContent(
                    state = state,
                    callSession = callSession,
                    config = config,
                    textColor = textColor,
                    primaryColor = primaryColor,
                    iconColor = iconColor
                )
            }
        }
    }
}

@Composable
private fun CollapsedIslandContent(
    state: LichiAssistantState,
    callSession: CallSessionInfo,
    config: DynamicIslandConfig,
    textColor: Color,
    primaryColor: Color,
    iconColor: Color
) {
    Row(
        modifier = Modifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Leading Section
        if (callSession.isCallActiveOrRinging) {
            CallLeadingIcon(callSession = callSession, config = config)
        } else {
            LeadingStateIcon(
                state = state,
                config = config,
                iconColor = iconColor,
                primaryColor = primaryColor
            )
        }

        Spacer(Modifier.width(8.dp))

        // Center Text
        val labelText = when {
            callSession.callState.isRinging -> "Incoming: ${callSession.displayTitle}"
            callSession.callState.isConnectedOrActive -> "${callSession.displayTitle} (${callSession.formattedDuration})"
            callSession.callState.isOutgoing -> "Calling ${callSession.displayTitle}..."
            state.uiState == LichiUiState.LISTENING && state.transcript.isNotBlank() -> state.transcript
            state.uiState == LichiUiState.SPEAKING && state.responsePreview.isNotBlank() -> state.responsePreview
            else -> state.statusText
        }

        if (config.shape != IslandShape.CIRCLE) {
            Text(
                text = labelText,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.2.sp
                ),
                color = if (callSession.callState.isRinging) Color(0xFF81C784) else textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        } else {
            Spacer(Modifier.weight(1f))
        }

        // Trailing Section: Dynamic Mini Waveform / Activity Indicator
        if (callSession.callState.isConnectedOrActive) {
            Spacer(Modifier.width(8.dp))
            CallPulseDot(color = Color(0xFF81C784), lowPowerMode = config.lowPowerMode)
        } else if (config.showWaveform && (state.uiState == LichiUiState.LISTENING || state.uiState == LichiUiState.SPEAKING)) {
            Spacer(Modifier.width(8.dp))
            MiniWaveform(
                rms = state.rmsLevel,
                color = primaryColor,
                lowPowerMode = config.lowPowerMode
            )
        } else if (state.uiState == LichiUiState.THINKING) {
            Spacer(Modifier.width(8.dp))
            ThinkingPulseDot(color = primaryColor, lowPowerMode = config.lowPowerMode)
        }
    }
}

@Composable
private fun ExpandedIslandContent(
    state: LichiAssistantState,
    callSession: CallSessionInfo,
    config: DynamicIslandConfig,
    textColor: Color,
    primaryColor: Color,
    iconColor: Color,
    onCollapse: () -> Unit,
    onOpenApp: () -> Unit,
    onOpenSettings: () -> Unit,
    onToggleVoice: () -> Unit,
    onStopSession: () -> Unit,
    onCallAction: (StructuredCallAction) -> Unit,
    onOpenCallDiagnostics: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (callSession.isCallActiveOrRinging) Icons.Default.PhoneInTalk else Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = if (callSession.isCallActiveOrRinging) Color(0xFF81C784) else primaryColor,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (callSession.isCallActiveOrRinging) "Call Manager" else "Lichi Assistant",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp
                    ),
                    color = textColor
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Settings",
                        tint = textColor.copy(alpha = 0.7f),
                        modifier = Modifier.size(16.dp)
                    )
                }
                Spacer(Modifier.width(4.dp))
                IconButton(
                    onClick = onCollapse,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = textColor.copy(alpha = 0.7f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // Call Handling Active Views
        if (callSession.callState.isRinging) {
            IncomingCallCard(
                callSession = callSession,
                textColor = textColor,
                onCallAction = onCallAction
            )
        } else if (callSession.callState.isConnectedOrActive) {
            ActiveCallCard(
                callSession = callSession,
                textColor = textColor,
                primaryColor = primaryColor,
                onCallAction = onCallAction
            )
        } else if (callSession.callState.isOutgoing) {
            OutgoingCallCard(
                callSession = callSession,
                textColor = textColor,
                onCallAction = onCallAction
            )
        } else {
            // Standard Assistant Active State View
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = primaryColor.copy(alpha = 0.12f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LeadingStateIcon(
                            state = state,
                            config = config,
                            iconColor = iconColor,
                            primaryColor = primaryColor
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = state.statusText,
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = primaryColor
                        )
                    }

                    if (state.transcript.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "\"${state.transcript}\"",
                            style = MaterialTheme.typography.bodyMedium,
                            color = textColor,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    if (state.responsePreview.isNotBlank() && state.uiState == LichiUiState.SPEAKING) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = state.responsePreview,
                            style = MaterialTheme.typography.bodySmall,
                            color = textColor.copy(alpha = 0.9f),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    if (state.toolName != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Tool: ${state.toolName}",
                            style = MaterialTheme.typography.labelSmall,
                            color = primaryColor
                        )
                    }

                    if (state.callingTarget != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Calling: ${state.callingTarget}",
                            style = MaterialTheme.typography.labelSmall,
                            color = primaryColor
                        )
                    }
                }
            }

            // Interactive Action Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (state.uiState == LichiUiState.LISTENING || state.uiState == LichiUiState.SPEAKING || state.uiState == LichiUiState.THINKING) {
                    OutlinedButton(
                        onClick = onStopSession,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF897D))
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Stop", fontSize = 12.sp)
                    }
                } else {
                    OutlinedButton(
                        onClick = onToggleVoice,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = primaryColor)
                    ) {
                        Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Voice", fontSize = 12.sp)
                    }
                }

                FilledTonalButton(
                    onClick = onOpenApp,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = primaryColor,
                        contentColor = Color(0xFF1E1A22)
                    )
                ) {
                    Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Open", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun IncomingCallCard(
    callSession: CallSessionInfo,
    textColor: Color,
    onCallAction: (StructuredCallAction) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF2E7D32).copy(alpha = 0.2f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Call, contentDescription = null, tint = Color(0xFF81C784), modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        text = if (callSession.callState == CallState.ANSWERING) "Answering Call..." else "Incoming Call",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color(0xFF81C784)
                    )
                    Text(
                        text = callSession.displayTitle,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = textColor
                    )
                    if (!callSession.callerNumber.isNullOrBlank()) {
                        Text(
                            text = callSession.callerNumber,
                            style = MaterialTheme.typography.bodySmall,
                            color = textColor.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            // Prompt text when waiting for decision or speaking
            if (callSession.decisionQuestion != null || callSession.isWaitingForDecision) {
                val promptDisplay = if (callSession.isWaitingForDecision) {
                    "Listening for your decision..."
                } else {
                    callSession.decisionQuestion ?: "Uthaun ya reject karun?"
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color.Black.copy(alpha = 0.3f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (callSession.isWaitingForDecision) Icons.Default.Mic else Icons.Default.VolumeUp,
                            contentDescription = null,
                            tint = Color(0xFF81C784),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = promptDisplay,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontWeight = FontWeight.Medium
                            ),
                            color = textColor.copy(alpha = 0.95f)
                        )
                    }
                }
            }

            // Action Buttons: Answer, Reject, Ask Lichi
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilledTonalButton(
                    onClick = { onCallAction(StructuredCallAction.AnswerCall()) },
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = Color(0xFF388E3C),
                        contentColor = Color.White
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Call, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Answer", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }

                FilledTonalButton(
                    onClick = { onCallAction(StructuredCallAction.RejectCall) },
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = Color(0xFFD32F2F),
                        contentColor = Color.White
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.CallEnd, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Reject", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = { onCallAction(StructuredCallAction.AskLichiCallDecision) },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFD0BCFF)),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Ask", fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun ActiveCallCard(
    callSession: CallSessionInfo,
    textColor: Color,
    primaryColor: Color,
    onCallAction: (StructuredCallAction) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF1976D2).copy(alpha = 0.18f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.PhoneInTalk, contentDescription = null, tint = Color(0xFF64B5F6), modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            text = callSession.displayTitle,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = textColor
                        )
                        Text(
                            text = "Duration: ${callSession.formattedDuration}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF64B5F6)
                        )
                    }
                }
            }

            // Controls: Mute, Speaker, Hold, End
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Mute button
                FilledTonalButton(
                    onClick = { onCallAction(StructuredCallAction.ToggleMute) },
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = if (callSession.isMuted) Color(0xFFFFB74D) else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (callSession.isMuted) Color.Black else textColor
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = if (callSession.isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(2.dp))
                    Text(if (callSession.isMuted) "Muted" else "Mute", fontSize = 10.sp)
                }

                // Speaker button
                FilledTonalButton(
                    onClick = { onCallAction(StructuredCallAction.ToggleSpeaker) },
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = if (callSession.isSpeakerOn) Color(0xFF81D4FA) else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (callSession.isSpeakerOn) Color.Black else textColor
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = if (callSession.isSpeakerOn) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(2.dp))
                    Text(if (callSession.isSpeakerOn) "Spk On" else "Speaker", fontSize = 10.sp)
                }

                // Hold button
                FilledTonalButton(
                    onClick = {
                        onCallAction(if (callSession.isHeld) StructuredCallAction.ResumeCall else StructuredCallAction.HoldCall)
                    },
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = if (callSession.isHeld) Color(0xFFFFCC80) else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (callSession.isHeld) Color.Black else textColor
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = if (callSession.isHeld) Icons.Default.PlayArrow else Icons.Default.Pause,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(2.dp))
                    Text(if (callSession.isHeld) "Resume" else "Hold", fontSize = 10.sp)
                }

                // End button
                FilledTonalButton(
                    onClick = { onCallAction(StructuredCallAction.EndCall) },
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = Color(0xFFD32F2F),
                        contentColor = Color.White
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.CallEnd, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(2.dp))
                    Text("End", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun OutgoingCallCard(
    callSession: CallSessionInfo,
    textColor: Color,
    onCallAction: (StructuredCallAction) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF00796B).copy(alpha = 0.2f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Call, contentDescription = null, tint = Color(0xFF80CBC4), modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        text = "Dialing...",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color(0xFF80CBC4)
                    )
                    Text(
                        text = callSession.displayTitle,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = textColor
                    )
                }
            }

            FilledTonalButton(
                onClick = { onCallAction(StructuredCallAction.CancelOutgoingCall) },
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = Color(0xFFD32F2F),
                    contentColor = Color.White
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.CallEnd, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text("Cancel Call", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun CallLeadingIcon(callSession: CallSessionInfo, config: DynamicIslandConfig) {
    val transition = rememberInfiniteTransition(label = "callPulse")
    val pulseScale by if (!config.lowPowerMode && callSession.callState.isRinging) {
        transition.animateFloat(
            initialValue = 0.85f,
            targetValue = 1.25f,
            animationSpec = infiniteRepeatable(
                animation = tween(600, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "callPulseScale"
        )
    } else {
        remember { mutableStateOf(1.0f) }
    }

    Box(
        modifier = Modifier
            .size(24.dp)
            .graphicsLayer {
                scaleX = pulseScale
                scaleY = pulseScale
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (callSession.callState.isConnectedOrActive) Icons.Default.PhoneInTalk else Icons.Default.Call,
            contentDescription = "Call Active",
            tint = if (callSession.callState.isRinging) Color(0xFF81C784) else Color(0xFF64B5F6),
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
private fun LeadingStateIcon(
    state: LichiAssistantState,
    config: DynamicIslandConfig,
    iconColor: Color,
    primaryColor: Color
) {
    val transition = rememberInfiniteTransition(label = "iconPulse")
    val pulseScale by if (!config.lowPowerMode && (state.uiState == LichiUiState.LISTENING || state.uiState == LichiUiState.WAKE_LISTENING)) {
        transition.animateFloat(
            initialValue = 0.9f,
            targetValue = 1.15f,
            animationSpec = infiniteRepeatable(
                animation = tween(800, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulse"
        )
    } else {
        remember { mutableStateOf(1.0f) }
    }

    Box(
        modifier = Modifier
            .size(24.dp)
            .graphicsLayer {
                scaleX = pulseScale
                scaleY = pulseScale
            },
        contentAlignment = Alignment.Center
    ) {
        when (state.uiState) {
            LichiUiState.IDLE -> Icon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = "Idle",
                tint = iconColor,
                modifier = Modifier.size(16.dp)
            )
            LichiUiState.WAKE_LISTENING -> Icon(
                imageVector = Icons.Default.Mic,
                contentDescription = "Wake Listening",
                tint = iconColor.copy(alpha = 0.85f),
                modifier = Modifier.size(16.dp)
            )
            LichiUiState.WAKE_DETECTED -> Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Wake Detected",
                tint = Color(0xFF81C784),
                modifier = Modifier.size(18.dp)
            )
            LichiUiState.LISTENING -> Icon(
                imageVector = Icons.Default.Mic,
                contentDescription = "Listening",
                tint = primaryColor,
                modifier = Modifier.size(18.dp)
            )
            LichiUiState.TRANSCRIBING -> Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = "Transcribing",
                tint = primaryColor,
                modifier = Modifier.size(16.dp)
            )
            LichiUiState.THINKING -> Icon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = "Thinking",
                tint = primaryColor,
                modifier = Modifier.size(16.dp)
            )
            LichiUiState.TOOL_EXECUTION -> Icon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = "Tool",
                tint = primaryColor,
                modifier = Modifier.size(16.dp)
            )
            LichiUiState.SPEAKING -> Icon(
                imageVector = Icons.Default.VolumeUp,
                contentDescription = "Speaking",
                tint = primaryColor,
                modifier = Modifier.size(18.dp)
            )
            LichiUiState.CALLING -> Icon(
                imageVector = Icons.Default.Call,
                contentDescription = "Calling",
                tint = Color(0xFF64B5F6),
                modifier = Modifier.size(16.dp)
            )
            LichiUiState.CALL_DECISION_REQUIRED -> Icon(
                imageVector = Icons.Default.Call,
                contentDescription = "Decision Required",
                tint = Color(0xFF81C784),
                modifier = Modifier.size(18.dp)
            )
            LichiUiState.WAITING_FOR_CALL_COMMAND -> Icon(
                imageVector = Icons.Default.Mic,
                contentDescription = "Listening for decision",
                tint = Color(0xFF81C784),
                modifier = Modifier.size(18.dp)
            )
            LichiUiState.MIC_UNAVAILABLE -> Icon(
                imageVector = Icons.Default.MicOff,
                contentDescription = "Mic In Use",
                tint = Color(0xFFFFB74D),
                modifier = Modifier.size(16.dp)
            )
            LichiUiState.ERROR -> Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = "Error",
                tint = Color(0xFFE57373),
                modifier = Modifier.size(16.dp)
            )
            LichiUiState.PAUSED -> Icon(
                imageVector = Icons.Default.MicOff,
                contentDescription = "Paused",
                tint = iconColor.copy(alpha = 0.5f),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun MiniWaveform(
    rms: Float,
    color: Color,
    lowPowerMode: Boolean
) {
    if (lowPowerMode) {
        Box(
            modifier = Modifier
                .size(width = 16.dp, height = 8.dp)
                .clip(CircleShape)
                .background(color)
        )
        return
    }

    val transition = rememberInfiniteTransition(label = "waveAnim")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 6.28f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    Row(
        modifier = Modifier.height(18.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val bars = 4
        for (i in 0 until bars) {
            val barHeight = remember(phase, rms, i) {
                val sinVal = kotlin.math.sin(phase + (i * 1.2f)).toFloat()
                val norm = ((sinVal + 1f) / 2f) * 0.7f + 0.3f
                val effective = if (rms > 0.05f) norm * (rms * 1.5f).coerceIn(0.4f, 1.0f) else norm * 0.4f
                (effective * 14f).coerceIn(3f, 16f)
            }

            Box(
                modifier = Modifier
                    .width(2.5.dp)
                    .height(barHeight.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color)
            )
        }
    }
}

@Composable
private fun ThinkingPulseDot(
    color: Color,
    lowPowerMode: Boolean
) {
    if (lowPowerMode) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color)
        )
        return
    }

    val transition = rememberInfiniteTransition(label = "dotAnim")
    val alphaVal by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(4.dp)
                .graphicsLayer { alpha = alphaVal }
                .clip(CircleShape)
                .background(color)
        )
        Box(
            modifier = Modifier
                .size(5.dp)
                .graphicsLayer { alpha = (1.3f - alphaVal).coerceIn(0.3f, 1f) }
                .clip(CircleShape)
                .background(color)
        )
        Box(
            modifier = Modifier
                .size(4.dp)
                .graphicsLayer { alpha = alphaVal }
                .clip(CircleShape)
                .background(color)
        )
    }
}

@Composable
private fun CallPulseDot(
    color: Color,
    lowPowerMode: Boolean
) {
    if (lowPowerMode) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color)
        )
        return
    }

    val transition = rememberInfiniteTransition(label = "callDotAnim")
    val alphaVal by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "callAlpha"
    )

    Box(
        modifier = Modifier
            .size(7.dp)
            .graphicsLayer { alpha = alphaVal }
            .clip(CircleShape)
            .background(color)
    )
}
