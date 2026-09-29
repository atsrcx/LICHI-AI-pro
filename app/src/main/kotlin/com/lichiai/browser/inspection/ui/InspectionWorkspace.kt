package com.lichiai.browser.inspection.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Api
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DynamicFeed
import androidx.compose.material.icons.filled.Http
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lichiai.browser.inspection.model.ComprehensiveInspectionReport

enum class InspectionTab(val title: String, val icon: ImageVector) {
    OVERVIEW("Overview", Icons.Default.DynamicFeed),
    ENDPOINTS("Endpoints", Icons.Default.Api),
    NETWORK("Network", Icons.Default.Http),
    RESOURCES("Resources", Icons.Default.Code),
    DOM("DOM & Forms", Icons.Default.Language),
    DOWNLOADS("Downloads", Icons.Default.Download),
    SECURITY("Security", Icons.Default.Security),
    CONSOLE("Console", Icons.Default.Terminal),
    PERF("Performance", Icons.Default.Speed)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InspectionWorkspaceSheet(
    report: ComprehensiveInspectionReport?,
    isInspecting: Boolean,
    onRefresh: () -> Unit,
    onDownloadTrigger: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        InspectionWorkspaceContent(
            report = report,
            isInspecting = isInspecting,
            onRefresh = onRefresh,
            onDownloadTrigger = onDownloadTrigger,
            onClose = onDismiss,
            modifier = Modifier
                .fillMaxWidth()
                .height(640.dp)
        )
    }
}

@Composable
fun InspectionWorkspaceContent(
    report: ComprehensiveInspectionReport?,
    isInspecting: Boolean,
    onRefresh: () -> Unit,
    onDownloadTrigger: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableStateOf(InspectionTab.OVERVIEW) }

    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface)
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.BugReport,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Browser DevTools & Inspection",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = report?.session?.targetUrl?.take(40) ?: "Inspect active page",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(onClick = onRefresh, enabled = !isInspecting) {
                if (isInspecting) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh Inspection")
                }
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Close")
            }
        }

        // Tabs
        ScrollableTabRow(
            selectedTabIndex = selectedTab.ordinal,
            edgePadding = 12.dp,
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            InspectionTab.entries.forEach { tab ->
                Tab(
                    selected = selectedTab == tab,
                    onClick = { selectedTab = tab },
                    text = { Text(tab.title, style = MaterialTheme.typography.labelMedium) },
                    icon = { Icon(tab.icon, contentDescription = null, modifier = Modifier.size(16.dp)) }
                )
            }
        }

        if (isInspecting && report == null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("Deeply inspecting page & network topology...", style = MaterialTheme.typography.bodyMedium)
                }
            }
        } else if (report == null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text("No inspection data available. Tap refresh.", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                when (selectedTab) {
                    InspectionTab.OVERVIEW -> InspectionOverviewPane(report)
                    InspectionTab.ENDPOINTS -> InspectionEndpointsPane(report)
                    InspectionTab.NETWORK -> InspectionNetworkPane(report)
                    InspectionTab.RESOURCES -> InspectionResourcesPane(report)
                    InspectionTab.DOM -> InspectionDomPane(report)
                    InspectionTab.DOWNLOADS -> InspectionDownloadsPane(report, onDownloadTrigger)
                    InspectionTab.SECURITY -> InspectionSecurityPane(report)
                    InspectionTab.CONSOLE -> InspectionConsolePane(report)
                    InspectionTab.PERF -> InspectionPerformancePane(report)
                }
            }
        }
    }
}

// --- SUB PANES ---

