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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lichiai.web.ui.WebImagesGrid
import com.lichiai.web.ui.WebSourcesList

enum class TaskActivityStatus {
    WORKING,
    SEARCHING,
    EXECUTING,
    COMPLETED,
    FAILED,
    PAUSED
}

fun ActivityKind.toTaskStatus(): TaskActivityStatus {
    return when (this) {
        ActivityKind.SEARCHING_WEB, ActivityKind.READING_SOURCES, ActivityKind.RESEARCHING, ActivityKind.INSPECTING_PAGE -> TaskActivityStatus.SEARCHING
        ActivityKind.AGENT_WORKING, ActivityKind.INTERACTING_SCREEN -> TaskActivityStatus.WORKING
        ActivityKind.TERMINAL_EXECUTING, ActivityKind.OPENING_BROWSER, ActivityKind.VERIFYING -> TaskActivityStatus.EXECUTING
        ActivityKind.COMPLETED -> TaskActivityStatus.COMPLETED
        ActivityKind.FAILED -> TaskActivityStatus.FAILED
        ActivityKind.IDLE, ActivityKind.THINKING -> TaskActivityStatus.WORKING
    }
}

/**
 * Ultra-Compact Inline Task Status Indicator for LICHI-AI.
 * 
 * Replaces large bounding boxes/cards with a clean, tiny metadata line:
 * e.g., "✓ Completed · 1 step" or "● Running · Browser"
 * Positioned tightly between the assistant header and response text.
 */
@Composable
fun TaskActivityChip(
    activity: AssistantActivityState,
    modifier: Modifier = Modifier,
    conversationId: String = "",
    messageId: String = "",
    initiallyExpanded: Boolean = false
) {
    if (activity.kind == ActivityKind.IDLE) return
    val hasSources = activity.sources.isNotEmpty()
    val hasImages = activity.images.isNotEmpty()
    val hasHistory = activity.stepHistory.isNotEmpty()
    val hasDetails = hasSources || hasImages || activity.detailNotes.isNotEmpty() || activity.disagreementNotice != null || hasHistory

    // If simple thinking and inactive without details, collapse completely
    if (!activity.isActive && activity.kind == ActivityKind.THINKING && !hasDetails) return

    var isExpanded by remember(conversationId, messageId, activity.requestId) {
        mutableStateOf(initiallyExpanded)
    }

    val isRunning = activity.isActive
    val status = if (activity.kind == ActivityKind.FAILED) {
        TaskActivityStatus.FAILED
    } else if (isRunning) {
        activity.kind.toTaskStatus()
    } else {
        TaskActivityStatus.COMPLETED
    }

    val infiniteTransition = rememberInfiniteTransition(label = "task_chip_anim")
    val rotationAnim by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "chip_rotation"
    )

    Column(
        modifier = modifier
            .wrapContentWidth(Alignment.Start)
            .testTag("task_activity_chip_${messageId}_${activity.requestId}")
            .padding(vertical = 2.dp)
    ) {
        // Ultra-Compact Inline Metadata Status Line
        Row(
            modifier = Modifier
                .wrapContentWidth(Alignment.Start)
                .clip(RoundedCornerShape(6.dp))
                .clickable(
                    enabled = hasDetails || isRunning,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(bounded = true, radius = 12.dp)
                ) {
                    isExpanded = !isExpanded
                }
                .padding(vertical = 2.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Tiny status glyph
            when {
                isRunning -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(11.dp),
                        strokeWidth = 1.6.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                status == TaskActivityStatus.COMPLETED -> {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Completed",
                        tint = Color(0xFF10B981),
                        modifier = Modifier.size(12.5.dp)
                    )
                }
                status == TaskActivityStatus.FAILED -> {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Failed",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(12.5.dp)
                    )
                }
                status == TaskActivityStatus.PAUSED -> {
                    Icon(
                        imageVector = Icons.Default.Pause,
                        contentDescription = "Paused",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(12.dp)
                    )
                }
                else -> {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
            }

            Spacer(Modifier.width(5.dp))

            // Concise Label: "Completed · 1 step"
            val stepCount = activity.stepHistory.size
            val stepLabel = if (stepCount > 0) " · $stepCount step${if (stepCount > 1) "s" else ""}" else ""
            val defaultTitle = when (status) {
                TaskActivityStatus.SEARCHING -> "Searching web"
                TaskActivityStatus.EXECUTING -> if (activity.kind == ActivityKind.TERMINAL_EXECUTING) "Terminal" else "Executing"
                TaskActivityStatus.COMPLETED -> "Completed$stepLabel"
                TaskActivityStatus.FAILED -> "Failed$stepLabel"
                else -> "Thinking"
            }
            val titleText = activity.title.ifBlank { defaultTitle }

            Text(
                text = titleText,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium
                ),
                color = when (status) {
                    TaskActivityStatus.FAILED -> MaterialTheme.colorScheme.error
                    TaskActivityStatus.COMPLETED -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
                    else -> MaterialTheme.colorScheme.primary
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (activity.subtitle.isNotBlank() && status != TaskActivityStatus.COMPLETED) {
                Text(
                    text = " · ${activity.subtitle}",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Tiny Chevron if details exist
            if (hasDetails && !isRunning) {
                Icon(
                    imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (isExpanded) "Collapse" else "Expand details",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier
                        .padding(start = 2.dp)
                        .size(13.dp)
                )
            }
        }

        // Expandable Step Execution Details (Tucked in smoothly when expanded)
        AnimatedVisibility(
            visible = isExpanded && hasDetails,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = 4.dp)
            ) {
                if (hasHistory) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        activity.stepHistory.forEach { step ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (step.isFailed) {
                                    Icon(
                                        imageVector = Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(11.dp)
                                    )
                                } else if (!step.isCompleted) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(10.dp),
                                        strokeWidth = 1.5.dp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = Color(0xFF10B981),
                                        modifier = Modifier.size(11.dp)
                                    )
                                }
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = step.title,
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                    if (hasSources || hasImages || activity.detailNotes.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                    }
                }

                if (activity.detailNotes.isNotEmpty()) {
                    activity.detailNotes.take(2).forEach { note ->
                        Text(
                            text = "• \"$note\"",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.5.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                            modifier = Modifier.padding(bottom = 2.dp)
                        )
                    }
                }

                if (activity.disagreementNotice != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f))
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = activity.disagreementNotice,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.5.sp),
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }

                if (hasImages) {
                    WebImagesGrid(images = activity.images)
                    if (hasSources) Spacer(Modifier.height(4.dp))
                }

                if (hasSources) {
                    Text(
                        text = "Sources (${activity.sources.size})",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 10.5.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                    WebSourcesList(sources = activity.sources)
                }
            }
        }
    }
}
