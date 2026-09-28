package com.lichiai.agentvision.renderer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lichiai.agentvision.model.CursorStyleConfig
import com.lichiai.agentvision.model.TargetHighlightStyle
import com.lichiai.agentvision.model.VisualBounds
import com.lichiai.agentvision.model.VisualPosition
import kotlin.math.roundToInt

@Composable
fun AgentVisionTargetRenderer(
    targetBounds: VisualBounds?,
    targetPosition: VisualPosition,
    config: CursorStyleConfig,
    isInteracting: Boolean,
    isTyping: Boolean,
    typingMasked: String,
    isScrolling: Boolean,
    scrollDirection: String,
    isPositionAvailable: Boolean,
    modifier: Modifier = Modifier
) {
    val highlightColor = Color(config.targetHighlightColorArgb)

    val infiniteTransition = rememberInfiniteTransition(label = "target_highlight_anim")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    val scrollAnimOffset by infiniteTransition.animateFloat(
        initialValue = -10f,
        targetValue = 10f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scroll_anim"
    )

    Box(modifier = modifier.fillMaxSize()) {
        // 1. Target Bounding Highlight (Only enabled in developer debug mode)
        if (config.developerDebugInfo && config.targetHighlight && targetBounds != null && targetBounds.isValid()) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val left = targetBounds.left
                val top = targetBounds.top
                val width = targetBounds.width
                val height = targetBounds.height

                when (config.targetHighlightStyle) {
                    TargetHighlightStyle.OUTLINE -> {
                        drawRoundRect(
                            color = highlightColor.copy(alpha = 0.85f),
                            topLeft = Offset(left, top),
                            size = Size(width, height),
                            cornerRadius = CornerRadius(6.dp.toPx()),
                            style = Stroke(width = 2.5.dp.toPx())
                        )
                    }

                    TargetHighlightStyle.GLOW -> {
                        drawRoundRect(
                            brush = Brush.radialGradient(
                                colors = listOf(highlightColor.copy(alpha = 0.35f * pulseAlpha), Color.Transparent),
                                center = Offset(left + width / 2f, top + height / 2f),
                                radius = (width.coerceAtLeast(height)) * 0.8f
                            ),
                            topLeft = Offset(left - 8.dp.toPx(), top - 8.dp.toPx()),
                            size = Size(width + 16.dp.toPx(), height + 16.dp.toPx())
                        )
                        drawRoundRect(
                            color = highlightColor.copy(alpha = 0.9f),
                            topLeft = Offset(left, top),
                            size = Size(width, height),
                            cornerRadius = CornerRadius(6.dp.toPx()),
                            style = Stroke(width = 2.dp.toPx())
                        )
                    }

                    TargetHighlightStyle.PULSE -> {
                        drawRoundRect(
                            color = highlightColor.copy(alpha = pulseAlpha),
                            topLeft = Offset(left, top),
                            size = Size(width, height),
                            cornerRadius = CornerRadius(8.dp.toPx()),
                            style = Stroke(width = 3.dp.toPx())
                        )
                    }

                    TargetHighlightStyle.RIPPLE -> {
                        drawRoundRect(
                            color = highlightColor.copy(alpha = pulseAlpha * 0.7f),
                            topLeft = Offset(left - 4.dp.toPx(), top - 4.dp.toPx()),
                            size = Size(width + 8.dp.toPx(), height + 8.dp.toPx()),
                            cornerRadius = CornerRadius(8.dp.toPx()),
                            style = Stroke(width = 2.dp.toPx())
                        )
                        drawRoundRect(
                            color = highlightColor,
                            topLeft = Offset(left, top),
                            size = Size(width, height),
                            cornerRadius = CornerRadius(6.dp.toPx()),
                            style = Stroke(width = 2.dp.toPx())
                        )
                    }

                    TargetHighlightStyle.CIRCLE -> {
                        val cx = left + width / 2f
                        val cy = top + height / 2f
                        val radius = (width.coerceAtLeast(height) / 2f) + 6.dp.toPx()
                        drawCircle(
                            color = highlightColor.copy(alpha = 0.85f),
                            center = Offset(cx, cy),
                            radius = radius,
                            style = Stroke(width = 2.5.dp.toPx())
                        )
                    }

                    TargetHighlightStyle.CORNER_BRACKETS -> {
                        val bracketLen = (width.coerceAtMost(height) * 0.28f).coerceIn(8.dp.toPx(), 24.dp.toPx())
                        val strokeW = 3.dp.toPx()
                        val c = highlightColor.copy(alpha = pulseAlpha)

                        // Top-Left
                        val tl = Path().apply {
                            moveTo(left, top + bracketLen)
                            lineTo(left, top)
                            lineTo(left + bracketLen, top)
                        }
                        drawPath(tl, color = c, style = Stroke(width = strokeW, cap = StrokeCap.Round))

                        // Top-Right
                        val tr = Path().apply {
                            moveTo(left + width - bracketLen, top)
                            lineTo(left + width, top)
                            lineTo(left + width, top + bracketLen)
                        }
                        drawPath(tr, color = c, style = Stroke(width = strokeW, cap = StrokeCap.Round))

                        // Bottom-Left
                        val bl = Path().apply {
                            moveTo(left, top + height - bracketLen)
                            lineTo(left, top + height)
                            lineTo(left + bracketLen, top + height)
                        }
                        drawPath(bl, color = c, style = Stroke(width = strokeW, cap = StrokeCap.Round))

                        // Bottom-Right
                        val br = Path().apply {
                            moveTo(left + width - bracketLen, top + height)
                            lineTo(left + width, top + height)
                            lineTo(left + width, top + height - bracketLen)
                        }
                        drawPath(br, color = c, style = Stroke(width = strokeW, cap = StrokeCap.Round))
                    }
                }
            }
        }

        // 2. Typing Indicator Pill
        AnimatedVisibility(
            visible = config.typingIndicator && isTyping,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.offset {
                IntOffset(
                    (targetPosition.x - 40f).roundToInt().coerceAtLeast(16),
                    (targetPosition.y - 50f).roundToInt().coerceAtLeast(16)
                )
            }
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                border = androidx.compose.foundation.BorderStroke(1.dp, highlightColor.copy(alpha = 0.6f)),
                tonalElevation = 4.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = "Typing",
                        tint = highlightColor,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (typingMasked.isNotBlank()) "Typing $typingMasked" else "Typing...",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 11.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        // 3. Scroll Indicator Direction Pill
        AnimatedVisibility(
            visible = config.scrollIndicator && isScrolling,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.offset {
                IntOffset(
                    (targetPosition.x - 30f).roundToInt().coerceAtLeast(16),
                    (targetPosition.y + 35f + scrollAnimOffset).roundToInt().coerceAtLeast(16)
                )
            }
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.95f),
                tonalElevation = 3.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (scrollDirection == "UP") Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                        contentDescription = "Scrolling",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "Scrolling $scrollDirection",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 10.5.sp),
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }

        // 4. Fallback Indicator for Unavailable Position
        if (!isPositionAvailable && isInteracting) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = "Target position unavailable",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // 5. Developer Coordinates Display
        if (config.coordinateDisplay) {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = Color.Black.copy(alpha = 0.75f),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp)
            ) {
                Text(
                    text = "X: ${targetPosition.x.toInt()}  Y: ${targetPosition.y.toInt()}",
                    color = Color.Green,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                )
            }
        }
    }
}