@Composable
fun InspectionOverviewPane(report: ComprehensiveInspectionReport) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Metric badges row
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MetricChip("Requests", "${report.summary.totalRequests}")
                MetricChip("APIs", "${report.summary.totalEndpointsDiscovered}")
                MetricChip("External Domains", "${report.summary.totalExternalDomains}")
                MetricChip("Downloads", "${report.summary.totalDownloadsDetected}")
                MetricChip("Forms", "${report.summary.totalFormsDetected}")
                MetricChip("Errors", "${report.summary.totalErrorsCount}", isError = report.summary.totalErrorsCount > 0)
            }
        }

        // Findings
        if (report.findings.isNotEmpty()) {
            item {
                Text("Key Findings & Insights", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            }
            items(report.findings) { finding ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = when (finding.severity) {
                            "HIGH" -> Color(0xFFFEE2E2)
                            "WARNING" -> Color(0xFFFEF3C7)
                            else -> MaterialTheme.colorScheme.surfaceContainer
                        }
                    )
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "[${finding.category}]",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = finding.title,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = finding.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (finding.evidenceDetails.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = finding.evidenceDetails,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // Technical Architecture Explanation
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("Technical Architecture Explanation", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = report.technicalStructureExplanation,
                        style = MaterialTheme.typography.bodySmall,
                        lineHeight = 20.sp
                    )
                }
            }
        }
    }
}

@Composable
fun InspectionEndpointsPane(report: ComprehensiveInspectionReport) {
    if (report.endpoints.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No asynchronous API endpoints observed yet.", style = MaterialTheme.typography.bodyMedium)
        }
    } else {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(report.endpoints) { ep ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = when (ep.method.uppercase()) {
                                    "GET" -> Color(0xFF10B981)
                                    "POST" -> Color(0xFF3B82F6)
                                    "PUT" -> Color(0xFFF59E0B)
                                    "DELETE" -> Color(0xFFEF4444)
                                    else -> Color(0xFF6B7280)
                                }
                            ) {
                                Text(
                                    text = ep.method,
                                    color = Color.White,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = ep.path,
                                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold),
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "[${ep.category}]",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Domain: ${ep.domain} (${ep.occurrences} calls, avg ${ep.averageLatencyMs}ms)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (ep.parameters.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Text("Parameters:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            ep.parameters.take(5).forEach { p ->
                                Text(" • ${p.sourceLocation} ${p.name} = \"${p.sampleValue}\"", style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp))
                            }
                        }

                        if (!ep.requestPayloadPreview.isNullOrBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text("Payload:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            Text(ep.requestPayloadPreview.take(150), style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 10.5.sp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun InspectionNetworkPane(report: ComprehensiveInspectionReport) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(report.networkRequests) { req ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${req.statusCode}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (req.statusCode in 200..299) Color(0xFF10B981) else Color(0xFFEF4444),
                        modifier = Modifier.width(36.dp)
                    )
                    Text(
                        text = req.method,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.width(42.dp)
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = req.url,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                            maxLines = 1
                        )
                        Text(
                            text = "${req.resourceType} • ${req.initiator} • ${req.durationMs}ms",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun InspectionResourcesPane(report: ComprehensiveInspectionReport) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(report.resources) { res ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                Column(Modifier.padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                text = res.type,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = res.domain,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                        if (res.isThirdParty) {
                            Spacer(Modifier.width(6.dp))
                            Text("[3rd Party]", style = MaterialTheme.typography.labelSmall, color = Color(0xFFD97706))
                        }
                        if (res.isExternalCdn) {
                            Spacer(Modifier.width(6.dp))
                            Text("[CDN]", style = MaterialTheme.typography.labelSmall, color = Color(0xFF2563EB))
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = res.url,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                        maxLines = 2
                    )
                }
            }
        }
    }
}

