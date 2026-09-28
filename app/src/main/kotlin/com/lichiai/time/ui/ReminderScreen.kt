package com.lichiai.time.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lichiai.time.manager.ReminderManager
import com.lichiai.time.model.ChecklistItem
import com.lichiai.time.model.RecurrenceType
import com.lichiai.time.model.ReminderCategory
import com.lichiai.time.model.ReminderItem
import com.lichiai.time.model.ReminderStatus
import com.lichiai.time.model.ReminderTab
import com.lichiai.time.model.ReminderType
import com.lichiai.ui.LichiVisualTokens
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun ReminderScreen(
    manager: ReminderManager,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val reminders by manager.repository.reminders.collectAsState()
    val diagnostics by manager.repository.diagnostics.collectAsState()

    var activeTab by remember { mutableStateOf(ReminderTab.TODAY) }
    var selectedCategory by remember { mutableStateOf<ReminderCategory?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }

    var editingReminder by remember { mutableStateOf<ReminderItem?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showDiagnosticsDialog by remember { mutableStateOf(false) }
    var showBackupRestoreDialog by remember { mutableStateOf(false) }

    val conflicts = remember(reminders) { manager.repository.detectConflicts() }

    // Filter reminders
    val filteredReminders = remember(reminders, activeTab, selectedCategory, searchQuery) {
        val now = System.currentTimeMillis()
        val calToday = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startOfToday = calToday.timeInMillis
        val endOfToday = startOfToday + (24 * 60 * 60 * 1000L)
        val endOfTomorrow = endOfToday + (24 * 60 * 60 * 1000L)

        reminders.filter { item ->
            // Search Query
            val matchesQuery = if (searchQuery.isBlank()) true else {
                item.title.contains(searchQuery, ignoreCase = true) ||
                        item.description.contains(searchQuery, ignoreCase = true) ||
                        item.category.displayName.contains(searchQuery, ignoreCase = true)
            }

            // Category
            val matchesCategory = if (selectedCategory == null) true else item.category == selectedCategory

            // Tab Filter
            val matchesTab = when (activeTab) {
                ReminderTab.TODAY -> item.triggerEpochMs in startOfToday until endOfToday && item.isActive
                ReminderTab.TOMORROW -> item.triggerEpochMs in endOfToday until endOfTomorrow && item.isActive
                ReminderTab.UPCOMING -> item.triggerEpochMs >= endOfTomorrow && item.isActive
                ReminderTab.CALENDAR -> item.isActive
                ReminderTab.RECURRING -> item.isRecurring && item.isActive
                ReminderTab.ALARMS -> item.isAlarm && item.isActive
                ReminderTab.TASKS -> item.type == ReminderType.TASK && item.isActive
                ReminderTab.COMPLETED -> item.status == ReminderStatus.COMPLETED
                ReminderTab.MISSED -> item.status == ReminderStatus.MISSED || item.status == ReminderStatus.DISMISSED
            }

            matchesQuery && matchesCategory && matchesTab
        }.sortedBy { it.triggerEpochMs }
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showCreateDialog = true },
                containerColor = LichiVisualTokens.BrandPurple,
                contentColor = Color.White,
                shape = CircleShape,
                modifier = Modifier.padding(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())
            ) {
                Icon(Icons.Default.Add, contentDescription = "Create Reminder or Alarm")
            }
        },
        containerColor = LichiVisualTokens.BackgroundLight
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding())
                .padding(bottom = innerPadding.calculateBottomPadding())
        ) {
            // 1. Top App Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = LichiVisualTokens.TextNavy
                    )
                }

                Text(
                    text = "Time Engine",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 19.sp
                    ),
                    color = LichiVisualTokens.TextNavy,
                    modifier = Modifier.weight(1f).padding(start = 4.dp)
                )

                // Search Toggle
                IconButton(onClick = { isSearchActive = !isSearchActive }) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search",
                        tint = if (isSearchActive) LichiVisualTokens.BrandPurple else LichiVisualTokens.TextNavy
                    )
                }

                // Diagnostics
                IconButton(onClick = { showDiagnosticsDialog = true }) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "Diagnostics",
                        tint = LichiVisualTokens.TextNavy
                    )
                }

                // Backup & Export Menu
                var menuExpanded by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "More",
                            tint = LichiVisualTokens.TextNavy
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Export Calendar (.ics)") },
                            leadingIcon = { Icon(Icons.Default.CalendarMonth, null, tint = LichiVisualTokens.BrandPurple) },
                            onClick = {
                                menuExpanded = false
                                val icsData = manager.repository.exportIcsCalendar()
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/calendar"
                                    putExtra(Intent.EXTRA_SUBJECT, "Lichi Reminders.ics")
                                    putExtra(Intent.EXTRA_TEXT, icsData)
                                }
                                context.startActivity(Intent.createChooser(shareIntent, "Share iCalendar"))
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Local Backup & Restore") },
                            leadingIcon = { Icon(Icons.Default.FileUpload, null, tint = LichiVisualTokens.BrandPurple) },
                            onClick = {
                                menuExpanded = false
                                showBackupRestoreDialog = true
                            }
                        )
                    }
                }
            }

            // 2. Search Input Bar (Visible when search is active)
            AnimatedVisibility(visible = isSearchActive) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = LichiVisualTokens.SurfaceWhite,
                    shadowElevation = 2.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.Search, contentDescription = null, tint = LichiVisualTokens.TextGraySubtle, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier.weight(1f),
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = LichiVisualTokens.TextNavy)
                        )
                        if (searchQuery.isNotEmpty()) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Clear",
                                tint = LichiVisualTokens.TextGray,
                                modifier = Modifier.size(18.dp).clickable { searchQuery = "" }
                            )
                        }
                    }
                }
            }

            // 3. Conflict Detection Alert Banner
            if (conflicts.isNotEmpty() && activeTab == ReminderTab.TODAY) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFFFEF3C7),
                    shadowElevation = 1.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(12.dp)
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFD97706), modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "Conflict detected: '${conflicts.first().reminder1.title}' and '${conflicts.first().reminder2.title}' are within ${conflicts.first().timeDifferenceMinutes} mins.",
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                            color = Color(0xFF92400E)
                        )
                    }
                }
            }

            // 4. Horizontal Scrollable Tabs
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ReminderTab.values().forEach { tab ->
                    val isSelected = activeTab == tab
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = if (isSelected) LichiVisualTokens.BrandPurple else LichiVisualTokens.SurfaceWhite,
                        shadowElevation = if (isSelected) 3.dp else 1.dp,
                        modifier = Modifier
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = ripple(bounded = true),
                                onClick = { activeTab = tab }
                            )
                    ) {
                        Text(
                            text = tab.label,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                fontSize = 13.sp
                            ),
                            color = if (isSelected) Color.White else LichiVisualTokens.TextNavy,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                        )
                    }
                }
            }

            // 5. Category Filter Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // All Chip
                FilterChipPill(
                    label = "All Categories",
                    isSelected = selectedCategory == null,
                    onClick = { selectedCategory = null }
                )
                ReminderCategory.values().forEach { cat ->
                    FilterChipPill(
                        label = cat.displayName,
                        isSelected = selectedCategory == cat,
                        onClick = { selectedCategory = if (selectedCategory == cat) null else cat }
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            // 6. Reminder Cards List
            if (filteredReminders.isEmpty()) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.NotificationsActive,
                            contentDescription = null,
                            tint = LichiVisualTokens.TextGraySubtle,
                            modifier = Modifier.size(52.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "No reminders found in ${activeTab.label}",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                            color = LichiVisualTokens.TextGray
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Tap + or use voice ('Set alarm at 7am') to create one.",
                            style = MaterialTheme.typography.bodySmall,
                            color = LichiVisualTokens.TextGraySubtle
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filteredReminders, key = { it.id }) { item ->
                        ReminderCardItem(
                            item = item,
                            onToggleActive = { scope.launch { manager.toggleActive(item.id) } },
                            onComplete = { scope.launch { manager.completeReminder(item.id) } },
                            onSnooze = { scope.launch { manager.snoozeReminder(item.id, 10) } },
                            onDelete = { scope.launch { manager.deleteReminder(item.id) } },
                            onEdit = { editingReminder = item },
                            onToggleChecklist = { checkId ->
                                scope.launch { manager.repository.toggleChecklistItem(item.id, checkId) }
                            }
                        )
                    }
                }
            }
        }
    }

    // Dialogs
    if (showCreateDialog) {
        CreateOrEditReminderDialog(
            initialItem = null,
            onDismiss = { showCreateDialog = false },
            onSave = { newItem ->
                scope.launch {
                    manager.createReminder(newItem)
                    showCreateDialog = false
                    Toast.makeText(context, "Reminder scheduled", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    if (editingReminder != null) {
        CreateOrEditReminderDialog(
            initialItem = editingReminder,
            onDismiss = { editingReminder = null },
            onSave = { updatedItem ->
                scope.launch {
                    manager.updateReminder(updatedItem)
                    editingReminder = null
                    Toast.makeText(context, "Reminder updated", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    if (showDiagnosticsDialog) {
        ReminderDiagnosticsDialog(
            diagnostics = diagnostics,
            onDismiss = { showDiagnosticsDialog = false },
            onRequestExactAlarmPermission = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                    context.startActivity(intent)
                }
            }
        )
    }

    if (showBackupRestoreDialog) {
        BackupRestoreDialog(
            onCreateBackup = { manager.repository.createBackupJson() },
            onRestoreBackup = { json ->
                scope.launch {
                    val success = manager.repository.restoreBackupJson(json)
                    if (success) {
                        Toast.makeText(context, "Reminders restored successfully", Toast.LENGTH_SHORT).show()
                        showBackupRestoreDialog = false
                    } else {
                        Toast.makeText(context, "Failed to restore backup", Toast.LENGTH_LONG).show()
                    }
                }
            },
            onDismiss = { showBackupRestoreDialog = false }
        )
    }
}

@Composable
private fun FilterChipPill(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (isSelected) LichiVisualTokens.BrandPurpleSoftBg else LichiVisualTokens.SurfaceWhite,
        border = androidx.compose.foundation.BorderStroke(
            0.8.dp,
            if (isSelected) LichiVisualTokens.BrandPurple else Color(0xFFE5E7EB)
        ),
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = 11.5.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
            ),
            color = if (isSelected) LichiVisualTokens.BrandPurple else LichiVisualTokens.TextNavy,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}

@Composable
private fun ReminderCardItem(
    item: ReminderItem,
    onToggleActive: () -> Unit,
    onComplete: () -> Unit,
    onSnooze: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    onToggleChecklist: (String) -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }

    val formattedTime = remember(item.triggerEpochMs) {
        SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(item.triggerEpochMs))
    }
    val formattedDate = remember(item.triggerEpochMs) {
        SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(Date(item.triggerEpochMs))
    }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = LichiVisualTokens.SurfaceWhite,
        shadowElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 3.dp,
                shape = RoundedCornerShape(20.dp),
                spotColor = LichiVisualTokens.SoftPillShadow
            )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Category Tag
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(LichiVisualTokens.BrandPurpleSoftBg)
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = item.category.displayName,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.5.sp
                        ),
                        color = LichiVisualTokens.BrandPurple
                    )
                }

                // Type Icon
                Spacer(Modifier.width(6.dp))
                Icon(
                    imageVector = when (item.type) {
                        ReminderType.ALARM -> Icons.Default.Alarm
                        ReminderType.TASK -> Icons.Default.TaskAlt
                        ReminderType.ROUTINE -> Icons.Default.Repeat
                        else -> Icons.Default.Notifications
                    },
                    contentDescription = null,
                    tint = LichiVisualTokens.TextGray,
                    modifier = Modifier.size(15.dp)
                )

                if (item.isRecurring) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "• ${item.recurrence.name.lowercase().capitalize()}",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                        color = LichiVisualTokens.TextGray
                    )
                }

                Spacer(Modifier.weight(1f))

                // Active Switch
                Switch(
                    checked = item.isActive,
                    onCheckedChange = { onToggleActive() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = LichiVisualTokens.BrandPurple
                    ),
                    modifier = Modifier.size(36.dp)
                )
            }

            Spacer(Modifier.height(8.dp))

            // Title & Time
            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp,
                            textDecoration = if (item.isCompleted) TextDecoration.LineThrough else TextDecoration.None
                        ),
                        color = if (item.isCompleted) LichiVisualTokens.TextGraySubtle else LichiVisualTokens.TextNavy
                    )
                    if (item.description.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = item.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = LichiVisualTokens.TextGray,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(Modifier.width(12.dp))

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = formattedTime,
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        ),
                        color = LichiVisualTokens.TextNavy
                    )
                    Text(
                        text = formattedDate,
                        style = MaterialTheme.typography.bodySmall,
                        color = LichiVisualTokens.TextGray
                    )
                }
            }

            // Checklist subtasks if present
            if (item.hasChecklist) {
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = Color(0xFFF3F4F6))
                Spacer(Modifier.height(6.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    item.checklist.forEach { sub ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onToggleChecklist(sub.id) }
                        ) {
                            Checkbox(
                                checked = sub.isCompleted,
                                onCheckedChange = { onToggleChecklist(sub.id) },
                                colors = CheckboxDefaults.colors(checkedColor = LichiVisualTokens.BrandPurple),
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = sub.title,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontSize = 13.sp,
                                    textDecoration = if (sub.isCompleted) TextDecoration.LineThrough else TextDecoration.None
                                ),
                                color = if (sub.isCompleted) LichiVisualTokens.TextGraySubtle else LichiVisualTokens.TextNavy
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // Action Affordances Row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (item.isActive) {
                    // Snooze Button
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFFF3F4F6),
                        modifier = Modifier.clickable(onClick = onSnooze)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                        ) {
                            Icon(Icons.Default.Snooze, contentDescription = null, tint = LichiVisualTokens.TextGray, modifier = Modifier.size(13.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Snooze +10m", style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp), color = LichiVisualTokens.TextNavy)
                        }
                    }

                    Spacer(Modifier.width(8.dp))

                    // Complete Button
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = LichiVisualTokens.BrandPurpleSoftBg,
                        modifier = Modifier.clickable(onClick = onComplete)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = LichiVisualTokens.BrandPurple, modifier = Modifier.size(13.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Done", style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold), color = LichiVisualTokens.BrandPurple)
                        }
                    }
                }

                Spacer(Modifier.weight(1f))

                // Edit Button
                IconButton(onClick = onEdit, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Edit, contentDescription = "Edit", tint = LichiVisualTokens.TextGray, modifier = Modifier.size(16.dp))
                }

                // Delete Button
                IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
