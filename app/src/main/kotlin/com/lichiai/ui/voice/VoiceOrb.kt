package com.lichiai.ui.voice

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.lichiai.voice.conversation.VoiceState
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun VoiceOrb(
    state: VoiceState,
    rms: Float,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "VoiceOrbTransition")

    // Breathing pulse
    val breatheScale by infiniteTransition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathe"
    )

    // Continuous rotation for thinking/active states
    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(6000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    // Wave ripple progression
    val rippleProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ripple"
    )

    // Compute dynamic color scheme based on state
    val colors = when (state) {
        VoiceState.LISTENING -> listOf(
            Color(0xFF00E5FF),
            Color(0xFF00B0FF),
            Color(0xFF00E676),
            Color(0xFF1DE9B6)
        )
        VoiceState.TRANSCRIBING -> listOf(
            Color(0xFF00E5FF),
            Color(0xFF7C4DFF),
            Color(0xFF651FFF),
            Color(0xFF00B0FF)
        )
        VoiceState.THINKING -> listOf(
            Color(0xFF7C4DFF),
            Color(0xFFFF4081),
            Color(0xFFE040FB),
            Color(0xFF536DFE)
        )
        VoiceState.SPEAKING -> listOf(
            Color(0xFFFF5252),
            Color(0xFFFF4081),
            Color(0xFFFF9100),
            Color(0xFFFFD700)
        )
        VoiceState.INTERRUPTED -> listOf(
            Color(0xFFFF9100),
            Color(0xFFFF5252),
            Color(0xFFFFB74D)
        )
        VoiceState.ERROR -> listOf(
            Color(0xFFFF1744),
            Color(0xFFFF5252),
            Color(0xFFFF8A80)
        )
        VoiceState.PAUSED, VoiceState.IDLE -> listOf(
            Color(0xFF78909C),
            Color(0xFF90A4AE),
            Color(0xFFB0BEC5)
        )
    }

    Box(
        modifier = modifier.size(260.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val baseRadius = size.minDimension / 3.4f
            val rmsBoost = (rms.coerceIn(0f, 12f) / 12f) * (baseRadius * 0.35f)
            val dynamicRadius = (baseRadius * breatheScale) + rmsBoost

            // Outer ethereal glow ring
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        colors.first().copy(alpha = 0.25f),
                        colors.getOrElse(1) { colors.first() }.copy(alpha = 0.08f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = dynamicRadius * 1.7f
                ),
                radius = dynamicRadius * 1.7f,
                center = center
            )

            // Animated wave rings for listening / speaking
            if (state == VoiceState.LISTENING || state == VoiceState.TRANSCRIBING || state == VoiceState.SPEAKING) {
                val rippleRadius = dynamicRadius + (rippleProgress * baseRadius * 0.65f)
                val rippleAlpha = (1f - rippleProgress).coerceIn(0f, 1f) * 0.45f
                drawCircle(
                    color = colors.first().copy(alpha = rippleAlpha),
                    radius = rippleRadius,
                    center = center,
                    style = Stroke(width = 3.dp.toPx())
                )
            }

            // Rotating gradient core orb
            val angleRad = Math.toRadians(rotationAngle.toDouble())
            val offsetMultiplier = dynamicRadius * 0.5f
            val gradCenter = Offset(
                center.x + (cos(angleRad) * offsetMultiplier).toFloat(),
                center.y + (sin(angleRad) * offsetMultiplier).toFloat()
            )

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.9f),
                        colors.first(),
                        colors.getOrElse(1) { colors.first() },
                        colors.getOrElse(2) { colors.first() }
                    ),
                    center = gradCenter,
                    radius = dynamicRadius
                ),
                radius = dynamicRadius,
                center = center
            )

            // Inner subtle highlights and dynamic waves
            drawInnerSpecular(center, dynamicRadius, colors.first())
        }
    }
}

private fun DrawScope.drawInnerSpecular(center: Offset, radius: Float, primaryColor: Color) {
    // Glass highlight crescent
    drawCircle(
        brush = Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.55f),
                Color.Transparent
            ),
            start = Offset(center.x - radius * 0.6f, center.y - radius * 0.6f),
            end = Offset(center.x + radius * 0.4f, center.y + radius * 0.4f)
        ),
        radius = radius * 0.85f,
        center = Offset(center.x - radius * 0.12f, center.y - radius * 0.15f)
    )
}