@Composable
fun InspectionDomPane(report: ComprehensiveInspectionReport) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (report.forms.isEmpty()) {
            item {
                Text("No HTML forms detected on this page.", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            items(report.forms) { form ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            text = "${form.formName} [${form.method}]",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Action: ${form.action}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("Fields (${form.fields.size}):", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        form.fields.forEach { f ->
                            Text(
                                text = " • [${f.type}] ${f.name.ifBlank { f.id }} ${if (f.placeholder.isNotBlank()) "(\"${f.placeholder}\")" else ""}",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun InspectionDownloadsPane(
    report: ComprehensiveInspectionReport,
    onDownloadTrigger: (String) -> Unit
) {
    if (report.downloads.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No downloadable files identified on page.", style = MaterialTheme.typography.bodyMedium)
        }
    } else {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(report.downloads) { dl ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = dl.filename,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Type: .${dl.extension} | Source: ${dl.detectedVia}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { onDownloadTrigger(dl.url) }) {
                            Icon(Icons.Default.Download, contentDescription = "Download")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun InspectionSecurityPane(report: ComprehensiveInspectionReport) {
    val sec = report.securitySignals
    if (sec == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No security signals available.", style = MaterialTheme.typography.bodyMedium)
        }
    } else {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (sec.riskScore == "LOW") Color(0xFFECFDF5) else Color(0xFFFEF2F2)
                    )
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (sec.riskScore == "LOW") Icons.Default.CheckCircle else Icons.Default.Warning,
                                contentDescription = null,
                                tint = if (sec.riskScore == "LOW") Color(0xFF059669) else Color(0xFFDC2626)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Security Risk: ${sec.riskScore}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text("Protocol: ${sec.protocol} | Mixed Content: ${sec.hasMixedContent}")
                        Text("Cookies: ${sec.totalCookies} total (${sec.secureCookiesCount} secure)")
                        Text("CSP Detected: ${sec.cspDetected}")
                    }
                }
            }

            if (sec.thirdPartyTrackers.isNotEmpty()) {
                item {
                    Text("Detected Trackers & Analytics", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    sec.thirdPartyTrackers.forEach { t ->
                        Text(" • $t", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            item {
                Text("Security Headers Present", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                if (sec.securityHeadersPresent.isEmpty()) {
                    Text(" None", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                } else {
                    sec.securityHeadersPresent.forEach { h ->
                        Text(" ✓ $h", style = MaterialTheme.typography.bodySmall, color = Color(0xFF059669))
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text("Missing Security Headers", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                sec.securityHeadersMissing.forEach { h ->
                    Text(" ✗ $h", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
fun InspectionConsolePane(report: ComprehensiveInspectionReport) {
    if (report.consoleLogs.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Console log is clean (0 messages).", style = MaterialTheme.typography.bodyMedium)
        }
    } else {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(report.consoleLogs) { log ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = when (log.level) {
                            "ERROR" -> Color(0xFFFEE2E2)
                            "WARN" -> Color(0xFFFEF3C7)
                            else -> MaterialTheme.colorScheme.surfaceContainerLow
                        }
                    )
                ) {
                    Column(Modifier.padding(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "[${log.level}]",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (log.level == "ERROR") Color(0xFFDC2626) else MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "${log.sourceId.substringAfterLast('/')}:${log.lineNumber}",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = log.message,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.5.sp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun InspectionPerformancePane(report: ComprehensiveInspectionReport) {
    val perf = report.performanceMetrics
    if (perf == null || perf.fullPageLoadMs == 0L) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Performance metrics collecting / unavailable.", style = MaterialTheme.typography.bodyMedium)
        }
    } else {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text("Performance Rating: ${perf.performanceRating}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            MetricBox("DNS Lookup", "${perf.dnsLookupTimeMs}ms")
                            MetricBox("TCP Connect", "${perf.tcpConnectTimeMs}ms")
                            MetricBox("TTFB", "${perf.ttfbMs}ms")
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            MetricBox("DOM Content Loaded", "${perf.domContentLoadedMs}ms")
                            MetricBox("Full Page Load", "${perf.fullPageLoadMs}ms")
                            MetricBox("First Contentful Paint", "${perf.firstContentfulPaintMs}ms")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MetricChip(label: String, value: String, isError: Boolean = false) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (isError) Color(0xFFFEE2E2) else MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "$label: ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(text = value, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = if (isError) Color(0xFFDC2626) else MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
fun MetricBox(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    }
}
