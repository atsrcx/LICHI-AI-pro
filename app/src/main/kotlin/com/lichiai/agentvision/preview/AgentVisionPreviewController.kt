package com.lichiai.agentvision.preview

import com.lichiai.agentvision.model.AgentVisualEvent
import com.lichiai.agentvision.model.VisualActionType
import com.lichiai.agentvision.model.VisualBounds
import com.lichiai.agentvision.model.VisualCoordinateSpace
import com.lichiai.agentvision.model.VisualPhase
import com.lichiai.agentvision.model.VisualPosition
import com.lichiai.agentvision.model.VisualSource
import com.lichiai.agentvision.state.AgentVisionSessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Preview Controller for Agent Vision Settings & Demo.
 *
 * CRITICAL RULE: Preview mode is VISUAL-ONLY.
 * It NEVER executes real Android accessibility gestures or browser commands.
 */
class AgentVisionPreviewController(
    private val scope: CoroutineScope
) {
    private val _previewSession = MutableStateFlow(
        AgentVisionSessionState(
            taskId = "preview_task",
            userGoal = "Previewing Agent Vision",
            source = VisualSource.PREVIEW_MODE,
            cursorPosition = VisualPosition(150f, 150f),
            statusText = "Tap a test action below to preview cursor"
        )
    )
    val previewSession: StateFlow<AgentVisionSessionState> = _previewSession.asStateFlow()

    private var activeJob: Job? = null

    fun triggerTapPreview(widthPx: Float = 400f, heightPx: Float = 300f) {
        activeJob?.cancel()
        activeJob = scope.launch {
            val targetX = widthPx * 0.5f
            val targetY = heightPx * 0.45f
            val bounds = VisualBounds.fromCenter(targetX, targetY, 60f, 24f)

            _previewSession.value = _previewSession.value.copy(
                statusText = "Moving to search button...",
                cursorPosition = VisualPosition(targetX, targetY),
                targetBounds = bounds,
                isInteracting = false,
                isTyping = false,
                isScrolling = false
            )
            delay(350)

            _previewSession.value = _previewSession.value.copy(
                statusText = "Clicking search button",
                isInteracting = true
            )
            delay(300)

            _previewSession.value = _previewSession.value.copy(
                statusText = "Clicked button successfully",
                isInteracting = false
            )
        }
    }

    fun triggerTypePreview(widthPx: Float = 400f, heightPx: Float = 300f) {
        activeJob?.cancel()
        activeJob = scope.launch {
            val targetX = widthPx * 0.5f
            val targetY = heightPx * 0.35f
            val bounds = VisualBounds.fromCenter(targetX, targetY, 110f, 25f)

            _previewSession.value = _previewSession.value.copy(
                statusText = "Targeting input field...",
                cursorPosition = VisualPosition(targetX, targetY),
                targetBounds = bounds,
                isInteracting = false,
                isTyping = false
            )
            delay(300)

            val text = "Lichi Agent"
            for (i in 1..text.length) {
                _previewSession.value = _previewSession.value.copy(
                    statusText = "Typing...",
                    isInteracting = true,
                    isTyping = true,
                    typingMasked = "•".repeat(i)
                )
                delay(120)
            }

            delay(300)
            _previewSession.value = _previewSession.value.copy(
                statusText = "Finished typing",
                isInteracting = false,
                isTyping = false
            )
        }
    }

    fun triggerScrollPreview(widthPx: Float = 400f, heightPx: Float = 300f) {
        activeJob?.cancel()
        activeJob = scope.launch {
            val cx = widthPx * 0.5f
            val startY = heightPx * 0.3f
            val endY = heightPx * 0.7f

            _previewSession.value = _previewSession.value.copy(
                statusText = "Scrolling down...",
                cursorPosition = VisualPosition(cx, startY),
                targetBounds = null,
                isInteracting = true,
                isScrolling = true,
                scrollDirection = "DOWN"
            )
            delay(200)

            _previewSession.value = _previewSession.value.copy(
                cursorPosition = VisualPosition(cx, endY)
            )
            delay(400)

            _previewSession.value = _previewSession.value.copy(
                statusText = "Scrolled page down",
                isInteracting = false,
                isScrolling = false
            )
        }
    }

    fun triggerDragPreview(widthPx: Float = 400f, heightPx: Float = 300f) {
        activeJob?.cancel()
        activeJob = scope.launch {
            val startX = widthPx * 0.2f
            val startY = heightPx * 0.5f
            val endX = widthPx * 0.8f

            _previewSession.value = _previewSession.value.copy(
                statusText = "Dragging item...",
                cursorPosition = VisualPosition(startX, startY),
                targetBounds = VisualBounds.fromCenter(startX, startY, 30f, 30f),
                isInteracting = true
            )
            delay(200)

            val steps = 8
            for (i in 1..steps) {
                val currentX = startX + ((endX - startX) * (i.toFloat() / steps.toFloat()))
                _previewSession.value = _previewSession.value.copy(
                    cursorPosition = VisualPosition(currentX, startY),
                    trailPoints = (_previewSession.value.trailPoints + VisualPosition(currentX, startY)).takeLast(12)
                )
                delay(60)
            }

            _previewSession.value = _previewSession.value.copy(
                statusText = "Dropped item",
                targetBounds = VisualBounds.fromCenter(endX, startY, 30f, 30f),
                isInteracting = false
            )
        }
    }
}
