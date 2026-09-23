package com.lichiai.ui.dynamicisland

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.lichiai.dynamicisland.PositionMode
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.lichiai.dynamicisland.DynamicIslandConfig
import com.lichiai.dynamicisland.DynamicIslandController
import com.lichiai.dynamicisland.DynamicIslandRepository
import com.lichiai.dynamicisland.DynamicIslandSurface
import com.lichiai.dynamicisland.GlowIntensity
import com.lichiai.dynamicisland.IslandAction
import com.lichiai.dynamicisland.IslandPreset
import com.lichiai.dynamicisland.IslandShape
import com.lichiai.dynamicisland.LichiAssistantState
import com.lichiai.dynamicisland.LichiUiState
import com.lichiai.dynamicisland.parseColorHex
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun DynamicIslandSettingsScreen(
    controller: DynamicIslandController,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = controller.repository

    val config by repo.configState.collectAsState()
    var hasOverlayPermission by remember { mutableStateOf(controller.isPermissionGranted()) }

    // Re-check permission on resume
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasOverlayPermission = controller.isPermissionGranted()
                controller.checkAndRefreshOverlay()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Live preview state simulation
    var previewUiState by remember { mutableStateOf(LichiUiState.IDLE) }
    var previewExpanded by remember { mutableStateOf(false) }

    val simulatedAssistantState = remember(previewUiState) {
        when (previewUiState) {
            LichiUiState.IDLE -> LichiAssistantState(
                uiState = LichiUiState.IDLE,
                statusText = "Lichi"
            )
            LichiUiState.WAKE_LISTENING -> LichiAssistantState(
                uiState = LichiUiState.WAKE_LISTENING,
                statusText = "Listening for wake word"
            )
            LichiUiState.WAKE_DETECTED -> LichiAssistantState(
                uiState = LichiUiState.WAKE_DETECTED,
                statusText = "Hey Lichi ✓"
            )
            LichiUiState.LISTENING -> LichiAssistantState(
                uiState = LichiUiState.LISTENING,
                statusText = "Listening...",
                transcript = "Call Rahul on mobile",
                rmsLevel = 0.7f
            )
            LichiUiState.THINKING -> LichiAssistantState(
                uiState = LichiUiState.THINKING,
                statusText = "Thinking..."
            )
            LichiUiState.SPEAKING -> LichiAssistantState(
                uiState = LichiUiState.SPEAKING,
                statusText = "Speaking...",
                responsePreview = "Sure! Calling Rahul on his mobile number now.",
                rmsLevel = 0.6f
            )
            LichiUiState.CALLING -> LichiAssistantState(
                uiState = LichiUiState.CALLING,
                statusText = "Calling Rahul...",
                callingTarget = "Rahul"
            )
            LichiUiState.MIC_UNAVAILABLE -> LichiAssistantState(
                uiState = LichiUiState.MIC_UNAVAILABLE,
                statusText = "Microphone In Use"
            )
            LichiUiState.ERROR -> LichiAssistantState(
                uiState = LichiUiState.ERROR,
                statusText = "Connection Timeout"
            )
            else -> LichiAssistantState()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(WindowInsets.statusBars.asPaddingValues())
    ) {
        // Top Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Spacer(Modifier.width(4.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Dynamic Island Surface",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                )
                Text(
                    text = "Customizable floating assistant overlay",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(
                onClick = {
                    scope.launch { repo.resetDefaults() }
                }
            ) {
                Icon(Icons.Default.Refresh, contentDescription = "Reset Defaults")
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Permission Banner if missing
            if (!hasOverlayPermission) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Overlay Permission Required",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "To display the persistent floating assistant surface outside of the app, Android requires 'Display over other apps' permission.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = {
                                try {
                                    val intent = Intent(
                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:${context.packageName}")
                                    )
                                    context.startActivity(intent)
                                } catch (_: Exception) {
                                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                                    context.startActivity(intent)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Grant Permission")
                        }
                    }
                }
            }

            // Live Interactive Preview Section
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Live Interactive Preview",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = if (previewExpanded) "Expanded" else "Collapsed",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(Modifier.height(18.dp))

                    // Preview Rendering
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(if (previewExpanded) 220.dp else 70.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xFF0F0E13))
                            .padding(12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        DynamicIslandSurface(
                            config = config,
                            state = simulatedAssistantState,
                            isExpanded = previewExpanded,
                            onExpandChanged = { previewExpanded = it },
                            onOpenApp = {},
                            onOpenSettings = {},
                            onToggleVoice = {},
                            onStopSession = {}
                        )
                    }

                    Spacer(Modifier.height(14.dp))

                    // State Switcher for Preview
                    Text(
                        text = "Simulate State:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Start)
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val states = listOf(
                            LichiUiState.IDLE to "Idle",
                            LichiUiState.LISTENING to "Listen",
                            LichiUiState.THINKING to "Think",
                            LichiUiState.SPEAKING to "Speak",
                            LichiUiState.CALLING to "Call"
                        )
                        states.forEach { (st, label) ->
                            FilterChip(
                                selected = previewUiState == st,
                                onClick = { previewUiState = st },
                                label = { Text(label, fontSize = 11.sp) }
                            )
                        }
                    }
                }
            }

            // Master Activation Card
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Enable Dynamic Island",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = "Show floating assistant surface across device",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = config.enabled,
                            onCheckedChange = { isChecked ->
                                scope.launch {
                                    repo.updateConfig { it.copy(enabled = isChecked) }
                                }
                            }
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Show when App is Closed", style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = config.showWhenAppClosed,
                            onCheckedChange = { isChecked ->
                                scope.launch {
                                    repo.updateConfig { it.copy(showWhenAppClosed = isChecked) }
                                }
                            }
                        )
                    }
                }
            }

            // Presets Selector Card
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.ColorLens, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Theme & Style Presets",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val presets = listOf(
                            IslandPreset.DEFAULT to "Default",
                            IslandPreset.CYBERPUNK to "Cyberpunk",
                            IslandPreset.GLASS to "Glass",
                            IslandPreset.MONOCHROME to "Mono",
                            IslandPreset.MINIMAL to "Minimal",
                            IslandPreset.VIBRANT to "Vibrant"
                        )
                        presets.forEach { (preset, label) ->
                            FilterChip(
                                selected = config.preset == preset,
                                onClick = {
                                    scope.launch {
                                        repo.updateConfig { current ->
                                            DynamicIslandConfig.applyPreset(preset, current)
                                        }
                                    }
                                },
                                label = { Text(label, fontSize = 11.sp) }
                            )
                        }
                    }
                }
            }

            // Position & Status Bar / Notch Alignment Section
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Position & Notch Alignment",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = "Auto-detect phone cutout, adjust status bar distance, or drag freely",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(Modifier.height(14.dp))

                    Text("Placement Mode", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val positionModes = listOf(
                            PositionMode.AUTO_DETECT to "Auto Notch",
                            PositionMode.TOP_CENTER to "Top Center",
                            PositionMode.TOP_LEFT to "Left Hole",
                            PositionMode.TOP_RIGHT to "Right Notch",
                            PositionMode.FREE to "Free Drag"
                        )
                        positionModes.forEach { (mode, label) ->
                            FilterChip(
                                selected = config.positionMode == mode,
                                onClick = {
                                    scope.launch {
                                        repo.updateConfig { current ->
                                            when (mode) {
                                                PositionMode.AUTO_DETECT -> current.copy(positionMode = mode, yOffsetDp = 12, xOffsetDp = 0)
                                                PositionMode.TOP_CENTER -> current.copy(positionMode = mode, yOffsetDp = 20, xOffsetDp = 0)
                                                PositionMode.TOP_LEFT -> current.copy(positionMode = mode, yOffsetDp = 20, xOffsetDp = 0)
                                                PositionMode.TOP_RIGHT -> current.copy(positionMode = mode, yOffsetDp = 20, xOffsetDp = 0)
                                                PositionMode.FREE -> current.copy(positionMode = mode)
                                                else -> current.copy(positionMode = mode)
                                            }
                                        }
                                    }
                                },
                                label = { Text(label, fontSize = 11.sp) }
                            )
                        }
                    }

                    Spacer(Modifier.height(14.dp))

                    // Vertical Y Offset (Status Bar distance / Shutter)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Vertical Distance (Y): ${config.yOffsetDp} dp",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        TextButton(
                            onClick = {
                                scope.launch { repo.updateConfig { it.copy(yOffsetDp = 12) } }
                            }
                        ) {
                            Text("Reset Y", fontSize = 12.sp)
                        }
                    }
                    Text(
                        text = "Fine-tune up/down height under status bar or camera notch",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Slider(
                        value = config.yOffsetDp.toFloat(),
                        onValueChange = { value ->
                            scope.launch {
                                repo.updateConfig { it.copy(yOffsetDp = value.roundToInt()) }
                            }
                        },
                        valueRange = 0f..180f
                    )

                    Spacer(Modifier.height(10.dp))

                    // Horizontal X Offset (Left / Right fine tune)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Horizontal Offset (X): ${config.xOffsetDp} dp",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        TextButton(
                            onClick = {
                                scope.launch { repo.updateConfig { it.copy(xOffsetDp = 0) } }
                            }
                        ) {
                            Text("Center X", fontSize = 12.sp)
                        }
                    }
                    Text(
                        text = "Move left or right to align precisely with your camera lens",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Slider(
                        value = config.xOffsetDp.toFloat(),
                        onValueChange = { value ->
                            scope.launch {
                                repo.updateConfig { it.copy(xOffsetDp = value.roundToInt()) }
                            }
                        },
                        valueRange = -150f..150f
                    )
                }
            }

            // Shape & Geometry Section
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Layers, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Shape & Geometry",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    Text("Island Shape", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(
                            IslandShape.PILL to "Pill",
                            IslandShape.ROUNDED_RECT to "Rounded",
                            IslandShape.CUT_CORNER to "Cut Corner",
                            IslandShape.SQUARE to "Square",
                            IslandShape.CIRCLE to "Circle"
                        ).forEach { (sh, label) ->
                            FilterChip(
                                selected = config.shape == sh,
                                onClick = {
                                    scope.launch {
                                        repo.updateConfig { it.copy(shape = sh, preset = IslandPreset.CUSTOM) }
                                    }
                                },
                                label = { Text(label, fontSize = 11.sp) }
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Text("Corner Radius: ${config.cornerRadiusDp} dp", style = MaterialTheme.typography.bodyMedium)
                    Slider(
                        value = config.cornerRadiusDp.toFloat(),
                        onValueChange = { value ->
                            scope.launch {
                                repo.updateConfig { it.copy(cornerRadiusDp = value.roundToInt(), preset = IslandPreset.CUSTOM) }
                            }
                        },
                        valueRange = 0f..50f
                    )

                    Spacer(Modifier.height(8.dp))
                    Text("Island Width: ${config.widthDp} dp", style = MaterialTheme.typography.bodyMedium)
                    Slider(
                        value = config.widthDp.toFloat(),
                        onValueChange = { value ->
                            scope.launch {
                                repo.updateConfig { it.copy(widthDp = value.roundToInt()) }
                            }
                        },
                        valueRange = config.minWidthDp.toFloat()..config.maxWidthDp.toFloat()
                    )

                    Spacer(Modifier.height(8.dp))
                    Text("Island Height: ${config.heightDp} dp", style = MaterialTheme.typography.bodyMedium)
                    Slider(
                        value = config.heightDp.toFloat(),
                        onValueChange = { value ->
                            scope.launch {
                                repo.updateConfig { it.copy(heightDp = value.roundToInt()) }
                            }
                        },
                        valueRange = config.minHeightDp.toFloat()..config.maxHeightDp.toFloat()
                    )
                }
            }

            // Colors & Appearance Section
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Colors & Transparency",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }

                    Spacer(Modifier.height(14.dp))

                    ColorHexEditorRow(
                        title = "Background Color",
                        hex = config.backgroundColorHex,
                        onHexChange = { hex ->
                            scope.launch { repo.updateConfig { it.copy(backgroundColorHex = hex, preset = IslandPreset.CUSTOM) } }
                        }
                    )

                    Spacer(Modifier.height(10.dp))
                    Text("Background Opacity: ${(config.backgroundAlpha * 100).roundToInt()}%", style = MaterialTheme.typography.bodyMedium)
                    Slider(
                        value = config.backgroundAlpha,
                        onValueChange = { value ->
                            scope.launch { repo.updateConfig { it.copy(backgroundAlpha = value) } }
                        },
                        valueRange = 0.1f..1.0f
                    )

                    Spacer(Modifier.height(10.dp))
                    ColorHexEditorRow(
                        title = "Primary Accent Color",
                        hex = config.primaryColorHex,
                        onHexChange = { hex ->
                            scope.launch { repo.updateConfig { it.copy(primaryColorHex = hex, preset = IslandPreset.CUSTOM) } }
                        }
                    )

                    Spacer(Modifier.height(10.dp))
                    ColorHexEditorRow(
                        title = "Text Color",
                        hex = config.textColorHex,
                        onHexChange = { hex ->
                            scope.launch { repo.updateConfig { it.copy(textColorHex = hex, preset = IslandPreset.CUSTOM) } }
                        }
                    )

                    Spacer(Modifier.height(10.dp))
                    ColorHexEditorRow(
                        title = "Icon Color",
                        hex = config.iconColorHex,
                        onHexChange = { hex ->
                            scope.launch { repo.updateConfig { it.copy(iconColorHex = hex, preset = IslandPreset.CUSTOM) } }
                        }
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                    // Border controls
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Enable Border", style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = config.borderEnabled,
                            onCheckedChange = { checked ->
                                scope.launch { repo.updateConfig { it.copy(borderEnabled = checked) } }
                            }
                        )
                    }

                    if (config.borderEnabled) {
                        Spacer(Modifier.height(8.dp))
                        ColorHexEditorRow(
                            title = "Border Color",
                            hex = config.borderColorHex,
                            onHexChange = { hex ->
                                scope.launch { repo.updateConfig { it.copy(borderColorHex = hex) } }
                            }
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("Border Width: ${String.format("%.1f", config.borderWidthDp)} dp", style = MaterialTheme.typography.bodyMedium)
                        Slider(
                            value = config.borderWidthDp,
                            onValueChange = { value ->
                                scope.launch { repo.updateConfig { it.copy(borderWidthDp = value) } }
                            },
                            valueRange = 0.5f..5.0f
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                    // Glow controls
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Ambient Glow Effect", style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = config.glowEnabled,
                            onCheckedChange = { checked ->
                                scope.launch { repo.updateConfig { it.copy(glowEnabled = checked) } }
                            }
                        )
                    }

                    if (config.glowEnabled) {
                        Spacer(Modifier.height(8.dp))
                        ColorHexEditorRow(
                            title = "Glow Color",
                            hex = config.glowColorHex,
                            onHexChange = { hex ->
                                scope.launch { repo.updateConfig { it.copy(glowColorHex = hex) } }
                            }
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("Glow Intensity", style = MaterialTheme.typography.labelMedium)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(
                                GlowIntensity.LOW to "Low",
                                GlowIntensity.MEDIUM to "Medium",
                                GlowIntensity.HIGH to "High"
                            ).forEach { (intensity, label) ->
                                FilterChip(
                                    selected = config.glowIntensity == intensity,
                                    onClick = {
                                        scope.launch { repo.updateConfig { it.copy(glowIntensity = intensity) } }
                                    },
                                    label = { Text(label, fontSize = 11.sp) }
                                )
                            }
                        }
                    }
                }
            }

            // Content Visibility Section
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Visibility, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Content & Indicators",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }

                    Spacer(Modifier.height(10.dp))

                    VisibilitySwitchRow("Show Assistant Icon", config.showIcon) { checked ->
                        scope.launch { repo.updateConfig { it.copy(showIcon = checked) } }
                    }
                    VisibilitySwitchRow("Show Status Text", config.showStatus) { checked ->
                        scope.launch { repo.updateConfig { it.copy(showStatus = checked) } }
                    }
                    VisibilitySwitchRow("Show Live Transcript", config.showTranscript) { checked ->
                        scope.launch { repo.updateConfig { it.copy(showTranscript = checked) } }
                    }
                    VisibilitySwitchRow("Show Response Preview", config.showResponse) { checked ->
                        scope.launch { repo.updateConfig { it.copy(showResponse = checked) } }
                    }
                    VisibilitySwitchRow("Show Waveform / RMS Visualizer", config.showWaveform) { checked ->
                        scope.launch { repo.updateConfig { it.copy(showWaveform = checked) } }
                    }
                    VisibilitySwitchRow("Show Tool & App Activity", config.showToolActivity) { checked ->
                        scope.launch { repo.updateConfig { it.copy(showToolActivity = checked) } }
                    }
                    VisibilitySwitchRow("Show Telephony Call Status", config.showCallingStatus) { checked ->
                        scope.launch { repo.updateConfig { it.copy(showCallingStatus = checked) } }
                    }
                }
            }

            // Behavior & Snapping Section
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.TouchApp, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Interaction & Snapping",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Auto Collapse", style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = config.autoCollapse,
                            onCheckedChange = { checked ->
                                scope.launch { repo.updateConfig { it.copy(autoCollapse = checked) } }
                            }
                        )
                    }

                    if (config.autoCollapse) {
                        Spacer(Modifier.height(8.dp))
                        Text("Auto Collapse Delay: ${config.autoCollapseDelaySec}s", style = MaterialTheme.typography.bodyMedium)
                        Slider(
                            value = config.autoCollapseDelaySec.toFloat(),
                            onValueChange = { value ->
                                scope.launch { repo.updateConfig { it.copy(autoCollapseDelaySec = value.roundToInt()) } }
                            },
                            valueRange = 1f..10f
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Snap to Edges on Drag", style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = config.snapToEdges,
                            onCheckedChange = { checked ->
                                scope.launch { repo.updateConfig { it.copy(snapToEdges = checked) } }
                            }
                        )
                    }

                    if (config.snapToEdges) {
                        Spacer(Modifier.height(8.dp))
                        Text("Snap Distance: ${config.snapDistanceDp} dp", style = MaterialTheme.typography.bodyMedium)
                        Slider(
                            value = config.snapDistanceDp.toFloat(),
                            onValueChange = { value ->
                                scope.launch { repo.updateConfig { it.copy(snapDistanceDp = value.roundToInt()) } }
                            },
                            valueRange = 10f..60f
                        )
                    }
                }
            }

            // Low Power Mode Section
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.BatteryChargingFull, contentDescription = null, tint = Color(0xFF81C784))
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "Low Power Mode",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                )
                                Text(
                                    text = "Minimal animations & battery optimization",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Switch(
                            checked = config.lowPowerMode,
                            onCheckedChange = { checked ->
                                scope.launch { repo.updateConfig { it.copy(lowPowerMode = checked) } }
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ColorHexEditorRow(
    title: String,
    hex: String,
    onHexChange: (String) -> Unit
) {
    var textValue by remember(hex) { mutableStateOf(hex) }
    val parsedColor = parseColorHex(textValue, Color.White)

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(parsedColor)
                    .border(1.dp, Color.Gray, CircleShape)
            )
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = textValue,
                onValueChange = {
                    textValue = it
                    if (it.length in 4..9) {
                        onHexChange(it)
                    }
                },
                modifier = Modifier.width(110.dp),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                singleLine = true
            )
        }
    }
}

@Composable
private fun VisibilitySwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}
