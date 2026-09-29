package com.lichiai.ui

import android.content.Intent
import android.speech.tts.TextToSpeech
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.Translate
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lichiai.R
import com.lichiai.agent.model.AgentLiveStatus
import com.lichiai.data.AppSettings
import com.lichiai.data.Attachment
import com.lichiai.data.Conversation
import com.lichiai.data.Message
import com.lichiai.data.ProviderConfig
import com.lichiai.ui.spy.PlatformProfileCard
import com.lichiai.ui.spy.ProfilePreviewCard
import com.lichiai.ui.spy.SpyProfileSerializer
import com.lichiai.ui.activity.AssistantActivityState
import com.lichiai.ui.activity.toAssistantActivity
import com.lichiai.web.model.WebActivityState
import java.util.Locale
import kotlinx.coroutines.delay

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
    onOpenBrowser: () -> Unit = {}
) {
    var input by rememberSaveable { mutableStateOf("") }
    var pendingAttachments by remember { mutableStateOf<List<Attachment>>(emptyList()) }
    val listState = rememberLazyListState()
    val rawMessages = conversation?.messages ?: emptyList()

    // Stable message resolution preventing duplicates or empty phantom placeholders
    val messages = remember(rawMessages, streamingOverlay, isStreaming) {
        val ov = streamingOverlay
        val mapped = rawMessages.map { msg ->
            if (ov != null && msg.id == ov.first) {
                msg.copy(content = ov.second)
            } else {
                msg
            }
        }
        // Filter out empty assistant messages that have no content, no active streaming, and no task activity
        mapped.filter { msg ->
            if (msg.role == "assistant" && msg.content.isEmpty() && msg.taskActivity == null) {
                // Keep if actively streaming this message
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
            activeProvider?.name ?: "Select Model"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LichiVisualTokens.BackgroundLight)
            .imePadding()
    ) {
        // 1. Floating Top Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 6.dp)
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            LichiBrandPill(onMenu = onMenu)
            LichiControlPill(
                modelLabel = modelDisplayLabel,
                onPickModel = onPickModel,
                onOpenBrowser = onOpenBrowser,
                onOpenVoiceMode = onOpenVoiceMode,
                onNewChat = onNew
            )
        }

        // 2. Main Content Area
        if (messages.isEmpty()) {
            // Empty State: Pixel-accurate recreation of reference image
            LichiEmptyChatLanding(
                onSelectPrompt = { prompt ->
                    onSend(prompt, emptyList())
                },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            )
        } else {
            // Conversation Message History (ChatGPT-style flowing format)
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp)
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
                            editingMessageId?.let { id ->
                                onEditMessage(id, editingDraft)
                            }
                            editingMessageId = null
                            editingDraft = ""
                        },
                        onCancelEdit = {
                            editingMessageId = null
                            editingDraft = ""
                        },
                        onDelete = { onDeleteMessage(msg.id) },
                        onRegenerateFrom = { onRegenerateFrom(msg.id) }
                    )
                }
            }
        }

        // 3. Bottom Pill Capsule Message Composer
        InputBar(
            value = input,
            onValueChange = { input = it },
            attachments = pendingAttachments,
            onAttachmentsChange = { pendingAttachments = it },
            onSend = {
                val text = input
                val atts = pendingAttachments
                input = ""
                pendingAttachments = emptyList()
                onSend(text, atts)
            },
            onStop = onStop,
            isStreaming = isStreaming,
            enabled = true,
            placeholder = "Message LICHI–AI..."
        )
    }
}

/**
 * Pixel-accurate Empty Landing Screen
 */
@Composable
private fun LichiEmptyChatLanding(
    onSelectPrompt: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    val suggestions = remember {
        listOf(
            LichiSuggestionData(
                category = "CONCEPTS",
                prompt = "Explain what an LLM is in one paragraph.",
                icon = Icons.Default.AutoAwesome,
                categoryColor = LichiVisualTokens.CardConceptsFg,
                tileBgColor = LichiVisualTokens.CardConceptsBg,
                iconColor = LichiVisualTokens.CardConceptsIcon
            ),
            LichiSuggestionData(
                category = "CREATIVITY",
                prompt = "Write a haiku about Android development.",
                icon = Icons.Default.SmartToy,
                categoryColor = LichiVisualTokens.CardCreativityFg,
                tileBgColor = LichiVisualTokens.CardCreativityBg,
                iconColor = LichiVisualTokens.CardCreativityIcon
            ),
            LichiSuggestionData(
                category = "PRODUCTIVITY",
                prompt = "Give me 5 tips for productive remote work.",
                icon = Icons.Default.Lightbulb,
                categoryColor = LichiVisualTokens.CardProductivityFg,
                tileBgColor = LichiVisualTokens.CardProductivityBg,
                iconColor = LichiVisualTokens.CardProductivityIcon
            ),
            LichiSuggestionData(
                category = "LANGUAGES",
                prompt = "Translate \"Good morning\" into Japanese.",
                icon = Icons.Default.Translate,
                categoryColor = LichiVisualTokens.CardLanguagesFg,
                tileBgColor = LichiVisualTokens.CardLanguagesBg,
                iconColor = LichiVisualTokens.CardLanguagesIcon
            )
        )
    }

    Column(
        modifier = modifier
            .verticalScroll(scrollState)
            .padding(horizontal = 22.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Spacer(Modifier.height(10.dp))

        // Center Status Badge: "420 T/S • NEURAL ENGINE ACTIVE"
        LichiStatusBadge()

        Spacer(Modifier.height(14.dp))

        // Central Logo Tile with ambient purple glow
        LichiLogoTile()

        Spacer(Modifier.height(10.dp))

        // Heading & Subtitle
        LichiGreetingHeader()

        Spacer(Modifier.height(20.dp))

        // 4 Suggestion Cards Stack
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            suggestions.forEach { card ->
                LichiSuggestionCard(
                    data = card,
                    onClick = { onSelectPrompt(card.prompt) }
                )
            }
        }

        Spacer(Modifier.height(18.dp))

        // Bottom Capabilities Line
        LichiCapabilityLine(speedLabel = "Groq 420 t/s")

        Spacer(Modifier.height(12.dp))
    }
}

