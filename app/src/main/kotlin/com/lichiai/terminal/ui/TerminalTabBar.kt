package com.lichiai.terminal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.VerticalSplit
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lichiai.terminal.core.TerminalSession
import com.lichiai.terminal.model.TerminalBackendType
import com.lichiai.terminal.model.TerminalSessionState
import com.lichiai.terminal.model.TerminalSplitMode

@Composable
fun TerminalTabBar(
    sessions: List<TerminalSession>,
    activeSessionId: String?,
    splitMode: TerminalSplitMode,
    onSelectSession: (String) -> Unit,
    onCloseSession: (String) -> Unit,
    onNewLocalSession: () -> Unit,
    onNewSshSession: () -> Unit,
    onToggleSplit: (TerminalSplitMode) -> Unit,
    onOpenSftp: () -> Unit,
    onOpenCommandPalette: () -> Unit,
    onOpenTaskActivity: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var newMenuOpen by remember { mutableStateOf(false) }
    var splitMenuOpen by remember { mutableStateOf(false) }
    val activeSession = sessions.firstOrNull { it.id == activeSessionId }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp,
        tonalElevation = 1.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Back button
            IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp)
                )
            }

            // Tab list
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                sessions.forEach { session ->
                    val isSelected = session.id == activeSessionId
                    val state by session.state.collectAsState()
                    val title by session.title.collectAsState()

                    SessionTabChip(
                        title = title,
                        backendType = session.backendType,
                        state = state,
                        isSelected = isSelected,
                        onClick = { onSelectSession(session.id) },
                        onClose = { onCloseSession(session.id) }
                    )
                }

                // Add button ("+")
                Box {
                    IconButton(
                        onClick = { newMenuOpen = true },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = "New Session",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(19.dp)
                        )
                    }

                    DropdownMenu(expanded = newMenuOpen, onDismissRequest = { newMenuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Local Shell / Termux") },
                            leadingIcon = { Icon(Icons.Default.Terminal, null) },
                            onClick = { newMenuOpen = false; onNewLocalSession() }
                        )
                        DropdownMenuItem(
                            text = { Text("New SSH Connection...") },
                            leadingIcon = { Icon(Icons.Default.Settings, null) },
                            onClick = { newMenuOpen = false; onNewSshSession() }
                        )
                    }
                }
            }

            // Quick actions on right
            Row(
                horizontalArrangement = Arrangement.spacedBy(1.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Search in terminal
                IconButton(onClick = onOpenSearch, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = "Search Terminal",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(17.dp)
                    )
                }

                // Task Activity Sheet
                IconButton(onClick = onOpenTaskActivity, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.AutoMirrored.Filled.Assignment,
                        contentDescription = "Terminal Tasks & Activity",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(17.dp)
                    )
                }

                // SFTP action (if active session is SSH)
                if (activeSession?.backendType == TerminalBackendType.SSH) {
                    IconButton(onClick = onOpenSftp, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Default.Folder,
                            contentDescription = "SFTP File Manager",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }

                // Split screen control
                Box {
                    IconButton(onClick = { splitMenuOpen = true }, modifier = Modifier.size(32.dp)) {
                        Icon(
                            when (splitMode) {
                                TerminalSplitMode.NONE -> Icons.Default.VerticalSplit
                                TerminalSplitMode.HORIZONTAL -> Icons.Default.ViewAgenda
                                TerminalSplitMode.VERTICAL -> Icons.Default.VerticalSplit
                            },
                            contentDescription = "Split Terminal",
                            tint = if (splitMode != TerminalSplitMode.NONE) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(17.dp)
                        )
                    }

                    DropdownMenu(expanded = splitMenuOpen, onDismissRequest = { splitMenuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Single Window") },
                            onClick = { splitMenuOpen = false; onToggleSplit(TerminalSplitMode.NONE) }
                        )
                        DropdownMenuItem(
                            text = { Text("Split Top / Bottom") },
                            onClick = { splitMenuOpen = false; onToggleSplit(TerminalSplitMode.HORIZONTAL) }
                        )
                        DropdownMenuItem(
                            text = { Text("Split Left / Right") },
                            onClick = { splitMenuOpen = false; onToggleSplit(TerminalSplitMode.VERTICAL) }
                        )
                    }
                }

                // AI Command Palette
                IconButton(onClick = onOpenCommandPalette, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.AutoAwesome,
                        contentDescription = "AI Command Palette",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(17.dp)
                    )
                }

                // Terminal Settings
                IconButton(onClick = onOpenSettings, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = "Terminal Settings",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(17.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionTabChip(
    title: String,
    backendType: TerminalBackendType,
    state: TerminalSessionState,
    isSelected: Boolean,
    onClick: () -> Unit,
    onClose: () -> Unit
) {
    val stateDotColor = when (state) {
        TerminalSessionState.CONNECTED, TerminalSessionState.ACTIVE -> Color(0xFF22C55E) // Green
        TerminalSessionState.CONNECTING, TerminalSessionState.RECONNECTING, TerminalSessionState.STARTING_SHELL -> Color(0xFFEAB308) // Yellow
        TerminalSessionState.DISCONNECTED, TerminalSessionState.FAILED -> Color(0xFFEF4444) // Red
        else -> Color.Gray
    }

    val chipBg = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f)
    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(chipBg)
            .border(
                width = 1.dp,
                color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else Color.Transparent,
                shape = RoundedCornerShape(8.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            // Live Status indicator dot
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(stateDotColor)
            )

            Spacer(Modifier.width(6.dp))

            Text(
                text = title,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(width = 80.dp)
            )

            Spacer(Modifier.width(4.dp))

            IconButton(
                onClick = onClose,
                modifier = Modifier.size(16.dp)
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Close Tab",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(12.dp)
                )
            }
        }
    }
}