fun CreateOrEditReminderDialog(
    initialItem: ReminderItem?,
    onDismiss: () -> Unit,
    onSave: (ReminderItem) -> Unit
) {
    val context = LocalContext.current
    var title by remember { mutableStateOf(initialItem?.title ?: "") }
    var description by remember { mutableStateOf(initialItem?.description ?: "") }
    var selectedType by remember { mutableStateOf(initialItem?.type ?: ReminderType.REMINDER) }
    var selectedCategory by remember { mutableStateOf(initialItem?.category ?: ReminderCategory.GENERAL) }
    var selectedRecurrence by remember { mutableStateOf(initialItem?.recurrence ?: RecurrenceType.NONE) }
    var triggerEpochMs by remember { mutableStateOf(initialItem?.triggerEpochMs ?: (System.currentTimeMillis() + 3600000L)) }
    var vibrate by remember { mutableStateOf(initialItem?.vibrate ?: true) }
    var ttsAnnounce by remember { mutableStateOf(initialItem?.ttsAnnounce ?: true) }
    var checklistItems by remember { mutableStateOf(initialItem?.checklist ?: emptyList()) }
    var newSubtaskText by remember { mutableStateOf("") }

    val cal = remember(triggerEpochMs) {
        Calendar.getInstance().apply { timeInMillis = triggerEpochMs }
    }

    val timeFormat = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }
    val dateFormat = remember { SimpleDateFormat("EEE, d MMM yyyy", Locale.getDefault()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (initialItem == null) "New Alarm / Reminder" else "Edit Reminder",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = LichiVisualTokens.TextNavy
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Title Field
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    placeholder = { Text("e.g. Medicine, Team Meeting, Wake up") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                // Type Segment
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ReminderType.values().take(3).forEach { t ->
                        val isSel = selectedType == t
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSel) LichiVisualTokens.BrandPurple else Color(0xFFF3F4F6),
                            modifier = Modifier.weight(1f).clickable { selectedType = t }
                        ) {
                            Text(
                                text = t.name.lowercase().capitalize(),
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                color = if (isSel) Color.White else LichiVisualTokens.TextNavy,
                                modifier = Modifier.padding(vertical = 8.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }

                // Date & Time Pickers
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFFF3F4F6),
                        modifier = Modifier.weight(1f).clickable {
                            DatePickerDialog(
                                context,
                                { _, y, m, d ->
                                    val c = Calendar.getInstance().apply {
                                        timeInMillis = triggerEpochMs
                                        set(Calendar.YEAR, y)
                                        set(Calendar.MONTH, m)
                                        set(Calendar.DAY_OF_MONTH, d)
                                    }
                                    triggerEpochMs = c.timeInMillis
                                },
                                cal.get(Calendar.YEAR),
                                cal.get(Calendar.MONTH),
                                cal.get(Calendar.DAY_OF_MONTH)
                            ).show()
                        }
                    ) {
                        Text(
                            text = dateFormat.format(Date(triggerEpochMs)),
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                            modifier = Modifier.padding(10.dp),
                            color = LichiVisualTokens.TextNavy
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFFF3F4F6),
                        modifier = Modifier.weight(1f).clickable {
                            TimePickerDialog(
                                context,
                                { _, hour, min ->
                                    val c = Calendar.getInstance().apply {
                                        timeInMillis = triggerEpochMs
                                        set(Calendar.HOUR_OF_DAY, hour)
                                        set(Calendar.MINUTE, min)
                                        set(Calendar.SECOND, 0)
                                    }
                                    triggerEpochMs = c.timeInMillis
                                },
                                cal.get(Calendar.HOUR_OF_DAY),
                                cal.get(Calendar.MINUTE),
                                false
                            ).show()
                        }
                    ) {
                        Text(
                            text = timeFormat.format(Date(triggerEpochMs)),
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                            modifier = Modifier.padding(10.dp),
                            color = LichiVisualTokens.BrandPurple
                        )
                    }
                }

                // Recurrence
                Column {
                    Text("Recurrence", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = LichiVisualTokens.TextGray)
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        RecurrenceType.values().forEach { rec ->
                            val isSel = selectedRecurrence == rec
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (isSel) LichiVisualTokens.BrandPurpleSoftBg else Color(0xFFF3F4F6),
                                border = if (isSel) androidx.compose.foundation.BorderStroke(1.dp, LichiVisualTokens.BrandPurple) else null,
                                modifier = Modifier.clickable { selectedRecurrence = rec }
                            ) {
                                Text(
                                    text = rec.name.lowercase().capitalize(),
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                                    color = if (isSel) LichiVisualTokens.BrandPurple else LichiVisualTokens.TextNavy,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }
                }

                // Category
                Column {
                    Text("Category", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = LichiVisualTokens.TextGray)
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ReminderCategory.values().forEach { cat ->
                            val isSel = selectedCategory == cat
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (isSel) LichiVisualTokens.BrandPurpleSoftBg else Color(0xFFF3F4F6),
                                border = if (isSel) androidx.compose.foundation.BorderStroke(1.dp, LichiVisualTokens.BrandPurple) else null,
                                modifier = Modifier.clickable { selectedCategory = cat }
                            ) {
                                Text(
                                    text = cat.displayName,
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                                    color = if (isSel) LichiVisualTokens.BrandPurple else LichiVisualTokens.TextNavy,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }
                }

                // Subtask Checklist Builder
                Column {
                    Text("Subtasks & Checklist", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = LichiVisualTokens.TextGray)
                    Spacer(Modifier.height(4.dp))
                    checklistItems.forEachIndexed { index, item ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("• ${item.title}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            IconButton(onClick = { checklistItems = checklistItems - item }, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Default.Close, null, modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newSubtaskText,
                            onValueChange = { newSubtaskText = it },
                            placeholder = { Text("Add subtask...") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        IconButton(onClick = {
                            if (newSubtaskText.isNotBlank()) {
                                checklistItems = checklistItems + ChecklistItem(title = newSubtaskText.trim())
                                newSubtaskText = ""
                            }
                        }) {
                            Icon(Icons.Default.Add, null, tint = LichiVisualTokens.BrandPurple)
                        }
                    }
                }

                // Toggles for Sound / TTS / Vibration
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("Vibration", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Switch(checked = vibrate, onCheckedChange = { vibrate = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("Voice TTS Announce", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Switch(checked = ttsAnnounce, onCheckedChange = { ttsAnnounce = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (title.isBlank()) {
                        Toast.makeText(context, "Please enter a title", Toast.LENGTH_SHORT).show()
                        return@TextButton
                    }
                    val item = (initialItem ?: ReminderItem(title = title, triggerEpochMs = triggerEpochMs)).copy(
                        title = title.trim(),
                        description = description.trim(),
                        type = selectedType,
                        category = selectedCategory,
                        recurrence = selectedRecurrence,
                        triggerEpochMs = triggerEpochMs,
                        vibrate = vibrate,
                        ttsAnnounce = ttsAnnounce,
                        checklist = checklistItems,
                        status = ReminderStatus.ACTIVE
                    )
                    onSave(item)
                }
            ) {
                Text("Save", color = LichiVisualTokens.BrandPurple, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = LichiVisualTokens.TextGray)
            }
        }
    )
}

@Composable
fun ReminderDiagnosticsDialog(
    diagnostics: com.lichiai.time.model.ReminderDiagnostics,
    onDismiss: () -> Unit,
    onRequestExactAlarmPermission: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Scheduler Diagnostics", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Total Reminders: ${diagnostics.totalReminders}")
                Text("Active Alarms: ${diagnostics.activeAlarms}")
                Text("Active Reminders: ${diagnostics.activeReminders}")
                Text("Exact Alarms Permission: ${if (diagnostics.canScheduleExactAlarms) "Granted (Exact)" else "Denied (Inexact fallback)"}")
                Text("Notifications: ${if (diagnostics.isNotificationsEnabled) "Enabled" else "Disabled"}")
                Text("Deliveries Logged: ${diagnostics.deliverySuccessCount}")
                Text("Delivery Failures: ${diagnostics.failureCount}")

                if (!diagnostics.canScheduleExactAlarms) {
                    Spacer(Modifier.height(6.dp))
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = LichiVisualTokens.BrandPurpleSoftBg,
                        modifier = Modifier.clickable { onRequestExactAlarmPermission() }
                    ) {
                        Text(
                            "Enable Exact Alarms in Android Settings",
                            color = LichiVisualTokens.BrandPurple,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("OK") }
        }
    )
}

@Composable
fun BackupRestoreDialog(
    onCreateBackup: () -> String,
    onRestoreBackup: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var backupJson by remember { mutableStateOf("") }
    var restoreInput by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Local Backup & Restore", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(onClick = { backupJson = onCreateBackup() }) {
                    Text("Generate Backup JSON", color = LichiVisualTokens.BrandPurple)
                }
                if (backupJson.isNotEmpty()) {
                    OutlinedTextField(
                        value = backupJson,
                        onValueChange = {},
                        label = { Text("Backup Payload") },
                        modifier = Modifier.fillMaxWidth().height(120.dp),
                        readOnly = true
                    )
                }

                HorizontalDivider()

                OutlinedTextField(
                    value = restoreInput,
                    onValueChange = { restoreInput = it },
                    label = { Text("Paste JSON to Restore") },
                    modifier = Modifier.fillMaxWidth().height(100.dp)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (restoreInput.isNotBlank()) onRestoreBackup(restoreInput)
                }
            ) {
                Text("Restore", fontWeight = FontWeight.Bold, color = LichiVisualTokens.BrandPurple)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
