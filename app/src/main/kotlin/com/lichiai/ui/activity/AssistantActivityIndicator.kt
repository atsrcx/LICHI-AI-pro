package com.lichiai.ui.activity

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
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Terminal
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
import com.lichiai.web.ui.WebImagesGrid
import com.lichiai.web.ui.WebSourcesList

/**
 * Unified ChatGPT-style Assistant Activity Indicator.
 *
 * Renders ABOVE the assistant response content, inside the same assistant message area.
 * Handles Thinking, Web Search, Reading Sources, Researching, Opening Browser,
 * Agent Working, Interacting with Screen, Verifying, and Completed states.
 */
@Composable
fun AssistantActivityIndicator(
    activity: AssistantActivityState,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false
) {
    if (activity.kind == ActivityKind.IDLE) return
    val hasSources = activity.sources.isNotEmpty()
    val hasImages = activity.images.isNotEmpty()
    val hasHistory = activity.stepHistory.isNotEmpty()
    val hasDetails = hasSources || hasImages || activity.detailNotes.isNotEmpty() || activity.disagreementNotice != null || hasHistory

    // If it was just simple thinking and is no longer active and has no details, do not render
    if (!activity.isActive && activity.kind == ActivityKind.THINKING && !hasDetails) return

    var isExpanded by remember { mutableStateOf(initiallyExpanded) }
    val isRunning = activity.isActive

    val infiniteTransition = rememberInfiniteTransition(label = "activity_anim")
    val rotationAnim by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "indicator_rotation"
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(
                width = 1.dp,
                color = when {
                    activity.kind == ActivityKind.FAILED -> MaterialTheme.colorScheme.error.copy(alpha = 0.35f)
                    isRunning -> MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                    isExpanded -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                    else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                },
                shape = RoundedCornerShape(14.dp)
            ),
        color = when {
            activity.kind == ActivityKind.FAILED -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)
            isRunning -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.18f)
            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)
        },
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 9.dp)
        ) {
            // Main Activity Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = hasDetails && !isRunning) {
                        isExpanded = !isExpanded
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Leading Icon Surface
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                isRunning -> MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                                activity.kind == ActivityKind.FAILED -> MaterialTheme.colorScheme.errorContainer
                                else -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        isRunning && (activity.kind == ActivityKind.SEARCHING_WEB || activity.kind == ActivityKind.OPENING_BROWSER) -> {
                            Icon(
                                imageVector = Icons.Default.Language,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .size(15.dp)
                                    .rotate(rotationAnim)
                            )
                        }
                        isRunning && (activity.kind == ActivityKind.AGENT_WORKING || activity.kind == ActivityKind.INTERACTING_SCREEN) -> {
                            Icon(
                                imageVector = Icons.Default.SmartToy,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                        isRunning && activity.kind == ActivityKind.TERMINAL_EXECUTING -> {
                            Icon(
                                imageVector = Icons.Default.Terminal,
                                contentDescription = "Terminal Task",
                                tint = Color(0xFF00E676),
                                modifier = Modifier.size(15.dp)
                            )
                        }
                        activity.kind == ActivityKind.FAILED -> {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                        activity.kind == ActivityKind.COMPLETED || (!isRunning && hasSources) -> {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                        else -> {
                            Text(
                                text = "✦",
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }
                    }
                }

                Spacer(Modifier.width(10.dp))

                // Title + Subtitle Column
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    AnimatedContent(
                        targetState = activity.title.ifBlank { "Thinking..." },
                        transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(180)) },
                        label = "activity_title_anim"
                    ) { targetTitle ->
                        Text(
                            text = targetTitle,
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp
                            ),
                            color = when {
                                activity.kind == ActivityKind.FAILED -> MaterialTheme.colorScheme.error
                                isRunning -> MaterialTheme.colorScheme.onSurface
                                else -> MaterialTheme.colorScheme.onSurface
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    if (activity.subtitle.isNotBlank()) {
                        AnimatedContent(
                            targetState = activity.subtitle,
                            transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(180)) },
                            label = "activity_subtitle_anim"
                        ) { targetSub ->
                            Text(
                                text = targetSub,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontSize = 11.5.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                // Trailing State Indicator
                if (isRunning) {
                    Box(
                        modifier = Modifier.padding(start = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                } else if (hasDetails) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (isExpanded) "Collapse" else "Expand details",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        modifier = Modifier
                            .padding(start = 6.dp)
                            .size(18.dp)
                    )
                }
            }

            // Expandable details (Sources, Images, Queries, Disagreement)
            AnimatedVisibility(
                visible = isExpanded && hasDetails,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                ) {
                    if (hasHistory) {
                        Text(
                            text = "Execution Steps (${activity.stepHistory.size})",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 11.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            activity.stepHistory.forEach { step ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(16.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (step.isFailed) MaterialTheme.colorScheme.errorContainer
                                                else MaterialTheme.colorScheme.primaryContainer
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (step.isFailed) {
                                            Icon(
                                                Icons.Default.Warning,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.size(10.dp)
                                            )
                                        } else {
                                            Icon(
                                                Icons.Default.CheckCircle,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(11.dp)
                                            )
                                        }
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = step.title,
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontSize = 11.5.sp,
                                                fontWeight = FontWeight.Medium
                                            ),
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        if (step.detail.isNotBlank()) {
                                            Text(
                                                text = step.detail,
                                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        if (hasSources || hasImages || activity.detailNotes.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                        }
                    }

                    if (activity.detailNotes.isNotEmpty()) {
                        Text(
                            text = "Search Query",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 11.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 2.dp)
                        )
                        activity.detailNotes.take(2).forEach { note ->
                            Text(
                                text = "• \"$note\"",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                modifier = Modifier.padding(bottom = 2.dp)
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                    }

                    if (activity.disagreementNotice != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f))
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = activity.disagreementNotice,
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                    }

                    if (hasImages) {
                        WebImagesGrid(images = activity.images)
                        if (hasSources) Spacer(Modifier.height(8.dp))
                    }

                    if (hasSources) {
                        Text(
                            text = "Sources (${activity.sources.size})",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 11.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                        WebSourcesList(sources = activity.sources)
                    }
                }
            }
        }
    }
}