/**
 * Message Row Component (Direct ChatGPT-style conversation hierarchy)
 */
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
    onRegenerateFrom: () -> Unit = {}
) {
    val isUser = message.role == "user"
    if (isUser) {
        LichiUserBubble(
            message = message,
            editing = editing,
            editingDraft = editingDraft,
            onEditingDraftChange = onEditingDraftChange,
            onStartEdit = onStartEdit,
            onCommitEdit = onCommitEdit,
            onCancelEdit = onCancelEdit,
            onDelete = onDelete,
            onRegenerateFrom = onRegenerateFrom
        )
    } else {
        LichiAssistantRow(
            message = message,
            senderLabel = senderLabel,
            isLastAssistant = isLastAssistant,
            isStreaming = isStreaming,
            webActivityState = webActivityState,
            agentLiveStatus = agentLiveStatus,
            liveActivityState = liveActivityState,
            isSpeaking = isSpeaking,
            onToggleSpeak = onToggleSpeak,
            onDelete = onDelete,
            onRegenerate = onRegenerateFrom
        )
    }
}

/**
 * Compact Right-aligned User Message Bubble
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LichiUserBubble(
    message: Message,
    editing: Boolean,
    editingDraft: String,
    onEditingDraftChange: (String) -> Unit,
    onStartEdit: () -> Unit,
    onCommitEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onDelete: () -> Unit,
    onRegenerateFrom: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        Column(
            horizontalAlignment = Alignment.End,
            modifier = Modifier.widthIn(max = 310.dp)
        ) {
            // Attachments
            if (message.attachments.isNotEmpty()) {
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    message.attachments.forEach { att ->
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(LichiVisualTokens.BrandPurpleSoftBg)
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (att.type == "image") Icons.Default.Image else Icons.Default.AttachFile,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = LichiVisualTokens.BrandPurple
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = att.name,
                                color = LichiVisualTokens.TextNavy,
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                                maxLines = 1
                            )
                        }
                    }
                }
                if (message.content.isNotEmpty() || editing) Spacer(Modifier.height(4.dp))
            }

            if (editing) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = LichiVisualTokens.SurfaceWhite,
                    shadowElevation = 2.dp,
                    modifier = Modifier.padding(2.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        androidx.compose.foundation.text.BasicTextField(
                            value = editingDraft,
                            onValueChange = onEditingDraftChange,
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                color = LichiVisualTokens.TextNavy,
                                fontSize = 15.sp,
                                lineHeight = 21.sp
                            ),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(LichiVisualTokens.BrandPurple)
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(
                            horizontalArrangement = Arrangement.End,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            androidx.compose.material3.TextButton(onClick = onCancelEdit) {
                                Text(stringResource(R.string.cancel), color = LichiVisualTokens.TextGray)
                            }
                            Spacer(Modifier.width(4.dp))
                            androidx.compose.material3.TextButton(onClick = onCommitEdit) {
                                Text(stringResource(R.string.save), color = LichiVisualTokens.BrandPurple, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            } else {
                Box {
                    // Right-aligned compact bubble hugging the content
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color(0xFFEBE6FB))
                            .combinedClickable(
                                onClick = {},
                                onLongClick = { menuOpen = true }
                            )
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        SelectionContainer {
                            Text(
                                text = message.content,
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontSize = 15.sp,
                                    lineHeight = 21.sp,
                                    fontWeight = FontWeight.Normal
                                ),
                                color = LichiVisualTokens.TextNavy
                            )
                        }
                    }

                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.copy)) },
                            leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                            onClick = {
                                menuOpen = false
                                clipboard.setText(AnnotatedString(message.content))
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.edit)) },
                            leadingIcon = { Icon(Icons.Default.Edit, null) },
                            onClick = {
                                menuOpen = false
                                onStartEdit()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.regenerate)) },
                            leadingIcon = { Icon(Icons.Default.Refresh, null) },
                            onClick = {
                                menuOpen = false
                                onRegenerateFrom()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
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

/**
 * Clean Assistant Response Row:
 * Rendered directly on the background without large white cards.
 */
