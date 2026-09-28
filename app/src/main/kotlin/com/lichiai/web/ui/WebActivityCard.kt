package com.lichiai.web.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lichiai.web.model.WebActivityState
import com.lichiai.web.model.WebActivityStatus

/**
 * Compact, ChatGPT-style Live Web Research Activity Component.
 * - Live State: Compact animated pill showing live search status ("Searching the web...", "Reading 4 sources...").
 * - Completed State: Sleek collapsed chip ("✓ Web research · 5 sources") with smooth one-tap expansion.
 * - Error State: Compact warning pill.
 */
@Composable
fun WebActivityCard(
    activityState: WebActivityState,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false
) {
    if (activityState.status == WebActivityStatus.IDLE) return

    var isExpanded by remember { mutableStateOf(initiallyExpanded) }
    val isRunning = activityState.isActive
    val hasSources = activityState.completedSources.isNotEmpty()
    val hasImages = activityState.images.isNotEmpty()

    // Smooth subtle rotation for active search icon
    val infiniteTransition = rememberInfiniteTransition(label = "web_search_anim")
    val rotationAnim by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "globe_rotation"
    )

    // Compute clean, compact status text
    val displayText = when {
        activityState.status == WebActivityStatus.FAILED ->
            activityState.error?.ifBlank { "Web search failed" } ?: "Web search failed"

        isRunning -> {
            when (activityState.status) {
                WebActivityStatus.STARTING, WebActivityStatus.BUILDING_QUERY ->
                    "Searching the web..."
                WebActivityStatus.SEARCHING -> {
                    if (activityState.query.isNotBlank()) "Searching \"${activityState.query.take(35)}\"..."
                    else "Searching the web..."
                }
                WebActivityStatus.READING_SOURCES -> {
                    if (activityState.activeDomains.isNotEmpty()) {
                        "Reading ${activityState.activeDomains.first()}..."
                    } else if (activityState.completedSources.isNotEmpty()) {
                        "Reading ${activityState.completedSources.size} sources..."
                    } else {
                        "Reading sources..."
                    }
                }
                WebActivityStatus.VERIFYING_FACTS -> "Verifying and cross-checking facts..."
                WebActivityStatus.ANALYZING, WebActivityStatus.GENERATING -> "Synthesizing answer..."
                else -> activityState.message.ifBlank { "Searching the web..." }
            }
        }

        activityState.verifiedBadge != null -> activityState.verifiedBadge
        hasSources -> "Web research · ${activityState.completedSources.size} sources"
        else -> "Web research completed"
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(
                width = 1.dp,
                color = when {
                    activityState.status == WebActivityStatus.FAILED -> MaterialTheme.colorScheme.error.copy(alpha = 0.3f)
                    isRunning -> MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                    isExpanded -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
                },
                shape = RoundedCornerShape(12.dp)
            ),
        color = when {
            activityState.status == WebActivityStatus.FAILED -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)
            isRunning -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        },
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 7.dp)
        ) {
            // Header Row: Compact Pill
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = (hasSources || hasImages || activityState.factEvidence != null) && !isRunning) {
                        isExpanded = !isExpanded
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Icon Indicator
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                isRunning -> MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                activityState.status == WebActivityStatus.FAILED -> MaterialTheme.colorScheme.errorContainer
                                else -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (isRunning) {
                        Icon(
                            imageVector = Icons.Default.Language,
                            contentDescription = "Searching",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .size(14.dp)
                                .rotate(rotationAnim)
                        )
                    } else if (activityState.status == WebActivityStatus.FAILED) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Failed",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(13.dp)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Complete",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }

                Spacer(Modifier.width(8.dp))

                // Animated status label
                AnimatedContent(
                    targetState = displayText,
                    transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) },
                    modifier = Modifier.weight(1f),
                    label = "status_text_anim"
                ) { targetText ->
                    Text(
                        text = targetText,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontSize = 12.5.sp,
                            fontWeight = if (isRunning) FontWeight.Medium else FontWeight.Normal
                        ),
                        color = when {
                            activityState.status == WebActivityStatus.FAILED -> MaterialTheme.colorScheme.error
                            isRunning -> MaterialTheme.colorScheme.primary
                            activityState.verifiedBadge != null -> Color(0xFF10B981)
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Active Spinner if Running
                if (isRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 1.5.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else if ((hasSources || hasImages || activityState.factEvidence != null)) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (isExpanded) "Collapse" else "Expand details",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            // Expandable Technical Details & Sources
            AnimatedVisibility(
                visible = isExpanded && (hasSources || hasImages || activityState.factEvidence != null),
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    if (activityState.strategyQueries.isNotEmpty()) {
                        Text(
                            text = "Search Query",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 2.dp)
                        )
                        activityState.strategyQueries.take(2).forEach { q ->
                            Text(
                                text = "• \"$q\"",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                modifier = Modifier.padding(bottom = 2.dp)
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                    }

                    if (activityState.factEvidence?.disagreementNotice != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f))
                                .padding(horizontal = 8.dp, vertical = 5.dp)
                        ) {
                            Text(
                                text = activityState.factEvidence.disagreementNotice,
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                    }

                    if (hasImages) {
                        WebImagesGrid(images = activityState.images)
                        if (hasSources) Spacer(Modifier.height(8.dp))
                    }

                    if (hasSources) {
                        Text(
                            text = "Sources (${activityState.completedSources.size})",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                        WebSourcesList(sources = activityState.completedSources)
                    }
                }
            }
        }
    }
}
