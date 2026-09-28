package com.lichiai.browser.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.lichiai.browser.provider.BrowserProviderManager
import com.lichiai.browser.storage.BrowserStorageManager
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserSettingsDialog(
    storageManager: BrowserStorageManager,
    providerManager: BrowserProviderManager,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val browserSettings by storageManager.settings.collectAsState()
    val providerSettings by providerManager.settings.collectAsState()
    val providers by providerManager.providers.collectAsState()

    var selectedEngineName by remember { mutableStateOf(browserSettings.searchEngineName) }
    var engineExpanded by remember { mutableStateOf(false) }

    val activeConfig = providerManager.getDedicatedActiveConfig()
    var useCurrentProvider by remember { mutableStateOf(providerSettings.useCurrentProvider) }
    var selectedProviderId by remember { mutableStateOf(providerSettings.activeProviderId) }
    var providerExpanded by remember { mutableStateOf(false) }

    var modelName by remember { mutableStateOf(activeConfig?.activeModel ?: "gemini-2.5-flash") }
    var apiKey by remember { mutableStateOf(activeConfig?.apiKey ?: "") }
    var zeroLlmEnabled by remember { mutableStateOf(providerSettings.zeroLlmFastPathEnabled) }

    val searchEngines = listOf(
        "Google" to "https://www.google.com/search?q=",
        "DuckDuckGo" to "https://duckduckgo.com/?q=",
        "Bing" to "https://www.bing.com/search?q=",
        "YouTube" to "https://www.youtube.com/results?search_query="
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Browser & Agent Settings", fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "SEARCH ENGINE",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )

                Spacer(Modifier.height(6.dp))

                ExposedDropdownMenuBox(
                    expanded = engineExpanded,
                    onExpandedChange = { engineExpanded = it }
                ) {
                    OutlinedTextField(
                        value = selectedEngineName,
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = engineExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = engineExpanded,
                        onDismissRequest = { engineExpanded = false }
                    ) {
                        searchEngines.forEach { (name, url) ->
                            DropdownMenuItem(
                                text = { Text(name) },
                                onClick = {
                                    selectedEngineName = name
                                    engineExpanded = false
                                }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                Text(
                    text = "REASONING PROVIDER FOR BROWSER AGENT",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )

                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Use Current Provider", fontWeight = FontWeight.SemiBold)
                        Text(
                            if (useCurrentProvider) "Browser Agent uses Lichi's active global LLM provider" else "Use dedicated Browser Agent provider configuration below",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = useCurrentProvider,
                        onCheckedChange = { useCurrentProvider = it }
                    )
                }

                if (!useCurrentProvider) {
                    Spacer(Modifier.height(12.dp))

                    Text(
                        text = "DEDICATED BROWSER PROVIDER",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(Modifier.height(6.dp))

                    ExposedDropdownMenuBox(
                        expanded = providerExpanded,
                        onExpandedChange = { providerExpanded = it }
                    ) {
                        val currentProvName = providers.firstOrNull { it.id == selectedProviderId }?.name ?: selectedProviderId
                        OutlinedTextField(
                            value = currentProvName,
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = providerExpanded) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor()
                        )
                        ExposedDropdownMenu(
                            expanded = providerExpanded,
                            onDismissRequest = { providerExpanded = false }
                        ) {
                            providers.forEach { prov ->
                                DropdownMenuItem(
                                    text = { Text(prov.name) },
                                    onClick = {
                                        selectedProviderId = prov.id
                                        modelName = prov.activeModel
                                        apiKey = prov.apiKey
                                        providerExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    OutlinedTextField(
                        value = modelName,
                        onValueChange = { modelName = it },
                        label = { Text("Model ID") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(10.dp))

                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text("Browser Provider API Key") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Zero-LLM Fast Path", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Execute routine navigation without calling LLM",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = zeroLlmEnabled,
                        onCheckedChange = { zeroLlmEnabled = it }
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    scope.launch {
                        val engineUrl = searchEngines.firstOrNull { it.first == selectedEngineName }?.second
                            ?: "https://www.google.com/search?q="
                        storageManager.updateSettings(
                            browserSettings.copy(
                                searchEngineName = selectedEngineName,
                                searchEngineUrl = engineUrl
                            )
                        )

                        providerManager.setUseCurrentProvider(useCurrentProvider)

                        if (!useCurrentProvider) {
                            // Update Dedicated Browser Provider Config
                            val cfg = providers.firstOrNull { it.id == selectedProviderId }
                            if (cfg != null) {
                                providerManager.upsertProvider(
                                    cfg.copy(activeModel = modelName, apiKey = apiKey)
                                )
                                providerManager.setActiveProvider(selectedProviderId, modelName)
                            }
                        }
                    }
                    onDismiss()
                }
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
