package com.lichiai.calling.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SimCard
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lichiai.ChatViewModel
import com.lichiai.calling.contacts.ContactCandidate
import com.lichiai.calling.engine.CallDiagnosticEvent
import com.lichiai.calling.intent.CallIntent
import com.lichiai.calling.intent.CallResultStatus
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallDiagnosticsScreen(
    viewModel: ChatViewModel,
    onRequestPermissions: () -> Unit,
    onBack: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val stats by viewModel.contactRepository.stats.collectAsState()
    val permState by viewModel.callPermissionManager.permissionState.collectAsState()
    val diagnosticsLogs by viewModel.callDiagnosticsRepo.events.collectAsState()
    val aliases by viewModel.contactAliasesRepo.aliasesFlow.collectAsState(initial = emptyMap())

    var selectedTab by remember { mutableIntStateOf(0) }
    var testQuery by remember { mutableStateOf("") }
    var testResultIntent by remember { mutableStateOf<CallIntent?>(null) }
    var testResultCandidates by remember { mutableStateOf<List<ContactCandidate>>(emptyList()) }
    var isTesting by remember { mutableStateOf(false) }

    var showAddAliasDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Call System & Contacts") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            coroutineScope.launch {
                                viewModel.contactRepository.refresh()
                            }
                        }
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh Contacts")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            ScrollableTabRow(
                selectedTabIndex = selectedTab,
                edgePadding = 16.dp
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Overview & Stats") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Query Tester") }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text("Relationship Aliases") }
                )
                Tab(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    text = { Text("Forensic Logs (${diagnosticsLogs.size})") }
                )
            }

            when (selectedTab) {
                0 -> OverviewTab(
                    stats = stats,
                    hasReadContacts = permState.hasReadContacts,
                    hasCallPhone = permState.hasCallPhone,
                    onRequestPermissions = onRequestPermissions,
                    onRefresh = { coroutineScope.launch { viewModel.contactRepository.refresh() } }
                )
                1 -> TesterTab(
                    testQuery = testQuery,
                    onQueryChange = { testQuery = it },
                    isTesting = isTesting,
                    resultIntent = testResultIntent,
                    resultCandidates = testResultCandidates,
                    onRunTest = {
                        isTesting = true
                        coroutineScope.launch {
                            val intent = viewModel.universalCallEngine.intentResolver.resolve(testQuery)
                            testResultIntent = intent
                            testResultCandidates = if (intent.targetText.isNotBlank()) {
                                viewModel.contactRepository.searchContacts(intent.targetText)
                            } else emptyList()
                            isTesting = false
                        }
                    }
                )
                2 -> AliasesTab(
                    aliases = aliases,
                    onAddAlias = { showAddAliasDialog = true },
                    onDeleteAlias = { term ->
                        coroutineScope.launch {
                            viewModel.contactAliasesRepo.removeAlias(term)
                        }
                    }
                )
                3 -> LogsTab(
                    logs = diagnosticsLogs,
                    onClear = { viewModel.callDiagnosticsRepo.clear() }
                )
            }
        }
    }

    if (showAddAliasDialog) {
        AddAliasDialog(
            onDismiss = { showAddAliasDialog = false },
            onSave = { term, targetName ->
                coroutineScope.launch {
                    viewModel.contactAliasesRepo.setAlias(term, targetName)
                    showAddAliasDialog = false
                }
            }
        )
    }
}

