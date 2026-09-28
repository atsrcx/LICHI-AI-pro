package com.lichiai.agentvision.renderer

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.lichiai.agentvision.model.CursorShape
import com.lichiai.agentvision.model.CursorSize
import com.lichiai.agentvision.model.CursorStyleConfig
import com.lichiai.agentvision.model.GlowLevel
import com.lichiai.agentvision.model.MovementEasing
import com.lichiai.agentvision.model.MovementSpeed
import com.lichiai.agentvision.model.VisualPosition
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun AgentVisionCursorRenderer(
    targetPosition: VisualPosition,
    trailPoints: List<VisualPosition>,
    config: CursorStyleConfig,
    isInteracting: Boolean,
    modifier: Modifier = Modifier
) {
    val cursorBaseSizeDp = when (config.size) {
        CursorSize.SMALL -> 20.dp
        CursorSize.MEDIUM -> 28.dp
        CursorSize.LARGE -> 36.dp
        CursorSize.EXTRA_LARGE -> 46.dp
    }

    // Animated Position
    val animX = remember { Animatable(targetPosition.x) }
    val animY = remember { Animatable(targetPosition.y) }

    val durationMs = when (config.movementSpeed) {
        MovementSpeed.SLOW -> 450
        MovementSpeed.NORMAL -> 240
        MovementSpeed.FAST -> 120
        MovementSpeed.INSTANT -> 0
    }

    LaunchedEffect(targetPosition.x, targetPosition.y) {
        if (durationMs == 0) {
            animX.snapTo(targetPosition.x)
            animY.snapTo(targetPosition.y)
        } else {
            val animSpec = when (config.movementEasing) {
                MovementEasing.LINEAR -> tween<Float>(durationMs, easing = LinearEasing)
                MovementEasing.EASE_OUT -> tween<Float>(durationMs, easing = FastOutSlowInEasing)
                MovementEasing.EASE_IN_OUT -> tween<Float>(durationMs, easing = FastOutSlowInEasing)
                MovementEasing.SPRING -> spring<Float>(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
                MovementEasing.SMOOTH -> tween<Float>(durationMs, easing = FastOutSlowInEasing)
            }
            coroutineScope {
                launch { animX.animateTo(targetPosition.x, animSpec) }
                launch { animY.animateTo(targetPosition.y, animSpec) }
            }
        }
    }

    val clickScale = remember { Animatable(1f) }
    LaunchedEffect(isInteracting) {
        if (isInteracting && config.clickAnimation) {
            clickScale.animateTo(0.82f, tween(80))
            clickScale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "cursor_glow_anim")
    val glowPulse by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow_pulse"
    )

    val cursorColor = Color(config.colorArgb).copy(alpha = config.opacity)

    Box(modifier = modifier.fillMaxSize()) {
        // 1. Action Trail Layer
        if (config.actionTrail && trailPoints.isNotEmpty()) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val trailSize = trailPoints.size
                for (i in 0 until trailSize) {
                    val p = trailPoints[i]
                    val fraction = (i + 1).toFloat() / trailSize.toFloat()
                    val pointAlpha = fraction * config.trailOpacity * config.opacity
                    val radius = (cursorBaseSizeDp.toPx() * 0.2f) * fraction
                    drawCircle(
                        color = cursorColor.copy(alpha = pointAlpha),
                        radius = radius,
                        center = Offset(p.x, p.y)
                    )
                }
            }
        }

        // 2. Click Ripple Animation Layer
        if (config.clickRipple && isInteracting) {
            val rippleRadius = remember { Animatable(0f) }
            val rippleAlpha = remember { Animatable(0.7f) }
            LaunchedEffect(isInteracting) {
                rippleRadius.snapTo(0f)
                rippleAlpha.snapTo(0.7f)
                coroutineScope {
                    launch { rippleRadius.animateTo(config.rippleSizeDp * 1.5f, tween(350, easing = FastOutSlowInEasing)) }
                    launch { rippleAlpha.animateTo(0f, tween(350, easing = FastOutSlowInEasing)) }
                }
            }

            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(
                    color = cursorColor.copy(alpha = rippleAlpha.value),
                    radius = rippleRadius.value,
                    center = Offset(animX.value, animY.value),
                    style = Stroke(width = 3.dp.toPx())
                )
            }
        }

        // 3. Main Cursor Pointer Element
        Canvas(
            modifier = Modifier
                .offset { IntOffset(animX.value.roundToInt(), animY.value.roundToInt()) }
                .size(cursorBaseSizeDp)
                .graphicsLayer {
                    scaleX = clickScale.value
                    scaleY = clickScale.value
                }
        ) {
            val canvasSize = size.width

            // Optional Glow
            if (config.glow != GlowLevel.OFF) {
                val glowRadius = when (config.glow) {
                    GlowLevel.LOW -> canvasSize * 0.45f
                    GlowLevel.MEDIUM -> canvasSize * 0.75f
                    GlowLevel.HIGH -> canvasSize * 1.1f
                    GlowLevel.OFF -> 0f
                } * glowPulse

                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(cursorColor.copy(alpha = 0.45f), Color.Transparent),
                        center = Offset(canvasSize * 0.35f, canvasSize * 0.35f),
                        radius = glowRadius
                    ),
                    radius = glowRadius,
                    center = Offset(canvasSize * 0.35f, canvasSize * 0.35f)
                )
            }

            // Shadow
            if (config.shadow) {
                drawCursorShape(
                    shape = config.shape,
                    color = Color.Black.copy(alpha = 0.35f),
                    size = canvasSize,
                    offset = Offset(2.dp.toPx(), 2.dp.toPx())
                )
            }

            // Foreground Cursor
            drawCursorShape(
                shape = config.shape,
                color = cursorColor,
                size = canvasSize,
                offset = Offset.Zero
            )
        }
    }
}