@Composable
private fun LichiAssistantRow(
    message: Message,
    senderLabel: String,
    isLastAssistant: Boolean,
    isStreaming: Boolean,
    webActivityState: WebActivityState,
    agentLiveStatus: AgentLiveStatus,
    liveActivityState: AssistantActivityState?,
    isSpeaking: Boolean = false,
    onToggleSpeak: () -> Unit = {},
    onDelete: () -> Unit,
    onRegenerate: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    var isLiked by remember { mutableStateOf<Boolean?>(null) }
    var menuOpen by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(2000)
            copied = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
    ) {
        // 1. Assistant Metadata Header (Small and lightweight)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(LichiVisualTokens.BrandPurpleSoftBg),
                contentAlignment = Alignment.Center
            ) {
                LichiSparkleLogo(modifier = Modifier.size(13.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = "LICHI–AI",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp,
                    fontSize = 12.sp
                ),
                color = LichiVisualTokens.BrandPurple
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "•  $senderLabel",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Normal,
                    fontSize = 11.5.sp
                ),
                color = LichiVisualTokens.TextGraySubtle
            )
        }

        // 2. Activity Indicators (Live Task / Stored Task / Web / Thinking)
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
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        // 3. Response Content: Plain text directly on the screen background (NO white card container)
        val spyProfile = remember(message.content) {
            SpyProfileSerializer.extractProfile(message.content)
        }
        val cleanMarkdown = remember(message.content) {
            SpyProfileSerializer.stripEmbeddedProfile(message.content)
        }

        if (message.content.isEmpty() && isStreaming && isLastAssistant) {
            LichiTypingPulse()
        } else if (message.content.isNotEmpty()) {
            if (spyProfile != null) {
                if (spyProfile.previewRequested) {
                    ProfilePreviewCard(
                        profile = spyProfile,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                } else {
                    PlatformProfileCard(
                        profile = spyProfile,
                        onAnalyzeWebsite = { webUrl ->
                            // Analyze website action
                        }
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
            if (cleanMarkdown.isNotEmpty()) {
                SelectionContainer {
                    MarkdownText(
                        text = cleanMarkdown,
                        color = LichiVisualTokens.TextNavy,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        // 4. Assistant Action Row (Rendered directly under response when not streaming)
        if (message.content.isNotEmpty() && (!isStreaming || !isLastAssistant)) {
            Spacer(Modifier.height(10.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(vertical = 2.dp)
            ) {
                // Copy Action
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(bounded = true, radius = 15.dp)
                        ) {
                            clipboard.setText(AnnotatedString(message.content))
                            copied = true
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                        contentDescription = "Copy message",
                        tint = if (copied) LichiVisualTokens.StatusGreen else LichiVisualTokens.TextGray,
                        modifier = Modifier.size(16.dp)
                    )
                }

                // Thumbs Up / Reaction Action
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(bounded = true, radius = 15.dp)
                        ) {
                            isLiked = if (isLiked == true) null else true
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isLiked == true) Icons.Default.ThumbUp else Icons.Outlined.ThumbUp,
                        contentDescription = "Like response",
                        tint = if (isLiked == true) LichiVisualTokens.BrandPurple else LichiVisualTokens.TextGray,
                        modifier = Modifier.size(16.dp)
                    )
                }

                // Voice / Read Aloud Action
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(bounded = true, radius = 15.dp)
                        ) {
                            onToggleSpeak()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.VolumeUp,
                        contentDescription = if (isSpeaking) "Stop reading" else "Read aloud",
                        tint = if (isSpeaking) LichiVisualTokens.BrandPurple else LichiVisualTokens.TextGray,
                        modifier = Modifier.size(17.dp)
                    )
                }

                // Share Action
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(bounded = true, radius = 15.dp)
                        ) {
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
                        contentDescription = "Share response",
                        tint = LichiVisualTokens.TextGray,
                        modifier = Modifier.size(16.dp)
                    )
                }

                // More Options Action
                Box {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = ripple(bounded = true, radius = 15.dp)
                            ) {
                                menuOpen = true
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "More actions",
                            tint = LichiVisualTokens.TextGray,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.regenerate)) },
                            leadingIcon = { Icon(Icons.Default.Refresh, null, tint = LichiVisualTokens.BrandPurple) },
                            onClick = {
                                menuOpen = false
                                onRegenerate()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
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
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(
                        LichiVisualTokens.BrandPurple.copy(
                            alpha = if (i == 0) alpha else if (i == 1) (1f - alpha) else alpha * 0.7f + 0.3f
                        )
                    )
            )
        }
    }
}