@Composable
private fun OverviewTab(
    stats: com.lichiai.calling.contacts.ContactRepositoryStats,
    hasReadContacts: Boolean,
    hasCallPhone: Boolean,
    onRequestPermissions: () -> Unit,
    onRefresh: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Permissions Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (hasReadContacts && hasCallPhone)
                        MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Telephony Permissions",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = if (hasReadContacts) Icons.Default.CheckCircle else Icons.Default.Error,
                            contentDescription = null,
                            tint = if (hasReadContacts) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = "READ_CONTACTS: ${if (hasReadContacts) "Granted" else "Missing"}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = if (hasCallPhone) Icons.Default.CheckCircle else Icons.Default.Error,
                            contentDescription = null,
                            tint = if (hasCallPhone) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = "CALL_PHONE: ${if (hasCallPhone) "Granted" else "Missing"}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    if (!hasReadContacts || !hasCallPhone) {
                        Spacer(modifier = Modifier.height(12.dp))
                        FilledTonalButton(
                            onClick = onRequestPermissions,
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError
                            )
                        ) {
                            Text("Grant Missing Permissions")
                        }
                    }
                }
            }
        }

        // Contact Counts Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Discovered Contacts Database",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        IconButton(onClick = onRefresh) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    StatRow("Total Discovered Contacts", "${stats.totalContacts}")
                    StatRow("Phone Numbers Indexed", "${stats.phoneNumbersCount}")
                    StatRow("SIM Contacts", "${stats.simContactsCount}")
                    StatRow("Device / Account Contacts", "${stats.localContactsCount + stats.cloudContactsCount}")
                    if (stats.lastRefreshTime > 0) {
                        val formattedDate = SimpleDateFormat("MMM d, yyyy HH:mm:ss", Locale.getDefault()).format(Date(stats.lastRefreshTime))
                        StatRow("Last Refreshed", formattedDate)
                    }
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Universal Calling Capabilities",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "• Natural Language calling in English, Hindi & Hinglish (\"Rahul ko call lagao\", \"Call Mom\", \"Bhaiya ko phone karo\").\n" +
                                "• Direct phone numbers (\"Call 9876543210\", \"Is number par call karo\").\n" +
                                "• Deep dual-source indexing (Device Contacts + SIM Contacts).\n" +
                                "• Unified engine for both Text Mode and Voice Mode.\n" +
                                "• Zero privacy leakage: Contacts are matched strictly locally on-device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun TesterTab(
    testQuery: String,
    onQueryChange: (String) -> Unit,
    isTesting: Boolean,
    resultIntent: CallIntent?,
    resultCandidates: List<ContactCandidate>,
    onRunTest: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Natural Language Call Intent Tester",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = testQuery,
                        onValueChange = onQueryChange,
                        label = { Text("Enter command (e.g., 'Rahul ko call lagao')") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    FilledTonalButton(
                        onClick = onRunTest,
                        enabled = testQuery.isNotBlank() && !isTesting,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Test Intent & Search Contact")
                    }
                }
            }
        }

        if (resultIntent != null) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Parsed Intent",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Action: ${resultIntent.action}\n" +
                                    "Target: ${resultIntent.targetText}\n" +
                                    "Phone Number: ${resultIntent.phoneNumber ?: "N/A"}\n" +
                                    "SIM Slot: ${resultIntent.simSlot ?: "Default"}\n" +
                                    "Confidence: ${(resultIntent.confidence * 100).toInt()}%",
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            item {
                Text(
                    text = "Matched Contact Candidates (${resultCandidates.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            if (resultCandidates.isEmpty()) {
                item {
                    Text(
                        text = "No contact found matching target \"${resultIntent.targetText}\"",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            } else {
                items(resultCandidates) { candidate ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = candidate.contact.displayName,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Score: ${candidate.score}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Text(
                                text = "Match Reason: ${candidate.matchReason} (${candidate.matchedTerm})",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (candidate.contact.isSimContact) {
                                Text(
                                    text = "Source: SIM Card",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.tertiary
                                )
                            }
                            candidate.contact.phoneNumbers.forEach { phone ->
                                Text(
                                    text = "• ${phone.rawNumber} (${if (phone.isPrimary) "Primary" else phone.label ?: "Phone"})",
                                    style = MaterialTheme.typography.bodySmall
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
private fun AliasesTab(
    aliases: Map<String, String>,
    onAddAlias: () -> Unit,
    onDeleteAlias: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Custom Relationship Aliases",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                FilledTonalButton(onClick = onAddAlias) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Add Alias")
                }
            }
            Text(
                text = "Map spoken terms like \"bhaiya\" or \"mom\" to specific contact names.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        items(aliases.entries.toList()) { (term, target) ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "\"$term\"",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Resolves to: $target",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { onDeleteAlias(term) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
private fun LogsTab(
    logs: List<CallDiagnosticEvent>,
    onClear: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Recent Call Diagnostics",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                if (logs.isNotEmpty()) {
                    TextButton(onClick = onClear) {
                        Icon(Icons.Default.Clear, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Clear Logs")
                    }
                }
            }
        }

        if (logs.isEmpty()) {
            item {
                Text(
                    text = "No call events logged yet. Spoken or typed call requests will appear here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            items(logs) { event ->
                val isSuccess = event.status == CallResultStatus.SUCCESS_STARTED
                val dateStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(event.timestamp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSuccess) MaterialTheme.colorScheme.surfaceVariant
                        else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "[$dateStr] [${event.executionMode}]",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = event.status.name,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (isSuccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Input: \"${event.rawInput}\"",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        if (event.matchedContactName != null) {
                            Text(
                                text = "Target: ${event.matchedContactName} (${event.maskedPhoneNumber ?: "N/A"})",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (event.failureReason != null) {
                            Text(
                                text = "Failure: ${event.failureReason}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AddAliasDialog(
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit
) {
    var term by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Relationship Alias") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = term,
                    onValueChange = { term = it },
                    label = { Text("Spoken Term (e.g., 'bhaiya', 'mom')") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = target,
                    onValueChange = { target = it },
                    label = { Text("Contact Name (e.g., 'Rahul Sharma')") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            FilledTonalButton(
                onClick = {
                    if (term.isNotBlank() && target.isNotBlank()) {
                        onSave(term.trim(), target.trim())
                    }
                },
                enabled = term.isNotBlank() && target.isNotBlank()
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
