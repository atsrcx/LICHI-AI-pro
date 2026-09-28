package com.lichiai.agentvision.ui

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lichiai.agentvision.model.AgentVisionPreset
import com.lichiai.agentvision.model.CursorShape
import com.lichiai.agentvision.model.CursorSize
import com.lichiai.agentvision.model.GlowLevel
import com.lichiai.agentvision.model.MovementSpeed
import com.lichiai.agentvision.model.TargetHighlightStyle
import com.lichiai.agentvision.preview.AgentVisionPreviewController
import com.lichiai.agentvision.renderer.AgentVisionOverlay
import com.lichiai.agentvision.settings.AgentVisionSettingsRepository
import com.lichiai.ui.SectionCard
import com.lichiai.ui.SectionHeader
import kotlinx.coroutines.launch

@Composable
fun AgentVisionSettingsScreen(
    repository: AgentVisionSettingsRepository,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val settings by repository.settings.collectAsState(initial = com.lichiai.agentvision.model.AgentVisionSettings())
    val config = settings.config

    val previewController = remember { AgentVisionPreviewController(scope) }
    val previewSession by previewController.previewSession.collectAsState()

    val colorsPalette = listOf(
        0xFF2563EBL to "Vibrant Blue",
        0xFF8B5CF6L to "Purple",
        0xFF10B981L to "Emerald Green",
        0xFFEF4444L to "Red",
        0xFFF59E0BL to "Amber",
        0xFF06B6D4L to "Cyan",
        0xFFEC4899L to "Pink",
        0xFF1E293BL to "Slate Dark",
        0xFFF8FAFCL to "Pure White"
    )

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
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Agent Vision",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            // Main Enable Toggle Card
            SectionCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Visibility,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Enable Agent Vision",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "Visually renders the real desktop cursor, target highlights & action telemetry",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = settings.enabled,
                        onCheckedChange = { checked ->
                            scope.launch { repository.updateSettings { it.copy(enabled = checked) } }
                        }
                    )
                }
            }

            // Interactive Live Preview Card
            SectionHeader("LIVE CURSOR PREVIEW")
            SectionCard {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "Interactive Preview Canvas",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = "Test cursor movements, clicks, typing and scroll animations safely (visual-only):",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(Modifier.height(10.dp))

                    // Mock UI Box containing live AgentVisionOverlay
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                            .padding(10.dp)
                    ) {
                        // Mock Page Mockup Elements
                        Column(modifier = Modifier.fillMaxSize()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(36.dp)
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(MaterialTheme.colorScheme.surface)
                                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(18.dp))
                                    .padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "🔍 Search Google or type URL...",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                            }

                            Spacer(Modifier.height(12.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(MaterialTheme.colorScheme.surface),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("Official Site", style = MaterialTheme.typography.labelSmall)
                                }
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(MaterialTheme.colorScheme.surface),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("Download App", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }

                        // Real visual AgentVisionOverlay on top of mock page
                        AgentVisionOverlay(
                            session = previewSession,
                            config = config,
                            showTimelinePanel = false
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    // Action Trigger Chips
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        item {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.clickable { previewController.triggerTapPreview(600f, 400f) }
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Icon(Icons.Default.TouchApp, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(15.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Test Tap", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
                                }
                            }
                        }

                        item {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                modifier = Modifier.clickable { previewController.triggerTypePreview(600f, 400f) }
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(15.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Test Type", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
                                }
                            }
                        }

                        item {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.tertiaryContainer,
                                modifier = Modifier.clickable { previewController.triggerScrollPreview(600f, 400f) }
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Text("↓", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onTertiaryContainer)
                                    Spacer(Modifier.width(4.dp))
                                    Text("Test Scroll", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
                                }
                            }
                        }

                        item {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                modifier = Modifier.clickable { previewController.triggerDragPreview(600f, 400f) }
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Icon(Icons.Default.Mouse, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(15.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Test Drag", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
                                }
                            }
                        }
                    }
                }
            }

            // Presets Selector
            SectionHeader("VISUAL PRESETS")
            SectionCard {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "Quick Presets",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(Modifier.height(8.dp))

                    val presets = AgentVisionPreset.values().filter { it != AgentVisionPreset.CUSTOM }
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(presets) { p ->
                            val selected = settings.activePreset == p
                            FilterChip(
                                selected = selected,
                                onClick = { scope.launch { repository.applyPreset(p) } },
                                label = {
                                    Text(
                                        p.name.replace("_", " ").lowercase().replaceFirstChar { it.uppercase() },
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            )
                        }
                    }
                }
            }

            // Cursor Customization Section
            SectionHeader("CURSOR STYLE & SHAPE")
            SectionCard {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Text("Pointer Shape", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(8.dp))

                    val shapes = CursorShape.values()
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(shapes) { shape ->
                            val isSel = config.shape == shape
                            FilterChip(
                                selected = isSel,
                                onClick = {
                                    scope.launch {
                                        repository.updateSettings {
                                            it.copy(
                                                activePreset = AgentVisionPreset.CUSTOM,
                                                config = it.config.copy(shape = shape)
                                            )
                                        }
                                    }
                                },
                                label = {
                                    Text(
                                        shape.name.replace("_", " ").lowercase().replaceFirstChar { it.uppercase() },
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            )
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Spacer(Modifier.height(14.dp))

                    // Color Palette
                    Text("Cursor Color", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(8.dp))

                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(colorsPalette) { pair ->
                            val argb = pair.first
                            val name = pair.second
                            val isChosen = config.colorArgb == argb
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(Color(argb))
                                    .border(
                                        width = if (isChosen) 3.dp else 1.dp,
                                        color = if (isChosen) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.5f),
                                        shape = CircleShape
                                    )
                                    .clickable {
                                        scope.launch {
                                            repository.updateSettings {
                                                it.copy(
                                                    activePreset = AgentVisionPreset.CUSTOM,
                                                    config = it.config.copy(
                                                        colorArgb = argb,
                                                        targetHighlightColorArgb = argb
                                                    )
                                                )
                                            }
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                if (isChosen) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = name,
                                        tint = if (argb == 0xFFF8FAFCL) Color.Black else Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(14.dp))

                    // Size Selection
                    Text("Cursor Size: ${config.size.name.lowercase()}", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        CursorSize.values().forEach { s ->
                            FilterChip(
                                selected = config.size == s,
                                onClick = {
                                    scope.launch {
                                        repository.updateSettings {
                                            it.copy(activePreset = AgentVisionPreset.CUSTOM, config = it.config.copy(size = s))
                                        }
                                    }
                                },
                                label = { Text(s.name.lowercase().replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }

                    Spacer(Modifier.height(14.dp))

                    // Glow Level
                    Text("Cursor Glow: ${config.glow.name.lowercase()}", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        GlowLevel.values().forEach { g ->
                            FilterChip(
                                selected = config.glow == g,
                                onClick = {
                                    scope.launch {
                                        repository.updateSettings {
                                            it.copy(activePreset = AgentVisionPreset.CUSTOM, config = it.config.copy(glow = g))
                                        }
                                    }
                                },
                                label = { Text(g.name.lowercase().replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }

                    Spacer(Modifier.height(14.dp))

                    // Movement Speed & Easing
                    Text("Movement Speed: ${config.movementSpeed.name.lowercase()}", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        MovementSpeed.values().forEach { spd ->
                            FilterChip(
                                selected = config.movementSpeed == spd,
                                onClick = {
                                    scope.launch {
                                        repository.updateSettings {
                                            it.copy(activePreset = AgentVisionPreset.CUSTOM, config = it.config.copy(movementSpeed = spd))
                                        }
                                    }
                                },
                                label = { Text(spd.name.lowercase().replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }

                    Spacer(Modifier.height(14.dp))

                    // Shadow Toggle
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Pointer Drop Shadow", style = MaterialTheme.typography.bodyLarge)
                            Text("Adds realistic depth under the pointer", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = config.shadow,
                            onCheckedChange = { checked ->
                                scope.launch {
                                    repository.updateSettings {
                                        it.copy(activePreset = AgentVisionPreset.CUSTOM, config = it.config.copy(shadow = checked))
                                    }
                                }
                            }
                        )
                    }
                }
            }

            // Target Highlight & Interactions
            SectionHeader("TARGET HIGHLIGHT & INTERACTION")
            SectionCard {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    // Target Highlight Toggle
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Target Element Highlight", style = MaterialTheme.typography.bodyLarge)
                            Text("Visually bounds the element targeted by the agent", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = config.targetHighlight,
                            onCheckedChange = { checked ->
                                scope.launch {
                                    repository.updateSettings {
                                        it.copy(activePreset = AgentVisionPreset.CUSTOM, config = it.config.copy(targetHighlight = checked))
                                    }
                                }
                            }
                        )
                    }

                    if (config.targetHighlight) {
                        Spacer(Modifier.height(12.dp))
                        Text("Highlight Style", style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.height(6.dp))

                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(TargetHighlightStyle.values()) { ths ->
                                FilterChip(
                                    selected = config.targetHighlightStyle == ths,
                                    onClick = {
                                        scope.launch {
                                            repository.updateSettings {
                                                it.copy(activePreset = AgentVisionPreset.CUSTOM, config = it.config.copy(targetHighlightStyle = ths))
                                            }
                                        }
                                    },
                                    label = { Text(ths.name.replace("_", " ").lowercase().replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelSmall) }
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Spacer(Modifier.height(14.dp))

                    // Click Animation & Ripple
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Click Animation & Ripple", style = MaterialTheme.typography.bodyLarge)
                            Text("Pulsing ripple feedback on taps", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = config.clickRipple,
                            onCheckedChange = { checked ->
                                scope.launch {
                                    repository.updateSettings {
                                        it.copy(activePreset = AgentVisionPreset.CUSTOM, config = it.config.copy(clickRipple = checked, clickAnimation = checked))
                                    }
                                }
                            }
                        )
                    }

                    Spacer(Modifier.height(14.dp))

                    // Action Trail
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Action Trail", style = MaterialTheme.typography.bodyLarge)
                            Text("Smooth pointer movement motion trail", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = config.actionTrail,
                            onCheckedChange = { checked ->
                                scope.launch {
                                    repository.updateSettings {
                                        it.copy(activePreset = AgentVisionPreset.CUSTOM, config = it.config.copy(actionTrail = checked))
                                    }
                                }
                            }
                        )
                    }

                    Spacer(Modifier.height(14.dp))

                    // Typing Indicator
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Typing Indicator", style = MaterialTheme.typography.bodyLarge)
                            Text("Shows typing badge over inputs (passwords masked)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = config.typingIndicator,
                            onCheckedChange = { checked ->
                                scope.launch {
                                    repository.updateSettings {
                                        it.copy(activePreset = AgentVisionPreset.CUSTOM, config = it.config.copy(typingIndicator = checked))
                                    }
                                }
                            }
                        )
                    }

                    Spacer(Modifier.height(14.dp))

                    // Scroll Indicator
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Scroll Direction Indicator", style = MaterialTheme.typography.bodyLarge)
                            Text("Shows directional arrows when scrolling", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = config.scrollIndicator,
                            onCheckedChange = { checked ->
                                scope.launch {
                                    repository.updateSettings {
                                        it.copy(activePreset = AgentVisionPreset.CUSTOM, config = it.config.copy(scrollIndicator = checked))
                                    }
                                }
                            }
                        )
                    }
                }
            }

            // Timeline & Developer Settings
            SectionHeader("TIMELINE & DEVELOPER OPTIONS")
            SectionCard {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    // Action Timeline
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Action Timeline Panel", style = MaterialTheme.typography.bodyLarge)
                            Text("Real operational step logs and Take Control button", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = config.actionTimeline,
                            onCheckedChange = { checked ->
                                scope.launch {
                                    repository.updateSettings {
                                        it.copy(activePreset = AgentVisionPreset.CUSTOM, config = it.config.copy(actionTimeline = checked))
                                    }
                                }
                            }
                        )
                    }

                    Spacer(Modifier.height(14.dp))

                    // Coordinate Display
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Display Screen Coordinates", style = MaterialTheme.typography.bodyLarge)
                            Text("Shows live X & Y pixel positions", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = config.coordinateDisplay,
                            onCheckedChange = { checked ->
                                scope.launch {
                                    repository.updateSettings {
                                        it.copy(activePreset = AgentVisionPreset.CUSTOM, config = it.config.copy(coordinateDisplay = checked))
                                    }
                                }
                            }
                        )
                    }

                    Spacer(Modifier.height(14.dp))

                    // Developer Debug Info
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Developer Debug Telemetry", style = MaterialTheme.typography.bodyLarge)
                            Text("Shows raw source, coordinate space & bounding rectangles", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = config.developerDebugInfo,
                            onCheckedChange = { checked ->
                                scope.launch {
                                    repository.updateSettings {
                                        it.copy(activePreset = AgentVisionPreset.CUSTOM, config = it.config.copy(developerDebugInfo = checked))
                                    }
                                }
                            }
                        )
                    }
                }
            }

            // Reset Button
            OutlinedButton(
                onClick = { scope.launch { repository.resetToDefault() } },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Reset Agent Vision to Defaults")
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}
