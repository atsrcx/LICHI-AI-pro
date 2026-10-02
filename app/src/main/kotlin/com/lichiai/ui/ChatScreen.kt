package com.lichiai.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lichiai.R
import com.lichiai.agent.model.AgentLiveStatus
import com.lichiai.data.AppSettings
import com.lichiai.data.Attachment
import com.lichiai.data.Conversation
import com.lichiai.data.Message
import com.lichiai.data.ProviderConfig
import com.lichiai.ui.activity.AssistantActivityState
import com.lichiai.ui.activity.toAssistantActivity
import com.lichiai.ui.spy.PlatformProfileCard
import com.lichiai.ui.spy.ProfilePreviewCard
import com.lichiai.ui.spy.SpyProfileSerializer
import com.lichiai.util.AttachmentLoader
import com.lichiai.web.model.WebActivityState
import kotlinx.coroutines.delay

/**
 * SCREEN 2: ACTIVE CLEAN CHAT & GREETING SCREEN
 * Pixel-perfect implementation matching user's HTML prototype specification.
 */
@Composable
fun ChatScreen(
    conversation: Conversation?,
    settings: AppSettings,
    activeProvider: ProviderConfig?,
    isStreaming: Boolean,
    streamingOverlay: Pair<String, String>? = null,
    agentLiveStatus: AgentLiveStatus = AgentLiveStatus(),
    webActivityState: WebActivityState = WebActivityState(),
    liveActivityState: AssistantActivityState? = null,
    activeSpeakingMessageId: String? = null,
    onMenu: () -> Unit,
    onSend: (String, List<Attachment>) -> Unit,
    onStop: () -> Unit,
    onRegenerate: () -> Unit,
    onRegenerateFrom: (String) -> Unit = {},
    onDeleteMessage: (String) -> Unit = {},
    onEditMessage: (String, String) -> Unit = { _, _ -> },
    onToggleSpeak: (String, String) -> Unit = { _, _ -> },
    onNew: () -> Unit,
    onOpenSettings: () -> Unit,
    onPickModel: () -> Unit,
    onOpenVoiceMode: () -> Unit = {},
    onOpenBrowser: () -> Unit = {},
    onClearChat: () -> Unit = {},
    onOpenReport: ((String) -> Unit)? = null
) {
    var input by rememberSaveable { mutableStateOf("") }
    var pendingAttachments by remember { mutableStateOf<List<Attachment>>(emptyList()) }
    var plusMenuOpen by remember { mutableStateOf(false) }
    var isLiveSearchActive by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val rawMessages = conversation?.messages ?: emptyList()
    val isDark = MaterialTheme.colorScheme.background.red < 0.2f
    val ctx = LocalContext.current

    // Stable message resolution preventing duplicates or empty placeholders
    val messages = remember(rawMessages, streamingOverlay, isStreaming) {
        val ov = streamingOverlay
        val mapped = rawMessages.map { msg ->
            if (ov != null && msg.id == ov.first) {
                msg.copy(content = ov.second)
            } else {
                msg
            }
        }
        mapped.filter { msg ->
            if (msg.role == "assistant" && msg.content.isEmpty() && msg.taskActivity == null) {
                isStreaming && (ov?.first == msg.id || msg.id == mapped.lastOrNull()?.id)
            } else {
                true
            }
        }.distinctBy { it.id }
    }

    var editingMessageId by rememberSaveable { mutableStateOf<String?>(null) }
    var editingDraft by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(messages.size, isStreaming) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    LaunchedEffect(streamingOverlay?.second?.length) {
        if (isStreaming && messages.isNotEmpty()) {
            val layoutInfo = listState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            if (lastVisible >= totalItems - 2) {
                listState.scrollToItem(messages.lastIndex)
            }
        }
    }

    val modelDisplayLabel = remember(settings.activeModel, activeProvider) {
        if (settings.activeModel.isNotBlank()) {
            val model = settings.activeModel
            if (model.contains("/")) model else "${activeProvider?.name?.lowercase() ?: "model"}/$model"
        } else {
            activeProvider?.name ?: "openai/gpt-oss-120b"
        }
    }

    val screenBg = if (isDark) Color(0xFF0F172A) else Color(0xFFF4F6FA)

    // Image & File Pickers
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            val (name, size) = AttachmentLoader.queryNameSize(ctx.contentResolver, uri)
            ctx.contentResolver.runCatching {
                takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val mime = ctx.contentResolver.getType(uri) ?: "image/jpeg"
            pendingAttachments = pendingAttachments + Attachment(
                type = "image", uri = uri.toString(), mimeType = mime, name = name, sizeBytes = size
            )
        }
    }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            val (name, size) = AttachmentLoader.queryNameSize(ctx.contentResolver, uri)
            ctx.contentResolver.runCatching {
                takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val mime = ctx.contentResolver.getType(uri) ?: "application/octet-stream"
            val isImg = mime.startsWith("image/")
            pendingAttachments = pendingAttachments + Attachment(
                type = if (isImg) "image" else "file", uri = uri.toString(), mimeType = mime, name = name, sizeBytes = size
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(screenBg)
            .imePadding()
    ) {
        // 1. App Top Navigation Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 6.dp)
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left Circle: Hamburger Menu (Three Lines) -> Opens Dashboard Hub
            LichiBrandPill(onMenu = onMenu)

            // Right Pill: Compact Model Picker + Action Icons
            LichiControlPill(
                modelLabel = modelDisplayLabel,
                onPickModel = onPickModel,
                onOpenBrowser = onOpenBrowser,
                onOpenVoiceMode = onOpenVoiceMode,
                onNewChat = onNew,
                isWebSearchActive = isLiveSearchActive,
                onToggleWebSearch = {
                    isLiveSearchActive = !isLiveSearchActive
                }
            )
        }

        // 2. Main Content Area (Landing View vs Active Chat Stream)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (messages.isEmpty()) {
                // LANDING VIEW (Clean minimal centered headline matching HTML prototype)
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Hi, I'm ",
                            fontSize = 30.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.5).sp,
                            color = if (isDark) Color(0xFFF8FAFC) else Color(0xFF0F172A)
                        )
                        Text(
                            text = "LICHI–AI",
                            fontSize = 30.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 0.sp,
                            color = Color(0xFF7C3AED)
                        )
                    }
                }
            } else {
                // ACTIVE CHAT VIEW
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(messages, key = { it.id }) { msg ->
                        LichiMessageItem(
                            message = msg,
                            senderLabel = if (msg.role == "user") "You" else modelDisplayLabel,
                            isLastAssistant = msg.id == messages.lastOrNull()?.id && msg.role == "assistant",
                            isStreaming = isStreaming,
                            webActivityState = webActivityState,
                            agentLiveStatus = agentLiveStatus,
                            liveActivityState = liveActivityState,
                            isSpeaking = activeSpeakingMessageId == msg.id,
                            onToggleSpeak = { onToggleSpeak(msg.id, msg.content) },
                            editing = editingMessageId == msg.id,
                            editingDraft = if (editingMessageId == msg.id) editingDraft else "",
                            onEditingDraftChange = { editingDraft = it },
                            onStartEdit = {
                                editingMessageId = msg.id
                                editingDraft = msg.content
                            },
                            onCommitEdit = {
                                editingMessageId?.let { id -> onEditMessage(id, editingDraft) }
                                editingMessageId = null
                                editingDraft = ""
                            },
                            onCancelEdit = {
                                editingMessageId = null
                                editingDraft = ""
                            },
                            onDelete = { onDeleteMessage(msg.id) },
                            onRegenerateFrom = { onRegenerateFrom(msg.id) },
                            onOpenReport = onOpenReport
                        )
                    }
                }
            }
        }

        // 3. Bottom Input Area with Realistic Smoky Cloud Halo Wrapper
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(WindowInsets.navigationBars.asPaddingValues())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Pending Attachments row
            if (pendingAttachments.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(pendingAttachments, key = { it.uri }) { att ->
                        AttachmentChip(att = att, onRemove = {
                            pendingAttachments = pendingAttachments - att
                        })
                    }
                }
                Spacer(Modifier.height(4.dp))
            }

            // Realistic Smooth Smoky Cloud Wrapper around Composer Pill
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .drawBehind {
                        // Multi-layer smoky mist background aura (recreating .smoke-aura-primary)
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    Color(0xFF0F172A).copy(alpha = if (isDark) 0.6f else 0.22f),
                                    Color(0xFF1E293B).copy(alpha = if (isDark) 0.35f else 0.10f),
                                    Color.Transparent
                                ),
                                center = center,
                                radius = size.width * 0.55f
                            )
                        )
                    }
                    .padding(vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                // Input Bar Container Pill
                Surface(
                    shape = CircleShape,
                    color = if (isDark) Color(0xFF1E293B) else Color.White,
                    shadowElevation = 8.dp,
                    border = BorderStroke(
                        1.dp,
                        if (isDark) Color(0xFF334155) else Color(0xFF1E293B).copy(alpha = 0.8f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Plus Button (Attach/Tools Menu)
                        Box {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = ripple(bounded = true),
                                        onClick = { plusMenuOpen = true }
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = "Tools & Attachments",
                                    tint = if (isDark) Color(0xFFCBD5E1) else Color(0xFF475569),
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            // Plus Menu Dropdown
                            DropdownMenu(
                                expanded = plusMenuOpen,
                                onDismissRequest = { plusMenuOpen = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Analyze Screen / Image", fontSize = 12.sp) },
                                    leadingIcon = {
                                        Icon(Icons.Default.CameraAlt, null, tint = Color(0xFF7C3AED), modifier = Modifier.size(16.dp))
                                    },
                                    onClick = {
                                        plusMenuOpen = false
                                        onSend("Analyze this screen and tell me what you see.", emptyList())
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Attach Media / Photo", fontSize = 12.sp) },
                                    leadingIcon = {
                                        Icon(Icons.Default.Image, null, tint = Color(0xFF7C3AED), modifier = Modifier.size(16.dp))
                                    },
                                    onClick = {
                                        plusMenuOpen = false
                                        pickImage.launch("image/*")
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Attach File", fontSize = 12.sp) },
                                    leadingIcon = {
                                        Icon(Icons.Default.AttachFile, null, tint = Color(0xFF7C3AED), modifier = Modifier.size(16.dp))
                                    },
                                    onClick = {
                                        plusMenuOpen = false
                                        pickFile.launch("*/*")
                                    }
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (isLiveSearchActive) "Live Web Grounding (ON)" else "Live Web Grounding",
                                            fontSize = 12.sp
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.Public, null, tint = Color(0xFF0284C7), modifier = Modifier.size(16.dp))
                                    },
                                    onClick = {
                                        plusMenuOpen = false
                                        isLiveSearchActive = !isLiveSearchActive
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Run Speed Benchmark", fontSize = 12.sp) },
                                    leadingIcon = {
                                        Icon(Icons.Default.Bolt, null, tint = Color(0xFFD97706), modifier = Modifier.size(16.dp))
                                    },
                                    onClick = {
                                        plusMenuOpen = false
                                        onSend("Run an inference benchmark at 420 tokens/sec.", emptyList())
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Clear History", fontSize = 12.sp, color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = {
                                        Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                                    },
                                    onClick = {
                                        plusMenuOpen = false
                                        onClearChat()
                                    }
                                )
                            }
                        }

                        // Text Input Field
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            if (input.isEmpty()) {
                                Text(
                                    text = "Message LICHI–AI...",
                                    fontSize = 12.sp,
                                    color = Color(0xFF94A3B8)
                                )
                            }
                            BasicTextField(
                                value = input,
                                onValueChange = { input = it },
                                textStyle = androidx.compose.ui.text.TextStyle(
                                    fontSize = 12.sp,
                                    color = if (isDark) Color(0xFFF8FAFC) else Color(0xFF1E293B)
                                ),
                                keyboardOptions = KeyboardOptions(
                                    capitalization = KeyboardCapitalization.Sentences,
                                    imeAction = ImeAction.Send
                                ),
                                keyboardActions = KeyboardActions(
                                    onSend = {
                                        val text = input.trim()
                                        if (text.isNotEmpty() || pendingAttachments.isNotEmpty()) {
                                            val atts = pendingAttachments
                                            input = ""
                                            pendingAttachments = emptyList()
                                            onSend(text, atts)
                                        }
                                    }
                                ),
                                maxLines = 4,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        // Send Arrow Button
                        val canSend = (input.trim().isNotEmpty() || pendingAttachments.isNotEmpty()) && !isStreaming
                        val btnBg = when {
                            isStreaming -> Color(0xFF7C3AED)
                            canSend -> Color(0xFF7C3AED)
                            else -> if (isDark) Color(0xFF334155) else Color(0xFFE2E8F0)
                        }
                        val iconColor = when {
                            isStreaming || canSend -> Color.White
                            else -> Color(0xFF64748B)
                        }

                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(btnBg)
                                .clickable(
                                    enabled = canSend || isStreaming,
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = ripple(bounded = true, color = Color.White),
                                    onClick = {
                                        if (isStreaming) {
                                            onStop()
                                        } else if (canSend) {
                                            val text = input.trim()
                                            val atts = pendingAttachments
                                            input = ""
                                            pendingAttachments = emptyList()
                                            onSend(text, atts)
                                        }
                                    }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isStreaming) Icons.Default.Stop else Icons.Default.ArrowUpward,
                                contentDescription = if (isStreaming) "Stop" else "Send message",
                                tint = iconColor,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            // Android Home Navigation Indicator Bar
            Box(
                modifier = Modifier
                    .padding(top = 8.dp, bottom = 4.dp)
                    .size(width = 128.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF94A3B8).copy(alpha = 0.8f))
                    .clickable(onClick = onMenu)
            )
        }
    }
}

/**
 * Message Row Component matching user's HTML prototype
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun LichiMessageItem(
    message: Message,
    senderLabel: String,
    isLastAssistant: Boolean,
    isStreaming: Boolean,
    webActivityState: WebActivityState = WebActivityState(),
    agentLiveStatus: AgentLiveStatus = AgentLiveStatus(),
    liveActivityState: AssistantActivityState? = null,
    isSpeaking: Boolean = false,
    onToggleSpeak: () -> Unit = {},
    editing: Boolean = false,
    editingDraft: String = "",
    onEditingDraftChange: (String) -> Unit = {},
    onStartEdit: () -> Unit = {},
    onCommitEdit: () -> Unit = {},
    onCancelEdit: () -> Unit = {},
    onDelete: () -> Unit = {},
    onRegenerateFrom: () -> Unit = {},
    onOpenReport: ((String) -> Unit)? = null
) {
    val isUser = message.role == "user"
    val isDark = MaterialTheme.colorScheme.background.red < 0.2f
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    var isLiked by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(2000)
            copied = false
        }
    }

    if (isUser) {
        // User Message Bubble (Right aligned, slate-800, white text, rounded-2xl with rounded-tr-sm)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier.widthIn(max = 310.dp)
            ) {
                // Attachments if present
                if (message.attachments.isNotEmpty()) {
                    Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(bottom = 4.dp)
                    ) {
                        message.attachments.forEach { att ->
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFFEDE9FE))
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (att.type == "image") Icons.Default.Image else Icons.Default.AttachFile,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = Color(0xFF7C3AED)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = att.name,
                                    color = Color(0xFF1E1B4B),
                                    fontSize = 11.sp,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .clip(
                            RoundedCornerShape(
                                topStart = 16.dp,
                                topEnd = 4.dp,
                                bottomStart = 16.dp,
                                bottomEnd = 16.dp
                            )
                        )
                        .background(Color(0xFF1E293B))
                        .combinedClickable(
                            onClick = {},
                            onLongClick = { menuOpen = true }
                        )
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    SelectionContainer {
                        Text(
                            text = message.content,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            color = Color.White
                        )
                    }
                }

                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Copy", fontSize = 12.sp) },
                        leadingIcon = { Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(16.dp)) },
                        onClick = {
                            menuOpen = false
                            clipboard.setText(AnnotatedString(message.content))
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Edit", fontSize = 12.sp) },
                        leadingIcon = { Icon(Icons.Default.Edit, null, modifier = Modifier.size(16.dp)) },
                        onClick = {
                            menuOpen = false
                            onStartEdit()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Regenerate", fontSize = 12.sp) },
                        leadingIcon = { Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp)) },
                        onClick = {
                            menuOpen = false
                            onRegenerateFrom()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete", fontSize = 12.sp, color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp)) },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        }
                    )
                }
            }
        }
    } else {
        // Assistant Message (Frameless Gemini/ChatGPT style with top metadata header)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
        ) {
            // Metadata Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 2.dp, bottom = 4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF7C3AED)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "✦",
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "LICHI–AI",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isDark) Color(0xFFCBD5E1) else Color(0xFF334155)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "⚡ ~420 T/S • $senderLabel",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF94A3B8)
                )
            }

            // Inline Task Activity Chip if working
            val act = if (isLastAssistant) {
                liveActivityState
                    ?: message.taskActivity
                    ?: agentLiveStatus.toAssistantActivity()
                    ?: webActivityState.toAssistantActivity()
            } else {
                message.taskActivity
            }
            if (act != null) {
                com.lichiai.ui.activity.TaskActivityChip(
                    activity = act,
                    messageId = message.id,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }

            // Message Content (Frameless & Borderless, Gemini/ChatGPT Style)
            val spyProfile = remember(message.content) {
                SpyProfileSerializer.extractProfile(message.content)
            }
            val cleanMarkdown = remember(message.content) {
                SpyProfileSerializer.stripEmbeddedProfile(message.content)
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 2.dp, vertical = 2.dp)
            ) {
                if (message.content.isEmpty() && isStreaming && isLastAssistant) {
                    LichiTypingPulse()
                } else {
                    if (spyProfile != null) {
                        if (spyProfile.previewRequested) {
                            ProfilePreviewCard(
                                profile = spyProfile,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                        } else {
                            PlatformProfileCard(
                                profile = spyProfile,
                                onAnalyzeWebsite = {},
                                onViewReport = onOpenReport
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                    }

                    if (cleanMarkdown.isNotEmpty()) {
                        SelectionContainer {
                            MarkdownText(
                                text = cleanMarkdown,
                                messageId = message.id,
                                color = if (isDark) Color(0xFFF8FAFC) else Color(0xFF1E293B),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            // Action Row under assistant response
            if (message.content.isNotEmpty() && (!isStreaming || !isLastAssistant)) {
                Spacer(Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(start = 4.dp)
                ) {
                    // Copy
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .clickable {
                                clipboard.setText(AnnotatedString(message.content))
                                copied = true
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                            contentDescription = "Copy",
                            tint = if (copied) Color(0xFF10B981) else Color(0xFF94A3B8),
                            modifier = Modifier.size(14.dp)
                        )
                    }

                    // Like
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .clickable {
                                isLiked = if (isLiked == true) null else true
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isLiked == true) Icons.Default.ThumbUp else Icons.Outlined.ThumbUp,
                            contentDescription = "Like",
                            tint = if (isLiked == true) Color(0xFF7C3AED) else Color(0xFF94A3B8),
                            modifier = Modifier.size(14.dp)
                        )
                    }

                    // Read Aloud
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .clickable { onToggleSpeak() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.VolumeUp,
                            contentDescription = "Read Aloud",
                            tint = if (isSpeaking) Color(0xFF7C3AED) else Color(0xFF94A3B8),
                            modifier = Modifier.size(15.dp)
                        )
                    }

                    // Share
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .clickable {
                                val sendIntent = Intent().apply {
                                    action = Intent.ACTION_SEND
                                    putExtra(Intent.EXTRA_TEXT, message.content)
                                    type = "text/plain"
                                }
                                val shareIntent = Intent.createChooser(sendIntent, "Share Assistant Response")
                                context.startActivity(shareIntent)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Share",
                            tint = Color(0xFF94A3B8),
                            modifier = Modifier.size(14.dp)
                        )
                    }

                    // More
                    Box {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                            .clickable { menuOpen = true },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "More",
                                tint = Color(0xFF94A3B8),
                                modifier = Modifier.size(14.dp)
                            )
                        }

                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Regenerate", fontSize = 12.sp) },
                                leadingIcon = { Icon(Icons.Default.Refresh, null, tint = Color(0xFF7C3AED), modifier = Modifier.size(16.dp)) },
                                onClick = {
                                    menuOpen = false
                                    onRegenerateFrom()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Delete", fontSize = 12.sp, color = MaterialTheme.colorScheme.error) },
                                leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp)) },
                                onClick = {
                                    menuOpen = false
                                    onDelete()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LichiTypingPulse() {
    val infinite = rememberInfiniteTransition(label = "typing_pulse")
    val alpha by infinite.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "alpha"
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 4.dp)
    ) {
        repeat(3) { i ->
            Box(
                modifier = Modifier
                    .padding(end = 5.dp)
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(
                        Color(0xFF7C3AED).copy(
                            alpha = if (i == 0) alpha else if (i == 1) (1f - alpha) else alpha * 0.7f + 0.3f
                        )
                    )
            )
        }
    }
}
