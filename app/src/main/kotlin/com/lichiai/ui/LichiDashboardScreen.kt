package com.lichiai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lichiai.data.Conversation
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * SCREEN 1: DASHBOARD & CHAT HUB
 * Pixel-perfect implementation matching user's HTML prototype specification.
 */
@Composable
fun LichiDashboardScreen(
    conversations: List<Conversation>,
    activeId: String?,
    onSelectConversation: (String) -> Unit,
    onNewChat: () -> Unit,
    onClearAll: () -> Unit,
    onOpenVoice: () -> Unit,
    onOpenCalls: () -> Unit,
    onOpenBrowser: () -> Unit,
    onOpenReminders: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenSettings: () -> Unit,
    onHomeClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }
    val isDark = MaterialTheme.colorScheme.background.red < 0.2f

    val filteredConversations = remember(conversations, searchQuery) {
        if (searchQuery.isBlank()) {
            conversations
        } else {
            conversations.filter { conv ->
                conv.title.contains(searchQuery, ignoreCase = true) ||
                    conv.messages.any { it.content.contains(searchQuery, ignoreCase = true) }
            }
        }
    }

    val screenBg = if (isDark) Color(0xFF0F172A) else Color(0xFFF4F6FA)
    val cardBg = if (isDark) Color(0xFF1E293B) else Color(0xFFFFFFFF)
    val textPrimary = if (isDark) Color(0xFFF8FAFC) else Color(0xFF1E293B)
    val textMuted = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(screenBg)
            .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding())
            .imePadding()
    ) {
        // 1. Top Dashboard Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Sparkle Squircle Avatar
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    Color(0xFF4A3B77),
                                    Color(0xFF3A2C68),
                                    Color(0xFF251A4A)
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    LichiSparkleLogo(
                        modifier = Modifier.size(20.dp),
                        primaryColor = Color(0xFFC7D2FE),
                        accentColor = Color(0xFFE0E7FF)
                    )
                }

                Column {
                    Text(
                        text = "LICHI-AI",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = textPrimary,
                        letterSpacing = (-0.2).sp
                    )
                    Text(
                        text = "AI Companion",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = textMuted
                    )
                }
            }

            // + New chat Button
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = if (isDark) Color(0xFF1E3A8A) else Color(0xFFE4EAFC),
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(bounded = true),
                        onClick = onNewChat
                    )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "New Chat",
                        tint = if (isDark) Color(0xFF93C5FD) else Color(0xFF344EB0),
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "New chat",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isDark) Color(0xFF93C5FD) else Color(0xFF344EB0)
                    )
                }
            }
        }

        // 2. Scrollable Dashboard Body
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Quick Action Circle Buttons (6 tools - Ultra Compact)
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = if (isDark) Color(0xFF1E293B).copy(alpha = 0.8f) else Color(0xFFFFFFFF).copy(alpha = 0.85f),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (isDark) Color(0xFF334155).copy(alpha = 0.6f) else Color(0xFFE2E8F0).copy(alpha = 0.7f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Tool 1: Voice
                    DashboardToolItem(
                        icon = {
                            Icon(Icons.Default.Mic, null, tint = Color(0xFF4F46E5), modifier = Modifier.size(14.dp))
                        },
                        label = "Voice",
                        bgColor = if (isDark) Color(0xFF312E81) else Color(0xFFEEF2FF),
                        borderColor = if (isDark) Color(0xFF3730A3) else Color(0xFFE0E7FF),
                        onClick = onOpenVoice
                    )

                    // Tool 2: Calls
                    DashboardToolItem(
                        icon = {
                            Icon(Icons.Default.Call, null, tint = Color(0xFF059669), modifier = Modifier.size(13.dp))
                        },
                        label = "Calls",
                        bgColor = if (isDark) Color(0xFF064E3B) else Color(0xFFECFDF5),
                        borderColor = if (isDark) Color(0xFF065F46) else Color(0xFFD1FAE5),
                        onClick = onOpenCalls
                    )

                    // Tool 3: Browser
                    DashboardToolItem(
                        icon = {
                            Icon(Icons.Default.Public, null, tint = Color(0xFF0284C7), modifier = Modifier.size(14.dp))
                        },
                        label = "Browser",
                        bgColor = if (isDark) Color(0xFF0C4A6E) else Color(0xFFF0F9FF),
                        borderColor = if (isDark) Color(0xFF075985) else Color(0xFFE0F2FE),
                        onClick = onOpenBrowser
                    )

                    // Tool 4: Alerts
                    DashboardToolItem(
                        icon = {
                            Icon(Icons.Default.Notifications, null, tint = Color(0xFFD97706), modifier = Modifier.size(14.dp))
                        },
                        label = "Alerts",
                        bgColor = if (isDark) Color(0xFF78350F) else Color(0xFFFFFBEB),
                        borderColor = if (isDark) Color(0xFF92400E) else Color(0xFFFEF3C7),
                        onClick = onOpenReminders
                    )

                    // Tool 5: Terminal
                    DashboardToolItem(
                        icon = {
                            Text(
                                text = ">_",
                                color = Color(0xFF34D399),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        },
                        label = "Terminal",
                        bgColor = Color(0xFF0F172A),
                        borderColor = Color(0xFF334155),
                        onClick = onOpenTerminal
                    )

                    // Tool 6: Settings
                    DashboardToolItem(
                        icon = {
                            Icon(Icons.Default.Settings, null, tint = if (isDark) Color(0xFFCBD5E1) else Color(0xFF475569), modifier = Modifier.size(14.dp))
                        },
                        label = "Settings",
                        bgColor = if (isDark) Color(0xFF334155) else Color(0xFFF1F5F9),
                        borderColor = if (isDark) Color(0xFF475569) else Color(0xFFE2E8F0),
                        onClick = onOpenSettings
                    )
                }
            }

            // Search chats bar
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = if (isDark) Color(0xFF1E293B) else Color(0xFFEDF1F8),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (isDark) Color(0xFF334155).copy(alpha = 0.6f) else Color(0xFFE2E8F0).copy(alpha = 0.6f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search",
                        tint = Color(0xFF94A3B8),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Box(modifier = Modifier.weight(1f)) {
                        if (searchQuery.isEmpty()) {
                            Text(
                                text = "Search chats",
                                fontSize = 12.sp,
                                color = Color(0xFF94A3B8)
                            )
                        }
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            textStyle = androidx.compose.ui.text.TextStyle(
                                fontSize = 12.sp,
                                color = textPrimary
                            ),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                }
            }

            // Section Title: Recent Conversations
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Recent Conversations",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isDark) Color(0xFF93C5FD) else Color(0xFF486396)
                )
                if (conversations.isNotEmpty()) {
                    Text(
                        text = "Clear all",
                        fontSize = 10.sp,
                        color = Color(0xFF94A3B8),
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable(onClick = onClearAll)
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }

            // Chats List
            if (filteredConversations.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(if (isDark) Color(0xFF1E293B) else Color(0xFFF1F5F9)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Chat,
                                contentDescription = null,
                                tint = Color(0xFF94A3B8),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "All clean",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = textPrimary
                        )
                        Text(
                            text = "No recent chats. Start a new conversation anytime.",
                            fontSize = 11.sp,
                            color = Color(0xFF94A3B8)
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredConversations, key = { it.id }) { chat ->
                        val lastMessage = chat.messages.lastOrNull()?.content ?: "Empty conversation"
                        val timeStr = remember(chat.updatedAt) {
                            SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(chat.updatedAt))
                        }

                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = cardBg,
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (isDark) Color(0xFF334155).copy(alpha = 0.5f) else Color(0xFFE2E8F0).copy(alpha = 0.6f)
                            ),
                            shadowElevation = 0.5.dp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .clickable { onSelectConversation(chat.id) }
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (isDark) Color(0xFF334155) else Color(0xFFF1F5F9)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.Chat,
                                        contentDescription = null,
                                        tint = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = chat.title.ifBlank { "Conversation" },
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = textPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = lastMessage,
                                        fontSize = 11.sp,
                                        color = Color(0xFF94A3B8),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        text = timeStr,
                                        fontSize = 10.sp,
                                        color = Color(0xFF94A3B8)
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Box(
                                        modifier = Modifier
                                            .clip(CircleShape)
                                            .background(if (isDark) Color(0xFF334155) else Color(0xFFF1F5F9))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = "${chat.messages.size}",
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isDark) Color(0xFFCBD5E1) else Color(0xFF64748B)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3. Android Home Navigation Indicator Bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(width = 128.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF94A3B8).copy(alpha = 0.8f))
                    .clickable(onClick = onHomeClick)
            )
        }
    }
}

@Composable
private fun DashboardToolItem(
    icon: @Composable () -> Unit,
    label: String,
    bgColor: Color,
    borderColor: Color,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(2.dp)
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(bgColor)
                .border(1.dp, borderColor, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            icon()
        }
        Spacer(Modifier.height(3.dp))
        Text(
            text = label,
            fontSize = 9.sp,
            fontWeight = FontWeight.Medium,
            color = Color(0xFF475569)
        )
    }
}