private fun DrawScope.drawCursorShape(
    shape: CursorShape,
    color: Color,
    size: Float,
    offset: Offset
) {
    val outlineColor = if (color.alpha > 0.5f) Color.White.copy(alpha = 0.9f) else Color.Black.copy(alpha = 0.3f)
    val strokeWidth = 1.5.dp.toPx()

    when (shape) {
        CursorShape.CLASSIC_ARROW, CursorShape.WINDOWS_ARROW -> {
            val path = Path().apply {
                moveTo(offset.x, offset.y)
                lineTo(offset.x, offset.y + size * 0.90f)
                lineTo(offset.x + size * 0.26f, offset.y + size * 0.66f)
                lineTo(offset.x + size * 0.58f, offset.y + size * 0.96f)
                lineTo(offset.x + size * 0.74f, offset.y + size * 0.82f)
                lineTo(offset.x + size * 0.42f, offset.y + size * 0.52f)
                lineTo(offset.x + size * 0.76f, offset.y + size * 0.52f)
                close()
            }
            drawPath(path, color = color, style = Fill)
            drawPath(path, color = outlineColor, style = Stroke(width = strokeWidth, join = StrokeJoin.Miter))
        }

        CursorShape.MACOS_ARROW -> {
            val path = Path().apply {
                moveTo(offset.x, offset.y)
                lineTo(offset.x, offset.y + size * 0.88f)
                lineTo(offset.x + size * 0.28f, offset.y + size * 0.65f)
                lineTo(offset.x + size * 0.54f, offset.y + size * 0.94f)
                lineTo(offset.x + size * 0.68f, offset.y + size * 0.84f)
                lineTo(offset.x + size * 0.44f, offset.y + size * 0.54f)
                lineTo(offset.x + size * 0.74f, offset.y + size * 0.54f)
                close()
            }
            drawPath(path, color = color, style = Fill)
            drawPath(path, color = outlineColor, style = Stroke(width = strokeWidth, join = StrokeJoin.Miter))
        }

        CursorShape.HAND -> {
            val path = Path().apply {
                moveTo(offset.x + size * 0.35f, offset.y)
                lineTo(offset.x + size * 0.5f, offset.y)
                lineTo(offset.x + size * 0.5f, offset.y + size * 0.45f)
                lineTo(offset.x + size * 0.85f, offset.y + size * 0.5f)
                lineTo(offset.x + size * 0.85f, offset.y + size * 0.85f)
                lineTo(offset.x + size * 0.25f, offset.y + size * 0.85f)
                lineTo(offset.x + size * 0.15f, offset.y + size * 0.55f)
                lineTo(offset.x + size * 0.35f, offset.y + size * 0.45f)
                close()
            }
            drawPath(path, color = color, style = Fill)
            drawPath(path, color = outlineColor, style = Stroke(width = strokeWidth, join = StrokeJoin.Round))
        }

        CursorShape.CIRCLE -> {
            val center = Offset(offset.x + size / 2f, offset.y + size / 2f)
            drawCircle(color = color, radius = size * 0.4f, center = center, style = Fill)
            drawCircle(color = outlineColor, radius = size * 0.4f, center = center, style = Stroke(width = strokeWidth))
        }

        CursorShape.DOT -> {
            val center = Offset(offset.x + size / 2f, offset.y + size / 2f)
            drawCircle(color = color, radius = size * 0.32f, center = center, style = Fill)
            drawCircle(color = outlineColor, radius = size * 0.32f, center = center, style = Stroke(width = strokeWidth))
        }

        CursorShape.CROSSHAIR -> {
            val cx = offset.x + size / 2f
            val cy = offset.y + size / 2f
            drawCircle(color = color, radius = size * 0.36f, center = Offset(cx, cy), style = Stroke(width = strokeWidth * 1.2f))
            drawLine(color = color, start = Offset(cx - size * 0.45f, cy), end = Offset(cx + size * 0.45f, cy), strokeWidth = strokeWidth * 1.2f)
            drawLine(color = color, start = Offset(cx, cy - size * 0.45f), end = Offset(cx, cy + size * 0.45f), strokeWidth = strokeWidth * 1.2f)
        }

        CursorShape.LICHI, CursorShape.CUSTOM -> {
            // Modern stylized 4-point sparkle star / Lichi diamond pointer
            val cx = offset.x + size * 0.4f
            val cy = offset.y + size * 0.4f
            val r = size * 0.42f
            val path = Path().apply {
                moveTo(cx, cy - r)
                quadraticTo(cx, cy, cx + r, cy)
                quadraticTo(cx, cy, cx, cy + r)
                quadraticTo(cx, cy, cx - r, cy)
                quadraticTo(cx, cy, cx, cy - r)
                close()
            }
            drawPath(path, color = color, style = Fill)
            drawPath(path, color = outlineColor, style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}
