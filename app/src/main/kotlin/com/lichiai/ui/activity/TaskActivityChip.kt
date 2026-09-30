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
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PauseCircle
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
 * High-Density Compact Inline Task Activity Chip.
 *
 * Placed inline directly with the assistant message.
 * Strict per-message binding: conversationId + messageId + taskId.
 * Minimalist collapsed footprint with smooth expansion for full execution details.
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

    Surface(
        modifier = modifier
            .wrapContentWidth(Alignment.Start)
            .testTag("task_activity_chip_${messageId}_${activity.requestId}")
            .clip(RoundedCornerShape(12.dp))
            .border(
                width = 1.dp,
                color = when (status) {
                    TaskActivityStatus.FAILED -> MaterialTheme.colorScheme.error.copy(alpha = 0.4f)
                    TaskActivityStatus.PAUSED -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.4f)
                    TaskActivityStatus.COMPLETED -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                    else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                },
                shape = RoundedCornerShape(12.dp)
            ),
        color = when (status) {
            TaskActivityStatus.FAILED -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.22f)
            TaskActivityStatus.PAUSED -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.22f)
            TaskActivityStatus.COMPLETED -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
            else -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.18f)
        },
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier
                .wrapContentWidth(Alignment.Start)
                .padding(horizontal = 10.dp, vertical = 7.dp)
        ) {
            // Compact Header Row
            Row(
                modifier = Modifier
                    .wrapContentWidth(Alignment.Start)
                    .clickable(enabled = hasDetails || isRunning) {
                        isExpanded = !isExpanded
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Status Icon Box
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(
                            when (status) {
                                TaskActivityStatus.FAILED -> MaterialTheme.colorScheme.errorContainer
                                TaskActivityStatus.PAUSED -> MaterialTheme.colorScheme.tertiaryContainer
                                TaskActivityStatus.COMPLETED -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f)
                                else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    when (status) {
                        TaskActivityStatus.SEARCHING -> {
                            Icon(
                                imageVector = Icons.Default.Language,
                                contentDescription = "Searching",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .size(14.dp)
                                    .rotate(rotationAnim)
                            )
                        }
                        TaskActivityStatus.EXECUTING -> {
                            if (activity.kind == ActivityKind.TERMINAL_EXECUTING) {
                                Icon(
                                    imageVector = Icons.Default.Terminal,
                                    contentDescription = "Terminal",
                                    tint = Color(0xFF00E676),
                                    modifier = Modifier.size(13.dp)
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.SmartToy,
                                    contentDescription = "Executing",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                        }
                        TaskActivityStatus.WORKING -> {
                            Icon(
                                imageVector = Icons.Default.SmartToy,
                                contentDescription = "Working",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(13.dp)
                            )
                        }
                        TaskActivityStatus.FAILED -> {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "Failed",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(13.dp)
                            )
                        }
                        TaskActivityStatus.PAUSED -> {
                            Icon(
                                imageVector = Icons.Default.PauseCircle,
                                contentDescription = "Paused",
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(13.dp)
                            )
                        }
                        TaskActivityStatus.COMPLETED -> {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Completed",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.width(8.dp))

                // Title + Subtitle inline
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        AnimatedContent(
                            targetState = activity.title.ifBlank {
                                when (status) {
                                    TaskActivityStatus.SEARCHING -> "Searching web"
                                    TaskActivityStatus.EXECUTING -> "Executing task"
                                    TaskActivityStatus.COMPLETED -> "Task completed"
                                    TaskActivityStatus.FAILED -> "Task failed"
                                    else -> "Working"
                                }
                            },
                            transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(150)) },
                            label = "task_chip_title"
                        ) { titleText ->
                            Text(
                                text = titleText,
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 12.5.sp
                                ),
                                color = if (status == TaskActivityStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        if (activity.subtitle.isNotBlank()) {
                            Text(
                                text = "· ${activity.subtitle}",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                // Trailing spinner or chevron
                if (isRunning) {
                    Box(modifier = Modifier.padding(start = 6.dp)) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 1.8.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                } else if (hasDetails) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (isExpanded) "Collapse" else "Expand details",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier
                            .padding(start = 4.dp)
                            .size(16.dp)
                    )
                }
            }

            // Expandable Step Execution Details
            AnimatedVisibility(
                visible = isExpanded && hasDetails,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    if (hasHistory) {
                        Text(
                            text = "Steps (${activity.stepHistory.size})",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 10.5.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
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
                                            imageVector = Icons.Default.CheckCircle,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
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
                            Spacer(Modifier.height(6.dp))
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
                        Spacer(Modifier.height(4.dp))
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
                        Spacer(Modifier.height(4.dp))
                    }

                    if (hasImages) {
                        WebImagesGrid(images = activity.images)
                        if (hasSources) Spacer(Modifier.height(6.dp))
                    }

                    if (hasSources) {
                        Text(
                            text = "Sources (${activity.sources.size})",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 10.5.sp
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
