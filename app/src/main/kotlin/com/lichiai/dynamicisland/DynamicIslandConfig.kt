package com.lichiai.dynamicisland

import androidx.compose.ui.graphics.Color
import kotlinx.serialization.Serializable

enum class IslandShape {
    PILL,
    ROUNDED_RECT,
    CIRCLE,
    SQUARE,
    CUT_CORNER
}

enum class PositionMode {
    AUTO_DETECT,
    TOP_CENTER,
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_CENTER,
    LEFT_CENTER,
    RIGHT_CENTER,
    FREE
}

enum class SnapEdge {
    AUTO,
    TOP,
    BOTTOM,
    LEFT,
    RIGHT,
    NONE
}

enum class GlowIntensity(val multiplier: Float) {
    LOW(0.5f),
    MEDIUM(1.0f),
    HIGH(1.6f)
}

enum class IslandAnimationStyle {
    SMOOTH,
    FADE,
    SCALE,
    PULSE,
    MORPH,
    NONE
}

enum class AnimationSpeed(val durationMultiplier: Float) {
    SLOW(1.5f),
    NORMAL(1.0f),
    FAST(0.6f)
}

enum class IslandAction {
    EXPAND,
    OPEN_LICHI,
    TOGGLE_VOICE,
    OPEN_SETTINGS,
    MOVE_MODE,
    NONE
}

enum class IslandPreset {
    DEFAULT,
    CYBERPUNK,
    MONOCHROME,
    GLASS,
    MINIMAL,
    VIBRANT,
    CUSTOM
}

