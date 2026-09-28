package com.lichiai.agentvision.model

import androidx.compose.ui.graphics.Color
import kotlinx.serialization.Serializable

enum class VisualSource {
    ANDROID_AGENT,
    BROWSER_AGENT,
    FUTURE_AGENT,
    PREVIEW_MODE
}

enum class VisualActionType {
    MOVE,
    TARGET,
    TAP,
    LONG_PRESS,
    TYPE,
    CLEAR_TEXT,
    SCROLL,
    SWIPE,
    BACK,
    FORWARD,
    OPEN_TAB,
    CLOSE_TAB,
    SWITCH_TAB,
    SELECT,
    WAIT,
    NAVIGATE,
    DRAG,
    VERIFY,
    SUCCESS,
    FAILURE
}

enum class VisualPhase {
    PLANNED,
    STARTED,
    EXECUTING,
    COMPLETED,
    FAILED,
    CANCELLED
}

enum class VisualCoordinateSpace {
    DOM,
    WEB_VIEW,
    COMPOSE,
    WINDOW,
    SCREEN
}

@Serializable
data class VisualPosition(
    val x: Float = 0f,
    val y: Float = 0f
)

@Serializable
data class VisualBounds(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 0f,
    val bottom: Float = 0f
) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
    val centerX: Float get() = left + width / 2f
    val centerY: Float get() = top + height / 2f

    fun isValid(): Boolean = width > 0f && height > 0f

    companion object {
        val ZERO = VisualBounds(0f, 0f, 0f, 0f)

        fun fromLtwh(left: Float, top: Float, width: Float, height: Float): VisualBounds {
            return VisualBounds(left, top, left + width, top + height)
        }

        fun fromCenter(cx: Float, cy: Float, halfWidth: Float, halfHeight: Float): VisualBounds {
            return VisualBounds(cx - halfWidth, cy - halfHeight, cx + halfWidth, cy + halfHeight)
        }
    }
}

@Serializable
data class AgentVisualEvent(
    val eventId: String = java.util.UUID.randomUUID().toString(),
    val taskId: String = "",
    val stepId: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val source: VisualSource = VisualSource.ANDROID_AGENT,
    val actionType: VisualActionType = VisualActionType.MOVE,
    val phase: VisualPhase = VisualPhase.STARTED,
    val startPosition: VisualPosition? = null,
    val targetPosition: VisualPosition? = null,
    val targetBounds: VisualBounds? = null,
    val coordinateSpace: VisualCoordinateSpace = VisualCoordinateSpace.SCREEN,
    val textLength: Int = 0,
    val typedMaskedText: String? = null,
    val scrollDeltaX: Float = 0f,
    val scrollDeltaY: Float = 0f,
    val durationMs: Long = 0,
    val resultSummary: String? = null,
    val error: String? = null,
    val targetIdentifier: String? = null,
    val operationalDescription: String = "",
    val isPositionAvailable: Boolean = true
)
