package com.lichiai.terminal.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lichiai.terminal.core.TerminalManager
import com.lichiai.terminal.model.TerminalCursorStyle
import com.lichiai.terminal.model.TerminalThemes
import kotlinx.coroutines.launch

@Composable
fun TerminalSettingsScreen(
    terminalManager: TerminalManager,
    onBack: () -> Unit
) {
    val settings by terminalManager.settings.collectAsState()
    val scope = rememberCoroutineScope()
    var showClearHistoryDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            Surface(shadowElevation = 2.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Terminal Customization",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Theme selection
            item {
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Terminal Color Scheme", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Spacer(Modifier.height(8.dp))
                        TerminalThemes.ALL_PRESETS.forEach { theme ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        scope.launch {
                                            terminalManager.preferences.updateSettings(
                                                settings.copy(activeThemeId = theme.id)
                                            )
                                        }
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                androidx.compose.material3.RadioButton(
                                    selected = settings.activeThemeId == theme.id,
                                    onClick = {
                                        scope.launch {
                                            terminalManager.preferences.updateSettings(
                                                settings.copy(activeThemeId = theme.id)
                                            )
                                        }
                                    }
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(theme.name, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }

            // Font size
            item {
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Font Size: ${settings.fontSizeSp.toInt()} sp", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Slider(
                            value = settings.fontSizeSp,
                            onValueChange = { newVal ->
                                scope.launch {
                                    terminalManager.preferences.updateSettings(
                                        settings.copy(fontSizeSp = newVal)
                                    )
                                }
                            },
                            valueRange = 10f..20f,
                            steps = 9
                        )
                    }
                }
            }

            // Cursor Style
            item {
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Cursor Style", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TerminalCursorStyle.values().forEach { style ->
                                androidx.compose.material3.FilterChip(
                                    selected = settings.cursorStyle == style,
                                    onClick = {
                                        scope.launch {
                                            terminalManager.preferences.updateSettings(
                                                settings.copy(cursorStyle = style)
                                            )
                                        }
                                    },
                                    label = { Text(style.name) }
                                )
                            }
                        }
                    }
                }
            }

            // Toggles
            item {
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Auto-Reconnect SSH", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                Text("Automatically re-establish dropped sessions", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = settings.autoReconnect,
                                onCheckedChange = { checked ->
                                    scope.launch {
                                        terminalManager.preferences.updateSettings(
                                            settings.copy(autoReconnect = checked)
                                        )
                                    }
                                }
                            )
                        }

                        Spacer(Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Confirm Destructive Commands", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                Text("Ask before executing commands like rm, mkfs, dd", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = settings.confirmDestructiveCommands,
                                onCheckedChange = { checked ->
                                    scope.launch {
                                        terminalManager.preferences.updateSettings(
                                            settings.copy(confirmDestructiveCommands = checked)
                                        )
                                    }
                                }
                            )
                        }
                    }
                }
            }

            // Clear history button
            item {
                TextButton(
                    onClick = { showClearHistoryDialog = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Clear Command History", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (showClearHistoryDialog) {
        AlertDialog(
            onDismissRequest = { showClearHistoryDialog = false },
            title = { Text("Clear Command History?") },
            text = { Text("This will permanently remove all cached command history across sessions.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearHistoryDialog = false
                        scope.launch {
                            terminalManager.preferences.clearHistory()
                        }
                    }
                ) {
                    Text("Clear", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearHistoryDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
