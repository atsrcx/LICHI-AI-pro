package com.lichiai.agentvision.model

import kotlinx.serialization.Serializable

enum class CursorShape {
    CLASSIC_ARROW,
    WINDOWS_ARROW,
    MACOS_ARROW,
    HAND,
    CIRCLE,
    DOT,
    CROSSHAIR,
    LICHI,
    CUSTOM
}

enum class TargetHighlightStyle {
    OUTLINE,
    GLOW,
    CIRCLE,
    PULSE,
    RIPPLE,
    CORNER_BRACKETS
}

enum class MovementEasing {
    LINEAR,
    EASE_OUT,
    EASE_IN_OUT,
    SPRING,
    SMOOTH
}

enum class MovementSpeed {
    SLOW,
    NORMAL,
    FAST,
    INSTANT
}

enum class GlowLevel {
    OFF,
    LOW,
    MEDIUM,
    HIGH
}

enum class CursorSize {
    SMALL,
    MEDIUM,
    LARGE,
    EXTRA_LARGE
}

@Serializable
data class CursorStyleConfig(
    val shape: CursorShape = CursorShape.CLASSIC_ARROW,
    val size: CursorSize = CursorSize.MEDIUM,
    val colorArgb: Long = 0xFF2563EB, // Modern vibrant blue default
    val opacity: Float = 0.95f,
    val glow: GlowLevel = GlowLevel.LOW,
    val shadow: Boolean = true,
    val movementSpeed: MovementSpeed = MovementSpeed.NORMAL,
    val movementEasing: MovementEasing = MovementEasing.SMOOTH,
    val clickAnimation: Boolean = true,
    val clickRipple: Boolean = true,
    val rippleSizeDp: Float = 42f,
    val targetHighlight: Boolean = false, // Disabled in normal mode per requirement
    val targetHighlightColorArgb: Long = 0xFF2563EB,
    val targetHighlightStyle: TargetHighlightStyle = TargetHighlightStyle.CORNER_BRACKETS,
    val actionTrail: Boolean = false,
    val trailLength: Int = 8,
    val trailOpacity: Float = 0.5f,
    val scrollIndicator: Boolean = true,
    val typingIndicator: Boolean = true,
    val coordinateDisplay: Boolean = false,
    val developerDebugInfo: Boolean = false,
    val actionTimeline: Boolean = true,
    val soundEffects: Boolean = false,
    val hapticFeedback: Boolean = true
)

@Serializable
enum class AgentVisionPreset {
    LICHI_DEFAULT,
    DESKTOP_CLASSIC,
    MINIMAL,
    CINEMATIC,
    DEVELOPER,
    ACCESSIBILITY,
    HIGH_VISIBILITY,
    GAMING,
    CUSTOM
}

