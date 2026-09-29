package com.lichiai.browser.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

enum class IntelligenceMode(
    val title: String,
    val iconEmoji: String,
    val badgeLabel: String,
    val helperText: String
) {
    ASK_LICHI(
        title = "Ask Lichi",
        iconEmoji = "✨",
        badgeLabel = "Ask Lichi Active",
        helperText = "Direct conversational answers & reasoning from Lichi AI"
    ),
    RESEARCH(
        title = "Research",
        iconEmoji = "📚",
        badgeLabel = "Research Active",
        helperText = "Deep multi-source web synthesis & autonomous agent research"
    ),
    WEB_SEARCH(
        title = "Web Search",
        iconEmoji = "🔍",
        badgeLabel = "Web Search Active",
        helperText = "Fast direct web crawling & unfiltered search results"
    )
}

data class QuickAccessItem(
    val id: String,
    val name: String,
    val url: String,
    val icon: ImageVector? = null,
    val customEmoji: String? = null,
    val iconTint: Color = Color.Black,
    val bgTint: Color = Color(0xFFF1F5F9),
    val isAddAction: Boolean = false
)

@Composable
fun BrowserModernStartPage(
    onSearch: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    onOpenCopilot: () -> Unit,
    onOpenResearch: () -> Unit,
    modifier: Modifier = Modifier
) {
    var queryText by remember { mutableStateOf("") }
    var selectedMode by remember { mutableStateOf(IntelligenceMode.ASK_LICHI) }
    var showAddDialog by remember { mutableStateOf(false) }

    val quickItems = remember {
        mutableStateListOf(
            QuickAccessItem("google", "Google", "https://www.google.com", icon = Icons.Default.Language, iconTint = Color(0xFF2563EB), bgTint = Color(0xFFEFF6FF)),
            QuickAccessItem("youtube", "YouTube", "https://www.youtube.com", icon = Icons.Default.PlayArrow, iconTint = Color(0xFFEF4444), bgTint = Color(0xFFFEF2F2)),
            QuickAccessItem("wikipedia", "Wikipedia", "https://www.wikipedia.org", icon = Icons.Default.MenuBook, iconTint = Color(0xFF334155), bgTint = Color(0xFFF1F5F9)),
            QuickAccessItem("github", "GitHub", "https://www.github.com", icon = Icons.Default.Code, iconTint = Color.White, bgTint = Color(0xFF0F172A)),
            QuickAccessItem("reddit", "Reddit", "https://www.reddit.com", icon = Icons.Default.ChatBubble, iconTint = Color(0xFFF97316), bgTint = Color(0xFFFFF7ED)),
            QuickAccessItem("twitter", "Twitter", "https://twitter.com", icon = Icons.Default.Close, iconTint = Color(0xFF0F172A), bgTint = Color(0xFFF1F5F9)),
            QuickAccessItem("chatgpt", "ChatGPT", "https://chatgpt.com", icon = Icons.Default.SmartToy, iconTint = Color(0xFF10B981), bgTint = Color(0xFFECFDF5)),
            QuickAccessItem("add", "Add", "", icon = Icons.Default.Add, iconTint = Color(0xFF94A3B8), bgTint = Color.Transparent, isAddAction = true)
        )
    }

    val suggestionChips = listOf(
        "✨ Latest AI News" to "Latest AI news breakthroughs 2026",
        "📱 S25 vs iPhone 16" to "Galaxy S25 Ultra vs iPhone 16 Pro Max comparison",
        "✈️ Flights to Dubai" to "Flights to Dubai cheap deals",
        "🧠 Quantum Computing" to "Quantum computing progress and roadmap"
    )

    // Infinite breathing animations
    val infiniteTransition = rememberInfiniteTransition(label = "StartPageAnimations")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "PulseAlpha"
    )
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "PulseScale"
    )

    val heroScale by infiniteTransition.animateFloat(
        initialValue = 0.98f,
        targetValue = 1.02f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "HeroScale"
    )

    val heroFloatY by infiniteTransition.animateFloat(
        initialValue = -3f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "HeroFloatY"
    )

    val heroRotation by infiniteTransition.animateFloat(
        initialValue = -4f,
        targetValue = 4f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "HeroRotation"
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFFFAFBFC))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Spacer(Modifier.height(14.dp))

        // 1. Center Hero Avatar Icon
        Box(
            modifier = Modifier
                .graphicsLayer {
                    translationY = heroFloatY
                    scaleX = heroScale
                    scaleY = heroScale
                }
                .size(82.dp)
                .clip(CircleShape)
                .background(Color(0xFFEEF2FF)),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFE0E7FF)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = "Lichi Sparkle",
                    tint = Color(0xFF4338CA),
                    modifier = Modifier
                        .size(32.dp)
                        .graphicsLayer { rotationZ = heroRotation }
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        // 2. Title & Subtitle
        Text(
            text = "Lichi Browser",
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.ExtraBold,
                fontSize = 24.sp,
                letterSpacing = (-0.5).sp,
                color = Color(0xFF0F172A)
            )
        )

        Spacer(Modifier.height(4.dp))

        Text(
            text = "Autonomous AI-Native Web Intelligence",
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 13.sp,
                fontWeight = FontWeight.Normal,
                color = Color(0xFF64748B)
            )
        )

        Spacer(Modifier.height(20.dp))

        // 3. Floating AI Omnibox Input Card
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(
                    elevation = 6.dp,
                    shape = RoundedCornerShape(26.dp),
                    spotColor = Color(0x1A0F172A),
                    ambientColor = Color(0x0A0F172A)
                ),
            shape = RoundedCornerShape(26.dp),
            color = Color.White,
            border = BorderStroke(1.dp, Color(0xFFE2E8F0))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left badge: Blue capsule icon with sparkle + "LICHI" + divider
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 2.dp, end = 8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF2563EB)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Spacer(Modifier.width(8.dp))

                    Text(
                        text = "LICHI",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Black,
                            fontSize = 13.sp,
                            letterSpacing = 0.5.sp,
                            color = Color(0xFF1E293B)
                        )
                    )

                    Spacer(Modifier.width(10.dp))

                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(22.dp)
                            .background(Color(0xFFE2E8F0))
                    )
                }

                // Text Input
                BasicTextField(
                    value = queryText,
                    onValueChange = { queryText = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = Color(0xFF0F172A),
                        fontSize = 14.sp
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = {
                            if (queryText.isNotBlank()) {
                                onSearch(queryText.trim())
                            }
                        }
                    ),
                    modifier = Modifier.weight(1f),
                    decorationBox = { innerTextField ->
                        if (queryText.isEmpty()) {
                            Text(
                                text = "Ask Lichi or enter URL...",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    color = Color(0xFF94A3B8),
                                    fontSize = 14.sp
                                )
                            )
                        }
                        innerTextField()
                    }
                )

                Spacer(Modifier.width(6.dp))

                // Voice Icon
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .bounceClick(scaleDown = 0.88f) { onOpenCopilot() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Mic,
                        contentDescription = "Voice Input",
                        tint = Color(0xFF64748B),
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(Modifier.width(6.dp))

                // Action Circle Button (Dark with arrow)
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF0F172A))
                        .bounceClick(scaleDown = 0.88f) {
                            if (queryText.isNotBlank()) {
                                onSearch(queryText.trim())
                            } else {
                                onOpenCopilot()
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "Submit",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // Status Row: "● Neural Engine Ready" and "v2.4 AI Native"
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .scale(pulseScale)
                        .clip(CircleShape)
                        .background(Color(0xFF22C55E).copy(alpha = pulseAlpha))
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "Neural Engine Ready",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF64748B)
                    )
                )
            }

            Text(
                text = "v2.4 AI Native",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Normal,
                    color = Color(0xFF94A3B8)
                )
            )
        }

        Spacer(Modifier.height(18.dp))

        // 4. Horizontal Trending / Suggestion Chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            suggestionChips.forEach { (label, searchPrompt) ->
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color.White,
                    border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                    modifier = Modifier
                        .bounceClick {
                            queryText = searchPrompt
                            onSearch(searchPrompt)
                        }
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF334155)
                        ),
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        // 5. INTELLIGENCE MODE CARD
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            color = Color.White,
            border = BorderStroke(1.dp, Color(0xFFE2E8F0))
        ) {
            Column(
                modifier = Modifier.padding(14.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF3B82F6))
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "INTELLIGENCE MODE",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.5.sp,
                                letterSpacing = 1.sp,
                                color = Color(0xFF334155)
                            )
                        )
                    }

                    // Active badge pill
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = Color(0xFFEFF6FF),
                        border = BorderStroke(1.dp, Color(0xFFBFDBFE))
                    ) {
                        Text(
                            text = selectedMode.badgeLabel,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF2563EB)
                            ),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.5.dp)
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                // 3 Segmented Mode Cards in a Row
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFFF1F5F9),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        IntelligenceMode.values().forEach { mode ->
                            val isSelected = selectedMode == mode
                            val animElevation by animateFloatAsState(
                                targetValue = if (isSelected) 3f else 0f,
                                animationSpec = spring(),
                                label = "Elevation_${mode.name}"
                            )

                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (isSelected) Color.White else Color.Transparent,
                                shadowElevation = animElevation.dp,
                                border = if (isSelected) BorderStroke(1.dp, Color(0xFFE2E8F0)) else null,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {
                                        selectedMode = mode
                                        if (mode == IntelligenceMode.RESEARCH) {
                                            onOpenResearch()
                                        }
                                    }
                            ) {
                                Column(
                                    modifier = Modifier.padding(vertical = 12.dp, horizontal = 4.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = mode.iconEmoji,
                                        fontSize = 18.sp
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = mode.title,
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontSize = 12.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            color = if (isSelected) Color(0xFF0F172A) else Color(0xFF64748B)
                                        )
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Helper banner at bottom of card
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color(0xFFF8FAFC),
                    border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        AnimatedContent(
                            targetState = selectedMode.helperText,
                            transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) },
                            modifier = Modifier.weight(1f),
                            label = "HelperText"
                        ) { helper ->
                            Text(
                                text = helper,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontSize = 12.sp,
                                    color = Color(0xFF475569)
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Spacer(Modifier.width(8.dp))

                        Icon(
                            Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = Color(0xFF3B82F6),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        // 6. QUICK ACCESS CARD
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            color = Color.White,
            border = BorderStroke(1.dp, Color(0xFFE2E8F0))
        ) {
            Column(
                modifier = Modifier.padding(14.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF22C55E))
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "QUICK ACCESS",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.5.sp,
                                letterSpacing = 1.sp,
                                color = Color(0xFF334155)
                            )
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(50),
                        color = Color(0xFFF8FAFC)
                    ) {
                        Text(
                            text = "Top Sites",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 11.sp,
                                color = Color(0xFF64748B),
                                fontWeight = FontWeight.Normal
                            ),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // 4x2 Grid of shortcuts
                val row1 = quickItems.take(4)
                val row2 = quickItems.drop(4).take(4)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    row1.forEach { item ->
                        QuickAccessTile(
                            item = item,
                            onClick = {
                                if (item.isAddAction) showAddDialog = true
                                else onOpenUrl(item.url)
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    row2.forEach { item ->
                        QuickAccessTile(
                            item = item,
                            onClick = {
                                if (item.isAddAction) showAddDialog = true
                                else onOpenUrl(item.url)
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(30.dp))
    }

    // Add Shortcut Dialog
    if (showAddDialog) {
        var newTitle by remember { mutableStateOf("") }
        var newUrl by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = {
                Text("Add Quick Access Shortcut", fontWeight = FontWeight.Bold)
            },
            text = {
                Column {
                    OutlinedTextField(
                        value = newTitle,
                        onValueChange = { newTitle = it },
                        label = { Text("Title") },
                        placeholder = { Text("e.g. My Favorite Site") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = newUrl,
                        onValueChange = { newUrl = it },
                        label = { Text("URL") },
                        placeholder = { Text("https://example.com") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = newTitle.isNotBlank() && newUrl.isNotBlank(),
                    onClick = {
                        val formattedUrl = if (!newUrl.startsWith("http://") && !newUrl.startsWith("https://")) {
                            "https://$newUrl"
                        } else newUrl

                        // Insert before the last item (Add button)
                        val insertIdx = (quickItems.size - 1).coerceAtLeast(0)
                        quickItems.add(
                            insertIdx,
                            QuickAccessItem(
                                id = "custom_${System.currentTimeMillis()}",
                                name = newTitle.trim(),
                                url = formattedUrl.trim(),
                                icon = Icons.Default.Language,
                                iconTint = Color(0xFF2563EB),
                                bgTint = Color(0xFFEFF6FF)
                            )
                        )
                        showAddDialog = false
                    }
                ) {
                    Text("Add", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun QuickAccessTile(
    item: QuickAccessItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .bounceClick(onClick = onClick)
            .padding(horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (item.isAddAction) {
            // Dashed outline circular button
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .drawBehind {
                        val stroke = Stroke(
                            width = 1.5.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f), 0f)
                        )
                        drawRoundRect(
                            color = Color(0xFFCBD5E1),
                            cornerRadius = CornerRadius(27.dp.toPx(), 27.dp.toPx()),
                            style = stroke
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    item.icon ?: Icons.Default.Add,
                    contentDescription = item.name,
                    tint = item.iconTint,
                    modifier = Modifier.size(24.dp)
                )
            }
        } else {
            // Solid rounded square with soft color
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(item.bgTint),
                contentAlignment = Alignment.Center
            ) {
                if (item.icon != null) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = item.name,
                        tint = item.iconTint,
                        modifier = Modifier.size(26.dp)
                    )
                } else if (item.customEmoji != null) {
                    Text(text = item.customEmoji, fontSize = 22.sp)
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        Text(
            text = item.name,
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF475569)
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * Interactive bounce click animation modifier that delivers tactile physical feedback on press.
 */
@Composable
fun Modifier.bounceClick(
    scaleDown: Float = 0.92f,
    onClick: () -> Unit
): Modifier {
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isPressed) scaleDown else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "BounceScale"
    )
    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .pointerInput(Unit) {
            detectTapGestures(
                onPress = {
                    isPressed = true
                    tryAwaitRelease()
                    isPressed = false
                },
                onTap = { onClick() }
            )
        }
}
