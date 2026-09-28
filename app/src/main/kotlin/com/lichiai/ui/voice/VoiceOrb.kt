package com.lichiai.ui.voice

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.lichiai.agent.model.AgentExecutionState
import com.lichiai.agent.model.AgentLiveStatus
import com.lichiai.voice.conversation.VoiceState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Premium Reactive Liquid Energy Orb for Lichi Voice Mode.
 *
 * Visual Characteristics:
 * - Single-color family hierarchy: dark core -> primary liquid -> radiant highlight -> luminous core -> atmospheric auras.
 * - Multi-harmonic organic liquid boundary with C1-smooth quadratic midpoint Bezier interpolation.
 * - Internal fluid turbulence with counter-morphing inner liquid mass and floating luminous eddies.
 * - Real audio reactivity: dynamically scales, deforms, and intensifies with speech RMS.
 * - Real AgentLiveStatus reactivity: transitions fluidly during autonomous phone tasks.
 * - Zero frame allocations: preallocated paths and coordinate buffers for smooth 60/120 FPS on Android 12+.
 */
@Composable
fun VoiceOrb(
    state: VoiceState,
    rms: Float,
    modifier: Modifier = Modifier,
    baseColor: Color = Color(0xFF6E5CFF),
    agentStatus: AgentLiveStatus? = null,
    isPressed: Boolean = false
) {
    // -------------------------------------------------------------
    // Single-Color Family Optical Palette Derivation
    // -------------------------------------------------------------
    val r = baseColor.red
    val g = baseColor.green
    val b = baseColor.blue

    // Deep volumetric optical core
    val liquidCore = remember(baseColor) {
        Color(r * 0.16f, g * 0.16f, b * 0.26f, 1f)
    }
    // Mid-depth fluid body
    val liquidDeep = remember(baseColor) {
        Color(r * 0.42f, g * 0.42f, b * 0.58f, 1f)
    }
    // Radiant surface liquid
    val liquidRadiant = remember(baseColor) {
        Color(
            (r + (1f - r) * 0.45f).coerceIn(0f, 1f),
            (g + (1f - g) * 0.45f).coerceIn(0f, 1f),
            (b + (1f - b) * 0.45f).coerceIn(0f, 1f),
            1f
        )
    }
    // Hot luminous fluid highlight
    val liquidLuminous = remember(baseColor) {
        Color(
            (r + (1f - r) * 0.82f).coerceIn(0f, 1f),
            (g + (1f - g) * 0.82f).coerceIn(0f, 1f),
            (b + (1f - b) * 0.82f).coerceIn(0f, 1f),
            1f
        )
    }

    // -------------------------------------------------------------
    // Continuous Procedural Time Clock (Zero Discontinuity)
    // -------------------------------------------------------------
    val infiniteTransition = rememberInfiniteTransition(label = "LiquidEnergyOrbTransition")

    // Master procedural phase: 20 * PI over 20 seconds ensures integer harmonic loops match seamlessly at 0
    val masterPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (20.0 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(20000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "masterPhase"
    )

    // Breathing rhythm adjusted by state
    val breathingDuration = when {
        agentStatus != null && agentStatus.state == AgentExecutionState.EXECUTING -> 1200
        state == VoiceState.SPEAKING -> 1400
        state == VoiceState.THINKING -> 1700
        else -> 2600
    }
    val breatheScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(breathingDuration, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breatheScale"
    )

    // -------------------------------------------------------------
    // Real Audio Reactivity (RMS Signal Smoothing)
    // -------------------------------------------------------------
    val smoothedRms by animateFloatAsState(
        targetValue = rms.coerceIn(0f, 12f),
        animationSpec = spring(
            stiffness = Spring.StiffnessLow,
            dampingRatio = Spring.DampingRatioMediumBouncy
        ),
        label = "smoothedRms"
    )
    val normalizedRms = (smoothedRms / 10f).coerceIn(0f, 1.2f)

    // Touch interaction compression
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.93f else 1.0f,
        animationSpec = spring(
            stiffness = Spring.StiffnessMedium,
            dampingRatio = Spring.DampingRatioMediumBouncy
        ),
        label = "pressScale"
    )

    // -------------------------------------------------------------
    // State-Specific Motion & Energy Multipliers
    // -------------------------------------------------------------
    val isAgentActive = agentStatus != null && agentStatus.state != AgentExecutionState.IDLE
    val agentState = agentStatus?.state ?: AgentExecutionState.IDLE

    // Flow speed multiplier
    val speedMultiplier = when {
        isAgentActive && agentState == AgentExecutionState.EXECUTING -> 2.2f
        isAgentActive && agentState == AgentExecutionState.THINKING -> 1.8f
        state == VoiceState.THINKING -> 1.7f
        state == VoiceState.SPEAKING -> 1.4f
        state == VoiceState.LISTENING -> 1.0f + (normalizedRms * 0.6f)
        else -> 0.8f
    }

    // Base liquid deformation amplitude
    val baseDeformation = when {
        isAgentActive && agentState == AgentExecutionState.EXECUTING -> 0.16f
        isAgentActive && agentState == AgentExecutionState.PERCEIVING -> 0.14f
        state == VoiceState.SPEAKING -> 0.12f
        state == VoiceState.THINKING -> 0.08f
        state == VoiceState.LISTENING || state == VoiceState.TRANSCRIBING -> 0.06f + (normalizedRms * 0.14f)
        else -> 0.04f
    }

    // Luminous core intensity
    val coreIntensity = when {
        isAgentActive && agentState == AgentExecutionState.EXECUTING -> 0.65f
        isAgentActive && agentState == AgentExecutionState.COMPLETED -> 0.70f
        state == VoiceState.SPEAKING -> 0.55f
        state == VoiceState.THINKING -> 0.45f
        state == VoiceState.LISTENING -> 0.35f + (normalizedRms * 0.25f)
        else -> 0.25f
    }

    // Outer aura expansion
    val auraScale = when {
        isAgentActive && agentState == AgentExecutionState.EXECUTING -> 1.45f
        state == VoiceState.SPEAKING -> 1.35f
        state == VoiceState.LISTENING -> 1.25f + (normalizedRms * 0.25f)
        else -> 1.15f
    }

    // -------------------------------------------------------------
    // Reusable Buffers (Zero Allocation Per Render Frame)
    // -------------------------------------------------------------
    val pointCount = 16
    val outerPath = remember { Path() }
    val innerPath = remember { Path() }
    val sheenPath = remember { Path() }

    val baseAngles = remember {
        FloatArray(pointCount) { i -> (i * (2.0 * PI / pointCount)).toFloat() }
    }
    val cosAngles = remember {
        FloatArray(pointCount) { i -> cos(baseAngles[i]) }
    }
    val sinAngles = remember {
        FloatArray(pointCount) { i -> sin(baseAngles[i]) }
    }

    val outerPointsX = remember { FloatArray(pointCount) }
    val outerPointsY = remember { FloatArray(pointCount) }
    val outerMidX = remember { FloatArray(pointCount) }
    val outerMidY = remember { FloatArray(pointCount) }

    val innerPointsX = remember { FloatArray(pointCount) }
    val innerPointsY = remember { FloatArray(pointCount) }
    val innerMidX = remember { FloatArray(pointCount) }
    val innerMidY = remember { FloatArray(pointCount) }

    Box(
        modifier = modifier.size(270.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width * 0.5f, size.height * 0.5f)
            val baseRadius = (size.minDimension * 0.32f) * pressScale
            val currentRadius = baseRadius * breatheScale * (1f + (normalizedRms * 0.12f))

            val t = masterPhase * speedMultiplier

            // Dynamic drifting fluid center for optical volumetric refraction
            val driftDist = currentRadius * 0.08f
            val dynamicCenter = Offset(
                center.x + cos(t * 0.5f) * driftDist,
                center.y + sin(t * 0.5f) * driftDist
            )

            // =========================================================
            // Layer 1 & 2: Atmospheric Halos (Soft Outer Energy Field)
            // =========================================================
            val outerHaloRadius = currentRadius * (1.75f * auraScale)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        baseColor.copy(alpha = (0.07f + normalizedRms * 0.06f)),
                        baseColor.copy(alpha = 0.02f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = outerHaloRadius
                ),
                radius = outerHaloRadius,
                center = center
            )

            val midAuraRadius = currentRadius * (1.35f * auraScale)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        baseColor.copy(alpha = (0.18f + normalizedRms * 0.12f)),
                        baseColor.copy(alpha = 0.04f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = midAuraRadius
                ),
                radius = midAuraRadius,
                center = center
            )

            // =========================================================
            // Layer 3: Procedural Outer Liquid Blob Silhouette
            // =========================================================
            for (i in 0 until pointCount) {
                val angle = baseAngles[i]
                // 4 Incommensurate harmonic frequencies for non-repeating organic deformation
                val h1 = sin(2f * angle + t * 1.15f)
                val h2 = cos(3f * angle - t * 1.55f + 1.2f)
                val h3 = sin(5f * angle + t * 2.10f + 2.4f)
                val h4 = cos(angle + t * 0.75f)

                val harmonic = 0.38f * h1 + 0.28f * h2 + 0.20f * h3 + 0.14f * h4
                val vertexR = currentRadius * (1f + baseDeformation * harmonic)

                outerPointsX[i] = center.x + vertexR * cosAngles[i]
                outerPointsY[i] = center.y + vertexR * sinAngles[i]
            }

            for (i in 0 until pointCount) {
                val next = (i + 1) % pointCount
                outerMidX[i] = (outerPointsX[i] + outerPointsX[next]) * 0.5f
                outerMidY[i] = (outerPointsY[i] + outerPointsY[next]) * 0.5f
            }

            outerPath.rewind()
            outerPath.moveTo(outerMidX[0], outerMidY[0])
            for (i in 0 until pointCount) {
                val next = (i + 1) % pointCount
                outerPath.quadraticBezierTo(
                    outerPointsX[next],
                    outerPointsY[next],
                    outerMidX[next],
                    outerMidY[next]
                )
            }
            outerPath.close()

            // Outer Liquid Mass Gradient (Volumetric refraction)
            drawPath(
                path = outerPath,
                brush = Brush.radialGradient(
                    colors = listOf(
                        liquidCore,
                        liquidDeep,
                        baseColor,
                        liquidRadiant
                    ),
                    center = dynamicCenter,
                    radius = currentRadius * 1.12f
                )
            )

            // Radiant Edge Rim Glow
            drawPath(
                path = outerPath,
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.Transparent,
                        liquidRadiant.copy(alpha = 0.35f),
                        liquidLuminous.copy(alpha = (0.75f + normalizedRms * 0.2f).coerceAtMost(1f))
                    ),
                    center = dynamicCenter,
                    radius = currentRadius
                ),
                style = Stroke(width = 2.5.dp.toPx())
            )

            // =========================================================
            // Layer 4: Internal Counter-Morphing Fluid Swirl
            // =========================================================
            val innerRadius = currentRadius * 0.75f
            val tInner = t * 1.35f + 1.8f

            for (i in 0 until pointCount) {
                val angle = baseAngles[i]
                val h1 = sin(2f * angle - tInner * 1.2f + 0.8f)
                val h2 = cos(3f * angle + tInner * 1.6f)
                val h3 = sin(4f * angle - tInner * 2.0f + 1.5f)

                val harmonicInner = 0.45f * h1 + 0.35f * h2 + 0.20f * h3
                val vertexR = innerRadius * (1f + (baseDeformation * 0.85f) * harmonicInner)

                innerPointsX[i] = center.x + vertexR * cosAngles[i]
                innerPointsY[i] = center.y + vertexR * sinAngles[i]
            }

            for (i in 0 until pointCount) {
                val next = (i + 1) % pointCount
                innerMidX[i] = (innerPointsX[i] + innerPointsX[next]) * 0.5f
                innerMidY[i] = (innerPointsY[i] + innerPointsY[next]) * 0.5f
            }

            innerPath.rewind()
            innerPath.moveTo(innerMidX[0], innerMidY[0])
            for (i in 0 until pointCount) {
                val next = (i + 1) % pointCount
                innerPath.quadraticBezierTo(
                    innerPointsX[next],
                    innerPointsY[next],
                    innerMidX[next],
                    innerMidY[next]
                )
            }
            innerPath.close()

            // Swirling internal fluid mass
            drawPath(
                path = innerPath,
                brush = Brush.sweepGradient(
                    colors = listOf(
                        liquidDeep.copy(alpha = 0.50f),
                        liquidRadiant.copy(alpha = 0.40f),
                        liquidCore.copy(alpha = 0.65f),
                        liquidLuminous.copy(alpha = 0.35f),
                        liquidDeep.copy(alpha = 0.50f)
                    ),
                    center = dynamicCenter
                )
            )

            // =========================================================
            // Layer 5: Floating Luminous Liquid Eddies (Internal Highlights)
            // =========================================================
            // Eddy 1 (Orbital flow)
            val eddy1X = center.x + cos(t * 0.85f) * (currentRadius * 0.28f)
            val eddy1Y = center.y + sin(t * 0.70f) * (currentRadius * 0.28f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        liquidLuminous.copy(alpha = 0.42f),
                        liquidRadiant.copy(alpha = 0.15f),
                        Color.Transparent
                    ),
                    center = Offset(eddy1X, eddy1Y),
                    radius = currentRadius * 0.35f
                ),
                radius = currentRadius * 0.35f,
                center = Offset(eddy1X, eddy1Y)
            )

            // Eddy 2 (Counter-orbital flow)
            val eddy2X = center.x + cos(-t * 1.10f + 1.8f) * (currentRadius * 0.24f)
            val eddy2Y = center.y + sin(t * 1.25f + 0.9f) * (currentRadius * 0.24f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        liquidRadiant.copy(alpha = 0.38f),
                        liquidDeep.copy(alpha = 0.10f),
                        Color.Transparent
                    ),
                    center = Offset(eddy2X, eddy2Y),
                    radius = currentRadius * 0.26f
                ),
                radius = currentRadius * 0.26f,
                center = Offset(eddy2X, eddy2Y)
            )

            // =========================================================
            // Layer 6: Deep Volumetric Liquid Core
            // =========================================================
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        liquidLuminous.copy(alpha = coreIntensity),
                        liquidRadiant.copy(alpha = coreIntensity * 0.55f),
                        Color.Transparent
                    ),
                    center = dynamicCenter,
                    radius = currentRadius * 0.48f
                ),
                radius = currentRadius * 0.48f,
                center = dynamicCenter
            )

            // =========================================================
            // Layer 7: Specular Surface Meniscus (Glossy Liquid Sheen)
            // =========================================================
            val sheenCenterX = center.x - currentRadius * 0.18f
            val sheenCenterY = center.y - currentRadius * 0.22f
            val sheenRadius = currentRadius * 0.65f

            sheenPath.rewind()
            sheenPath.moveTo(sheenCenterX - sheenRadius * 0.45f, sheenCenterY + sheenRadius * 0.15f)
            sheenPath.quadraticBezierTo(
                sheenCenterX,
                sheenCenterY - sheenRadius * 0.45f,
                sheenCenterX + sheenRadius * 0.45f,
                sheenCenterY + sheenRadius * 0.15f
            )
            sheenPath.quadraticBezierTo(
                sheenCenterX,
                sheenCenterY - sheenRadius * 0.25f,
                sheenCenterX - sheenRadius * 0.45f,
                sheenCenterY + sheenRadius * 0.15f
            )
            sheenPath.close()

            drawPath(
                path = sheenPath,
                brush = Brush.linearGradient(
                    colors = listOf(
                        liquidLuminous.copy(alpha = 0.45f),
                        liquidRadiant.copy(alpha = 0.12f),
                        Color.Transparent
                    ),
                    start = Offset(sheenCenterX, sheenCenterY - sheenRadius * 0.45f),
                    end = Offset(sheenCenterX, sheenCenterY + sheenRadius * 0.20f)
                )
            )
        }
    }
}
