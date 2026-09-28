package com.lichiai.ui.skill

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lichiai.skill.model.Skill
import com.lichiai.skill.model.SkillRiskLevel
import com.lichiai.skill.model.SkillSource
import com.lichiai.skill.repository.SkillRepository
import com.lichiai.skill.validator.SkillValidator
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SkillsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val repository = remember { SkillRepository.getInstance(context) }
    val skills by repository.skills.collectAsState()
    val scope = rememberCoroutineScope()

    var selectedSkill by remember { mutableStateOf<Skill?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(WindowInsets.statusBars.asPaddingValues())
        ) {
            // Top App Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.testTag("skills_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
                Text(
                    text = "Skills Library",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = { showCreateDialog = true },
                    modifier = Modifier.testTag("add_skill_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Add Skill",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Info Banner
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                        .padding(16.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Autonomous Agent V2 Skills",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Modular, task-scoped capabilities with sandboxed workflows. When active, skills guide Agent V2 while core safety and verification rules remain strictly authoritative.",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                            lineHeight = 18.sp
                        )
                    }
                }

                // Skills Count
                val enabledCount = skills.count { it.enabled }
                Text(
                    text = "Installed Skills ($enabledCount of ${skills.size} active)",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary
                )

                // Skills List
                skills.forEach { skill ->
                    SkillItemCard(
                        skill = skill,
                        onToggle = { isEnabled ->
                            scope.launch {
                                repository.toggleSkill(skill.id, isEnabled)
                            }
                        },
                        onClick = {
                            selectedSkill = skill
                        }
                    )
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }

    // Detail & Editor Dialog
    selectedSkill?.let { skill ->
        SkillDetailEditorDialog(
            skill = skill,
            onDismiss = { selectedSkill = null },
            onSave = { updatedMarkdown, reason ->
                scope.launch {
                    val result = repository.updateSkill(skill.id, updatedMarkdown, reason)
                    if (result.isSuccess) {
                        Toast.makeText(context, "Skill updated to v${result.getOrThrow().version}", Toast.LENGTH_SHORT).show()
                        selectedSkill = null
                    } else {
                        Toast.makeText(context, "Update failed: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                    }
                }
            },
            onRollback = { targetVersion ->
                scope.launch {
                    val result = repository.rollbackSkill(skill.id, targetVersion)
                    if (result.isSuccess) {
                        Toast.makeText(context, "Restored v$targetVersion", Toast.LENGTH_SHORT).show()
                        selectedSkill = null
                    } else {
                        Toast.makeText(context, "Rollback failed: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                    }
                }
            },
            onDelete = {
                scope.launch {
                    val result = repository.deleteSkill(skill.id)
                    if (result.isSuccess) {
                        Toast.makeText(context, "Skill deleted", Toast.LENGTH_SHORT).show()
                        selectedSkill = null
                    } else {
                        Toast.makeText(context, "Cannot delete: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                    }
                }
            },
            onExport = {
                val clipManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Skill ${skill.name}", skill.markdownContent)
                clipManager.setPrimaryClip(clip)
                Toast.makeText(context, "Skill Markdown copied to clipboard", Toast.LENGTH_SHORT).show()
            }
        )
    }

    // Create New Skill Dialog
    if (showCreateDialog) {
        CreateSkillDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { markdown, source ->
                scope.launch {
                    val result = repository.createSkill(markdown, source)
                    if (result.isSuccess) {
                        Toast.makeText(context, "Skill created successfully", Toast.LENGTH_SHORT).show()
                        showCreateDialog = false
                    } else {
                        Toast.makeText(context, "Creation failed: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        )
    }
}

@Composable
private fun SkillItemCard(
    skill: Skill,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit
) {
    val cardBg = MaterialTheme.colorScheme.surface
    val borderCol = if (skill.enabled) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(cardBg)
            .border(1.dp, borderCol, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .testTag("skill_item_${skill.id}")
            .padding(14.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = skill.name,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "v${skill.version}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Text(
                        text = skill.description,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }

                Switch(
                    checked = skill.enabled,
                    onCheckedChange = onToggle,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .testTag("toggle_skill_${skill.id}")
                )
            }

            // Badges Row
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SourceBadge(source = skill.source)
                RiskBadge(risk = skill.riskLevel)
            }
        }
    }
}

@Composable
private fun SourceBadge(source: SkillSource) {
    val (label, bg, fg) = when (source) {
        SkillSource.SYSTEM -> Triple("SYSTEM", Color(0xFF1565C0), Color.White)
        SkillSource.USER -> Triple("USER", Color(0xFF2E7D32), Color.White)
        SkillSource.IMPORTED -> Triple("IMPORTED", Color(0xFF6A1B9A), Color.White)
        SkillSource.AGENT_GENERATED -> Triple("AUTONOMOUS", Color(0xFFE65100), Color.White)
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bg.copy(alpha = 0.2f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(text = label, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = bg)
    }
}

@Composable
private fun RiskBadge(risk: SkillRiskLevel) {
    val (label, bg) = when (risk) {
        SkillRiskLevel.LOW -> "LOW RISK" to Color(0xFF2E7D32)
        SkillRiskLevel.MEDIUM -> "MED RISK" to Color(0xFFF57C00)
        SkillRiskLevel.HIGH -> "HIGH RISK" to Color(0xFFD32F2F)
        SkillRiskLevel.CRITICAL -> "CRITICAL" to Color(0xFFC2185B)
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bg.copy(alpha = 0.15f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(text = label, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = bg)
    }
}

@Composable
private fun SkillDetailEditorDialog(
    skill: Skill,
    onDismiss: () -> Unit,
    onSave: (newMarkdown: String, reason: String) -> Unit,
    onRollback: (targetVersion: String) -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit
) {
    var editableMarkdown by remember { mutableStateOf(skill.markdownContent) }
    var changeReason by remember { mutableStateOf("Manual edit") }
    var activeTab by remember { mutableStateOf(0) } // 0: Editor, 1: History, 2: Overview
    val validation = remember(editableMarkdown) { SkillValidator.validate(editableMarkdown, skill.id) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = skill.name, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(text = "ID: ${skill.id} • v${skill.version}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onExport) {
                    Icon(imageVector = Icons.Default.Share, contentDescription = "Export Markdown")
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(420.dp)
            ) {
                // Tab Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("Editor", "History (${skill.versionHistory.size})", "Overview").forEachIndexed { index, title ->
                        val isSelected = activeTab == index
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { activeTab = index }
                                .padding(vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = title,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                when (activeTab) {
                    0 -> { // Markdown Editor
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Validation Status Bar
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(
                                        if (validation.isValid) Color(0xFF2E7D32).copy(alpha = 0.15f)
                                        else Color(0xFFD32F2F).copy(alpha = 0.15f)
                                    )
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = if (validation.isValid) Icons.Default.CheckCircle else Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = if (validation.isValid) Color(0xFF2E7D32) else Color(0xFFD32F2F),
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = if (validation.isValid) "Skill syntax and safety validated" else validation.errors.firstOrNull() ?: "Validation issues",
                                    fontSize = 11.sp,
                                    color = if (validation.isValid) Color(0xFF2E7D32) else Color(0xFFD32F2F)
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(280.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                                    .padding(10.dp)
                            ) {
                                BasicTextField(
                                    value = editableMarkdown,
                                    onValueChange = { editableMarkdown = it },
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .verticalScroll(rememberScrollState()),
                                    textStyle = LocalTextStyle.current.copy(
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp
                                    ),
                                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary)
                                )
                            }
                        }
                    }
                    1 -> { // Version History & Rollback
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (skill.versionHistory.isEmpty()) {
                                Text(
                                    text = "No previous versions recorded yet. Initial version: v${skill.version}",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(16.dp)
                                )
                            } else {
                                val sdf = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
                                skill.versionHistory.reversed().forEach { v ->
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(MaterialTheme.colorScheme.surfaceVariant)
                                            .padding(10.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(text = "v${v.version}", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                                Text(text = v.changeReason, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                Text(text = sdf.format(Date(v.timestamp)), fontSize = 10.sp, color = MaterialTheme.colorScheme.outline)
                                            }
                                            TextButton(onClick = { onRollback(v.version) }) {
                                                Text("Restore")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    2 -> { // Overview & Permissions
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(text = "Purpose", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Text(text = skill.purpose.ifBlank { "Not specified" }, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

                            Text(text = "When to Apply", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Text(text = skill.whenToUse.ifBlank { "Any relevant context" }, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

                            Text(text = "Declared Permissions", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                skill.permissions.forEach { perm ->
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(MaterialTheme.colorScheme.primaryContainer)
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(text = perm.name, fontSize = 10.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (activeTab == 0 && skill.source != SkillSource.SYSTEM) {
                TextButton(
                    onClick = { onSave(editableMarkdown, changeReason) },
                    enabled = validation.isValid
                ) {
                    Text("Save Changes")
                }
            }
        },
        dismissButton = {
            Row {
                if (skill.source != SkillSource.SYSTEM) {
                    TextButton(onClick = onDelete) {
                        Text("Delete", color = Color(0xFFD32F2F))
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Close")
                }
            }
        }
    )
}

@Composable
private fun CreateSkillDialog(
    onDismiss: () -> Unit,
    onCreate: (markdown: String, source: SkillSource) -> Unit
) {
    val template = """
        # My Custom Skill

        ## Purpose
        Describe what this skill accomplishes on the device.

        ## When to use
        Mention apps, trigger words, or tasks when this skill should activate.

        ## When NOT to use
        Mention tasks that should NOT use this skill.

        ## Workflow
        1. Open target app.
        2. Inspect the screen.
        3. Perform the requested interaction.
        4. Verify completion.

        ## Rules
        - Never perform unauthorized actions.
        - Verify results before reporting completion.
    """.trimIndent()

    var markdown by remember { mutableStateOf(template) }
    val validation = remember(markdown) { SkillValidator.validate(markdown) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create New Skill", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(380.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Define your custom skill in Markdown format (SKILL.md). It will be validated for safety before activation.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Live Validation
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            if (validation.isValid) Color(0xFF2E7D32).copy(alpha = 0.15f)
                            else Color(0xFFD32F2F).copy(alpha = 0.15f)
                        )
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = if (validation.isValid) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = null,
                        tint = if (validation.isValid) Color(0xFF2E7D32) else Color(0xFFD32F2F),
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = if (validation.isValid) "Valid skill structure" else validation.errors.firstOrNull() ?: "Invalid format",
                        fontSize = 11.sp,
                        color = if (validation.isValid) Color(0xFF2E7D32) else Color(0xFFD32F2F)
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                        .padding(10.dp)
                ) {
                    BasicTextField(
                        value = markdown,
                        onValueChange = { markdown = it },
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                        textStyle = LocalTextStyle.current.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(markdown, SkillSource.USER) },
                enabled = validation.isValid
            ) {
                Text("Create Skill")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
