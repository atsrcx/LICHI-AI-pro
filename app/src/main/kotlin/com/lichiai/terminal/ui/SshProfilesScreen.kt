package com.lichiai.terminal.ui

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lichiai.terminal.core.TerminalManager
import com.lichiai.terminal.model.SshAuthType
import com.lichiai.terminal.model.SshProfile
import com.lichiai.terminal.storage.SshCredentialStore
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
fun SshProfilesScreen(
    terminalManager: TerminalManager,
    onConnectProfile: (SshProfile) -> Unit,
    onBack: () -> Unit
) {
    val profiles by terminalManager.profiles.collectAsState()
    val scope = rememberCoroutineScope()
    var editingProfile by remember { mutableStateOf<SshProfile?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }

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
                        "SSH Server Profiles",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showCreateDialog = true },
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Profile")
            }
        }
    ) { padding ->
        if (profiles.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Dns,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "No SSH Profiles Saved",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Tap + to configure a Linux/SSH server",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(profiles, key = { it.id }) { profile ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Dns,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    profile.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "${profile.username}@${profile.hostname}:${profile.port}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            // Connect Button
                            IconButton(onClick = { onConnectProfile(profile) }) {
                                Icon(
                                    Icons.Default.PlayArrow,
                                    contentDescription = "Connect",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }

                            // Delete Button
                            IconButton(
                                onClick = {
                                    scope.launch {
                                        terminalManager.profileRepository.deleteProfile(profile.id)
                                    }
                                }
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Delete",
                                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        SshProfileEditorDialog(
            initial = null,
            onDismiss = { showCreateDialog = false },
            onSave = { newProfile ->
                scope.launch {
                    terminalManager.profileRepository.saveProfile(newProfile)
                    showCreateDialog = false
                }
            }
        )
    }
}

@Composable
fun SshProfileEditorDialog(
    initial: SshProfile?,
    onDismiss: () -> Unit,
    onSave: (SshProfile) -> Unit
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var hostname by remember { mutableStateOf(initial?.hostname ?: "") }
    var portText by remember { mutableStateOf(initial?.port?.toString() ?: "22") }
    var username by remember { mutableStateOf(initial?.username ?: "") }
    var password by remember { mutableStateOf(SshCredentialStore.decrypt(initial?.passwordEncrypted) ?: "") }
    var privateKey by remember { mutableStateOf(SshCredentialStore.decrypt(initial?.privateKeyEncrypted) ?: "") }
    var defaultDir by remember { mutableStateOf(initial?.defaultWorkingDirectory ?: "") }
    var authType by remember { mutableStateOf(initial?.authType ?: SshAuthType.PASSWORD) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "New SSH Profile" else "Edit Profile") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Profile Name (e.g. Ubuntu Server)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    OutlinedTextField(
                        value = hostname,
                        onValueChange = { hostname = it },
                        label = { Text("Hostname / IP Address") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    OutlinedTextField(
                        value = portText,
                        onValueChange = { portText = it },
                        label = { Text("Port (Default 22)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("Username") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { authType = SshAuthType.PASSWORD },
                            colors = if (authType == SshAuthType.PASSWORD) androidx.compose.material3.ButtonDefaults.buttonColors() else androidx.compose.material3.ButtonDefaults.outlinedButtonColors()
                        ) {
                            Text("Password")
                        }
                        Button(
                            onClick = { authType = SshAuthType.PRIVATE_KEY },
                            colors = if (authType == SshAuthType.PRIVATE_KEY) androidx.compose.material3.ButtonDefaults.buttonColors() else androidx.compose.material3.ButtonDefaults.outlinedButtonColors()
                        ) {
                            Text("Private Key")
                        }
                    }
                }
                if (authType == SshAuthType.PASSWORD) {
                    item {
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("Password (Encrypted)") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                } else {
                    item {
                        OutlinedTextField(
                            value = privateKey,
                            onValueChange = { privateKey = it },
                            label = { Text("Private Key (PEM format)") },
                            maxLines = 4,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                item {
                    OutlinedTextField(
                        value = defaultDir,
                        onValueChange = { defaultDir = it },
                        label = { Text("Default Directory (Optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val port = portText.toIntOrNull() ?: 22
                    val profile = SshProfile(
                        id = initial?.id ?: UUID.randomUUID().toString(),
                        name = name.ifBlank { hostname },
                        hostname = hostname.trim(),
                        port = port,
                        username = username.trim(),
                        authType = authType,
                        passwordEncrypted = SshCredentialStore.encrypt(password),
                        privateKeyEncrypted = SshCredentialStore.encrypt(privateKey),
                        defaultWorkingDirectory = defaultDir.ifBlank { null }
                    )
                    onSave(profile)
                },
                enabled = hostname.isNotBlank() && username.isNotBlank()
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
