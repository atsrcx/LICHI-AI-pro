package com.lichiai.agentvision.renderer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lichiai.agentvision.model.CursorStyleConfig
import com.lichiai.agentvision.state.AgentVisionSessionState
import com.lichiai.agentvision.telemetry.AgentVisionTelemetryHub

@Composable
fun AgentVisionOverlay(
    session: AgentVisionSessionState?,
    config: CursorStyleConfig,
    showTimelinePanel: Boolean = true,
    onStopOrTakeControl: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    if (session == null) return

    val isVisible = session.isInteracting || (session.targetBounds != null && session.targetBounds.isValid()) || session.statusText.isNotBlank()
    if (!isVisible) return

    Box(modifier = modifier.fillMaxSize()) {
        // 1. Target Highlight & Indicators Layer
        AgentVisionTargetRenderer(
            targetBounds = session.targetBounds,
            targetPosition = session.cursorPosition,
            config = config,
            isInteracting = session.isInteracting,
            isTyping = session.isTyping,
            typingMasked = session.typingMasked,
            isScrolling = session.isScrolling,
            scrollDirection = session.scrollDirection,
            isPositionAvailable = session.targetBounds != null || (session.cursorPosition.x > 0f && session.cursorPosition.y > 0f)
        )

        // 2. Cursor Pointer Layer
        if (session.cursorPosition.x > 0f || session.cursorPosition.y > 0f) {
            AgentVisionCursorRenderer(
                targetPosition = session.cursorPosition,
                trailPoints = session.trailPoints,
                config = config,
                isInteracting = session.isInteracting
            )
        }

        // 3. Compact Operational Timeline Panel (Top or Bottom overlay)
        if (showTimelinePanel && config.actionTimeline && (session.isInteracting || session.statusText.isNotBlank())) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                AgentVisionTimelineView(
                    session = session,
                    config = config,
                    onStopOrTakeControl = onStopOrTakeControl
                )
            }
        }
    }
}