@Serializable
data class DynamicIslandConfig(
    val enabled: Boolean = false,
    val showWhenAppClosed: Boolean = true,
    val showOnLockScreen: Boolean = false,
    val startAutomatically: Boolean = true,

    // Position & Coordinates
    val positionMode: PositionMode = PositionMode.AUTO_DETECT,
    val xFraction: Float = 0.5f,
    val yFraction: Float = 0.03f,
    val xOffsetDp: Int = 0,
    val yOffsetDp: Int = 12,

    // Dimensions (Collapsed)
    val widthDp: Int = 180,
    val heightDp: Int = 42,
    val minWidthDp: Int = 120,
    val maxWidthDp: Int = 320,
    val minHeightDp: Int = 36,
    val maxHeightDp: Int = 60,

    // Dimensions (Expanded)
    val expandedWidthDp: Int = 340,
    val expandedHeightDp: Int = 220,

    // Shape & Corner Radius
    val shape: IslandShape = IslandShape.PILL,
    val cornerRadiusDp: Int = 24,

    // Appearance & Colors
    val preset: IslandPreset = IslandPreset.DEFAULT,
    val backgroundColorHex: String = "#1E1A22",
    val backgroundAlpha: Float = 0.92f,
    val primaryColorHex: String = "#D0BCFF",
    val secondaryColorHex: String = "#CCC2DC",
    val textColorHex: String = "#FFFFFF",
    val iconColorHex: String = "#D0BCFF",

    // Border
    val borderEnabled: Boolean = true,
    val borderWidthDp: Float = 1.0f,
    val borderColorHex: String = "#4A4458",
    val borderAlpha: Float = 0.6f,

    // Glow
    val glowEnabled: Boolean = false,
    val glowColorHex: String = "#D0BCFF",
    val glowIntensity: GlowIntensity = GlowIntensity.MEDIUM,
    val glowRadiusDp: Float = 12f,

    // Overall Opacity
    val overallAlpha: Float = 1.0f,

    // Content Visibility
    val showIcon: Boolean = true,
    val showStatus: Boolean = true,
    val showTranscript: Boolean = true,
    val showResponse: Boolean = true,
    val showWaveform: Boolean = true,
    val showToolActivity: Boolean = true,
    val showCallingStatus: Boolean = true,

    // Animations
    val animationStyle: IslandAnimationStyle = IslandAnimationStyle.SMOOTH,
    val animationSpeed: AnimationSpeed = AnimationSpeed.NORMAL,

    // Interaction & Behavior
    val tapAction: IslandAction = IslandAction.EXPAND,
    val longPressAction: IslandAction = IslandAction.OPEN_SETTINGS,
    val doubleTapAction: IslandAction = IslandAction.TOGGLE_VOICE,
    val autoCollapse: Boolean = true,
    val autoCollapseDelaySec: Int = 4,

    // Snapping
    val snapToEdges: Boolean = true,
    val snapEdge: SnapEdge = SnapEdge.AUTO,
    val snapDistanceDp: Int = 32,

    // Performance / Battery Mode
    val lowPowerMode: Boolean = false
) {
    fun sanitized(): DynamicIslandConfig {
        return copy(
            xFraction = xFraction.coerceIn(0f, 1f),
            yFraction = yFraction.coerceIn(0f, 1f),
            widthDp = widthDp.coerceIn(minWidthDp, maxWidthDp),
            heightDp = heightDp.coerceIn(minHeightDp, maxHeightDp),
            cornerRadiusDp = cornerRadiusDp.coerceIn(0, 50),
            backgroundAlpha = backgroundAlpha.coerceIn(0.1f, 1.0f),
            borderWidthDp = borderWidthDp.coerceIn(0f, 6f),
            borderAlpha = borderAlpha.coerceIn(0f, 1.0f),
            glowRadiusDp = glowRadiusDp.coerceIn(2f, 32f),
            overallAlpha = overallAlpha.coerceIn(0.2f, 1.0f),
            autoCollapseDelaySec = autoCollapseDelaySec.coerceIn(1, 15),
            snapDistanceDp = snapDistanceDp.coerceIn(8, 80)
        )
    }

    companion object {
        fun applyPreset(preset: IslandPreset, base: DynamicIslandConfig): DynamicIslandConfig {
            return when (preset) {
                IslandPreset.DEFAULT -> base.copy(
                    preset = preset,
                    shape = IslandShape.PILL,
                    cornerRadiusDp = 24,
                    backgroundColorHex = "#1E1A22",
                    backgroundAlpha = 0.92f,
                    primaryColorHex = "#D0BCFF",
                    textColorHex = "#FFFFFF",
                    iconColorHex = "#D0BCFF",
                    borderEnabled = true,
                    borderColorHex = "#4A4458",
                    borderAlpha = 0.6f,
                    glowEnabled = false
                )
                IslandPreset.CYBERPUNK -> base.copy(
                    preset = preset,
                    shape = IslandShape.CUT_CORNER,
                    cornerRadiusDp = 10,
                    backgroundColorHex = "#0D0221",
                    backgroundAlpha = 0.95f,
                    primaryColorHex = "#00F0FF",
                    textColorHex = "#00F0FF",
                    iconColorHex = "#FF007F",
                    borderEnabled = true,
                    borderColorHex = "#00F0FF",
                    borderWidthDp = 1.5f,
                    borderAlpha = 0.9f,
                    glowEnabled = true,
                    glowColorHex = "#00F0FF",
                    glowIntensity = GlowIntensity.HIGH
                )
                IslandPreset.MONOCHROME -> base.copy(
                    preset = preset,
                    shape = IslandShape.ROUNDED_RECT,
                    cornerRadiusDp = 16,
                    backgroundColorHex = "#000000",
                    backgroundAlpha = 0.95f,
                    primaryColorHex = "#FFFFFF",
                    textColorHex = "#FFFFFF",
                    iconColorHex = "#CCCCCC",
                    borderEnabled = true,
                    borderColorHex = "#FFFFFF",
                    borderWidthDp = 1.0f,
                    borderAlpha = 0.4f,
                    glowEnabled = false
                )
                IslandPreset.GLASS -> base.copy(
                    preset = preset,
                    shape = IslandShape.PILL,
                    cornerRadiusDp = 24,
                    backgroundColorHex = "#2B2D42",
                    backgroundAlpha = 0.55f,
                    primaryColorHex = "#8D99AE",
                    textColorHex = "#EDF2F4",
                    iconColorHex = "#EF233C",
                    borderEnabled = true,
                    borderColorHex = "#EDF2F4",
                    borderWidthDp = 1.0f,
                    borderAlpha = 0.35f,
                    glowEnabled = true,
                    glowColorHex = "#8D99AE",
                    glowIntensity = GlowIntensity.LOW
                )
                IslandPreset.MINIMAL -> base.copy(
                    preset = preset,
                    shape = IslandShape.PILL,
                    cornerRadiusDp = 22,
                    backgroundColorHex = "#121212",
                    backgroundAlpha = 0.85f,
                    primaryColorHex = "#E0E0E0",
                    textColorHex = "#F5F5F5",
                    iconColorHex = "#BDBDBD",
                    borderEnabled = false,
                    glowEnabled = false,
                    showWaveform = false
                )
                IslandPreset.VIBRANT -> base.copy(
                    preset = preset,
                    shape = IslandShape.PILL,
                    cornerRadiusDp = 24,
                    backgroundColorHex = "#311B92",
                    backgroundAlpha = 0.92f,
                    primaryColorHex = "#FFD700",
                    textColorHex = "#FFFFFF",
                    iconColorHex = "#FF4081",
                    borderEnabled = true,
                    borderColorHex = "#FFD700",
                    borderWidthDp = 1.2f,
                    borderAlpha = 0.7f,
                    glowEnabled = true,
                    glowColorHex = "#FF4081",
                    glowIntensity = GlowIntensity.MEDIUM
                )
                IslandPreset.CUSTOM -> base.copy(preset = preset)
            }
        }
    }
}