@Serializable
data class AgentVisionSettings(
    val enabled: Boolean = true,
    val activePreset: AgentVisionPreset = AgentVisionPreset.LICHI_DEFAULT,
    val config: CursorStyleConfig = CursorStyleConfig()
) {
    companion object {
        fun getPresetConfig(preset: AgentVisionPreset): CursorStyleConfig {
            return when (preset) {
                AgentVisionPreset.LICHI_DEFAULT -> CursorStyleConfig(
                    shape = CursorShape.LICHI,
                    size = CursorSize.MEDIUM,
                    colorArgb = 0xFF2563EB,
                    opacity = 0.95f,
                    glow = GlowLevel.LOW,
                    shadow = true,
                    movementSpeed = MovementSpeed.NORMAL,
                    movementEasing = MovementEasing.SMOOTH,
                    targetHighlight = false,
                    targetHighlightStyle = TargetHighlightStyle.CORNER_BRACKETS,
                    actionTrail = false
                )
                AgentVisionPreset.DESKTOP_CLASSIC -> CursorStyleConfig(
                    shape = CursorShape.CLASSIC_ARROW,
                    size = CursorSize.MEDIUM,
                    colorArgb = 0xFF1E293B,
                    opacity = 1.0f,
                    glow = GlowLevel.OFF,
                    shadow = true,
                    movementSpeed = MovementSpeed.NORMAL,
                    movementEasing = MovementEasing.EASE_OUT,
                    targetHighlight = false,
                    targetHighlightStyle = TargetHighlightStyle.OUTLINE,
                    actionTrail = false
                )
                AgentVisionPreset.MINIMAL -> CursorStyleConfig(
                    shape = CursorShape.DOT,
                    size = CursorSize.SMALL,
                    colorArgb = 0xFF3B82F6,
                    opacity = 0.85f,
                    glow = GlowLevel.LOW,
                    shadow = false,
                    movementSpeed = MovementSpeed.FAST,
                    movementEasing = MovementEasing.SMOOTH,
                    targetHighlightStyle = TargetHighlightStyle.CIRCLE,
                    actionTrail = false
                )
                AgentVisionPreset.CINEMATIC -> CursorStyleConfig(
                    shape = CursorShape.LICHI,
                    size = CursorSize.LARGE,
                    colorArgb = 0xFF8B5CF6,
                    opacity = 0.95f,
                    glow = GlowLevel.HIGH,
                    shadow = true,
                    movementSpeed = MovementSpeed.SLOW,
                    movementEasing = MovementEasing.SPRING,
                    targetHighlightStyle = TargetHighlightStyle.GLOW,
                    actionTrail = true,
                    trailLength = 14,
                    trailOpacity = 0.75f
                )
                AgentVisionPreset.DEVELOPER -> CursorStyleConfig(
                    shape = CursorShape.CROSSHAIR,
                    size = CursorSize.MEDIUM,
                    colorArgb = 0xFF10B981,
                    opacity = 1.0f,
                    glow = GlowLevel.LOW,
                    shadow = true,
                    movementSpeed = MovementSpeed.FAST,
                    movementEasing = MovementEasing.LINEAR,
                    targetHighlightStyle = TargetHighlightStyle.CORNER_BRACKETS,
                    coordinateDisplay = true,
                    developerDebugInfo = true,
                    actionTimeline = true
                )
                AgentVisionPreset.ACCESSIBILITY -> CursorStyleConfig(
                    shape = CursorShape.CIRCLE,
                    size = CursorSize.EXTRA_LARGE,
                    colorArgb = 0xFFF59E0B,
                    opacity = 1.0f,
                    glow = GlowLevel.HIGH,
                    shadow = true,
                    movementSpeed = MovementSpeed.SLOW,
                    movementEasing = MovementEasing.SMOOTH,
                    targetHighlightStyle = TargetHighlightStyle.PULSE,
                    actionTrail = true,
                    trailLength = 12
                )
                AgentVisionPreset.HIGH_VISIBILITY -> CursorStyleConfig(
                    shape = CursorShape.HAND,
                    size = CursorSize.EXTRA_LARGE,
                    colorArgb = 0xFFEF4444,
                    opacity = 1.0f,
                    glow = GlowLevel.HIGH,
                    shadow = true,
                    movementSpeed = MovementSpeed.NORMAL,
                    movementEasing = MovementEasing.SMOOTH,
                    targetHighlightStyle = TargetHighlightStyle.RIPPLE,
                    actionTrail = true
                )
                AgentVisionPreset.GAMING -> CursorStyleConfig(
                    shape = CursorShape.CROSSHAIR,
                    size = CursorSize.LARGE,
                    colorArgb = 0xFF06B6D4,
                    opacity = 0.95f,
                    glow = GlowLevel.HIGH,
                    shadow = true,
                    movementSpeed = MovementSpeed.FAST,
                    movementEasing = MovementEasing.SPRING,
                    targetHighlightStyle = TargetHighlightStyle.CORNER_BRACKETS,
                    actionTrail = true,
                    trailLength = 12
                )
                AgentVisionPreset.CUSTOM -> CursorStyleConfig()
            }
        }
    }
}
